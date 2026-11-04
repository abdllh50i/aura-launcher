package com.abdllh.aura.nav

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.abdllh.aura.R
import com.abdllh.aura.util.Prefs
import java.util.Locale

/**
 * Foreground service that runs while navigating, so GPS keeps flowing to [NavSession] (and its voice prompts) when
 * another app is in front. Its notification shows the next manoeuvre and opens the Maps screen.
 */
class NavService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val listener: () -> Unit = { refresh() }
    private var lastText = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.nav_channel), NotificationManager.IMPORTANCE_LOW))
        startForeground(ID, build(getString(R.string.nav_starting)))
        state = RUNNING
        NavSession.addListener(listener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (NavSession.state == NavSession.State.IDLE) stopSelf() // ended while this start was on its way
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        NavSession.removeListener(listener)
        if (state == RUNNING) state = STOPPED
        super.onDestroy()
    }

    private fun refresh() {
        if (NavSession.state == NavSession.State.IDLE) { stopSelf(); return }
        val r = NavSession.route ?: return
        val p = NavSession.progress ?: return
        val ar = Locale.getDefault().language == "ar"
        val text = if (NavSession.state == NavSession.State.ARRIVED) getString(R.string.nav_arrived)
        else "${Units.distance(p.toNext, ar)} · ${Instructions.text(r.steps[p.next], ar)}"
        if (text == lastText) return
        lastText = text
        getSystemService(NotificationManager::class.java).notify(ID, build(text))
    }

    private fun build(text: String): Notification {
        val open = com.abdllh.aura.home.Screens.pending(this, com.abdllh.aura.home.Screens.MAPS, 0)
        val title = NavSession.destination?.name ?: getString(R.string.nav_title)
        val p = NavSession.progress
        val sub = if (p != null) "${Units.duration(p.timeLeft, Locale.getDefault().language == "ar")} · ${Units.arrival(p.timeLeft, Prefs.clock24)}" else ""
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_nav)
            .setContentTitle(title)
            .setContentText(if (sub.isNotEmpty()) "$text  ($sub)" else text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "navigation"
        private const val ID = 7
        private const val STOPPED = 0
        private const val STARTING = 1 // startForegroundService() sent, onCreate() not run yet
        private const val RUNNING = 2
        private var state = STOPPED // main thread only

        fun start(ctx: Context) {
            if (state != STOPPED) return
            val i = Intent(ctx, NavService::class.java)
            try {
                // With the app in front a plain start is allowed, and no clock runs: after startForegroundService() the
                // service has 10 s to call startForeground(), and a main thread held up that long (a first map frame on
                // a stalled GPU: seen on a loaded emulator) brings the whole app down, the home screen with it. In the
                // background (a place from the phone over another app) only the other way is allowed.
                try { ctx.startService(i) } catch (_: IllegalStateException) { ctx.startForegroundService(i) }
                state = STARTING
            } catch (_: Throwable) {
            }
        }

        /** Stopping before startForeground() would crash the app: a service still starting stops itself (onStartCommand). */
        fun stop(ctx: Context) {
            if (state != RUNNING) return
            state = STOPPED
            try { ctx.stopService(Intent(ctx, NavService::class.java)) } catch (_: Throwable) { }
        }
    }
}

/** Drives the car along the active route (emulator testing and the demo in debug builds). */
object NavSim {
    private val main = Handler(Looper.getMainLooper())
    private var along = 0.0
    private var kmh = 60.0
    private var running = false

    fun start(speedKmh: Double = 60.0) {
        kmh = speedKmh
        along = 0.0
        running = true
        CarLocation.simulated = true
        main.removeCallbacks(tick)
        main.post(tick)
    }

    fun stop() {
        if (!running) return
        running = false
        CarLocation.simulated = false
        main.removeCallbacks(tick)
    }

    private val tick: Runnable = object : Runnable {
        override fun run() {
            if (!running) return
            val r = NavSession.route ?: run { stop(); return }
            along = (NavSession.progress?.along ?: along) + kmh / 3.6
            val (p, b) = r.line.at(along)
            val l = Location("sim").apply {
                latitude = p.lat; longitude = p.lon; bearing = b.toFloat(); speed = (kmh / 3.6).toFloat(); accuracy = 5f
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() // (its age is checked like a live fix's: CameraMark)
            }
            CarLocation.publish(l)
            if (along < r.line.length) main.postDelayed(this, 1000) else stop()
        }
    }
}
