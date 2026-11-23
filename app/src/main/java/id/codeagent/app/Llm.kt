package id.codeagent.app

import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

class LlmError(val code: Int, message: String) : Exception(message)
class ToolSpec(val name: String, val description: String, val schema: JSONObject)
class LlmResult(val text: String, val calls: List<ToolCall>, val truncated: Boolean = false)

object Llm {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    /** Klien streaming: putus bila tidak ada data selama 120 detik (macet). */
    val streamHttp: OkHttpClient = http.newBuilder().readTimeout(120, TimeUnit.SECONDS).build()
    private val JSON_TYPE = "application/json".toMediaType()

    fun stream(
        p: Provider, key: String, model: String, system: String, msgs: List<Msg>, tools: List<ToolSpec>,
        effort: String, onCall: (Call) -> Unit, onText: (String) -> Unit, onReasoning: (String) -> Unit,
    ): LlmResult = if (p.style == "anthropic")
        streamAnthropic(p, key, model, system, msgs, tools, onCall, onText, onReasoning)
    else streamOpenAI(p, key, model, system, msgs, tools, effort, onCall, onText, onReasoning)

    // ---------- OpenAI-compatible ----------
    private fun streamOpenAI(
        p: Provider, key: String, model: String, system: String, msgs: List<Msg>, tools: List<ToolSpec>,
        effort: String, onCall: (Call) -> Unit, onText: (String) -> Unit, onReasoning: (String) -> Unit,
    ): LlmResult {
        val ms = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        for (m in msgs) when (m.role) {
            "user" -> ms.put(JSONObject().put("role", "user").put("content", m.text))
            "assistant" -> {
                val o = JSONObject().put("role", "assistant").put("content", m.text)
                if (m.calls.isNotEmpty()) o.put("tool_calls", JSONArray().also { a ->
                    m.calls.forEach {
                        a.put(JSONObject().put("id", it.id).put("type", "function")
                            .put("function", JSONObject().put("name", it.name).put("arguments", it.args)))
                    }
                })
                ms.put(o)
            }
            "tool" -> ms.put(JSONObject().put("role", "tool").put("tool_call_id", m.callId).put("content", m.text))
        }
        val body = JSONObject().put("model", model).put("stream", true).put("messages", ms)
        if (tools.isNotEmpty()) body.put("tools", JSONArray().also { a ->
            tools.forEach {
                a.put(JSONObject().put("type", "function").put("function",
                    JSONObject().put("name", it.name).put("description", it.description).put("parameters", it.schema)))
            }
        })
        if (effort != "auto") body.put("reasoning_effort", effort)

        val rb = Request.Builder().url(p.baseUrl.trimEnd('/') + "/chat/completions")
            .post(body.toString().toRequestBody(JSON_TYPE))
        if (key.isNotBlank()) rb.header("Authorization", "Bearer $key")
        val call = streamHttp.newCall(rb.build())
        onCall(call)
        call.execute().use { resp ->
            if (!resp.isSuccessful) throw LlmError(resp.code, resp.body?.string()?.take(600) ?: "HTTP ${resp.code}")
            val src = resp.body!!.source()
            val text = StringBuilder()
            val acc = sortedMapOf<Int, Array<StringBuilder>>() // id, nama, argumen
            var truncated = false
            while (true) {
                val line = src.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val d = line.substring(5).trim()
                if (d == "[DONE]") break
                if (d.isEmpty()) continue
                val j = try { JSONObject(d) } catch (e: Exception) { continue }
                j.optJSONObject("error")?.let { throw LlmError(it.optInt("code", 500), it.str("message")) }
                val ch = j.optJSONArray("choices")?.optJSONObject(0) ?: continue
                if (ch.str("finish_reason") == "length") truncated = true
                val delta = ch.optJSONObject("delta") ?: continue
                val c = delta.str("content")
                if (c.isNotEmpty()) { text.append(c); onText(c) }
                val r = delta.str("reasoning_content").ifEmpty { delta.str("reasoning") }
                if (r.isNotEmpty()) onReasoning(r)
                val tcs = delta.optJSONArray("tool_calls")
                if (tcs != null) for (i in 0 until tcs.length()) {
                    val tc = tcs.getJSONObject(i)
                    val e = acc.getOrPut(tc.optInt("index", i)) { arrayOf(StringBuilder(), StringBuilder(), StringBuilder()) }
                    if (tc.str("id").isNotEmpty() && e[0].isEmpty()) e[0].append(tc.str("id"))
                    val fn = tc.optJSONObject("function")
                    if (fn != null) {
                        if (fn.str("name").isNotEmpty() && e[1].isEmpty()) e[1].append(fn.str("name"))
                        e[2].append(fn.str("arguments"))
                    }
                }
            }
            val calls = acc.values.filter { it[1].isNotEmpty() }.map {
                ToolCall(it[0].toString().ifEmpty { "call_" + UUID.randomUUID().toString().take(8) }, it[1].toString(),
                    it[2].toString().ifBlank { "{}" })
            }
            return LlmResult(text.toString(), calls, truncated)
        }
    }

    // ---------- Anthropic ----------
    private fun streamAnthropic(
        p: Provider, key: String, model: String, system: String, msgs: List<Msg>, tools: List<ToolSpec>,
        onCall: (Call) -> Unit, onText: (String) -> Unit, onReasoning: (String) -> Unit,
    ): LlmResult {
        val ms = JSONArray()
        var i = 0
        while (i < msgs.size) {
            val m = msgs[i]
            when (m.role) {
                "user" -> ms.put(JSONObject().put("role", "user").put("content", m.text))
                "assistant" -> {
                    val c = JSONArray()
                    if (m.text.isNotEmpty()) c.put(JSONObject().put("type", "text").put("text", m.text))
                    m.calls.forEach {
                        c.put(JSONObject().put("type", "tool_use").put("id", it.id).put("name", it.name)
                            .put("input", try { JSONObject(it.args) } catch (e: Exception) { JSONObject() }))
                    }
                    if (c.length() == 0) c.put(JSONObject().put("type", "text").put("text", "(kosong)"))
                    ms.put(JSONObject().put("role", "assistant").put("content", c))
                }
                "tool" -> {
                    val c = JSONArray()
                    while (i < msgs.size && msgs[i].role == "tool") {
                        c.put(JSONObject().put("type", "tool_result").put("tool_use_id", msgs[i].callId)
                            .put("content", msgs[i].text).put("is_error", msgs[i].isError))
                        i++
                    }
                    i--
                    ms.put(JSONObject().put("role", "user").put("content", c))
                }
            }
            i++
        }
        val body = JSONObject().put("model", model).put("max_tokens", 8192).put("stream", true)
            .put("messages", ms)
        if (tools.isNotEmpty()) body.put("tools", JSONArray().also { a ->
            tools.forEach {
                a.put(JSONObject().put("name", it.name).put("description", it.description).put("input_schema", it.schema))
            }
        })
        val cache = p.baseUrl.contains("anthropic.com")
        val eph = { JSONObject().put("type", "ephemeral") }
        if (cache) {
            body.put("system", JSONArray().put(JSONObject().put("type", "text").put("text", system).put("cache_control", eph())))
            if (ms.length() > 0) {
                val last = ms.getJSONObject(ms.length() - 1)
                val c = last.get("content")
                if (c is String) last.put("content", JSONArray().put(JSONObject().put("type", "text").put("text", c).put("cache_control", eph())))
                else if (c is JSONArray && c.length() > 0) c.getJSONObject(c.length() - 1).put("cache_control", eph())
            }
        } else body.put("system", system)
        val rb = Request.Builder().url(p.baseUrl.trimEnd('/') + "/messages")
            .header("anthropic-version", "2023-06-01")
            .post(body.toString().toRequestBody(JSON_TYPE))
        if (key.isNotBlank()) rb.header("x-api-key", key).header("Authorization", "Bearer $key")
        val call = streamHttp.newCall(rb.build())
        onCall(call)
        call.execute().use { resp ->
            if (!resp.isSuccessful) throw LlmError(resp.code, resp.body?.string()?.take(600) ?: "HTTP ${resp.code}")
            val src = resp.body!!.source()
            val text = StringBuilder()
            val tools2 = sortedMapOf<Int, Array<StringBuilder>>() // id, nama, argumen
            var truncated = false
            while (true) {
                val line = src.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val d = line.substring(5).trim()
                if (d.isEmpty()) continue
                val j = try { JSONObject(d) } catch (e: Exception) { continue }
                when (j.str("type")) {
                    "content_block_start" -> {
                        val cb = j.optJSONObject("content_block")
                        if (cb != null && cb.str("type") == "tool_use") {
                            val e = arrayOf(StringBuilder(cb.str("id")), StringBuilder(cb.str("name")), StringBuilder())
                            tools2[j.optInt("index")] = e
                        }
                    }
                    "content_block_delta" -> {
                        val dl = j.optJSONObject("delta") ?: continue
                        when (dl.str("type")) {
                            "text_delta" -> { val t = dl.str("text"); text.append(t); onText(t) }
                            "thinking_delta" -> onReasoning(dl.str("thinking"))
                            "input_json_delta" -> tools2[j.optInt("index")]?.get(2)?.append(dl.str("partial_json"))
                        }
                    }
                    "message_delta" -> if (j.optJSONObject("delta")?.str("stop_reason") == "max_tokens") truncated = true
                    "error" -> {
                        val er = j.optJSONObject("error")
                        val code = when (er?.str("type")) {
                            "rate_limit_error" -> 429
                            "overloaded_error" -> 529
                            "authentication_error" -> 401
                            "permission_error" -> 403
                            else -> 500
                        }
                        throw LlmError(code, er?.str("message") ?: d)
                    }
                }
            }
            val calls = tools2.values.map {
                ToolCall(it[0].toString(), it[1].toString(), it[2].toString().ifBlank { "{}" })
            }
            return LlmResult(text.toString(), calls, truncated)
        }
    }

    // ---------- Klasifikasi error ----------
    private val QUOTA_WORDS = listOf("quota", "insufficient", "exceeded your", "billing", "credit", "balance", "usage limit", "daily limit", "per day", "exhausted", "limit reached")
    private val SHORT_WORDS = listOf("per minute", "per second", "rpm", "tpm", "try again in", "retry after", "too many requests", "rate limit")

    /** 1 = kuota habis (tandai sampai besok), 2 = batas sementara (tunggu lalu coba lagi), 0 = bukan batas. */
    fun limitKind(code: Int, msg: String): Int {
        val m = msg.lowercase()
        val quota = QUOTA_WORDS.any { m.contains(it) }
        val short = SHORT_WORDS.any { m.contains(it) } && !m.contains("per day") && !m.contains("daily")
        if (code == 402) return 1
        if ((code == 429 || code == 403 || code == 400) && quota && !short) return 1
        return if (code == 429 || code == 529) 2 else 0
    }

    fun looksOffline(e: Exception): Boolean =
        e is java.net.UnknownHostException || e is java.net.ConnectException ||
            (e.message ?: "").let { it.contains("Unable to resolve host") || it.contains("Network is unreachable") }

    // ---------- Ambil daftar model ----------
    fun fetchModels(p: Provider, key: String): List<String> {
        val rb = Request.Builder().url(p.baseUrl.trimEnd('/') + "/models" + if (p.style == "anthropic") "?limit=1000" else "")
        if (p.style == "anthropic") rb.header("anthropic-version", "2023-06-01")
        if (key.isNotBlank()) {
            if (p.style == "anthropic") rb.header("x-api-key", key)
            rb.header("Authorization", "Bearer $key")
        }
        http.newBuilder().readTimeout(30, TimeUnit.SECONDS).build().newCall(rb.build()).execute().use { r ->
            val b = r.body?.string() ?: ""
            if (!r.isSuccessful) throw LlmError(r.code, b.take(300))
            val j = JSONObject(b)
            val a = j.optJSONArray("data") ?: j.optJSONArray("models") ?: JSONArray()
            return (0 until a.length()).mapNotNull {
                val o = a.optJSONObject(it)
                o?.str("id")?.ifEmpty { null } ?: o?.str("name")?.removePrefix("models/")?.ifEmpty { null }
            }.sorted()
        }
    }

    /** Panggilan sekali jalan tanpa tool (dipakai untuk /compact). */
    fun complete(p: Provider, key: String, model: String, system: String, msgs: List<Msg>): String =
        stream(p, key, model, system, msgs, emptyList(), "auto", {}, {}, {}).text
}
