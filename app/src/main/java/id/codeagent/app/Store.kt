package id.codeagent.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Store {
    private lateinit var sp: SharedPreferences

    fun init(c: Context) {
        if (!::sp.isInitialized) sp = c.applicationContext.getSharedPreferences("code_agent", Context.MODE_PRIVATE)
    }

    private fun arr(k: String): JSONArray = try { JSONArray(sp.getString(k, "[]")) } catch (e: Exception) { JSONArray() }

    private fun <T> load(k: String, f: (JSONObject) -> T): List<T> {
        val a = arr(k)
        return (0 until a.length()).mapNotNull { try { f(a.getJSONObject(it)) } catch (e: Exception) { null } }
    }

    private fun <T> save(k: String, v: List<T>, f: (T) -> JSONObject) {
        sp.edit().putString(k, JSONArray().also { a -> v.forEach { a.put(f(it)) } }.toString()).apply()
    }

    var providers: List<Provider>
        get() = load("providers") { Provider.from(it) }
        set(v) = save("providers", v) { it.toJson() }

    var fallbacks: List<Fallback>
        get() = load("fallbacks") { Fallback(it.getString("p"), it.getString("m")) }
        set(v) = save("fallbacks", v) { JSONObject().put("p", it.providerId).put("m", it.model) }

    var mcp: List<McpServer>
        get() = load("mcp") { McpServer(it.getString("n"), it.getString("u"), it.optString("h"), it.optBoolean("e", true)) }
        set(v) = save("mcp", v) { JSONObject().put("n", it.name).put("u", it.url).put("h", it.headers).put("e", it.enabled) }

    var rules: List<PermRule>
        get() = load("rules") { PermRule(it.getString("t"), it.getString("p"), it.getString("a")) }
        set(v) = save("rules", v) { JSONObject().put("t", it.tool).put("p", it.pattern).put("a", it.action) }

    var commands: List<Command>
        get() = load("commands") { Command(it.getString("n"), it.getString("p")) }
        set(v) = save("commands", v) { JSONObject().put("n", it.name).put("p", it.prompt) }

    var skills: List<SkillDef>
        get() = load("skills") { SkillDef(it.getString("n"), it.getString("d"), it.getString("b")) }
        set(v) = save("skills", v) { JSONObject().put("n", it.name).put("d", it.description).put("b", it.body) }

    var selected: String
        get() = sp.getString("selected", "") ?: ""
        set(v) = sp.edit().putString("selected", v).apply()

    var autoSwitch: Boolean
        get() = sp.getBoolean("autoSwitch", true)
        set(v) = sp.edit().putBoolean("autoSwitch", v).apply()

    var autoApprove: Boolean
        get() = sp.getBoolean("autoApprove", false)
        set(v) = sp.edit().putBoolean("autoApprove", v).apply()

    var voice: Boolean
        get() = sp.getBoolean("voice", true)
        set(v) = sp.edit().putBoolean("voice", v).apply()

    var effort: String
        get() = sp.getString("effort", "auto") ?: "auto"
        set(v) = sp.edit().putString("effort", v).apply()

    var theme: String
        get() = sp.getString("theme", "system") ?: "system"
        set(v) = sp.edit().putString("theme", v).apply()

    var ctxWindow: Int
        get() = sp.getInt("ctxWindow", 128000)
        set(v) = sp.edit().putInt("ctxWindow", v).apply()

    var lastFolder: String
        get() = sp.getString("lastFolder", "/storage/emulated/0/CodeAgent") ?: ""
        set(v) = sp.edit().putString("lastFolder", v).apply()

    // Penanda batas: "providerId#indeksKunci" -> tanggal (yyyy-MM-dd). Otomatis kedaluwarsa besok.
    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    fun isFlagged(pid: String, idx: Int): Boolean {
        val o = try { JSONObject(sp.getString("flags", "{}")) } catch (e: Exception) { JSONObject() }
        return o.optString("$pid#$idx") == today()
    }

    fun flag(pid: String, idx: Int) {
        val o = try { JSONObject(sp.getString("flags", "{}")) } catch (e: Exception) { JSONObject() }
        o.put("$pid#$idx", today())
        sp.edit().putString("flags", o.toString()).apply()
    }

    fun resetFlags() = sp.edit().putString("flags", "{}").apply()

    fun exportAll(): String = JSONObject()
        .put("providers", JSONArray().also { a -> providers.forEach { a.put(it.toJson()) } })
        .put("fallbacks", JSONArray().also { a -> fallbacks.forEach { a.put(JSONObject().put("p", it.providerId).put("m", it.model)) } })
        .toString(2)

    fun importAll(json: String): Int {
        val o = JSONObject(json)
        val ps = o.getJSONArray("providers")
        val list = (0 until ps.length()).map { Provider.from(ps.getJSONObject(it)) }
        val old = providers.filter { p -> list.none { it.id == p.id } }
        providers = old + list
        o.optJSONArray("fallbacks")?.let { fa ->
            fallbacks = (0 until fa.length()).map { Fallback(fa.getJSONObject(it).getString("p"), fa.getJSONObject(it).getString("m")) }
        }
        return list.size
    }
}
