package id.codeagent.app

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class ToolCtx(val root: File, val skills: () -> List<SkillDef>)

object Tools {
    private val skipDirs = setOf(".git", "node_modules", "build", ".gradle", ".idea", "__pycache__", ".dart_tool")

    private fun spec(n: String, d: String, vararg props: Triple<String, String, String>, req: List<String> = emptyList()): ToolSpec {
        val p = JSONObject()
        props.forEach { p.put(it.first, JSONObject().put("type", it.second).put("description", it.third)) }
        return ToolSpec(n, d, JSONObject().put("type", "object").put("properties", p).put("required", req.toJsonArray()))
    }

    fun specs(): List<ToolSpec> = listOf(
        spec("read", "Read a file (lines prefixed with 'N: ') or list a directory. Use offset/limit for large files.",
            Triple("path", "string", "File or directory path (absolute or relative to project root)"),
            Triple("offset", "integer", "1-based start line"), Triple("limit", "integer", "Max lines (default 2000)"),
            req = listOf("path")),
        spec("write", "Create or overwrite a file with the given content. Parent folders are created.",
            Triple("path", "string", "File path"), Triple("content", "string", "Full file content"),
            req = listOf("path", "content")),
        spec("edit", "Edit a file by replacing exact text. oldString must match uniquely unless replaceAll is true. Do not include the 'N: ' line prefix.",
            Triple("path", "string", "File path"), Triple("oldString", "string", "Exact text to replace"),
            Triple("newString", "string", "Replacement text"), Triple("replaceAll", "boolean", "Replace every occurrence"),
            req = listOf("path", "oldString", "newString")),
        spec("list", "List files and folders in a directory (folders end with '/').",
            Triple("path", "string", "Directory (default: project root)")),
        spec("glob", "Find files by glob pattern such as '**/*.kt'. Skips .git, build, node_modules.",
            Triple("pattern", "string", "Glob pattern"), Triple("path", "string", "Base directory"),
            req = listOf("pattern")),
        spec("grep", "Search file contents with a regular expression. Returns file:line: text.",
            Triple("pattern", "string", "Regex"), Triple("path", "string", "Base directory"),
            Triple("include", "string", "Glob filter for file names, e.g. '*.kt'"), req = listOf("pattern")),
        spec("bash", "Run a shell command (Android sh/toybox) in the project root. Output is truncated.",
            Triple("command", "string", "Command to run"), Triple("timeout", "integer", "Timeout in seconds (default 120)"),
            req = listOf("command")),
        spec("webfetch", "Fetch an HTTP(S) URL and return it as plain text. Read-only.",
            Triple("url", "string", "URL"), req = listOf("url")),
        spec("websearch", "Search the web (DuckDuckGo) and return titles, links and snippets.",
            Triple("query", "string", "Search query"), req = listOf("query")),
        spec("todowrite", "Record or update the task checklist for multi-step work (markdown checklist text).",
            Triple("todos", "string", "Checklist, one '- [ ] item' or '- [x] item' per line"), req = listOf("todos")),
        spec("skill", "Load a skill's instructions by name.",
            Triple("name", "string", "Skill name"), req = listOf("name")),
    )

    /** Kelompok izin: read | edit | bash | web | mcp */
    fun group(name: String): String = when (name) {
        "write", "edit" -> "edit"
        "bash" -> "bash"
        "webfetch", "websearch" -> "web"
        "read", "list", "glob", "grep", "todowrite", "skill" -> "read"
        else -> "mcp"
    }

    fun detail(name: String, a: JSONObject): String = when (name) {
        "bash" -> a.str("command")
        "webfetch" -> a.str("url")
        "websearch" -> a.str("query")
        "glob", "grep" -> a.str("pattern")
        "skill" -> a.str("name")
        "todowrite" -> "daftar tugas"
        else -> a.str("path").ifEmpty { a.toString().take(200) }
    }

    fun title(name: String, args: String): String {
        val a = try { JSONObject(args) } catch (e: Exception) { JSONObject() }
        return "$name  ${detail(name, a).replace("\n", " ").take(90)}"
    }

    fun resolve(root: File, p: String): File = if (p.startsWith("/")) File(p) else File(root, p)

    suspend fun run(name: String, a: JSONObject, ctx: ToolCtx): String = when (name) {
        "read" -> read(a, ctx)
        "write" -> {
            val f = resolve(ctx.root, a.str("path")); f.parentFile?.mkdirs()
            val c = a.str("content"); f.writeText(c)
            "Berhasil menulis ${c.length} karakter ke ${f.path}"
        }
        "edit" -> edit(a, ctx)
        "list" -> {
            val d = resolve(ctx.root, a.str("path").ifEmpty { "." })
            val l = d.listFiles() ?: throw Exception("Bukan folder / tidak bisa dibaca: ${d.path}")
            l.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })).take(500)
                .joinToString("\n") { it.name + if (it.isDirectory) "/" else "" }.ifEmpty { "(kosong)" }
        }
        "glob" -> glob(a, ctx)
        "grep" -> grep(a, ctx)
        "bash" -> bash(a, ctx)
        "webfetch" -> webfetch(a.str("url"))
        "websearch" -> websearch(a.str("query"))
        "todowrite" -> "Daftar tugas diperbarui."
        "skill" -> {
            val n = a.str("name")
            ctx.skills().firstOrNull { it.name == n }?.body ?: throw Exception("Skill tidak ditemukan: $n")
        }
        else -> throw Exception("Tool tidak dikenal: $name")
    }

    private fun read(a: JSONObject, ctx: ToolCtx): String {
        val f = resolve(ctx.root, a.str("path"))
        if (!f.exists()) throw Exception("Tidak ada: ${f.path}")
        if (f.isDirectory) return (f.listFiles() ?: emptyArray()).sortedBy { it.name }
            .joinToString("\n") { it.name + if (it.isDirectory) "/" else "" }.ifEmpty { "(kosong)" }
        if (f.length() > 5_000_000) throw Exception("File terlalu besar (${f.length()} byte)")
        val off = maxOf(1, a.optInt("offset", 1)); val lim = a.optInt("limit", 2000)
        val lines = f.readLines()
        val sb = StringBuilder()
        for (i in (off - 1) until minOf(lines.size, off - 1 + lim)) sb.append(i + 1).append(": ").append(lines[i].take(2000)).append('\n')
        if (off - 1 + lim < lines.size) sb.append("... (${lines.size} baris total, lanjutkan dengan offset=${off + lim})")
        return sb.toString().ifEmpty { "(file kosong)" }
    }

    private fun edit(a: JSONObject, ctx: ToolCtx): String {
        val f = resolve(ctx.root, a.str("path"))
        if (!f.isFile) throw Exception("File tidak ada: ${f.path}")
        val old = a.str("oldString"); val new = a.str("newString")
        if (old.isEmpty()) throw Exception("oldString kosong")
        val text = f.readText()
        val n = Regex.escape(old).toRegex().findAll(text).count()
        if (n == 0) throw Exception("oldString tidak ditemukan. Baca ulang file dan salin teks persis (tanpa awalan nomor baris).")
        val all = a.optBoolean("replaceAll", false)
        if (n > 1 && !all) throw Exception("oldString cocok di $n tempat. Tambah konteks agar unik atau set replaceAll=true.")
        f.writeText(if (all) text.replace(old, new) else text.replaceFirst(old, new))
        return "Berhasil mengedit ${f.path} ($n penggantian)"
    }

    private fun globRe(g: String): Regex {
        val sb = StringBuilder(); var i = 0
        while (i < g.length) {
            val c = g[i]
            when {
                c == '*' && i + 1 < g.length && g[i + 1] == '*' -> { sb.append(".*"); i++; if (i + 1 < g.length && g[i + 1] == '/') i++ }
                c == '*' -> sb.append("[^/]*")
                c == '?' -> sb.append("[^/]")
                else -> sb.append(Regex.escape(c.toString()))
            }
            i++
        }
        return Regex(sb.toString())
    }

    private fun walk(base: File) = base.walkTopDown().onEnter { it == base || it.name !in skipDirs }.filter { it.isFile }

    private fun glob(a: JSONObject, ctx: ToolCtx): String {
        val base = resolve(ctx.root, a.str("path").ifEmpty { "." })
        val re = globRe(a.str("pattern"))
        val out = walk(base).map { it.relativeTo(base).path }.filter { re.matches(it) }.take(200).toList()
        return out.joinToString("\n").ifEmpty { "(tidak ada yang cocok)" }
    }

    private fun grep(a: JSONObject, ctx: ToolCtx): String {
        val base = resolve(ctx.root, a.str("path").ifEmpty { "." })
        val re = try { Regex(a.str("pattern")) } catch (e: Exception) { throw Exception("Regex tidak valid: ${e.message}") }
        val inc = a.str("include").ifEmpty { null }?.let { globRe(it) }
        val out = ArrayList<String>()
        val files = if (base.isFile) sequenceOf(base) else walk(base)
        for (f in files) {
            if (inc != null && !inc.matches(f.name)) continue
            if (f.length() > 2_000_000) continue
            val lines = try { f.readLines() } catch (e: Exception) { continue }
            for ((i, l) in lines.withIndex()) if (re.containsMatchIn(l)) {
                out.add("${f.relativeTo(if (base.isFile) base.parentFile!! else base).path}:${i + 1}: ${l.take(300)}")
                if (out.size >= 100) return out.joinToString("\n") + "\n... (dipotong di 100 hasil)"
            }
        }
        return out.joinToString("\n").ifEmpty { "(tidak ada yang cocok)" }
    }

    private suspend fun bash(a: JSONObject, ctx: ToolCtx): String {
        val timeout = a.optLong("timeout", 120).coerceIn(1, 600)
        val proc = ProcessBuilder("sh", "-c", a.str("command")).directory(ctx.root).redirectErrorStream(true).start()
        val out = StringBuilder()
        val t = Thread {
            try {
                proc.inputStream.bufferedReader().use { r ->
                    val buf = CharArray(4096)
                    while (true) {
                        val n = r.read(buf); if (n < 0) break
                        synchronized(out) { if (out.length < 60000) out.append(buf, 0, n) }
                    }
                }
            } catch (e: Exception) { }
        }
        t.start()
        val end = System.currentTimeMillis() + timeout * 1000
        try {
            while (!proc.waitFor(300, TimeUnit.MILLISECONDS)) {
                currentCoroutineContext().ensureActive()
                if (System.currentTimeMillis() > end) { proc.destroyForcibly(); synchronized(out) { out.append("\n[waktu habis setelah ${timeout}s]") }; break }
            }
        } catch (e: Exception) { proc.destroyForcibly(); throw e }
        t.join(1500)
        val code = try { proc.exitValue() } catch (e: Exception) { -1 }
        return synchronized(out) { out.toString() }.ifEmpty { "(tanpa keluaran)" } + if (code != 0) "\n[exit code $code]" else ""
    }

    private fun htmlToText(h: String): String = h
        .replace(Regex("(?is)<(script|style|noscript)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</li>|</h[1-6]>"), "\n")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        .replace(Regex("[ \\t]+"), " ").replace(Regex("\\n\\s*\\n+"), "\n\n").trim()

    private fun webfetch(url: String): String {
        if (!url.startsWith("http")) throw Exception("URL harus diawali http:// atau https://")
        val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Android) CodeAgent").build()
        Llm.http.newBuilder().readTimeout(30, TimeUnit.SECONDS).build().newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw Exception("HTTP ${r.code}")
            val body = r.body?.string() ?: ""
            val ct = r.header("Content-Type") ?: ""
            return (if (ct.contains("html")) htmlToText(body) else body).take(40000)
        }
    }

    private fun websearch(q: String): String {
        val url = "https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(q, "UTF-8")
        val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Android) CodeAgent").build()
        Llm.http.newBuilder().readTimeout(30, TimeUnit.SECONDS).build().newCall(req).execute().use { r ->
            val html = r.body?.string() ?: ""
            val links = Regex("class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).findAll(html).toList()
            val snips = Regex("class=\"result__snippet\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).findAll(html).toList()
            if (links.isEmpty()) return "(tidak ada hasil / pencarian diblokir)"
            return links.take(8).mapIndexed { i, m ->
                var href = m.groupValues[1]
                Regex("uddg=([^&]+)").find(href)?.let { href = URLDecoder.decode(it.groupValues[1], "UTF-8") }
                "${i + 1}. ${htmlToText(m.groupValues[2])}\n   $href\n   ${snips.getOrNull(i)?.let { htmlToText(it.groupValues[1]) } ?: ""}"
            }.joinToString("\n")
        }
    }
}
