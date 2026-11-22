package id.codeagent.app

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Notify.channels(this)
    }
}
