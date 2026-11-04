package com.abdllh.aura.guard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.abdllh.aura.BuildConfig
import com.abdllh.aura.R
import com.abdllh.aura.system.Device
import com.abdllh.aura.system.SystemProps
import com.abdllh.aura.system.WifiStep
import com.abdllh.aura.update.Rollback
import java.io.File

/** Starts the guard when the unit has started, and after an update (which ended it). A process of its own: see [GuardService]. */
class GuardReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        GuardService.start(c, if (i.action == Intent.ACTION_BOOT_COMPLETED) "boot" else "update")
    }
}

/**
 * The guard: a process apart from the main one (":guard"), which sees what CrashGuard cannot, because CrashGuard dies
 * with what it guards: a native crash, a throw in AuraApp, a frozen main thread, a home screen that never draws. It holds
 * nothing of the main process: no native code, no design system, none of its singletons (AuraApp returns at once here).
 *
 * What it knows comes from [GuardMarks]: starts that never reached OK, counted across process deaths, and a heartbeat
 * that stopped. What it does about them is staged by one constant, [ACTS_FROM_CODE]: in the 1.x versions it only writes
 * down what it would have done (files/guard-log.txt, shown in Settings → About); from 2.0 on, while a version is on
 * trial, it may go back to the saved version or, with none, to the stock launcher; after the trial it only offers
 * (AMRI Rescue). It also runs the Wi-Fi step the main process runs at a cold boot when the main process did not get
 * there, so the rescue paths (a phone, a PC) have a network.
 *
 * A foreground service, since Android stops a plain one soon after the app has left the screen, which is when it is
 * needed. The notification channel is the quietest there is, and nothing on this unit opens the shade.
 */
class GuardService : Service() {
    private var watch: GuardWatch? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // a fault in here stays in here, without a "keeps stopping" window over the car's screen
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            try { GuardLog.add(applicationContext, "the guard itself crashed: $e") } catch (_: Throwable) { }
            Process.killProcess(Process.myPid())
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.guard_channel), NotificationManager.IMPORTANCE_MIN))
        startForeground(ID, Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(getString(R.string.guard_channel))
            .setOngoing(true)
            .build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (watch == null) {
            val t = HandlerThread("AuraGuard", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
            watch = GuardWatch(applicationContext, Handler(t.looper)).also { it.start(intent?.getStringExtra(EXTRA_WHY) ?: "restart") }
        }
        return START_STICKY
    }

    companion object {
        private const val CHANNEL = "guard"
        private const val ID = 7
        private const val EXTRA_WHY = "why"

        /**
         * The staged release, in one number: the versionCode from which the guard may act by itself during the trial
         * (2.0.0-beta.1 = 2000001). Below it, it only writes down what it would have done.
         */
        const val ACTS_FROM_CODE = 2_000_000

        /** Never throws: asking for the guard must not be what takes the home screen down. */
        fun start(c: Context, why: String) {
            try {
                c.startForegroundService(Intent(c, GuardService::class.java).putExtra(EXTRA_WHY, why))
            } catch (t: Throwable) {
                Log.w("AuraGuard", "start: $t")
            }
        }
    }
}

/** Looks every few seconds at what the main process left, and decides. All on one low-priority thread of the guard's process. */
internal class GuardWatch(private val c: Context, private val h: Handler) {
    private class Start(val pid: Int, val at: Long) {
        var home = false
        var ok = false
        var okAt = 0L
        var alive = false
        var frozen = false
    }

    private class Marks {
        val starts = ArrayList<Start>()
        var wifi = false
        var verdict = false
        var frozen = 0
    }

    private val boot = GuardMarks.bootId()
    private val trial = GuardTrial.load(c)
    private val mayAct = BuildConfig.VERSION_CODE >= GuardService.ACTS_FROM_CODE
    private val upAt = SystemClock.uptimeMillis()
    private var marksLen = -1L
    private var marks = Marks()
    private var lastLook = upAt
    private var lastSave = upAt
    private var savedPassed = false
    private var wifiDone = false
    private var seenStarts = 0
    private var loggedStreak = 0
    private var beatPid = 0
    private var beatSeq = Long.MIN_VALUE
    private var beatSince = upAt
    private var noDrawSince = upAt
    private var drawPid = 0
    private var killPid = 0

    fun start(why: String) {
        GuardLog.add(c, "guard up ($why) boot $boot, ${BuildConfig.VERSION_NAME}: " + when {
            !mayAct -> "only watching in 1.x"
            trial.passed -> "trial over, offers only"
            else -> "on trial, may act"
        })
        h.post(tick)
    }

    private val tick = object : Runnable {
        override fun run() {
            try {
                look()
            } catch (t: Throwable) {
                Log.w(TAG, "look: $t")
            }
            h.postDelayed(this, TICK_MS)
        }
    }

    private fun look() {
        val f = File(c.filesDir, GuardMarks.FILE_MARKS)
        val len = f.length()
        if (len != marksLen) {
            marksLen = len
            marks = read(f)
            newMarks()
        }
        val now = SystemClock.uptimeMillis()
        val dt = now - lastLook
        lastLook = now
        wifi(now)
        val cur = marks.starts.lastOrNull()
        val live = cur != null && alive(cur.pid)
        // (the kill switch on by the owner's choice: the stock launcher is the home screen, there is nothing to guard. On
        // by CrashGuard, which is quicker on a throw in AuraApp: that is a failure, and going back still beats the stock launcher)
        if ((SystemProps.get(Device.DISABLED_PROP) != "1" || crashOff()) && cur != null) {
            if (live) { beat(cur, now, dt); draw(cur, now) } else noDrawSince = now
            kill()
            judge()
        } else {
            beatSince = now
            noDrawSince = now
        }
        // (also the one save after it passed: the main process reads the file to know whether this version has earned its place)
        if (!savedPassed && now - lastSave >= SAVE_MS) {
            lastSave = now
            trial.save(c)
            savedPassed = trial.passed
        }
    }

    private fun read(f: File): Marks {
        val m = Marks()
        val version = BuildConfig.VERSION_CODE.toString()
        var run: String? = null
        for (l in try { f.readLines() } catch (_: Throwable) { emptyList<String>() }) {
            val p = l.split(' ')
            if (p.size < 4 || p[1] != boot) continue
            val t = p[2].toLongOrNull() ?: continue
            val pid = p[3].toIntOrNull() ?: continue
            val s = m.starts.lastOrNull { it.pid == pid }
            when (p[0]) {
                GuardMarks.START -> {
                    // (only the last stretch of this version: what the version before an update did says nothing about this
                    // one, nor what this one did before a way back and an update again, in one boot)
                    val v = p.getOrNull(4)
                    if (v != run) { run = v; m.starts.clear(); m.verdict = false; m.frozen = 0 }
                    if (v == version) m.starts.add(Start(pid, t))
                }
                GuardMarks.HOME -> s?.home = true
                // (a healthy start after a verdict: a new failure later is a new story, and gets its own)
                GuardMarks.OK -> s?.let { it.ok = true; it.okAt = t; m.verdict = false }
                GuardMarks.ALIVE -> s?.alive = true
                GuardMarks.WIFI, GuardMarks.GUARD_WIFI -> m.wifi = true
                GuardMarks.RESET -> { m.starts.clear(); m.verdict = false; m.frozen = 0 }
                GuardMarks.VERDICT -> { m.verdict = true; m.frozen = 0 }
                GuardMarks.FROZEN -> p.getOrNull(4)?.toIntOrNull()?.let { fp -> m.starts.lastOrNull { it.pid == fp }?.let { it.frozen = true; m.frozen++ } }
            }
        }
        return m
    }

    /** What the new lines mean for the record: a start that reached OK is a healthy one; one after a short life gets a line. */
    private fun newMarks() {
        val starts = marks.starts
        val s = starts.lastOrNull { it.ok }
        if (s != null && (trial.lastOkBoot != boot || s.pid != trial.lastOkPid)) {
            trial.healthy++
            trial.lastOkBoot = boot
            trial.lastOkPid = s.pid
            trial.save(c)
            GuardLog.add(c, "start ok: home stayed up, ${(s.okAt - s.at) / 1000} s after the start (healthy starts ${trial.healthy}, use ${trial.seconds / 60} min)")
        }
        if (starts.size > seenStarts && seenStarts > 0) {
            val prev = starts.getOrNull(starts.size - 2)
            val last = starts.last()
            if (prev != null && prev.ok && last.at - prev.at < SHORT_LIFE_MS) {
                GuardLog.add(c, "the last process reached OK but lived only ${(last.at - prev.at) / 1000} s")
            }
        }
        seenStarts = starts.size
    }

    /** Starts in a row, newest first, that never reached OK: dead by now, or frozen. The one still starting is not one (yet). */
    private fun streak(): Int {
        val starts = marks.starts
        val last = starts.lastOrNull()
        var n = 0
        for (s in starts.asReversed()) {
            when {
                s.frozen -> n++
                s.ok -> return n
                s === last && alive(s.pid) -> { }
                s.alive && !s.home -> { } // lived on without a home screen to show: says nothing
                else -> n++
            }
        }
        return n
    }

    private fun alive(pid: Int): Boolean = try {
        File("/proc/$pid/cmdline").readText().trimEnd('\u0000') == c.packageName
    } catch (_: Throwable) {
        false
    }

    /** The heartbeat of the running start. uptimeMillis on both sides, which stands still while the unit sleeps: a night's sleep is not a freeze. */
    private fun beat(cur: Start, now: Long, dt: Long) {
        val b = try { File(c.filesDir, GuardMarks.FILE_BEAT).readText().trim().split(' ') } catch (_: Throwable) { emptyList() }
        val seq = if (b.size >= 2 && b[0].toIntOrNull() == cur.pid) b[1].toLongOrNull() ?: -1L else -1L
        if (cur.pid != beatPid || seq != beatSeq) {
            beatPid = cur.pid
            beatSeq = seq
            beatSince = now
        }
        val quiet = now - beatSince
        if (quiet < BEAT_LIVE_MS && interactive()) trial.seconds += (dt / 1000).toInt().coerceAtMost((TICK_MS / 1000).toInt() * 2)
        if (!cur.frozen && quiet > if (trial.neverOk) STALE_FIRST_MS else STALE_MS) stuck(cur, "the main thread has not answered for ${quiet / 1000} s")
    }

    /** Home wanted, nothing drawn: a black screen. Only while the screen is on and home is what should be showing (the firmware says what is in front, where it does). */
    private fun draw(cur: Start, now: Long) {
        if (cur.pid != drawPid) { drawPid = cur.pid; noDrawSince = now }
        if (!cur.home || cur.ok || cur.frozen) { noDrawSince = now; return }
        val front = try { Settings.System.getString(c.contentResolver, "activity_starting") } catch (_: Throwable) { null }
        if (!interactive() || (front != null && !front.contains("HomeActivity"))) { noDrawSince = now; return }
        if (now - noDrawSince > if (trial.neverOk) STALE_FIRST_MS else NO_DRAW_MS) stuck(cur, "home has not drawn for ${(now - noDrawSince) / 1000} s")
    }

    private fun stuck(cur: Start, what: String) {
        GuardMarks.add(c, GuardMarks.FROZEN, cur.pid.toString())
        cur.frozen = true
        when {
            !mayAct -> GuardLog.add(c, "$what: would end that process (only watching in 1.x)")
            trial.passed -> { GuardLog.add(c, "$what: trial over, offering Rescue"); RescueActivity.open(c) }
            else -> { GuardLog.add(c, "$what: ending that process"); killPid = cur.pid }
        }
    }

    private fun kill() {
        val pid = killPid
        if (pid == 0 || busy()) return
        killPid = 0
        if (alive(pid)) Process.killProcess(pid)
    }

    private fun judge() {
        val n = streak()
        val limit = if (trial.neverOk) LIMIT_FIRST else LIMIT
        if (n != loggedStreak) {
            // (a loop that goes on all day must not push everything else out of the log)
            if (n > loggedStreak && (n <= limit || n % 20 == 0)) GuardLog.add(c, "a start died or froze with no OK: $n in a row this boot (limit $limit)")
            loggedStreak = n
        }
        if (marks.verdict) return
        if (n < limit && marks.frozen < LIMIT) return
        verdict("$n starts in a row with no OK, ${marks.frozen} frozen")
    }

    private fun verdict(why: String) {
        if (mayAct && busy()) return // looked at again next time
        GuardMarks.add(c, GuardMarks.VERDICT)
        val p = Rollback.payload()
        val usable = Rollback.usable(p)
        val crashed = SystemProps.get(Device.DISABLED_PROP) == "1" && crashOff()
        val plan = if (usable) "go back to ${p?.version}" else "kill switch and stock launcher (no version to go back to)"
        when {
            !mayAct -> GuardLog.add(c, "$why: would $plan (only watching in 1.x)")
            !crashed && !Device.isDefaultHome(c) -> GuardLog.add(c, "$why: AMRI is not the home screen, nothing to do")
            trial.passed -> {
                GuardLog.add(c, "$why: trial over, offering Rescue")
                RescueActivity.open(c)
            }
            crashed && !usable -> GuardLog.add(c, "$why: CrashGuard has already taken the stock launcher, and there is no version to go back to")
            else -> {
                GuardLog.add(c, "$why: $plan")
                act(if (usable) p else null)
            }
        }
    }

    /** The kill switch was CrashGuard's doing (its marker says why; the marker is a file, so another process can read it). */
    private fun crashOff(): Boolean = try {
        File(c.filesDir, "disable_home").readText().startsWith("crash")
    } catch (_: Throwable) {
        false
    }

    private fun act(p: Rollback.Payload?) {
        Thread({
            try {
                if (p != null && Rollback.runScript(c, false)) {
                    val r = Rollback.awaitResult() // (a success ends this process before it gets here)
                    if (Rollback.failed(r) || r == null) {
                        GuardLog.add(c, "going back failed: ${Rollback.failureText(r)}")
                        crashDisabled()
                        RescueActivity.open(c)
                    }
                } else {
                    crashDisabled()
                    Rollback.toStock(c, "crash")
                    GuardLog.add(c, "kill switch on, stock launcher")
                }
            } catch (t: Throwable) {
                GuardLog.add(c, "acting: $t")
            }
        }, "AuraGuardAct").start()
    }

    /** As CrashGuard's own bail-out says it: a new build gets another chance at being the home screen (retryAfterUpdate). */
    private fun crashDisabled() {
        try { c.getSharedPreferences("aura_crash", Context.MODE_PRIVATE).edit().putBoolean("crashDisabled", true).commit() } catch (_: Throwable) { }
    }

    /** A call or the reverse camera: not the moment to take the screen away. */
    private fun busy(): Boolean = try {
        val cr = c.contentResolver
        Settings.System.getString(cr, "key_bt_state") in setOf("3", "4", "5") || Settings.System.getString(cr, "mcu_backcar_state") == "1"
    } catch (_: Throwable) {
        false
    }

    private fun interactive() = try {
        (c.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
    } catch (_: Throwable) {
        true
    }

    /** The cold boot's airplane mode, when the main process has not dealt with it a while after the boot: a phone or a PC can only reach the unit over Wi-Fi. */
    private fun wifi(now: Long) {
        if (wifiDone) return
        if (marks.wifi) { wifiDone = true; return }
        if (now - upAt < WIFI_AFTER_MS || SystemProps.get("sys.boot_completed") != "1") return
        if (!Device.isNwd && !(BuildConfig.DEBUG && SystemProps.get("debug.aura.unit") == "1")) { wifiDone = true; return }
        if (!interactive() || linkActive()) return // (going to sleep, or ZLink has the Wi-Fi: looked at again)
        wifiDone = true
        if (!wifiWanted()) return
        GuardMarks.add(c, GuardMarks.GUARD_WIFI)
        val o = WifiStep.run(c, "guard")
        if (o.acted) GuardLog.add(c, "wifi: the main process did not get there. airplane was ${if (o.airplane) "on" else "off"}, wifi ${if (o.ok) "on now" else "still off"}")
    }

    /** The owner's switch for this step: WifiOnStart reads the same key. Read here, late: a first read in this process comes from the disk. */
    private fun wifiWanted(): Boolean = try {
        c.getSharedPreferences("aura", Context.MODE_PRIVATE).getBoolean("wifiOnStart", true)
    } catch (_: Throwable) {
        true
    }

    private fun linkActive(): Boolean {
        val t = SystemProps.get("zj.phonelink.type").trim().lowercase()
        return t.isNotEmpty() && t != "none" && t != "0"
    }

    private companion object {
        const val TAG = "AuraGuard"
        const val TICK_MS = 5_000L
        const val SAVE_MS = 60_000L
        /** Starts in a row with no OK. A version that never reached OK gets more room: its first start is the slow one. */
        const val LIMIT = 3
        const val LIMIT_FIRST = 4
        /** A heartbeat that stands still this long is a freeze. Far longer than anything the screen does, on a slow day. */
        const val STALE_MS = 90_000L
        /** While a version has never reached OK: the first start after an install runs on code still being compiled (3 minutes and more on the unit). */
        const val STALE_FIRST_MS = 5 * 60_000L
        const val NO_DRAW_MS = 2 * 60_000L
        const val BEAT_LIVE_MS = 20_000L
        const val SHORT_LIFE_MS = 5 * 60_000L
        /** After the guard came up: the main process's own step runs 6 s after boot_completed and takes up to 18 s. */
        const val WIFI_AFTER_MS = 45_000L
    }
}
