package id.codeagent.app

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Klien MCP sederhana (HTTP JSON-RPC; respons JSON atau SSE). */
class McpClient(private val s: McpServer) {
    private var session: String? = null
    private var nid = 1
    private val client = Llm.http.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()

    private fun rpc(method: String, params: JSONObject?, notify: Boolean = false): JSONObject? {
        val b = JSONObject().put("jsonrpc", "2.0").put("method", method)
        if (params != null) b.put("params", params)
        if (!notify) b.put("id", nid++)
        val rb = Request.Builder().url(s.url)
            .header("Accept", "application/json, text/event-stream")
            .post(b.toString().toRequestBody("application/json".toMediaType()))
        session?.let { rb.header("Mcp-Session-Id", it) }
        s.headers.lines().forEach { l ->
            val i = l.indexOf(':')
            if (i > 0) rb.header(l.substring(0, i).trim(), l.substring(i + 1).trim())
        }
        client.newCall(rb.build()).execute().use { r ->
            r.header("Mcp-Session-Id")?.let { session = it }
            if (!r.isSuccessful) throw Exception("MCP ${s.name}: HTTP ${r.code}")
            if (notify) return null
            val body = r.body?.string() ?: ""
            val ct = r.header("Content-Type") ?: ""
            val txt = if (ct.contains("event-stream"))
                body.lines().filter { it.startsWith("data:") }.map { it.substring(5).trim() }
                    .lastOrNull { it.contains("\"result\"") || it.contains("\"error\"") }
                    ?: throw Exception("MCP ${s.name}: respons kosong")
            else body
            val j = JSONObject(txt)
            j.optJSONObject("error")?.let { throw Exception(it.str("message")) }
            return j.optJSONObject("result")
        }
    }

    fun init() {
        rpc("initialize", JSONObject().put("protocolVersion", "2025-03-26").put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", "code-agent").put("version", "1.0")))
        rpc("notifications/initialized", null, true)
    }

    fun listTools(): List<Triple<String, String, JSONObject>> {
        val arr = rpc("tools/list", JSONObject())?.optJSONArray("tools") ?: return emptyList()
        return (0 until arr.length()).map {
            val t = arr.getJSONObject(it)
            Triple(t.str("name"), t.str("description"),
                t.optJSONObject("inputSchema") ?: JSONObject().put("type", "object").put("properties", JSONObject()))
        }
    }

    fun call(tool: String, args: JSONObject): String {
        val res = rpc("tools/call", JSONObject().put("name", tool).put("arguments", args)) ?: return ""
        val c = res.optJSONArray("content")
        val text = if (c == null) res.toString() else (0 until c.length()).joinToString("\n") {
            val o = c.getJSONObject(it); if (o.str("type") == "text") o.str("text") else "[${o.str("type")}]"
        }
        if (res.optBoolean("isError")) throw Exception(text)
        return text
    }
}

class McpRegistry {
    val specs = ArrayList<ToolSpec>()
    private val map = HashMap<String, Pair<McpClient, String>>()
    val errors = ArrayList<String>()

    fun has(name: String) = map.containsKey(name)
    fun call(name: String, args: JSONObject): String {
        val (c, t) = map[name] ?: throw Exception("Tool MCP tidak dikenal")
        return c.call(t, args)
    }

    companion object {
        fun load(servers: List<McpServer>): McpRegistry {
            val r = McpRegistry()
            for (s in servers.filter { it.enabled }) {
                try {
                    val c = McpClient(s); c.init()
                    val safe = s.name.replace(Regex("[^A-Za-z0-9_]"), "_")
                    for ((n, d, sch) in c.listTools()) {
                        val full = ("mcp_${safe}_" + n.replace(Regex("[^A-Za-z0-9_-]"), "_")).take(64)
                        r.map[full] = c to n
                        r.specs.add(ToolSpec(full, d.ifEmpty { "Tool MCP $n dari ${s.name}" }, sch))
                    }
                } catch (e: Exception) { r.errors.add("${s.name}: ${e.message}") }
            }
            return r
        }
    }
}
