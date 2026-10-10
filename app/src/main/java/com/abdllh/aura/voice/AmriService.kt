package com.abdllh.aura.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.abdllh.aura.R

/**
 * Listens for the wake word while the assistant is on: a foreground service, as Android only lets an app hear the
 * microphone from the background that way. Off the microphone while the assistant itself listens or answers, while
 * Settings records the wake word, and during calls; on again by itself afterwards.
 */
class AmriService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var loop: MicLoop? = null       // main thread
    @Volatile private var model: WakeModel? = null
    private var modelStamp = 0L             // the recordings' file as [model] was read from it
    @Volatile private var lastWake = 0L
    private var failures = 0                // the microphone, one after another (main thread)
    private var retryAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.amri_name), NotificationManager.IMPORTANCE_MIN))
        startForeground(ID, Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.amri_name))
            .setContentText(getString(R.string.amri_notification))
            .setOngoing(true)
            .build())
        instance = this
        state = RUNNING
        sync()
        main.postDelayed(watch, 2000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // stopped while this start was on its way, or brought back by Android (START_STICKY) in a process where it
        // is no longer wanted: switched off, the microphone taken away, AMRI switched off after crashes
        if (state == STOPPED || !Amri.shouldRun(this)) stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacks(watch)
        stopMic()
        if (instance === this) instance = null
        state = STOPPED
        super.onDestroy()
    }

    private fun shouldListen(): Boolean =
        Amri.state == Amri.State.IDLE && !Amri.enrolling && !inCall() && !Amri.reversing && SystemClock.elapsedRealtime() >= retryAt

    private fun inCall(): Boolean {
        if (Amri.inCall) return true // the unit's own Bluetooth calls
        val m = (getSystemService(Context.AUDIO_SERVICE) as AudioManager).mode
        return m == AudioManager.MODE_IN_CALL || m == AudioManager.MODE_IN_COMMUNICATION || m == AudioManager.MODE_RINGTONE
    }

    // calls and the reverse gear begin and end without telling: looked at every two seconds
    private val watch = object : Runnable {
        override fun run() { sync(); main.postDelayed(this, 2000) }
    }

    /** On the microphone or off it, as things are now (main thread). */
    fun sync() {
        if (!Amri.shouldRun(this)) { stopMic(); stopSelf(); return }
        if (shouldListen()) startMic() else stopMic()
    }

    private fun startMic() {
        if (loop != null) return
        val f = WakeModel.file(this)
        if (model == null || f.lastModified() != modelStamp) { // the owner's recordings, read again only when changed
            model = WakeModel.load(this) ?: return
            modelStamp = f.lastModified()
        }
        val seg = Segmenter(maxMs = 1500) { word, noise -> onWord(word, noise) }
        lateinit var l: MicLoop
        l = MicLoop(device(this, Amri.micId), seg, onError = { err ->
            Log.w(TAG, err)
            main.post {
                if (loop !== l) return@post
                stopMic()
                // tried again by the watch, less often each time the microphone fails (2 s .. 1 min)
                failures++
                retryAt = SystemClock.elapsedRealtime() + minOf(60_000L, 2_000L shl minOf(failures - 1, 5))
            }
        }, onStarted = { main.post { if (loop === l) failures = 0 } })
        loop = l
        l.start()
    }

    private fun stopMic() {
        val l = loop ?: return
        loop = null
        l.quit()
        try { l.join(300) } catch (_: InterruptedException) { }
    }

    /** A word was heard (microphone thread): the wake word? */
    private fun onWord(pcm: ShortArray, noise: ShortArray) {
        val m = model ?: return
        if (Amri.inCall || Amri.reversing) return // until the watch takes the microphone away
        val now = SystemClock.elapsedRealtime()
        if (now - lastWake < 1500) return
        val score = m.score(Features.spectrum(pcm, noise))
        lastScore = score
        Log.d(TAG, "word ${pcm.size * 1000 / Features.RATE} ms, score ${"%.2f".format(java.util.Locale.ROOT, score)}")
        if (score < Amri.threshold()) {
            lastWake = now
            main.post { Amri.wake(this) }
        }
    }

    companion object {
        private const val TAG = "AuraAmri"
        private const val CHANNEL = "amri"
        private const val ID = 11
        private const val STOPPED = 0
        private const val STARTING = 1
        private const val RUNNING = 2
        private var state = STOPPED
        private var instance: AmriService? = null

        /** The last word's distance from the wake word (diagnostics: under the threshold wakes it). */
        @Volatile var lastScore = 0f; private set

        fun start(ctx: Context) {
            if (state != STOPPED) { instance?.sync(); return }
            try {
                ctx.startForegroundService(Intent(ctx, AmriService::class.java))
                state = STARTING
            } catch (t: Throwable) {
                Log.w(TAG, "start: $t")
            }
        }

        fun stop(ctx: Context) {
            // always asked: in a new process [state] says STOPPED while Android may be bringing the service back
            state = STOPPED
            try { ctx.stopService(Intent(ctx, AmriService::class.java)) } catch (_: Throwable) { }
        }

        /** Off the microphone now (the recogniser or the enrolment needs it). */
        fun pauseMic() { instance?.stopMic() }

        /** Back on it, if nothing keeps it off. */
        fun resumeMic() { instance?.sync() }

        /** Another microphone was chosen. */
        fun restartMic() { instance?.let { it.stopMic(); it.sync() } }

        /** The chosen input ([id] from Settings; 0 or a device that is gone: the system's choice). */
        fun device(ctx: Context, id: Int): AudioDeviceInfo? {
            if (id == 0) return null
            return try {
                (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == id }
            } catch (_: Throwable) {
                null
            }
        }
    }
}
