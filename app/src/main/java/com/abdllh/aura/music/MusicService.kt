package com.abdllh.aura.music

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.abdllh.aura.R

/** Keeps Aura Music playing (and the process in the foreground) while another app is on screen. */
class MusicService : Service() {
    private val listener: () -> Unit = { refresh() }
    private var lastText = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.music_title), NotificationManager.IMPORTANCE_LOW))
        startForeground(ID, build())
        state = RUNNING
        Player.addListener(listener)
        if (stopWanted) { // stop() came while this start was on its way
            stopWanted = false
            @Suppress("DEPRECATION") stopForeground(true)
            stopSelf()
        }
    }

    override fun onDestroy() {
        Player.removeListener(listener)
        if (state == RUNNING) state = STOPPED // (not when a new start is already on its way)
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    private fun refresh() {
        val t = Player.current
        val text = "${t?.title}|${t?.artist}|${Player.playing}"
        if (text == lastText) return
        lastText = text
        getSystemService(NotificationManager::class.java).notify(ID, build())
    }

    private fun build(): Notification {
        val t = Player.current
        val open = PendingIntent.getActivity(this, 1, Intent(this, MusicActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_music)
            .setContentTitle(t?.title ?: getString(R.string.music_title))
            .setContentText(t?.artist.orEmpty())
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "music"
        private const val ID = 8
        private const val STOPPED = 0
        private const val STARTING = 1 // startForegroundService() sent, onCreate() not run yet
        private const val RUNNING = 2

        // main thread only
        private var state = STOPPED
        private var stopWanted = false

        fun start(ctx: Context) {
            stopWanted = false
            if (state != STOPPED) return
            try {
                ctx.startForegroundService(Intent(ctx, MusicService::class.java))
                state = STARTING
            } catch (_: Throwable) {
            }
        }

        fun stop(ctx: Context) {
            when (state) {
                // Stopping a service that has not called startForeground() yet crashes the app ("did not then call
                // Service.startForeground()"): onCreate() stops it instead.
                STARTING -> stopWanted = true
                RUNNING -> {
                    state = STOPPED
                    try { ctx.stopService(Intent(ctx, MusicService::class.java)) } catch (_: Throwable) { }
                }
            }
        }
    }
}
