package id.codeagent.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Agent {
    private const val MAX_STEPS = 80

    suspend fun run(vm: AppViewModel, s: ChatSession, text: String) = withContext(Dispatchers.IO) {
        s.running = true
        s.msgs.add(Msg("user", text))
        s.items.add(ChatItem.User(Keys.next(), text))
        if (s.msgs.count { it.role == "user" } == 1) s.title = text.lineSequence().first().take(48)
        vm.persist(s)
        try {
            val mcp = McpRegistry.load(vm.mcp)
            mcp.errors.forEach { s.note("MCP gagal: $it", true) }
            val specs = Tools.specs() + mcp.specs
            val ctx = ToolCtx(File(s.root), { vm.allSkills(File(s.root)) })
            var step = 0
            while (step++ < MAX_STEPS) {
                ensureActive()
                val key = Keys.next()
                val cands = vm.candidates(s.model)
                if (cands.isEmpty()) throw Exception("Tidak ada provider/kunci yang tersedia. Aktifkan provider, pilih model, atau tekan \"Reset penanda batas\" di Pengaturan.")
                var res: LlmResult? = null
                var lastErr: Exception? = null
                for ((ci, c) in cands.withIndex()) {
                    val tb = StringBuilder(); val rb = StringBuilder()
                    s.items.add(ChatItem.Assistant(key, "", ""))
                    try {
                        res = Llm.stream(c.provider, c.key, c.model, vm.systemPrompt(s), s.msgs, specs, s.effort,
                            { s.call = it },
                            { d -> tb.append(d); s.update(key) { (it as ChatItem.Assistant).copy(text = tb.toString()) } },
                            { d -> rb.append(d); s.update(key) { (it as ChatItem.Assistant).copy(reasoning = rb.toString()) } })
                        break
                    } catch (e: LlmError) {
                        lastErr = e
                        s.items.removeAll { it.key == key }
                        if (vm.isLimit(e)) {
                            Store.flag(c.provider.id, c.keyIdx)
                            if (ci + 1 < cands.size) { s.note("Batas/kunci bermasalah di ${c.provider.name} (${e.code}). Beralih otomatis…"); continue }
                        }
                        throw Exception("${c.provider.name} · ${c.model}: HTTP ${e.code}\n${e.message}")
                    } catch (e: IOException) {
                        s.items.removeAll { it.key == key }
                        if (!isActive) throw CancellationException("dihentikan")
                        throw Exception("Koneksi gagal: ${e.message}")
                    }
                }
                val r = res ?: throw (lastErr ?: Exception("Tidak ada hasil"))
                if (r.text.isBlank() && r.calls.isEmpty()) s.items.removeAll { it.key == key }
                s.msgs.add(Msg("assistant", r.text, r.calls))
                if (r.calls.isEmpty()) break
                for (c in r.calls) {
                    ensureActive()
                    val tk = Keys.next()
                    s.items.add(ChatItem.Tool(tk, c.name, Tools.title(c.name, c.args), "", "jalan"))
                    var out = ""; var status = "selesai"
                    val args = try { JSONObject(c.args.ifBlank { "{}" }) } catch (e: Exception) { null }
                    if (args == null) { out = "Argumen JSON tidak valid"; status = "gagal" }
                    else {
                        val group = Tools.group(c.name)
                        val detail = Tools.detail(c.name, args)
                        val ok = when (vm.permission(group, detail)) {
                            "allow" -> true
                            "deny" -> false
                            else -> vm.ask(group, c.name, detail)
                        }
                        if (!ok) { out = "Ditolak oleh pengguna atau aturan izin."; status = "ditolak" }
                        else try {
                            out = if (mcp.has(c.name)) mcp.call(c.name, args) else Tools.run(c.name, args, ctx)
                            if (c.name == "todowrite") s.note("Daftar tugas:\n" + args.str("todos"))
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { out = "Error: ${e.message}"; status = "gagal" }
                    }
                    s.msgs.add(Msg("tool", out.take(30000), callId = c.id, isError = status != "selesai"))
                    s.update(tk) { (it as ChatItem.Tool).copy(output = out.take(4000), status = status) }
                }
                vm.persist(s)
            }
            if (step > MAX_STEPS) s.note("Batas $MAX_STEPS langkah tercapai. Kirim pesan lagi untuk melanjutkan.")
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

    fun summarize(vm: AppViewModel, s: ChatSession): String {
        val c = vm.candidates(s.model).firstOrNull() ?: throw Exception("Tidak ada provider aktif")
        val ask = Msg("user", "Ringkas seluruh percakapan di atas untuk melanjutkan pekerjaan di sesi baru: tujuan pengguna, keputusan penting, file yang diubah, status terakhir, dan langkah berikutnya. Singkat dan padat.")
        return Llm.complete(c.provider, c.key, c.model, "Kamu meringkas percakapan agen coding.", s.msgs.toList() + ask)
    }

    fun today(): String = SimpleDateFormat("EEEE, d MMMM yyyy", Locale("id", "ID")).format(Date())
}
