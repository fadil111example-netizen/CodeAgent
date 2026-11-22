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

class ChatSession(val id: String, var title: String, val root: String, model: String, effort: String = "auto") {
    var model by mutableStateOf(model)
    var effort by mutableStateOf(effort)
    var running by mutableStateOf(false)
    var updated by mutableStateOf(System.currentTimeMillis())
    val msgs = mutableListOf<Msg>()
    val items = mutableStateListOf<ChatItem>()
    var job: Job? = null
    @Volatile var call: Call? = null

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
            "user" -> items.add(ChatItem.User(Keys.next(), m.text))
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
            o.optJSONArray("msgs")?.let { a -> for (i in 0 until a.length()) s.msgs.add(Msg.from(a.getJSONObject(i))) }
            s.rebuild()
            return s
        }
    }
}
