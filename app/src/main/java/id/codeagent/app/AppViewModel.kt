package id.codeagent.app

import android.app.Application
import android.content.Context
import android.os.Build
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID

data class Cand(val provider: Provider, val key: String, val keyIdx: Int, val model: String)
class QItem(val question: String, val options: List<String>)
class QAsk(val items: List<QItem>, val answer: CompletableDeferred<String>)
class PermAsk(val group: String, val tool: String, val detail: String, val answer: CompletableDeferred<Boolean>)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Context get() = getApplication<Application>()
    private val sessDir get() = File(ctx.filesDir, "sessions")

    // ---- pengaturan (tersimpan) ----
    var providers by mutableStateOf(Store.providers); private set
    var fallbacks by mutableStateOf(Store.fallbacks); private set
    var mcp by mutableStateOf(Store.mcp); private set
    var rules by mutableStateOf(Store.rules); private set
    var commands by mutableStateOf(Store.commands); private set
    var userSkills by mutableStateOf(Store.skills); private set
    var selected by mutableStateOf(Store.selected); private set
    var ctxWindow by mutableStateOf(Store.ctxWindow); private set
    var prefill by mutableStateOf("")
    var autoSwitch by mutableStateOf(Store.autoSwitch); private set
    var autoApprove by mutableStateOf(Store.autoApprove); private set
    var voice by mutableStateOf(Store.voice); private set
    var effort by mutableStateOf(Store.effort); private set
    var theme by mutableStateOf(Store.theme); private set

    fun ubahProviders(v: List<Provider>) { providers = v; Store.providers = v }
    fun ubahFallbacks(v: List<Fallback>) { fallbacks = v; Store.fallbacks = v }
    fun ubahMcp(v: List<McpServer>) { mcp = v; Store.mcp = v }
    fun ubahRules(v: List<PermRule>) { rules = v; Store.rules = v }
    fun ubahCommands(v: List<Command>) { commands = v; Store.commands = v }
    fun ubahUserSkills(v: List<SkillDef>) { userSkills = v; Store.skills = v }
    fun ubahSelected(v: String) { selected = v; Store.selected = v }
    fun ubahAutoSwitch(v: Boolean) { autoSwitch = v; Store.autoSwitch = v }
    fun ubahAutoApprove(v: Boolean) { autoApprove = v; Store.autoApprove = v }
    fun ubahVoice(v: Boolean) { voice = v; Store.voice = v }
    fun ubahEffort(v: String) { effort = v; Store.effort = v }
    fun ubahTheme(v: String) { theme = v; Store.theme = v }

    // ---- navigasi & UI ----
    val sessions = mutableStateListOf<ChatSession>()
    var currentId by mutableStateOf<String?>(null)
    var showSettings by mutableStateOf(false)
    var showModelSheet by mutableStateOf(false)
    var pendingAsk by mutableStateOf<PermAsk?>(null); private set
    var pendingQuestion by mutableStateOf<QAsk?>(null); private set
    var toast by mutableStateOf<String?>(null)
    var permTick by mutableIntStateOf(0)
    var inForeground = true
    val current: ChatSession? get() = sessions.firstOrNull { it.id == currentId }

    private var tts: TextToSpeech? = null
    private var activeRuns = 0

    init { loadSessions() }

    fun loadSessions() {
        sessions.clear()
        val files = sessDir.listFiles { f -> f.extension == "json" } ?: emptyArray()
        files.mapNotNull { try { ChatSession.from(org.json.JSONObject(it.readText())) } catch (e: Exception) { null } }
            .sortedByDescending { it.updated }.forEach { sessions.add(it) }
    }

    fun persist(s: ChatSession) = s.save(sessDir)

    fun newSession(folder: String, name: String, copyFrom: String): ChatSession? {
        val root = File(folder.trim())
        try {
            root.mkdirs()
            if (copyFrom.isNotBlank() && File(copyFrom.trim()).isDirectory && (root.list()?.isEmpty() != false))
                File(copyFrom.trim()).copyRecursively(root, overwrite = false)
        } catch (e: Exception) { toast = "Gagal menyiapkan folder: ${e.message}"; return null }
        if (!root.isDirectory) { toast = "Folder tidak bisa dibuat/diakses. Berikan akses semua file di Pengaturan."; return null }
        Store.lastFolder = root.path
        val s = ChatSession(UUID.randomUUID().toString(), name.ifBlank { root.name }, root.path, defaultModel(), effort)
        sessions.add(0, s); persist(s); currentId = s.id
        return s
    }

    fun deleteSession(s: ChatSession) {
        s.stop(); sessions.remove(s); File(sessDir, "${s.id}.json").delete()
        if (currentId == s.id) currentId = null
    }

    fun defaultModel(): String {
        if (selected.isNotBlank()) return selected
        val p = providers.firstOrNull { it.enabled && it.models.isNotEmpty() } ?: return ""
        return p.id + "|" + p.models.first()
    }

    fun allModels(): List<Pair<String, String>> =
        providers.filter { it.enabled }.flatMap { p -> p.models.map { (p.id + "|" + it) to (p.name + " · " + it) } }

    fun modelLabel(sel: String): String {
        val (pid, m) = sel.split("|", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val p = providers.firstOrNull { it.id == pid } ?: return "Pilih model"
        return p.name + "/" + m
    }

    fun ubahCtxWindow(v: Int) { ctxWindow = v; Store.ctxWindow = v }

    fun chooseModel(s: ChatSession?, sel: String) { s?.model = sel; ubahSelected(sel); s?.let { persist(it) } }

    // ---- pemilihan provider/kunci + pergantian otomatis ----
    fun candidates(sel: String): List<Cand> {
        val out = ArrayList<Cand>()
        fun add(pid: String, model: String) {
            val p = providers.firstOrNull { it.id == pid && it.enabled } ?: return
            val ks = if (p.keys.isEmpty()) listOf("") else p.keys
            ks.forEachIndexed { i, k -> if (!Store.isFlagged(pid, i)) out.add(Cand(p, k, i, model)) }
        }
        val parts = sel.split("|", limit = 2)
        if (parts.size == 2) add(parts[0], parts[1])
        if (!autoSwitch) return out.take(1)
        fallbacks.forEach { add(it.providerId, it.model) }
        return out
    }

    fun isLimit(e: LlmError): Boolean =
        e.code in listOf(401, 402, 403, 429, 529) ||
            Regex("quota|rate.?limit|insufficient|exceeded", RegexOption.IGNORE_CASE).containsMatchIn(e.message ?: "")

    // ---- izin ----
    fun permission(group: String, detail: String): String {
        var verdict = if (group == "read") "allow" else if (autoApprove) "allow" else "ask"
        for (r in rules) {
            if (r.tool != group && r.tool != "*") continue
            val re = Regex(r.pattern.split("*").joinToString(".*") { Regex.escape(it) })
            if (re.matches(detail)) verdict = r.action
        }
        return verdict
    }

    suspend fun ask(group: String, tool: String, detail: String): Boolean {
        val d = CompletableDeferred<Boolean>()
        pendingAsk = PermAsk(group, tool, detail, d)
        try { return d.await() } finally { pendingAsk = null }
    }

    suspend fun askQuestions(arr: org.json.JSONArray): String {
        val items = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            .map { o -> QItem(o.str("question"), o.optJSONArray("options")?.strings() ?: emptyList()) }
            .filter { it.question.isNotBlank() }
        if (items.isEmpty()) return "No questions provided."
        val d = CompletableDeferred<String>()
        pendingQuestion = QAsk(items, d)
        try { return d.await() } finally { pendingQuestion = null }
    }

    fun answerQuestions(text: String) { pendingQuestion?.answer?.complete(text) }

    private var bgCounter = 0

    fun runBackground(s: ChatSession, cmd: String, dir: File): String {
        val id = "bg${++bgCounter}"
        Thread {
            val (out, code) = try { Tools.exec(cmd, dir, 3_600_000L) { } } catch (e: Exception) { ("Error: ${e.message}" to -1) }
            s.reminders.add("Background command $id (`${cmd.take(100)}`) finished with exit code $code. Output tail:\n${out.takeLast(4000)}")
            s.note("Perintah latar $id selesai (exit $code)")
            if (!inForeground) Notify.done(ctx, "Perintah latar selesai")
        }.start()
        return id
    }

    fun isOnline(): Boolean = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (e: Exception) { true }

    fun answerAsk(allow: Boolean, always: Boolean) {
        val a = pendingAsk ?: return
        if (always && allow) ubahRules(rules + PermRule(a.group, "*", "allow"))
        a.answer.complete(allow)
    }

    // ---- prompt & skill ----
    fun allSkills(root: File?): List<SkillDef> {
        val out = ArrayList<SkillDef>()
        try {
            val am = ctx.assets
            am.list("skills")?.forEach { d ->
                try { parseSkill(am.open("skills/$d/SKILL.md").bufferedReader().readText())?.let { out.add(it) } } catch (e: Exception) { }
            }
        } catch (e: Exception) { }
        out.addAll(userSkills)
        if (root != null) for (base in listOf(".codeagent/skills", ".claude/skills")) {
            File(root, base).listFiles()?.forEach { d ->
                val f = File(d, "SKILL.md")
                if (f.isFile) parseSkill(f.readText())?.let { out.add(it) }
            }
        }
        return out.distinctBy { it.name }
    }

    private fun parseSkill(t: String): SkillDef? {
        val m = Regex("(?s)^---\\s*\\n(.*?)\\n---\\s*\\n?(.*)$").find(t.trim()) ?: return null
        val fm = m.groupValues[1]
        val n = Regex("(?m)^name:\\s*(.+)$").find(fm)?.groupValues?.get(1)?.trim() ?: return null
        val d = Regex("(?m)^description:\\s*(.+)$").find(fm)?.groupValues?.get(1)?.trim() ?: ""
        return SkillDef(n, d, m.groupValues[2].trim())
    }

    fun systemPrompt(s: ChatSession): String {
        val base = try { ctx.assets.open("prompts/system.txt").bufferedReader().readText() } catch (e: Exception) { "Kamu adalah agen coding." }
        val root = File(s.root)
        val sb = StringBuilder(base)
        sb.append("\n\n<env>\nWorking directory: ${s.root}\nPlatform: Android ${Build.VERSION.RELEASE} (${Build.MODEL}); shell: /system/bin/sh with toybox utilities only unless the user installed more\nBuild limitation: building an APK/AAB is normally not possible in this environment (no JDK, Gradle or Android SDK). Do not run gradle or assemble tasks unless the user explicitly asks; when the work is done, tell the user to build it themselves (Android Studio or CI).\nToday's date: ${Agent.today()}\n</env>\n")
        for (n in listOf("AGENTS.md", "CODEAGENT.md", "CLAUDE.md")) {
            val f = File(root, n)
            if (f.isFile) { sb.append("\n# Instruksi proyek ($n)\n").append(f.readText().take(12000)).append('\n'); break }
        }
        if (s.extraDirs.isNotEmpty()) {
            sb.append("\n# Source projects (read-only)\nThese folders are source projects to copy features from. You may read and search them, but never modify them. Write all ported code into the current project and follow ITS structure and conventions:\n")
            s.extraDirs.forEach { sb.append("- $it\n") }
        }
        val sk = allSkills(root)
        if (sk.isNotEmpty()) {
            sb.append("\n# Skill tersedia (muat dengan tool `skill`)\n")
            sk.forEach { sb.append("- ${it.name}: ${it.description}\n") }
        }
        return sb.toString()
    }

    fun projectCommands(root: String): List<Command> =
        File(root, ".codeagent/commands").listFiles { f -> f.extension == "md" }
            ?.map { Command(it.nameWithoutExtension, it.readText()) } ?: emptyList()

    // ---- kirim pesan & perintah slash ----
    fun send(s: ChatSession, raw: String) {
        val text = raw.trim()
        if (text.isEmpty() || s.running) return
        if (text.startsWith("/")) { if (slash(s, text)) return }
        launchRun(s, text)
    }

    private fun launchRun(s: ChatSession, text: String) {
        if (candidates(s.model).isEmpty() && providers.none { it.enabled }) {
            s.note("Belum ada provider aktif. Buka Pengaturan, tambahkan provider + kunci API, lalu pilih model.", true); return
        }
        if (activeRuns++ == 0) AgentService.start(ctx)
        s.job = viewModelScope.launch { Agent.run(this@AppViewModel, s, text) }
    }

    fun onRunFinished(s: ChatSession) {
        if (--activeRuns <= 0) { activeRuns = 0; AgentService.stop(ctx) }
        if (!inForeground) {
            val last = s.msgs.lastOrNull { it.role == "assistant" }?.text ?: "Tugas selesai"
            Notify.done(ctx, last)
            if (voice) speak("Tugas selesai, Bos.")
        }
    }

    fun speak(t: String) {
        if (tts == null) {
            tts = TextToSpeech(ctx) { st ->
                if (st == TextToSpeech.SUCCESS) { tts?.language = Locale("id", "ID"); tts?.speak(t, TextToSpeech.QUEUE_FLUSH, null, "done") }
            }
        } else tts?.speak(t, TextToSpeech.QUEUE_FLUSH, null, "done")
    }

    private fun slash(s: ChatSession, text: String): Boolean {
        val name = text.substring(1).substringBefore(' ').lowercase()
        val args = text.substringAfter(' ', "").trim()
        when (name) {
            "bantuan", "help" -> s.note("Perintah: /init, /plan, /build, /undo, /port <folder sumber> [fitur], /model, /effort <auto|low|medium|high>, /compact, /clear, /bantuan" +
                (commands + projectCommands(s.root)).joinToString("") { ", /${it.name}" })
            "model", "models" -> showModelSheet = true
            "effort" -> if (args in listOf("auto", "low", "medium", "high")) { s.effort = args; persist(s); s.note("Tingkat penalaran sesi: $args") }
                else s.note("Pakai: /effort auto|low|medium|high", true)
            "clear" -> { s.msgs.clear(); s.items.clear(); persist(s); s.note("Percakapan dibersihkan.") }
            "compact" -> compact(s)
            "plan" -> switchAgent(s, "plan")
            "build" -> switchAgent(s, "build")
            "undo" -> undo(s)
            "port" -> port(s, args)
            "init" -> {
                launchRun(s, "Analyze this project and create or update AGENTS.md in the project root. It must be short and accurate, and contain only what a future agent could not guess: what the project is, the important folders, the exact build/test/lint commands (taken from the build files and CI config, not invented), code conventions you actually observed, and pitfalls. Read the README, build configuration and CI files first, and verify every command against them. If AGENTS.md already exists, improve it instead of replacing it. Write it in Indonesian.")
            }
            "ekspor" -> toast = "Gunakan menu ⋮ di atas chat untuk mengekspor."
            else -> {
                val cmd = (commands + projectCommands(s.root)).firstOrNull { it.name.lowercase() == name }
                    ?: run { s.note("Perintah tidak dikenal: /$name (ketik /bantuan)", true); return true }
                val parts = args.split(" ").filter { it.isNotEmpty() }
                var p = cmd.prompt.replace("\$ARGUMENTS", args)
                parts.forEachIndexed { i, a -> p = p.replace("\$${i + 1}", a) }
                launchRun(s, p)
            }
        }
        return true
    }

    fun switchAgent(s: ChatSession, agent: String) {
        if (s.running) { toast = "Hentikan agen dulu sebelum mengganti mode."; return }
        s.agent = agent; persist(s)
        s.note(if (agent == "plan") "Mode Plan: agen hanya membaca dan merencanakan (tidak mengubah file)." else "Mode Build: agen boleh mengubah file dan menjalankan perintah.")
    }

    fun undo(s: ChatSession) {
        if (s.running) { toast = "Hentikan agen dulu sebelum /undo."; return }
        val idx = s.msgs.indexOfLast { it.role == "user" }
        if (idx < 0) { s.note("Tidak ada yang bisa dibatalkan."); return }
        val userText = s.msgs[idx].text.replace(Regex("(?s)<system-reminder>.*?</system-reminder>\\s*"), "").trim()
        val recs = s.undoLog.filter { it.idx >= idx }
        var n = 0
        for (r in recs.asReversed()) {
            try {
                val f = File(r.path)
                if (r.before == null) f.delete() else { f.parentFile?.mkdirs(); f.writeText(r.before) }
                n++
            } catch (e: Exception) { }
        }
        s.undoLog.removeAll { it.idx >= idx }
        while (s.msgs.size > idx) s.msgs.removeAt(s.msgs.size - 1)
        s.rebuild()
        s.note("Dibatalkan: $n perubahan file dikembalikan dan giliran terakhir dihapus.")
        persist(s)
        prefill = userText
    }

    private fun port(s: ChatSession, args: String) {
        val m = Regex("^\"([^\"]+)\"\\s*(.*)$|^(\\S+)\\s*(.*)$", RegexOption.DOT_MATCHES_ALL).find(args.trim())
        val src = (m?.groupValues?.get(1)?.ifEmpty { null } ?: m?.groupValues?.get(3) ?: "").trim()
        val what = (m?.groupValues?.get(2)?.ifEmpty { null } ?: m?.groupValues?.get(4) ?: "").trim()
        if (src.isEmpty()) { s.note("Pakai: /port <folder proyek sumber> [fitur yang ingin disalin]", true); return }
        if (!File(src).isDirectory) { s.note("Folder sumber tidak ditemukan: $src", true); return }
        if (src !in s.extraDirs) s.extraDirs.add(src)
        persist(s)
        launchRun(s, "Port functionality from a SOURCE project into the TARGET project (the current working directory).\n\nSOURCE (read-only, never modify): $src\nTARGET: ${s.root}\nWhat to port: ${what.ifEmpty { "the main feature(s) the user wants; if unclear, inspect the SOURCE and ask with the question tool" }}\n\nWork in this order:\n1. Study the TARGET first: build files, manifest, AGENTS.md/README, package layout, naming, architecture and UI patterns.\n2. Study the requested feature in the SOURCE: entry points, data flow, resources, dependencies.\n3. Write TARGET-native code. Translate instead of copying: follow the TARGET's conventions and existing dependencies, and add a new library only when unavoidable.\n4. Wire everything up (manifest, navigation, dependency injection, resources, strings).\n5. Verify: re-read what you changed and run diagnostics.\n6. Finish with what was ported, what was adapted, and what the user must build or test.")
    }

    fun generateTitle(s: ChatSession) {
        if (s.titled) return
        val first = s.msgs.firstOrNull { it.role == "user" }?.text
            ?.replace(Regex("(?s)<system-reminder>.*?</system-reminder>\\s*"), "")?.trim() ?: return
        s.titled = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val c = candidates(s.model).firstOrNull() ?: return@launch
                val t = Llm.complete(c.provider, c.key, c.model,
                    "Write a short title (maximum 6 words, no quotes, no trailing punctuation) for a coding conversation that starts with the user's message below. Reply with the title only.",
                    listOf(Msg("user", first.take(1500)))).trim().lines().first().trim('"', ' ', '.').take(60)
                if (t.isNotBlank()) { s.title = t; persist(s) }
            } catch (e: Exception) { }
        }
    }

    private fun compact(s: ChatSession) {
        if (s.msgs.isEmpty()) { s.note("Belum ada percakapan."); return }
        s.running = true
        viewModelScope.launch {
            try {
                val sum = withContext(Dispatchers.IO) { Agent.summarize(this@AppViewModel, s) }
                s.msgs.clear(); s.msgs.add(Msg("user", "Ringkasan sesi sebelumnya:\n$sum"))
                s.rebuild(); s.note("Percakapan dipadatkan.")
                persist(s)
            } catch (e: Exception) { s.note("Gagal memadatkan: ${e.message}", true) }
            finally { s.running = false }
        }
    }

    override fun onCleared() { tts?.shutdown(); super.onCleared() }
}
