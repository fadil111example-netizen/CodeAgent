package id.codeagent.app

import org.json.JSONObject
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.NativeJSON
import org.mozilla.javascript.RhinoException
import org.mozilla.javascript.ScriptRuntime
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined

/** Tool "execute": menjalankan program JavaScript (Rhino) yang memanggil beberapa tool dalam SATU langkah. */
object CodeTool {
    const val NAME = "execute"

    private class Fn(val f: (Array<out Any?>?) -> Any?) : BaseFunction() {
        override fun call(cx: Context?, scope: Scriptable?, thisObj: Scriptable?, args: Array<out Any?>?): Any? = f(args)
    }

    fun spec(tools: List<ToolSpec>): ToolSpec {
        val sb = StringBuilder("Run a JavaScript program that calls several tools in ONE step. Use it for loops, conditionals and batching, for example reading many files, searching then editing each hit, or processing results before answering.\n\nRules:\n")
        sb.append("- Every tool is a synchronous function on the global `tools` object. Call it with ONE object argument; it returns the tool's text output (a string) and throws an Error if the tool fails or is denied.\n")
        sb.append("- Use `return value` to give back a result (objects are JSON-encoded). `console.log(...)` output is also returned.\n")
        sb.append("- `await` and `async` are accepted but not needed. No require/import, network, timers or file access except through tools. Limits: 60 tool calls, 3 minutes.\n")
        sb.append("- Permissions still apply: edits and shell commands can ask the user.\n\nAvailable tools:\n")
        for (t in tools) {
            val props = t.schema.optJSONObject("properties")
            val req = t.schema.optJSONArray("required")?.strings() ?: emptyList()
            val params = props?.keys()?.asSequence()?.joinToString(", ") { if (it in req) it else "$it?" } ?: ""
            sb.append("- tools.${t.name}({ $params }) — ${t.description.lineSequence().first().take(110)}\n")
        }
        val schema = JSONObject().put("type", "object")
            .put("properties", JSONObject().put("code", JSONObject().put("type", "string").put("description", "JavaScript source to run")))
            .put("required", listOf("code").toJsonArray())
        return ToolSpec(NAME, sb.toString(), schema)
    }

    private fun prep(src: String): String =
        src.replace(Regex("\\bawait\\s+"), "").replace(Regex("\\basync\\s+(?=function\\b|\\(|[A-Za-z_$])"), "")

    fun run(code: String, names: List<String>, call: (String, JSONObject) -> String): String {
        val logs = StringBuilder()
        var calls = 0
        val start = System.currentTimeMillis()
        val factory = object : ContextFactory() {
            override fun makeContext(): Context {
                val c = super.makeContext()
                c.optimizationLevel = -1
                c.languageVersion = Context.VERSION_ES6
                c.instructionObserverThreshold = 10000
                return c
            }

            override fun observeInstructionCount(cx: Context?, instructionCount: Int) {
                if (System.currentTimeMillis() - start > 180_000) throw Error("Script timed out (3 minutes)")
            }
        }
        val cx = factory.enterContext()
        try {
            val scope = cx.initSafeStandardObjects()
            val toolsObj = cx.newObject(scope)
            for (n in names) {
                ScriptableObject.putProperty(toolsObj, n, Fn { args ->
                    if (++calls > 60) throw ScriptRuntime.constructError("Error", "Too many tool calls (max 60)")
                    val a = args?.getOrNull(0)
                    if (a == null || a is Undefined) throw ScriptRuntime.constructError("TypeError", "tools.$n expects a single object argument")
                    val json = Context.toString(NativeJSON.stringify(cx, scope, a, null, null))
                    val jo = try { JSONObject(json) } catch (e: Exception) { throw ScriptRuntime.constructError("TypeError", "tools.$n expects a single object argument") }
                    try { call(n, jo) } catch (e: Exception) {
                        throw ScriptRuntime.constructError("Error", e.message ?: e.javaClass.simpleName)
                    }
                })
            }
            ScriptableObject.putProperty(scope, "tools", toolsObj)
            val console = cx.newObject(scope)
            ScriptableObject.putProperty(console, "log", Fn { args ->
                logs.append(args?.joinToString(" ") { Context.toString(it) } ?: "").append('\n'); Undefined.instance
            })
            ScriptableObject.putProperty(scope, "console", console)
            val res = try {
                cx.evaluateString(scope, "(function(){\n" + prep(code) + "\n})()", "code", 1, null)
            } catch (e: RhinoException) {
                throw Exception("Script error: ${e.details()} (line ${maxOf(e.lineNumber() - 1, 1)})" +
                    if (logs.isNotEmpty()) "\nLogs so far:\n$logs" else "")
            }
            val out = when {
                res == null || res is Undefined -> ""
                res is CharSequence -> res.toString()
                else -> Context.toString(NativeJSON.stringify(cx, scope, res, null, null))
            }
            return (logs.toString() + out).trim().ifEmpty { "(no output)" }
        } finally {
            Context.exit()
        }
    }
}
