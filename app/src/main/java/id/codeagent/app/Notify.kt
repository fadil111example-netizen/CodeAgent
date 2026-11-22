package id.codeagent.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

object Notify {
    const val WORK = "work"
    const val DONE = "done"

    fun channels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(WORK, "Agen sedang bekerja", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(DONE, "Tugas selesai", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun open(c: Context) = PendingIntent.getActivity(
        c, 0, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun working(c: Context): Notification = NotificationCompat.Builder(c, WORK)
        .setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("Code Agent sedang bekerja")
        .setContentText("Agen tetap berjalan saat layar terkunci").setOngoing(true)
        .setContentIntent(open(c)).build()

    fun done(c: Context, text: String) {
        try {
            c.getSystemService(NotificationManager::class.java).notify(2, NotificationCompat.Builder(c, DONE)
                .setSmallIcon(android.R.drawable.stat_notify_chat).setContentTitle("Tugas selesai")
                .setContentText(text.take(120)).setAutoCancel(true).setContentIntent(open(c)).build())
        } catch (e: Exception) { }
    }
}

class AgentService : Service() {
    private var wl: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notify.channels(this)
        val n = Notify.working(this)
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(1, n)
        if (wl == null) {
            wl = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "codeagent:run")
                .apply { acquire(60 * 60 * 1000L) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try { wl?.release() } catch (e: Exception) { }
        super.onDestroy()
    }

    companion object {
        fun start(c: Context) { try { ContextCompat.startForegroundService(c, Intent(c, AgentService::class.java)) } catch (e: Exception) { } }
        fun stop(c: Context) { c.stopService(Intent(c, AgentService::class.java)) }
    }
}
