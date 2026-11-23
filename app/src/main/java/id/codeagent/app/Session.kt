package id.codeagent.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Job
import okhttp3.Call
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class UndoRec(val idx: Int, val path: String, val before: String?)

class ChatSession(val id: String, title: String, val root: String, model: String, effort: String = "auto") {
    var title by mutableStateOf(title)
    var model by mutableStateOf(model)
    var effort by mutableStateOf(effort)
    var running by mutableStateOf(false)
    var updated by mutableStateOf(System.currentTimeMillis())
    val msgs = mutableListOf<Msg>()
    val reminders = mutableListOf<String>()
    var agent by mutableStateOf("build")
    var lastAgent = "build"
    var titled = false
    var lastPrompt = 0
    val extraDirs = mutableListOf<String>()
    val undoLog = mutableListOf<UndoRec>()
    val callHist = ArrayList<String>()
    var todos by mutableStateOf<List<Triple<String, String, String>>>(emptyList())
    val items = mutableStateListOf<ChatItem>()
    var job: Job? = null
    @Volatile var call: Call? = null

    fun setTodosFrom(a: JSONArray) {
        todos = (0 until a.length()).mapNotNull { i ->
            a.optJSONObject(i)?.let { o -> Triple(o.str("content"), o.str("status").ifEmpty { "pending" }, o.str("priority")) }
        }
    }

    fun stop() { job?.cancel(); call?.cancel() }

    fun update(key: String, f: (ChatItem) -> ChatItem) {
        val i = items.indexOfLast { it.key == key }
        if (i >= 0) items[i] = f(items[i])
    }

    fun note(text: String, err: Boolean = false) { items.add(ChatItem.Note(Keys.next(), text, err)) }

    fun rebuild() {
        items.clear()
        val toolIdx = HashMap<String, Int>()
        for (m in msgs) when (m.role) {
            "user" -> items.add(ChatItem.User(Keys.next(), m.text.replace(Regex("(?s)<system-reminder>.*?</system-reminder>\\s*"), "")))
            "assistant" -> {
                if (m.text.isNotBlank()) items.add(ChatItem.Assistant(Keys.next(), m.text, ""))
                m.calls.forEach { c ->
                    toolIdx[c.id] = items.size
                    items.add(ChatItem.Tool(Keys.next(), c.name, Tools.title(c.name, c.args), "", "selesai"))
                }
            }
            "tool" -> toolIdx[m.callId]?.let { i ->
                val t = items[i] as ChatItem.Tool
                items[i] = t.copy(output = m.text, status = if (m.isError) "gagal" else "selesai")
            }
        }
    }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("root", root)
        .put("model", model).put("effort", effort).put("updated", updated)
        .put("agent", agent).put("titled", titled)
        .put("extraDirs", JSONArray().also { a -> extraDirs.forEach { a.put(it) } })
        .put("undo", JSONArray().also { a ->
            undoLog.takeLast(200).forEach { u -> a.put(JSONObject().put("i", u.idx).put("p", u.path).also { o -> if (u.before != null) o.put("b", u.before) }) }
        })
        .put("todos", JSONArray().also { a -> todos.forEach { a.put(JSONObject().put("content", it.first).put("status", it.second).put("priority", it.third)) } })
        .put("msgs", JSONArray().also { a -> msgs.forEach { a.put(it.toJson()) } })

    fun save(dir: File) {
        try { dir.mkdirs(); File(dir, "$id.json").writeText(toJson().toString()) } catch (e: Exception) { }
    }

    fun toMarkdown(): String = buildString {
        append("# $title\n\nProyek: `$root`\n\n")
        for (m in msgs) when (m.role) {
            "user" -> append("## Pengguna\n\n${m.text}\n\n")
            "assistant" -> {
                if (m.text.isNotBlank()) append("## Agen\n\n${m.text}\n\n")
                m.calls.forEach { append("- tool `${it.name}`: ${Tools.title(it.name, it.args)}\n") }
                if (m.calls.isNotEmpty()) append("\n")
            }
        }
    }

    companion object {
        fun from(o: JSONObject): ChatSession {
            val s = ChatSession(o.getString("id"), o.optString("title", "Sesi"), o.getString("root"),
                o.optString("model"), o.optString("effort", "auto"))
            s.updated = o.optLong("updated")
            o.optJSONArray("todos")?.let { s.setTodosFrom(it) }
            s.agent = o.optString("agent", "build").ifEmpty { "build" }; s.lastAgent = s.agent
            s.titled = o.optBoolean("titled", true)
            o.optJSONArray("extraDirs")?.let { s.extraDirs.addAll(it.strings()) }
            o.optJSONArray("undo")?.let { a ->
                for (i in 0 until a.length()) {
                    val u = a.getJSONObject(i)
                    s.undoLog.add(UndoRec(u.optInt("i"), u.str("p"), if (u.has("b")) u.getString("b") else null))
                }
            }
            o.optJSONArray("msgs")?.let { a -> for (i in 0 until a.length()) s.msgs.add(Msg.from(a.getJSONObject(i))) }
            s.rebuild()
            return s
        }
    }
}
