package com.abdllh.aura.nav.pack

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import com.abdllh.aura.R
import com.abdllh.aura.settings.SettingsActivity
import com.abdllh.aura.util.LocaleHelper

/**
 * Foreground while a pack is on its way, so the process keeps its priority with another app in front. The work itself is [PackManager]'s thread, not this: if this cannot
 * start, the work goes on, and a process killed here is not started again by Android (START_NOT_STICKY: nothing resumes by itself).
 */
class PackService : Service() {
    private val listener: () -> Unit = { refresh() }
    private var lastText = ""
    private var lastAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, LocaleHelper.wrap(this).getString(R.string.pack_channel), NotificationManager.IMPORTANCE_LOW))
        startForeground(ID, build(PackManager.line()))
        state = RUNNING
        PackManager.addListener(listener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!PackManager.status.busy) stopSelf() // ended while this start was on its way
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        PackManager.removeListener(listener)
        state = STOPPED
        super.onDestroy()
        if (PackManager.status.busy) start(this) // (new work came while this was stopping)
    }

    private fun refresh() {
        if (!PackManager.status.busy) { stopSelf(); return }
        val text = PackManager.line()
        val now = SystemClock.elapsedRealtime()
        if (text == lastText || now - lastAt < 2000L) return
        lastText = text
        lastAt = now
        getSystemService(NotificationManager::class.java).notify(ID, build(text))
    }

    private fun build(text: String): Notification {
        val s = PackManager.status
        val open = PendingIntent.getActivity(this, 0, Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_nav)
            .setContentTitle(LocaleHelper.wrap(this).getString(R.string.pack_title))
            .setContentText(text)
            .setProgress(100, s.percent, false)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "mappack"
        private const val ID = 9
        private const val STOPPED = 0
        private const val STARTING = 1 // startForegroundService() sent, onCreate() not run yet
        private const val RUNNING = 2
        private var state = STOPPED // main thread only

        /** Main thread. */
        fun start(ctx: Context) {
            if (state != STOPPED) return
            try {
                ctx.startForegroundService(Intent(ctx, PackService::class.java))
                state = STARTING
            } catch (_: Throwable) {
            }
        }

        /** The work may have ended with nothing left to tell the listener. Stopping before startForeground() would crash the app: a service still starting stops itself. */
        fun refresh(ctx: Context) {
            if (state != RUNNING || PackManager.status.busy) return
            try { ctx.stopService(Intent(ctx, PackService::class.java)) } catch (_: Throwable) { }
        }
    }
}
