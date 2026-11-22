package id.codeagent.app

import org.json.JSONArray
import org.json.JSONObject

fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
fun List<String>.toJsonArray(): JSONArray = JSONArray().also { a -> forEach { a.put(it) } }
fun JSONObject.str(k: String): String = if (isNull(k)) "" else optString(k, "")

data class Provider(
    val id: String,
    val name: String,
    val style: String, // "openai" | "anthropic"
    val baseUrl: String,
    val keys: List<String>,
    val models: List<String>,
    val enabled: Boolean,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("style", style)
        .put("baseUrl", baseUrl).put("keys", keys.toJsonArray()).put("models", models.toJsonArray())
        .put("enabled", enabled)

    companion object {
        fun from(o: JSONObject) = Provider(
            o.getString("id"), o.getString("name"), o.optString("style", "openai"), o.getString("baseUrl"),
            o.optJSONArray("keys")?.strings() ?: emptyList(),
            o.optJSONArray("models")?.strings() ?: emptyList(),
            o.optBoolean("enabled", true),
        )
    }
}

data class Preset(val name: String, val style: String, val baseUrl: String, val models: String)

val PRESETS = listOf(
    Preset("OpenRouter", "openai", "https://openrouter.ai/api/v1", ""),
    Preset("Anthropic", "anthropic", "https://api.anthropic.com/v1", "claude-sonnet-4-5,claude-opus-4-5,claude-haiku-4-5"),
    Preset("OpenAI", "openai", "https://api.openai.com/v1", ""),
    Preset("DeepSeek", "openai", "https://api.deepseek.com/v1", "deepseek-chat,deepseek-reasoner"),
    Preset("Groq", "openai", "https://api.groq.com/openai/v1", ""),
    Preset("Gemini", "openai", "https://generativelanguage.googleapis.com/v1beta/openai", ""),
    Preset("Mistral", "openai", "https://api.mistral.ai/v1", ""),
    Preset("Cerebras", "openai", "https://api.cerebras.ai/v1", ""),
    Preset("GitHub Models", "openai", "https://models.github.ai/inference", ""),
    Preset("NVIDIA NIM", "openai", "https://integrate.api.nvidia.com/v1", ""),
    Preset("Ollama (lokal)", "openai", "http://127.0.0.1:11434/v1", ""),
    Preset("LM Studio (lokal)", "openai", "http://127.0.0.1:1234/v1", ""),
)

data class Fallback(val providerId: String, val model: String)
data class McpServer(val name: String, val url: String, val headers: String, val enabled: Boolean)
data class PermRule(val tool: String, val pattern: String, val action: String) // allow | ask | deny
data class Command(val name: String, val prompt: String)
data class SkillDef(val name: String, val description: String, val body: String)

data class ToolCall(val id: String, val name: String, val args: String)

data class Msg(
    val role: String, // user | assistant | tool
    val text: String = "",
    val calls: List<ToolCall> = emptyList(),
    val callId: String = "",
    val isError: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().put("role", role).put("text", text).put("callId", callId)
        .put("isError", isError).put("calls", JSONArray().also { a ->
            calls.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("args", it.args)) }
        })

    companion object {
        fun from(o: JSONObject): Msg {
            val ca = o.optJSONArray("calls")
            val calls = if (ca == null) emptyList() else (0 until ca.length()).map {
                val c = ca.getJSONObject(it); ToolCall(c.str("id"), c.str("name"), c.str("args"))
            }
            return Msg(o.str("role"), o.str("text"), calls, o.str("callId"), o.optBoolean("isError"))
        }
    }
}

sealed class ChatItem {
    abstract val key: String

    data class User(override val key: String, val text: String) : ChatItem()
    data class Assistant(override val key: String, val text: String, val reasoning: String) : ChatItem()
    data class Tool(
        override val key: String, val name: String, val title: String,
        val output: String, val status: String, // jalan | selesai | gagal | ditolak
    ) : ChatItem()
    data class Note(override val key: String, val text: String, val isError: Boolean) : ChatItem()
}

object Keys {
    private var n = 0L
    @Synchronized fun next(): String = "k${n++}"
}
