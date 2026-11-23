package id.codeagent.app

import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Calendar
import java.util.concurrent.TimeUnit

class ToolCtx(
    val root: File,
    val skills: () -> List<SkillDef>,
    val ask: suspend (JSONArray) -> String,
    val setTodos: (JSONArray) -> Unit,
    val background: (String, File) -> String,
    val recordUndo: (String, String?) -> Unit,
)

object ToolRepair {
    /** Memperbaiki argumen tool yang tipenya meleset (mis. "5" untuk integer) sesuai skema. */
    fun fix(args: JSONObject, schema: JSONObject): JSONObject {
        val props = schema.optJSONObject("properties") ?: return args
        val keys = args.keys().asSequence().toList()
        for (k in keys) {
            val t = props.optJSONObject(k)?.optString("type") ?: continue
            val v = args.opt(k)
            try {
                when {
                    t == "integer" && v is String -> args.put(k, v.trim().toDouble().toInt())
                    t == "boolean" && v is String -> args.put(k, v.trim().equals("true", true))
                    t == "array" && v is String -> args.put(k, try { JSONArray(v) } catch (e: Exception) { JSONArray().put(v) })
                    t == "object" && v is String -> args.put(k, JSONObject(v))
                    t == "string" && (v is Number || v is Boolean) -> args.put(k, v.toString())
                }
            } catch (e: Exception) { }
        }
        return args
    }
}

object Diagnostics {
    fun note(f: File): String {
        val p = check(f)
        return if (p.isEmpty()) "" else "\nSyntax check: " + p.joinToString("; ")
    }

    fun check(f: File): List<String> {
        if (!f.isFile || f.length() > 1_000_000) return emptyList()
        val text = try { f.readText() } catch (e: Exception) { return emptyList() }
        return when (f.extension.lowercase()) {
            "json" -> try {
                val t = text.trim(); if (t.startsWith("[")) JSONArray(t) else JSONObject(t); emptyList()
            } catch (e: Exception) { listOf("invalid JSON: ${e.message?.take(120)}") }
            "xml" -> try {
                val b = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
                b.setErrorHandler(null)
                b.parse(java.io.ByteArrayInputStream(text.toByteArray())); emptyList()
            } catch (e: Exception) { listOf("invalid XML: ${e.message?.take(120)}") }
            "kt", "kts", "java", "js", "ts", "tsx", "jsx", "c", "cpp", "h", "cs", "css", "dart", "php", "go", "swift", "gradle" -> brackets(text)
            else -> emptyList()
        }
    }

    private fun brackets(t: String): List<String> {
        val st = ArrayDeque<Pair<Char, Int>>()
        var line = 1; var i = 0; val n = t.length
        while (i < n) {
            val c = t[i]
            if (c == '\n') { line++; i++; continue }
            if (c == '/' && i + 1 < n && t[i + 1] == '/') { while (i < n && t[i] != '\n') i++; continue }
            if (c == '/' && i + 1 < n && t[i + 1] == '*') {
                i += 2
                while (i + 1 < n && !(t[i] == '*' && t[i + 1] == '/')) { if (t[i] == '\n') line++; i++ }
                i += 2; continue
            }
            if (c == '"' && t.startsWith("\"\"\"", i)) {
                i += 3
                while (i < n && !t.startsWith("\"\"\"", i)) { if (t[i] == '\n') line++; i++ }
                i += 3; continue
            }
            if (c == '"' || c == '\'') {
                i++
                while (i < n && t[i] != c && t[i] != '\n') { if (t[i] == '\\') i++; i++ }
                i++; continue
            }
            if (c == '`') { i++; while (i < n && t[i] != '`') { if (t[i] == '\n') line++; i++ }; i++; continue }
            if (c == '(' || c == '[' || c == '{') st.addLast(c to line)
            else if (c == ')' || c == ']' || c == '}') {
                val o = st.removeLastOrNull() ?: return listOf("unexpected '$c' at line $line")
                val ok = (o.first == '(' && c == ')') || (o.first == '[' && c == ']') || (o.first == '{' && c == '}')
                if (!ok) return listOf("mismatched '$c' at line $line (opened '${o.first}' at line ${o.second})")
            }
            i++
        }
        return st.lastOrNull()?.let { listOf("unclosed '${it.first}' opened at line ${it.second}") } ?: emptyList()
    }
}

object Tools {
    private val skipDirs = setOf(".git", "node_modules", "build", ".gradle", ".idea", "__pycache__", ".dart_tool")

    private fun prop(type: String, desc: String) = JSONObject().put("type", type).put("description", desc)
    private fun obj(req: List<String>, vararg p: Pair<String, JSONObject>): JSONObject {
        val o = JSONObject(); p.forEach { o.put(it.first, it.second) }
        return JSONObject().put("type", "object").put("properties", o).put("required", req.toJsonArray())
    }
    private fun arrOf(items: JSONObject, desc: String) = JSONObject().put("type", "array").put("description", desc).put("items", items)

    fun specs(skills: List<SkillDef>): List<ToolSpec> = listOf(
        ToolSpec("read", "Read a file or directory. Text lines are prefixed with '<line>: '; the prefix is not part of the content. Directory entries are listed one per line. Use offset and limit for large files. Prefer one larger read over many small slices; use grep to find content in large files.",
            obj(listOf("path"), "path" to prop("string", "File or directory to read"),
                "offset" to prop("integer", "Line or entry to start from (1-based)"),
                "limit" to prop("integer", "Maximum lines or entries (default and cap 2000)"))),
        ToolSpec("glob", "Search file paths with a glob pattern (examples: \"**/*.kt\", \"src/**/*.{ts,tsx}\").",
            obj(listOf("pattern"), "pattern" to prop("string", "Glob pattern"),
                "path" to prop("string", "Directory to search (default: project root)"),
                "hidden" to prop("boolean", "Include hidden files (default false)"),
                "limit" to prop("integer", "Maximum files (default 100)"))),
        ToolSpec("grep", "Search file contents with a regular expression or literal text. Returns file paths, line numbers and line previews.",
            obj(listOf("pattern"), "pattern" to prop("string", "Regex or literal text"),
                "path" to prop("string", "File or directory (default: project root)"),
                "include" to prop("string", "Glob filter for file names, e.g. \"*.{kt,java}\""),
                "literal" to prop("boolean", "Treat pattern as exact text (default false)"),
                "caseSensitive" to prop("boolean", "Case-sensitive matching (default true)"),
                "limit" to prop("integer", "Maximum matching lines (default 100)"))),
        ToolSpec("edit", "Edit a file by replacing exact text. Preserve indentation and omit the 'N: ' line-number prefix from read output. CRLF/LF endings are preserved. If there is no exact match it retries ignoring typographic quote/dash/space variants, then trailing whitespace. oldString must be unique unless replaceAll is true.",
            obj(listOf("path", "oldString", "newString"), "path" to prop("string", "File to edit"),
                "oldString" to prop("string", "Exact text to find"),
                "newString" to prop("string", "Replacement (must differ from oldString)"),
                "replaceAll" to prop("boolean", "Replace every occurrence (default false)"))),
        ToolSpec("write", "Write a file, overwriting if it exists. Missing parent directories are created. Use it to create files or replace them completely; use edit for partial changes.",
            obj(listOf("path", "content"), "path" to prop("string", "File path"), "content" to prop("string", "Full file content"))),
        ToolSpec("patch", "Apply a multi-file patch. Format:\n*** Begin Patch\n*** Add File: <path>   (every following line starts with +)\n*** Delete File: <path>\n*** Update File: <path>   (optionally followed by '*** Move to: <new path>')\n@@ optional context line\n context line (starts with space)\n-removed line\n+added line\n*** End Patch\nInclude about 3 lines of context around each change so it can be located uniquely.",
            obj(listOf("patchText"), "patchText" to prop("string", "Full patch text"))),
        ToolSpec("bash", "Execute a shell command and return its output. Runs on Android (/system/bin/sh, toybox); git, python or node exist only if installed. Quote paths with spaces. Prefer dedicated tools over shell commands. Large output is truncated. Use background=true for long commands: it returns immediately and you are notified when it finishes (do not poll).",
            obj(listOf("command"), "command" to prop("string", "Command to execute"),
                "workdir" to prop("string", "Working directory (default: project root)"),
                "timeout" to prop("integer", "Timeout in milliseconds; 0 disables (default 120000)"),
                "background" to prop("boolean", "Run in the background"))),
        ToolSpec("webfetch", "Fetch an HTTP(S) URL and return it as text, markdown or html (default markdown). Read-only.",
            obj(listOf("url"), "url" to prop("string", "URL"), "format" to prop("string", "text, markdown or html"))),
        ToolSpec("websearch", "Search the web for current information beyond your knowledge cutoff. The current year is ${Calendar.getInstance().get(Calendar.YEAR)}; use it when searching for recent information.",
            obj(listOf("query"), "query" to prop("string", "Search query"))),
        ToolSpec("diagnostics", "Quick syntax check of files (JSON/XML validity, unbalanced brackets or quotes in common source languages). Use after larger edits. It is not a compiler: it cannot find type or import errors.",
            obj(listOf("paths"), "paths" to arrOf(JSONObject().put("type", "string"), "Files to check"))),
        ToolSpec("todowrite", "Create and update the todo list for this session; the user sees it as your plan. Use it when a task has 3+ steps. Send the full list every call (it replaces the previous one). Mark items in_progress when you start and completed as soon as they are done.",
            obj(listOf("todos"), "todos" to arrOf(obj(listOf("content", "status"),
                "content" to prop("string", "Task description"),
                "status" to prop("string", "pending, in_progress or completed"),
                "priority" to prop("string", "high, medium or low")), "The full todo list"))),
        ToolSpec("question", "Ask the user one or more questions when you need a decision or missing information. Provide short options when possible; the user can also type their own answer.",
            obj(listOf("questions"), "questions" to arrOf(obj(listOf("question"),
                "question" to prop("string", "The question"),
                "options" to arrOf(JSONObject().put("type", "string"), "Short answer options")), "Questions to ask"))),
        ToolSpec("task", "Launch a subagent to handle a self-contained task and return its final report. Use agent \"explore\" for read-only codebase searches and questions, or \"general\" for multi-step work. The subagent starts with no memory of this conversation, so include all the context it needs in the prompt.",
            obj(listOf("description", "prompt"), "description" to prop("string", "Short (3-5 words) description of the task"),
                "prompt" to prop("string", "The complete task for the subagent"),
                "agent" to prop("string", "explore or general (default general)"))),
        ToolSpec("skill", "Load a specialized skill's instructions into the conversation when the task matches its description.\n\n<available_skills>\n" +
            skills.joinToString("\n") { "- ${it.name}: ${it.description}" } + "\n</available_skills>",
            obj(listOf("id"), "id" to prop("string", "The ID of an available skill"))),
    )

    /** Kelompok izin: read | edit | bash | web | mcp */
    fun group(name: String): String = when (name) {
        "write", "edit", "patch" -> "edit"
        "bash" -> "bash"
        "webfetch", "websearch" -> "web"
        "read", "glob", "grep", "diagnostics", "todowrite", "question", "skill", "list", "task", "execute" -> "read"
        else -> "mcp"
    }

    fun detail(name: String, a: JSONObject): String = when (name) {
        "bash" -> a.str("command")
        "webfetch" -> a.str("url")
        "websearch" -> a.str("query")
        "glob", "grep" -> a.str("pattern")
        "skill" -> a.str("id").ifEmpty { a.str("name") }
        "task" -> a.str("description").ifEmpty { "subagen" }
        "execute" -> "skrip JavaScript"
        "todowrite" -> "daftar tugas"
        "question" -> a.optJSONArray("questions")?.optJSONObject(0)?.str("question") ?: "pertanyaan"
        "diagnostics" -> a.optJSONArray("paths")?.strings()?.joinToString(", ") ?: ""
        "patch" -> Regex("\\*\\*\\* (?:Add|Update|Delete) File:\\s*(.+)").findAll(a.str("patchText"))
            .map { it.groupValues[1].trim() }.joinToString(", ").ifEmpty { "patch" }
        else -> a.str("path").ifEmpty { a.toString().take(200) }
    }

    fun title(name: String, args: String): String {
        val a = try { JSONObject(args) } catch (e: Exception) { JSONObject() }
        return "$name  ${detail(name, a).replace("\n", " ").take(90)}"
    }

    /** Path yang akan diubah oleh tool edit/write/patch (untuk pemeriksaan mode plan dan folder sumber). */
    fun editPaths(name: String, a: JSONObject): List<String> = when (name) {
        "write", "edit" -> listOf(a.str("path"))
        "patch" -> Regex("\\*\\*\\* (?:Add|Update|Delete) File:\\s*(.+)|\\*\\*\\* Move to:\\s*(.+)").findAll(a.str("patchText"))
            .map { (it.groupValues[1].ifEmpty { it.groupValues[2] }).trim() }.toList()
        else -> emptyList()
    }

    private fun snap(ctx: ToolCtx, f: File) {
        try {
            if (!f.exists()) ctx.recordUndo(f.path, null)
            else if (f.isFile && f.length() <= 200_000) ctx.recordUndo(f.path, f.readText())
        } catch (e: Exception) { }
    }

    fun resolve(root: File, p: String): File = if (p.startsWith("/")) File(p) else File(root, p)

    suspend fun run(name: String, a: JSONObject, ctx: ToolCtx): String = when (name) {
        "read" -> read(a, ctx)
        "write" -> {
            val f = resolve(ctx.root, a.str("path")); f.parentFile?.mkdirs()
            snap(ctx, f)
            val c = a.str("content"); f.writeText(c)
            "Wrote ${c.lines().size} lines to ${f.path}" + Diagnostics.note(f)
        }
        "edit" -> edit(a, ctx)
        "patch" -> patch(a, ctx)
        "glob" -> glob(a, ctx)
        "grep" -> grep(a, ctx)
        "bash" -> bash(a, ctx)
        "webfetch" -> webfetch(a.str("url"), a.str("format").ifEmpty { "markdown" })
        "websearch" -> websearch(a.str("query"))
        "diagnostics" -> {
            val paths = a.optJSONArray("paths")?.strings() ?: emptyList()
            val probs = paths.flatMap { p -> Diagnostics.check(resolve(ctx.root, p)).map { "$p: $it" } }
            if (probs.isEmpty()) "No syntax problems found." else "${probs.size} possible problem(s).\n" + probs.joinToString("\n")
        }
        "todowrite" -> {
            val arr = a.optJSONArray("todos") ?: JSONArray()
            ctx.setTodos(arr)
            val left = (0 until arr.length()).count { arr.optJSONObject(it)?.str("status") != "completed" }
            "Todo list updated: ${arr.length()} items, $left remaining."
        }
        "question" -> ctx.ask(a.optJSONArray("questions") ?: JSONArray())
        "skill" -> {
            val n = a.str("id").ifEmpty { a.str("name") }
            val sk = ctx.skills().firstOrNull { it.name == n } ?: throw Exception("Skill not found: $n")
            "<skill_content name=\"${sk.name}\">\n# Skill: ${sk.name}\n\n${sk.body}\n</skill_content>"
        }
        else -> throw Exception("Unknown tool: $name")
    }

    // ---------------- read ----------------
    private fun looksBinary(f: File): Boolean = try {
        f.inputStream().use { s -> val b = ByteArray(4096); val n = s.read(b); (0 until maxOf(n, 0)).any { b[it].toInt() == 0 } }
    } catch (e: Exception) { false }

    private fun read(a: JSONObject, ctx: ToolCtx): String {
        val f = resolve(ctx.root, a.str("path"))
        if (!f.exists()) {
            val sugg = f.parentFile?.listFiles()?.map { it.name }
                ?.filter { it.contains(f.name.take(4), true) || f.name.contains(it, true) }?.take(3).orEmpty()
            throw Exception("Not found: ${f.path}" + if (sugg.isNotEmpty()) "\nDid you mean: ${sugg.joinToString(", ")}" else "")
        }
        val off = maxOf(1, a.optInt("offset", 1))
        val lim = a.optInt("limit", 2000).coerceIn(1, 2000)
        if (f.isDirectory) {
            val all = (f.listFiles() ?: emptyArray()).sortedBy { it.name.lowercase() }.map { it.name + if (it.isDirectory) "/" else "" }
            val part = all.drop(off - 1).take(lim)
            val more = all.size - (off - 1) - part.size
            return part.joinToString("\n").ifEmpty { "(empty)" } + if (more > 0) "\n($more more entries, use offset=${off + part.size})" else ""
        }
        if (looksBinary(f)) return "Binary file (${f.length()} bytes, ${f.extension}); cannot display as text."
        if (f.length() > 20_000_000) throw Exception("File too large (${f.length()} bytes). Use grep or bash.")
        val sb = StringBuilder(); var n = 0; var more = false
        f.bufferedReader().useLines { seq ->
            for ((i, l) in seq.withIndex()) {
                if (i + 1 < off) continue
                if (n >= lim || sb.length > 60_000) { more = true; break }
                sb.append(i + 1).append(": ").append(l.take(2000)).append('\n'); n++
            }
        }
        if (more) sb.append("(more lines available, use offset=${off + n})")
        return sb.toString().ifEmpty { "(empty file)" }
    }

    // ---------------- edit ----------------
    private fun findAll(t: String, s: String): List<IntRange> {
        val out = ArrayList<IntRange>(); var i = t.indexOf(s)
        while (i >= 0) { out.add(i until i + s.length); i = t.indexOf(s, i + s.length) }
        return out
    }

    private fun norm(s: String): String = buildString(s.length) {
        for (c in s) append(when (c) {
            '\u2018', '\u2019' -> '\''
            '\u201C', '\u201D' -> '"'
            '\u2010', '\u2011', '\u2012', '\u2013', '\u2014', '\u2015', '\u2212' -> '-'
            '\u00A0', '\u2009', '\u202F' -> ' '
            else -> c
        })
    }

    private fun findLines(text: String, old: String): List<IntRange> {
        val tl = text.split("\n"); val ol = old.trimEnd('\n').split("\n").map { it.trimEnd() }
        val offs = IntArray(tl.size); var p = 0
        for (i in tl.indices) { offs[i] = p; p += tl[i].length + 1 }
        val out = ArrayList<IntRange>(); var i = 0
        while (i + ol.size <= tl.size) {
            var ok = true
            for (j in ol.indices) if (tl[i + j].trimEnd() != ol[j]) { ok = false; break }
            if (ok) {
                val last = i + ol.size - 1
                out.add(offs[i]..(offs[last] + tl[last].trimEnd('\r').length - 1)); i += ol.size
            } else i++
        }
        return out
    }

    private fun edit(a: JSONObject, ctx: ToolCtx): String {
        val f = resolve(ctx.root, a.str("path"))
        if (!f.isFile) throw Exception("File not found: ${f.path}")
        val old0 = a.str("oldString"); val new0 = a.str("newString")
        if (old0.isEmpty()) throw Exception("oldString must not be empty. Use the write tool to create or replace whole files.")
        if (old0 == new0) throw Exception("oldString and newString must differ")
        val text = f.readText(); val crlf = text.contains("\r\n")
        fun eol(s: String) = if (crlf) s.replace("\r\n", "\n").replace("\n", "\r\n") else s
        val old = eol(old0); val new = eol(new0)
        val all = a.optBoolean("replaceAll", false)
        var ranges = findAll(text, old)
        if (ranges.isEmpty()) ranges = findAll(norm(text), norm(old))
        if (ranges.isEmpty()) ranges = findLines(text, old)
        if (ranges.isEmpty()) throw Exception("oldString was not found in ${f.path}. Re-read the file and copy the exact text.")
        if (ranges.size > 1 && !all) throw Exception("Found ${ranges.size} matches for oldString. Add more surrounding context to make it unique, or set replaceAll to true.")
        val use = if (all) ranges else ranges.take(1)
        snap(ctx, f)
        val sb = StringBuilder(); var last = 0
        for (r in use) { sb.append(text, last, r.first); sb.append(new); last = r.last + 1 }
        sb.append(text, last, text.length)
        f.writeText(sb.toString())
        return "Edited ${f.path} (${use.size} replacement${if (use.size == 1) "" else "s"})" + Diagnostics.note(f)
    }

    // ---------------- patch ----------------
    private class FileOp(val kind: String, val path: String, val move: String?, val lines: List<String>, val hunks: List<List<String>>)

    private fun parsePatch(text: String): List<FileOp> {
        val ls = text.replace("\r\n", "\n").split("\n")
        var i = 0
        while (i < ls.size && ls[i].isBlank()) i++
        if (i >= ls.size || ls[i].trim() != "*** Begin Patch") throw Exception("Patch must start with '*** Begin Patch'")
        i++
        val ops = ArrayList<FileOp>()
        while (i < ls.size) {
            val l = ls[i]
            when {
                l.trim() == "*** End Patch" -> i = ls.size
                l.startsWith("*** Add File:") -> {
                    val path = l.removePrefix("*** Add File:").trim(); i++
                    val body = ArrayList<String>()
                    while (i < ls.size && !ls[i].startsWith("*** ")) {
                        if (!ls[i].startsWith("+")) {
                            if (ls[i].isEmpty()) { i++; continue }
                            throw Exception("Add File lines must start with '+': ${ls[i]}")
                        }
                        body.add(ls[i].substring(1)); i++
                    }
                    ops.add(FileOp("add", path, null, body, emptyList()))
                }
                l.startsWith("*** Delete File:") -> { ops.add(FileOp("del", l.removePrefix("*** Delete File:").trim(), null, emptyList(), emptyList())); i++ }
                l.startsWith("*** Update File:") -> {
                    val path = l.removePrefix("*** Update File:").trim(); i++
                    var move: String? = null
                    if (i < ls.size && ls[i].startsWith("*** Move to:")) { move = ls[i].removePrefix("*** Move to:").trim(); i++ }
                    val hunks = ArrayList<List<String>>(); var cur = ArrayList<String>()
                    while (i < ls.size && !ls[i].startsWith("*** ")) {
                        val h = ls[i]
                        if (h.startsWith("@@")) { if (cur.isNotEmpty()) { hunks.add(cur); cur = ArrayList() } }
                        else if (h.startsWith("+") || h.startsWith("-") || h.startsWith(" ")) cur.add(h)
                        else if (h.isEmpty()) cur.add(" ")
                        else throw Exception("Unexpected line in hunk (must start with ' ', '+', '-' or '@@'): $h")
                        i++
                    }
                    while (cur.isNotEmpty() && cur.last() == " ") cur.removeAt(cur.size - 1)
                    if (cur.isNotEmpty()) hunks.add(cur)
                    ops.add(FileOp("upd", path, move, emptyList(), hunks))
                }
                l.isBlank() -> i++
                else -> throw Exception("Unexpected line in patch: $l")
            }
        }
        if (ops.isEmpty()) throw Exception("Patch contains no operations")
        return ops
    }

    private fun findSeq(lines: List<String>, old: List<String>, from: Int): Int {
        for (mode in 0..2) for (i in from..(lines.size - old.size)) {
            var ok = true
            for (j in old.indices) {
                val x = lines[i + j]; val y = old[j]
                val m = when (mode) { 0 -> x == y; 1 -> x.trimEnd() == y.trimEnd(); else -> x.trim() == y.trim() }
                if (!m) { ok = false; break }
            }
            if (ok) return i
        }
        return -1
    }

    private fun applyHunks(path: String, orig: String, hunks: List<List<String>>): String {
        val crlf = orig.contains("\r\n")
        val lines = orig.replace("\r\n", "\n").split("\n").toMutableList()
        var cursor = 0
        for (h in hunks) {
            val old = h.filter { it[0] != '+' }.map { it.substring(1) }
            val new = h.filter { it[0] != '-' }.map { it.substring(1) }
            var idx = if (old.isEmpty()) (if (lines.isNotEmpty() && lines.last().isEmpty()) lines.size - 1 else lines.size) else findSeq(lines, old, cursor)
            if (idx < 0) idx = findSeq(lines, old, 0)
            if (idx < 0) throw Exception("Could not find the lines to change in $path:\n" + old.take(6).joinToString("\n") +
                "\nRe-read the file and rebuild the patch from its exact current text.")
            repeat(old.size) { lines.removeAt(idx) }
            lines.addAll(idx, new); cursor = idx + new.size
        }
        val out = lines.joinToString("\n")
        return if (crlf) out.replace("\n", "\r\n") else out
    }

    private fun patch(a: JSONObject, ctx: ToolCtx): String {
        val ops = parsePatch(a.str("patchText"))
        val writes = ArrayList<Triple<File, String?, File?>>()
        val summary = ArrayList<String>()
        for (op in ops) {
            val f = resolve(ctx.root, op.path)
            when (op.kind) {
                "add" -> {
                    if (f.exists()) throw Exception("File already exists: ${op.path}")
                    writes.add(Triple(f, op.lines.joinToString("\n") + "\n", null)); summary.add("A ${op.path}")
                }
                "del" -> {
                    if (!f.isFile) throw Exception("File to delete not found: ${op.path}")
                    writes.add(Triple(f, null, null)); summary.add("D ${op.path}")
                }
                else -> {
                    if (!f.isFile) throw Exception("File to update not found: ${op.path}")
                    val nc = applyHunks(op.path, f.readText(), op.hunks)
                    writes.add(Triple(f, nc, op.move?.let { resolve(ctx.root, it) }))
                    summary.add("M ${op.path}" + (op.move?.let { " -> $it" } ?: ""))
                }
            }
        }
        for ((f, c, mv) in writes) {
            snap(ctx, f); if (mv != null) snap(ctx, mv)
            if (c == null) f.delete()
            else { val t = mv ?: f; t.parentFile?.mkdirs(); t.writeText(c); if (mv != null) f.delete() }
        }
        val notes = writes.filter { it.second != null }.joinToString("") { (f, _, mv) ->
            val t = mv ?: f; val p = Diagnostics.check(t)
            if (p.isEmpty()) "" else "\nSyntax check ${t.name}: " + p.joinToString("; ")
        }
        return "Patch applied:\n" + summary.joinToString("\n") + notes
    }

    // ---------------- glob / grep ----------------
    private fun globRe(g: String): Regex {
        val sb = StringBuilder(); var i = 0; var inBrace = false
        while (i < g.length) {
            val c = g[i]
            when {
                c == '*' && i + 1 < g.length && g[i + 1] == '*' -> { sb.append(".*"); i++; if (i + 1 < g.length && g[i + 1] == '/') i++ }
                c == '*' -> sb.append("[^/]*")
                c == '?' -> sb.append("[^/]")
                c == '{' -> { sb.append("(?:"); inBrace = true }
                c == '}' && inBrace -> { sb.append(")"); inBrace = false }
                c == ',' && inBrace -> sb.append("|")
                else -> sb.append(Regex.escape(c.toString()))
            }
            i++
        }
        return Regex(sb.toString())
    }

    private fun walk(base: File, hidden: Boolean) =
        base.walkTopDown().onEnter { it == base || (it.name !in skipDirs && (hidden || !it.name.startsWith("."))) }
            .filter { it.isFile && (hidden || !it.name.startsWith(".")) }

    private fun glob(a: JSONObject, ctx: ToolCtx): String {
        val base = resolve(ctx.root, a.str("path").ifEmpty { "." })
        if (!base.isDirectory) throw Exception("Not a directory: ${a.str("path")}")
        val re = globRe(a.str("pattern"))
        val lim = a.optInt("limit", 100).coerceIn(1, 1000)
        val out = walk(base, a.optBoolean("hidden", false)).map { it.relativeTo(base).path }.filter { re.matches(it) }.take(lim).toList()
        return out.joinToString("\n").ifEmpty { "(no matches)" }
    }

    private fun grep(a: JSONObject, ctx: ToolCtx): String {
        val base = resolve(ctx.root, a.str("path").ifEmpty { "." })
        if (!base.exists()) throw Exception("Path not found: ${a.str("path")}")
        val pat = a.str("pattern")
        val opts = if (a.optBoolean("caseSensitive", true)) emptySet<RegexOption>() else setOf(RegexOption.IGNORE_CASE)
        val re = try { Regex(if (a.optBoolean("literal", false)) Regex.escape(pat) else pat, opts) }
        catch (e: Exception) { throw Exception("Invalid regex: ${e.message}") }
        val inc = a.str("include").ifEmpty { null }?.let { globRe(it) }
        val lim = a.optInt("limit", 100).coerceIn(1, 1000)
        val out = ArrayList<String>()
        val files = if (base.isFile) sequenceOf(base) else walk(base, false)
        val relBase = if (base.isFile) base.parentFile!! else base
        for (f in files) {
            if (inc != null && !inc.matches(f.name)) continue
            if (f.length() > 2_000_000 || looksBinary(f)) continue
            val lines = try { f.readLines() } catch (e: Exception) { continue }
            for ((i, l) in lines.withIndex()) if (re.containsMatchIn(l)) {
                out.add("${f.relativeTo(relBase).path}:${i + 1}: ${l.take(300)}")
                if (out.size >= lim) return out.joinToString("\n") + "\n(truncated at $lim results)"
            }
        }
        return out.joinToString("\n").ifEmpty { "(no matches)" }
    }

    // ---------------- bash ----------------
    private fun truncate(s: String): String =
        if (s.length > 30000) s.take(15000) + "\n… [truncated ${s.length - 30000} chars] …\n" + s.takeLast(15000) else s

    /** Menjalankan perintah shell. [check] dipanggil berkala; boleh melempar untuk membatalkan. */
    fun exec(cmd: String, dir: File, timeoutMs: Long, check: () -> Unit): Pair<String, Int> {
        val proc = ProcessBuilder("sh", "-c", cmd).directory(dir).redirectErrorStream(true).start()
        val out = StringBuilder()
        val t = Thread {
            try {
                proc.inputStream.bufferedReader().use { r ->
                    val buf = CharArray(4096)
                    while (true) {
                        val n = r.read(buf); if (n < 0) break
                        synchronized(out) { if (out.length < 400_000) out.append(buf, 0, n) }
                    }
                }
            } catch (e: Exception) { }
        }
        t.start()
        val end = if (timeoutMs <= 0) Long.MAX_VALUE else System.currentTimeMillis() + timeoutMs
        try {
            while (!proc.waitFor(300, TimeUnit.MILLISECONDS)) {
                check()
                if (System.currentTimeMillis() > end) {
                    proc.destroyForcibly(); synchronized(out) { out.append("\n[timed out after ${timeoutMs}ms]") }; break
                }
            }
        } catch (e: Exception) { proc.destroyForcibly(); throw e }
        t.join(1500)
        val code = try { proc.exitValue() } catch (e: Exception) { -1 }
        return truncate(synchronized(out) { out.toString() }) to code
    }

    private suspend fun bash(a: JSONObject, ctx: ToolCtx): String {
        val cmd = a.str("command")
        if (cmd.isBlank()) throw Exception("command is empty")
        val dir = a.str("workdir").ifEmpty { null }?.let { resolve(ctx.root, it) } ?: ctx.root
        if (!dir.isDirectory) throw Exception("workdir not found: ${dir.path}")
        if (a.optBoolean("background", false))
            return "Started background command ${ctx.background(cmd, dir)}. You will be notified automatically when it finishes. Do not poll."
        val job = currentCoroutineContext()[Job]
        val (out, code) = exec(cmd, dir, if (a.has("timeout")) a.optLong("timeout", 120000) else 120000L) { job?.ensureActive() }
        return out.ifEmpty { "(no output)" } + if (code != 0) "\n[exit code $code]" else ""
    }

    // ---------------- web ----------------
    private fun entities(s: String) = s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")

    private fun htmlToMd(h: String): String {
        var s = h.replace(Regex("(?is)<!--.*?-->"), "").replace(Regex("(?is)<(script|style|noscript|svg)[^>]*>.*?</\\1>"), "")
        s = Regex("(?is)<h([1-6])[^>]*>(.*?)</h\\1>").replace(s) { "\n\n" + "#".repeat(it.groupValues[1].toInt()) + " " + it.groupValues[2] + "\n\n" }
        s = Regex("(?is)<a [^>]*href=\"([^\"]*)\"[^>]*>(.*?)</a>").replace(s) { "[" + it.groupValues[2].replace(Regex("<[^>]+>"), "") + "](" + it.groupValues[1] + ")" }
        s = Regex("(?is)<pre[^>]*>(.*?)</pre>").replace(s) { "\n```\n" + it.groupValues[1].replace(Regex("<[^>]+>"), "") + "\n```\n" }
        s = Regex("(?is)<code[^>]*>(.*?)</code>").replace(s) { "`" + it.groupValues[1] + "`" }
        s = Regex("(?i)<li[^>]*>").replace(s, "\n- ")
        s = Regex("(?i)</(p|div|tr|ul|ol|section|article|header|footer)>|<br\\s*/?>").replace(s, "\n")
        s = Regex("<[^>]+>").replace(s, "")
        return Regex("\n{3,}").replace(entities(s).lines().joinToString("\n") { it.trimEnd() }, "\n\n").trim()
    }

    private fun webfetch(url: String, format: String): String {
        if (!url.startsWith("http://") && !url.startsWith("https://")) throw Exception("URL must start with http:// or https://")
        val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Android) CodeAgent").build()
        Llm.http.newBuilder().readTimeout(30, TimeUnit.SECONDS).build().newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw Exception("HTTP ${r.code}")
            val body = r.body?.string() ?: ""
            val isHtml = (r.header("Content-Type") ?: "").contains("html")
            return when {
                !isHtml || format == "html" -> body
                format == "text" -> entities(Regex("<[^>]+>").replace(Regex("(?is)<(script|style|noscript|svg)[^>]*>.*?</\\1>").replace(body, ""), " ")).replace(Regex("[ \\t]+"), " ").replace(Regex("\\n\\s*\\n+"), "\n\n").trim()
                else -> htmlToMd(body)
            }.take(50000)
        }
    }

    private fun websearch(q: String): String {
        val url = "https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(q, "UTF-8")
        val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Android) CodeAgent").build()
        Llm.http.newBuilder().readTimeout(30, TimeUnit.SECONDS).build().newCall(req).execute().use { r ->
            val html = r.body?.string() ?: ""
            val links = Regex("class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).findAll(html).toList()
            val snips = Regex("class=\"result__snippet\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).findAll(html).toList()
            if (links.isEmpty()) return "No search results found. Please try a different query."
            return links.take(8).mapIndexed { i, m ->
                var href = m.groupValues[1]
                Regex("uddg=([^&]+)").find(href)?.let { href = URLDecoder.decode(it.groupValues[1], "UTF-8") }
                "${i + 1}. ${htmlToMd(m.groupValues[2])}\n   $href\n   ${snips.getOrNull(i)?.let { htmlToMd(it.groupValues[1]) } ?: ""}"
            }.joinToString("\n")
        }
    }
}
