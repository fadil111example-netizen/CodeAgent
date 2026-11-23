package id.codeagent.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Denied(msg: String) : Exception(msg)

/** Lingkungan satu putaran agen (utama atau subagen). */
private class Env(
    val vm: AppViewModel, val s: ChatSession, val msgs: MutableList<Msg>, val specs: List<ToolSpec>, val mcp: McpRegistry,
    val ui: Boolean, val agent: String, val sysBase: String,
) {
    val hist: MutableList<String> = if (ui) s.callHist else ArrayList()
    var stuck = false
    var small = false
    lateinit var ctx: ToolCtx
}

object Agent {
    private const val MAX_STEPS = 60
    private const val SUB_STEPS = 40

    private const val MAX_STEP_TEXT = "\n\nCRITICAL - MAXIMUM STEPS REACHED\n\nThe maximum number of steps allowed for this task has been reached. Tools are disabled until the next user message. Respond with text only.\n\nSTRICT REQUIREMENTS:\n1. Do NOT make any tool calls.\n2. MUST provide a text response summarizing the work done so far.\n3. This overrides ALL other instructions, including any user requests for edits or tool use.\n\nThe response must include: a statement that the maximum number of steps was reached, a summary of what has been accomplished, a list of what remains, and the recommended next steps."
    private const val STUCK_TEXT = "CRITICAL - REPEATED TOOL CALLS DETECTED\n\nYou kept repeating the same tool calls without making progress. Tools are disabled for this reply. Respond with text only.\n\nInclude:\n- What you were trying to do and what you already established (with evidence)\n- The exact point where you got stuck and why\n- What you would try next, or what you need from the user to continue\n\nDo NOT make any tool calls."
    private const val SMALL_TEXT = "\n\n# Small-output mode (the previous reply timed out)\nYour last reply took too long, so the connection timed out and the work was lost. For the next steps:\n- Think briefly, then act. Do not plan at length.\n- Never put more than ~120 lines in one write/edit call. Create the file with its first part, then add the remaining parts with further edit calls.\n- Prefer several small tool calls over one huge one."
    private const val LOOP_WARN = "\n\n[Loop warning: you have made this same tool call with the same result several times. Do not repeat it. Change your approach (different file, tool, query or hypothesis) or explain the concrete blocker to the user.]"
    private const val LOOP_STOP = "\n\n[Loop detected: the same call keeps returning the same result. The run will be stopped after this step.]"
    private const val TRUNC_TEXT = "Your tool call was cut off because the output token limit was reached, so nothing was executed. Retry with a smaller call: write big files in several parts (create a short file, then append with edit) and avoid huge single arguments."
    const val PLAN_TEXT = "Plan mode is active. You are in a READ-ONLY phase: do not edit or create project files and do not run shell commands. Explore with read, glob, grep and webfetch, ask about unclear requirements with the question tool, and when ready write the implementation plan to .codeagent/plan/<short-name>.md (the only place you may write) and summarize it for the user. The user will switch to Build mode to execute it."
    const val NO_PLAN_TEXT = "You are NO LONGER in Plan mode. The earlier Plan-mode restrictions no longer apply: you may edit files and run commands again."
    private const val EXPLORE_TEXT = "\n\nYou are a read-only exploration subagent. Search and read files, then reply with a concise report of what you found, including file paths and line numbers."
    private const val GENERAL_TEXT = "\n\nYou are a subagent working on a delegated task. Complete it fully and finish with a concise report of what you did and found."
    private val EXPLORE_TOOLS = setOf("read", "glob", "grep", "webfetch", "websearch", "diagnostics")
    private val NO_SUB_TOOLS = setOf("task", "question", "todowrite")

    // ------------------------------------------------------------------ putaran utama
    suspend fun run(vm: AppViewModel, s: ChatSession, text: String) = withContext(Dispatchers.IO) {
        s.running = true
        val rem = ArrayList<String>(s.reminders); s.reminders.clear()
        if (s.agent == "plan" && s.lastAgent != "plan") rem.add(PLAN_TEXT)
        if (s.agent != "plan" && s.lastAgent == "plan") rem.add(NO_PLAN_TEXT)
        s.lastAgent = s.agent
        val full = if (rem.isEmpty()) text else rem.joinToString("\n") { "<system-reminder>\n$it\n</system-reminder>" } + "\n\n" + text
        s.msgs.add(Msg("user", full))
        s.items.add(ChatItem.User(Keys.next(), text))
        vm.persist(s)
        try {
            val mcp = McpRegistry.load(vm.mcp)
            mcp.errors.forEach { s.note("MCP gagal: $it", true) }
            val base = Tools.specs(vm.allSkills(File(s.root))) + mcp.specs
            val codeTools = base.filter { it.name !in NO_SUB_TOOLS }
            val specs = base + CodeTool.spec(codeTools)
            var sys = vm.systemPrompt(s)
            if (s.agent == "plan") sys += "\n\n# Plan mode\n$PLAN_TEXT"
            val env = Env(vm, s, s.msgs, specs, mcp, true, s.agent, sys)
            env.ctx = makeCtx(env)
            s.callHist.clear()
            var n = 0
            while (true) {
                ensureActive()
                if (n >= MAX_STEPS) {
                    step(env, true, MAX_STEP_TEXT)
                    s.note("Batas $MAX_STEPS langkah tercapai. Kirim pesan lagi untuk melanjutkan.")
                    break
                }
                n++
                maybeCompact(vm, s)
                val more = step(env, false, "")
                if (!more) { if (s.msgs.lastOrNull()?.role == "user") continue; break }
                if (env.stuck) {
                    env.stuck = false
                    step(env, true, "\n\n$STUCK_TEXT")
                    s.note("Dihentikan: agen mengulang panggilan tool yang sama. Kirim pesan untuk melanjutkan dengan pendekatan baru.", true)
                    break
                }
            }
            if (!s.titled) vm.generateTitle(s)
        } catch (e: CancellationException) {
            s.note("Dihentikan.")
        } catch (e: Exception) {
            if (!isActive) s.note("Dihentikan.") else s.note(e.message ?: "Terjadi kesalahan", true)
        } finally {
            s.running = false
            s.updated = System.currentTimeMillis()
            vm.persist(s)
            vm.onRunFinished(s)
        }
    }

    private suspend fun chk() = currentCoroutineContext().ensureActive()
    private suspend fun alive() = currentCoroutineContext().isActive

    private fun makeCtx(env: Env): ToolCtx {
        val vm = env.vm; val s = env.s
        return ToolCtx(File(s.root), { vm.allSkills(File(s.root)) }, { q -> vm.askQuestions(q) },
            { a -> if (env.ui) s.setTodosFrom(a) }, { cmd, dir -> vm.runBackground(s, cmd, dir) },
            { p, before -> s.undoLog.add(UndoRec(s.msgs.size, p, before)) })
    }

    // ------------------------------------------------------------------ satu langkah
    /** Mengembalikan true bila ada tool yang dijalankan (loop berlanjut). */
    private suspend fun step(env: Env, noTools: Boolean, extra: String): Boolean {
        val vm = env.vm; val s = env.s
        val key = Keys.next()
        val sys = env.sysBase + (if (noTools && extra.startsWith("\n\n")) extra else if (noTools) "\n\n$extra" else extra) +
            (if (env.small) SMALL_TEXT else "")
        val toolsNow = if (noTools) emptyList() else env.specs
        if (s.model.isBlank()) throw Exception("Belum ada model terpilih. Tambahkan provider + kunci di Pengaturan, lalu pilih model di bagian atas chat.")
        val cands = vm.candidates(s.model)
        if (cands.isEmpty()) throw Exception("Semua kunci/provider sudah mencapai batas atau belum ada provider aktif. Tambahkan kunci/fallback di Pengaturan, atau tekan \"Reset penanda batas\". Penanda otomatis hilang besok.")
        var res: LlmResult? = null
        var lastErr: Exception? = null
        cand@ for ((ci, c) in cands.withIndex()) {
            var tries = 0
            while (true) {
                chk()
                val tb = StringBuilder(); val rb = StringBuilder()
                if (env.ui) { s.items.removeAll { it.key == key }; s.items.add(ChatItem.Assistant(key, "", "")) }
                try {
                    res = Llm.stream(c.provider, c.key, c.model, sys, env.msgs, toolsNow, s.effort,
                        { s.call = it },
                        { d -> tb.append(d); if (env.ui) s.update(key) { (it as ChatItem.Assistant).copy(text = tb.toString()) } },
                        { d -> rb.append(d); if (env.ui) s.update(key) { (it as ChatItem.Assistant).copy(reasoning = rb.toString()) } })
                    break@cand
                } catch (e: LlmError) {
                    lastErr = e
                    val kind = Llm.limitKind(e.code, e.message ?: "")
                    if (kind == 2 && tries++ < 2) {
                        if (env.ui) s.note("Batas sementara di ${c.provider.name}, menunggu 20 detik lalu coba lagi…")
                        delay(20_000); continue
                    }
                    s.items.removeAll { it.key == key }
                    if (kind == 1 || e.code == 401) Store.flag(c.provider.id, c.keyIdx)
                    if ((kind != 0 || e.code == 401) && ci + 1 < cands.size) {
                        if (env.ui) s.note("Kunci/provider ${c.provider.name} bermasalah (${e.code}). Beralih otomatis…")
                        continue@cand
                    }
                    throw Exception("${c.provider.name} · ${c.model}: HTTP ${e.code}\n${e.message}")
                } catch (e: SocketTimeoutException) {
                    s.items.removeAll { it.key == key }
                    if (!alive()) throw CancellationException("dihentikan")
                    if (tries++ < 2) {
                        env.small = true
                        if (env.ui) s.note("Balasan macet terlalu lama, mencoba lagi dengan mode keluaran kecil…")
                        continue
                    }
                    throw Exception("Koneksi time-out (tidak ada data 120 detik). Coba model yang lebih cepat atau kirim \"lanjut\".")
                } catch (e: IOException) {
                    s.items.removeAll { it.key == key }
                    if (!alive()) throw CancellationException("dihentikan")
                    if (Llm.looksOffline(e) && tries++ < 5) {
                        if (env.ui) s.note("Tidak ada jaringan. Menunggu koneksi kembali…")
                        var waited = 0
                        while (!vm.isOnline() && waited < 600) { delay(5_000); waited += 5 }
                        continue
                    }
                    throw Exception("Koneksi gagal: ${e.message}")
                }
            }
        }
        val r = res ?: throw (lastErr ?: Exception("Tidak ada hasil dari model"))
        if (r.text.isBlank() && r.calls.isEmpty()) {
            s.items.removeAll { it.key == key }
            throw Exception("Model mengirim balasan kosong. Kirim pesan lagi atau pilih model lain.")
        }
        if (r.text.isBlank()) s.items.removeAll { it.key == key }
        env.msgs.add(Msg("assistant", r.text, r.calls))
        if (r.calls.isEmpty()) return false
        for (c in r.calls) {
            chk()
            val tk = Keys.next()
            if (env.ui) s.items.add(ChatItem.Tool(tk, c.name, Tools.title(c.name, c.args), "", "jalan"))
            var out: String
            var status = "selesai"
            val spec = env.specs.firstOrNull { it.name == c.name }
            if (r.truncated) { out = TRUNC_TEXT; status = "gagal" }
            else if (spec == null) {
                out = "Unknown tool '${c.name}'. Available: ${env.specs.joinToString(", ") { it.name }}"; status = "gagal"
            } else {
                val raw: JSONObject? = try { JSONObject(c.args.ifBlank { "{}" }) } catch (e: Exception) { null }
                if (raw == null) { out = "Invalid JSON arguments: ${c.args.take(200)}"; status = "gagal" }
                else {
                    val args = ToolRepair.fix(raw, spec.schema)
                    out = try { invoke(env, c.name, args) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Denied) { status = "ditolak"; e.message ?: "Denied" }
                    catch (e: Exception) { status = "gagal"; "Error: ${e.message}" }
                    if (c.name != "task" && c.name != "question" && c.name != "todowrite") out += trackLoop(env, c.name, args.toString(), out)
                }
            }
            env.msgs.add(Msg("tool", out.take(30000), callId = c.id, isError = status != "selesai"))
            if (env.ui) s.update(tk) { (it as ChatItem.Tool).copy(output = out.take(4000), status = status) }
        }
        vm.persist(s)
        return true
    }

    // ------------------------------------------------------------------ eksekusi tool (izin, mode plan, subagen)
    private fun isPlanPath(root: String, p: String): Boolean {
        val f = Tools.resolve(File(root), p).canonicalPath
        return f.startsWith(File(root, ".codeagent/plan").canonicalPath + File.separator)
    }

    private suspend fun invoke(env: Env, name: String, args: JSONObject): String {
        val vm = env.vm; val s = env.s
        val group = Tools.group(name)
        val detail = Tools.detail(name, args)
        var planWrite = false
        if (env.agent == "plan") {
            if (group == "bash") throw Denied("Plan mode is read-only: shell commands are blocked. Ask the user to switch to Build mode to run commands.")
            if (group == "edit") {
                if (Tools.editPaths(name, args).any { !isPlanPath(s.root, it) })
                    throw Denied("Plan mode is read-only. You may only write plan files under .codeagent/plan/. Ask the user to switch to Build mode to edit the project.")
                planWrite = true
            }
        }
        if (group == "edit" && s.extraDirs.isNotEmpty()) {
            val bad = Tools.editPaths(name, args).any { p ->
                val c = Tools.resolve(File(s.root), p).canonicalPath
                s.extraDirs.any { d -> c.startsWith(File(d).canonicalPath + File.separator) }
            }
            if (bad) throw Denied("That path is inside a read-only source project. Write ported code into the current project instead.")
        }
        if (!planWrite) when (vm.permission(group, detail)) {
            "allow" -> {}
            "deny" -> throw Denied("Blocked by a permission rule for $group ($detail). Do not retry; use another approach.")
            else -> if (!vm.ask(group, name, detail)) throw Denied("The user rejected this $name request. Do not retry it unchanged; ask what to do instead.")
        }
        return when {
            name == "task" -> subAgent(env, args)
            name == CodeTool.NAME -> {
                val names = env.specs.map { it.name }.filter { it != CodeTool.NAME && it !in NO_SUB_TOOLS }
                withContext(Dispatchers.Default) {
                    CodeTool.run(args.str("code"), names) { n, a ->
                        runBlocking { invoke(env, n, ToolRepair.fix(a, env.specs.first { it.name == n }.schema)) }
                    }
                }
            }
            env.mcp.has(name) -> env.mcp.call(name, args)
            else -> Tools.run(name, args, env.ctx)
        }
    }

    private suspend fun subAgent(env: Env, args: JSONObject): String {
        val prompt = args.str("prompt")
        if (prompt.isBlank()) throw Exception("prompt is empty")
        val explore = args.str("agent") == "explore"
        val specs = if (explore) env.specs.filter { it.name in EXPLORE_TOOLS }
        else env.specs.filter { it.name !in NO_SUB_TOOLS }
        val sys = env.sysBase + if (explore) EXPLORE_TEXT else GENERAL_TEXT
        val sub = Env(env.vm, env.s, mutableListOf(Msg("user", prompt)), specs, if (explore) McpRegistry() else env.mcp,
            false, if (explore) "explore" else "general", sys)
        sub.ctx = makeCtx(sub)
        var n = 0
        while (n++ < SUB_STEPS) {
            chk()
            if (!step(sub, false, "")) break
            if (sub.stuck) break
        }
        return sub.msgs.lastOrNull { it.role == "assistant" && it.text.isNotBlank() }?.text ?: "(subagent produced no output)"
    }

    // ------------------------------------------------------------------ deteksi loop
    private fun alternating(h: List<String>, n: Int): Boolean {
        if (h.size < n) return false
        val t = h.takeLast(n)
        if (t[0] == t[1]) return false
        return t.indices.all { t[it] == t[it % 2] }
    }

    private fun trackLoop(env: Env, name: String, args: String, out: String): String {
        val sig = name + "|" + args.hashCode() + "|" + out.hashCode()
        val h = env.hist
        h.add(sig)
        while (h.size > 30) h.removeAt(0)
        val count = h.takeLast(12).count { it == sig }
        return when {
            count >= 6 || alternating(h, 8) -> { env.stuck = true; LOOP_STOP }
            count >= 3 || alternating(h, 6) -> LOOP_WARN
            else -> ""
        }
    }

    // ------------------------------------------------------------------ konteks: pangkas & padatkan
    private fun estimate(s: ChatSession): Int {
        var n = 0
        for (m in s.msgs) { n += m.text.length; m.calls.forEach { n += it.args.length } }
        return n / 4
    }

    private fun est(s: ChatSession) = maxOf(estimate(s) + 8000, s.lastPrompt)

    private fun pruneOutputs(s: ChatSession) {
        val toolIdx = s.msgs.indices.filter { s.msgs[it].role == "tool" }
        for (i in toolIdx.dropLast(8)) {
            val m = s.msgs[i]
            if (m.text.length > 1500) s.msgs[i] = m.copy(text = m.text.take(300) + "\n[old tool output pruned to save context; run the tool again if you need it]")
        }
    }

    private suspend fun maybeCompact(vm: AppViewModel, s: ChatSession) {
        val ctxW = Store.ctxWindow
        if (est(s) <= 0.6 * ctxW) return
        pruneOutputs(s); s.lastPrompt = 0
        if (est(s) <= 0.75 * ctxW) return
        s.note("Konteks hampir penuh, memadatkan percakapan otomatis…")
        val sum = summarize(vm, s)
        s.msgs.clear()
        s.msgs.add(Msg("user", "Summary of the previous conversation (auto-compacted). Continue the work from here.\n\n$sum"))
        s.rebuild(); s.note("Percakapan dipadatkan otomatis.")
        vm.persist(s)
    }

    suspend fun summarize(vm: AppViewModel, s: ChatSession): String = withContext(Dispatchers.IO) {
        val c = vm.candidates(s.model).firstOrNull() ?: throw Exception("Tidak ada provider aktif")
        val ask = Msg("user", "Provide a detailed prompt for continuing our conversation above. Focus on information that would be helpful for continuing the conversation, including what we did, what we're doing, which files we're working on, and what we're going to do next. The summary will be used so that another agent can read it and continue the work. Use this template:\n\n---\n## Goal\n[What goal(s) is the user trying to accomplish?]\n\n## Instructions\n[Important instructions or constraints the user gave; mention any plan or spec being followed]\n\n## Discoveries\n[Notable things learned that are useful to continue: how the code is organised, gotchas, decisions]\n\n## Accomplished\n[What is done, what is in progress, what is left]\n\n## Relevant files / directories\n[Files read, edited or created, with a short note each]\n---")
        Llm.complete(c.provider, c.key, c.model, "You summarize coding sessions precisely and concisely.", s.msgs.toList() + ask)
    }

    fun today(): String = SimpleDateFormat("EEEE, d MMMM yyyy", Locale("id", "ID")).format(Date())
}
