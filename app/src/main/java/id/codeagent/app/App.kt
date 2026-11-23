package id.codeagent.app

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Date

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Notify.channels(this)
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                File(filesDir, "crash.log").writeText("${Date()}  thread=${t.name}\n$sw")
            } catch (ex: Throwable) { }
            old?.uncaughtException(t, e)
        }
    }
}
