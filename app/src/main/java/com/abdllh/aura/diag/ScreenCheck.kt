package com.abdllh.aura.diag

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.abdllh.aura.BuildConfig
import com.abdllh.aura.R
import com.abdllh.aura.music.LyricsServer
import com.abdllh.aura.nav.CarLocation
import com.abdllh.aura.nav.NavSession
import com.abdllh.aura.system.Device
import com.abdllh.aura.system.Gear
import com.abdllh.aura.system.MusicKeys
import com.abdllh.aura.util.LocaleHelper
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The screen check, in the main process: one press of the button in Settings › About runs it, parked, for about two
 * minutes; at its end there is a report the owner can read and photograph, and his server has it. Until then no number
 * had ever come from the unit (no RAM, no GPU memory, no frame time), and the plans for 2.0 rested on guesses.
 *
 * Nothing runs unless the button was pressed, and it is built to be the harmless part of a car's home screen:
 *  - this process only reads text and files on a thread of its own, every call with an end ([Sh], [Collect]);
 *  - all that loads GL or native code runs in the ":diag" process ([ProbeActivity]), watched from here through files
 *    (no binder to hang on). A test that kills that process leaves its marker: it is reported as "crashed", the rest
 *    goes on in a new process, and later runs skip it until the app's version changes or the owner asks to try again;
 *    a test past its time is killed from here and is reported as "hung", the same way;
 *  - the car moving or reversing, another screen coming over the test, Home pressed: the check stops at once and keeps
 *    what it has, and nothing is sent that he did not see come to its end (the page offers to send it).
 * If this process dies meanwhile (the system freeing memory for the test is a result in itself), the next opening of the
 * page finds run.json and makes a report of what was left, saying so.
 */
object ScreenCheck {
    private const val TAG = "AuraDiag"
    private const val WHEEL_SECONDS = 10
    private const val PART_MS = 45_000L
    const val DIED_MAIN = "the main process died during the check"
    const val DIED_UNIT = "the unit restarted during the check"

    /** What the page shows while it runs. */
    class Status(val running: Boolean, val line: String = "", val frac: Float = 0f, val wheelLeft: Int = 0, val keys: String = "", val sending: Boolean = false)

    /** Set by the page. Main thread. */
    var listener: ((Status) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    @Volatile var running = false
        private set
    @Volatile var sending = false
        private set
    @Volatile private var cancelWhy: String? = null
    private var page: Activity? = null
    @Volatile private var pageResumed = false
    @Volatile private var keyWatch: Collect.KeyWatch? = null
    private var last = Status(false)

    private fun show(s: Status) {
        last = s
        main.post { listener?.invoke(s) }
    }

    /** The latest status (a page that opens while it runs). */
    val status: Status get() = last

    // ------------------------------------------------------------------------------------------ the page
    /** The page is up. A run whose process died is ended now (on its own thread); [done] when it was. Main thread. */
    fun attach(a: Activity, done: () -> Unit) {
        page = a
        if (running) return
        val app = a.applicationContext
        if (!DiagFiles.run(app).isFile) return
        running = true
        Thread({
            try { endDeadRun(app) } catch (t: Throwable) { Log.w(TAG, "dead run: $t") }
            running = false
            show(Status(false))
            main.post(done)
        }, "aura-diag").apply { isDaemon = true; start() }
    }

    fun pageResumed(a: Activity, resumed: Boolean) {
        if (page === a) pageResumed = resumed
    }

    /** The page is gone (Back, the Home key): so is the check. Main thread. */
    fun detach(a: Activity) {
        if (page !== a) return
        page = null
        pageResumed = false
        if (running) cancel("left")
    }

    fun cancel(why: String) {
        if (cancelWhy == null) cancelWhy = why
    }

    /** A key that reached the page as an Android key, in the step where keys are asked for. */
    fun keyEvent(code: Int) { keyWatch?.keyEvent(code) }

    /** Why it cannot start now (a string), or null when the car is standing. Main thread. */
    fun refusal(): Int? = when {
        NavSession.state != NavSession.State.IDLE -> R.string.diag_refuse_trip
        Gear.current == Gear.Pos.R || Gear.source == Gear.Source.MOTION -> R.string.diag_refuse_moving
        (CarLocation.last?.let { SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos < 10_000_000_000L && it.hasSpeed() && it.speed * 3.6 >= 8 } == true) -> R.string.diag_refuse_moving
        com.abdllh.aura.phone.Calls.busy -> R.string.diag_refuse_call
        else -> null
    }

    /** The button. Main thread. */
    fun start(a: Activity) {
        if (running) return
        running = true
        cancelWhy = null
        page = a
        pageResumed = true
        val app = a.applicationContext
        CarLocation.acquire(app, "diag") // (so the car starting to move is seen, and the GPS step has fixes to count)
        Gear.addListener(gearWatch)
        val texts = LocaleHelper.wrap(app)
        show(Status(true, texts.getString(R.string.diag_starting)))
        Thread({
            try {
                work(app, texts)
            } catch (t: Throwable) {
                Log.w(TAG, "the check: $t")
            }
            main.post {
                Gear.removeListener(gearWatch)
                CarLocation.release("diag")
            }
            running = false
            show(Status(false, sending = sending))
        }, "aura-diag").apply { isDaemon = true; start() }
    }

    private val gearWatch: () -> Unit = {
        if (Gear.current == Gear.Pos.R || Gear.source == Gear.Source.MOTION) cancel("moving")
    }

    // ------------------------------------------------------------------------------------------ the run
    private class Step(val label: Int, val seconds: Int)

    private val STEPS_BEFORE = listOf(
        Step(R.string.diag_s_identity, 2), Step(R.string.diag_s_capacity, 4), Step(R.string.diag_s_memory, 5), Step(R.string.diag_s_voice, 3),
        Step(R.string.diag_s_voice, 0))
    private const val SECONDS_AFTER = 4 // the logs, the report

    private val totalSeconds: Int
        get() = STEPS_BEFORE.sumOf { it.seconds } + WHEEL_SECONDS + Checks.GROUPS.sumOf { g -> g.sumOf { it.seconds } + Checks.START_SECONDS } + SECONDS_AFTER

    private var doneSeconds = 0

    private fun progress(texts: Context, label: Int, wheelLeft: Int = 0, keys: String = "") {
        val left = totalSeconds - doneSeconds
        show(Status(true, texts.getString(R.string.diag_progress, texts.getString(label), Checks.time(left)),
            doneSeconds.toFloat() / totalSeconds, wheelLeft, keys))
    }

    /**
     * One part of the report: whatever goes wrong in it stays in it, and it has an end (a disk or a firmware call that
     * never returns must not leave the page saying "cancelling" for good).
     */
    private fun part(what: () -> JSONObject): JSONObject = try {
        val f = Sh.pool.submit(Callable { what() })
        try {
            f.get(PART_MS, TimeUnit.MILLISECONDS)
        } catch (t: TimeoutException) {
            f.cancel(true)
            JSONObject().put("error", "no answer in ${PART_MS / 1000} s")
        }
    } catch (t: Throwable) {
        Log.w(TAG, "part: $t")
        JSONObject().put("error", (t.cause ?: t).toString())
    }

    /** What the check gives way to, wherever it stands. */
    private fun giveWay() {
        if (com.abdllh.aura.phone.Calls.busy) cancel("call")
    }

    private fun work(app: Context, texts: Context) {
        val startedAt = System.currentTimeMillis()
        val t0 = SystemClock.elapsedRealtime()
        doneSeconds = 0
        deaths = JSONArray()
        val r = JSONObject()
        begin(app, r)
        DiagFiles.write(DiagFiles.run(app), JSONObject().put("pid", Process.myPid()).put("at", startedAt).put("boot", Marker.bootId()))
        var i = 0
        fun step(key: String, what: () -> JSONObject) {
            val s = STEPS_BEFORE[i++]
            if (cancelWhy == null) {
                progress(texts, s.label)
                r.put(key, part(what))
            }
            doneSeconds += s.seconds
        }
        step("a_identity") { Collect.identity(app) }
        step("b_capacity") { Collect.capacity(app) }
        step("d_memory") { Collect.memory(app) }
        step("g_voice") { Collect.voice(app) }
        step("k_settings") { Collect.settings(app) }
        if (cancelWhy == null) wheelStep(app, texts, r)
        doneSeconds += WHEEL_SECONDS
        for (g in Checks.GROUPS) {
            if (cancelWhy == null) group(app, texts, g)
            doneSeconds += g.sumOf { it.seconds } + Checks.START_SECONDS
        }
        progress(texts, R.string.diag_s_logs)
        finishReport(app, r, startedAt, SystemClock.elapsedRealtime() - t0, cancelWhy)
        DiagFiles.run(app).delete()
        if (cancelWhy == null) send(app)
    }

    /** What the last run left: a test that never came back, old results. */
    private fun begin(app: Context, r: JSONObject) {
        val d = DiagFiles.dir(app)
        val m = DiagFiles.read(DiagFiles.marker(app))
        if (m != null) {
            // a test running when the unit went off, or that took the whole unit with it: it is not tried again blindly
            val same = m.optString("boot") == Marker.bootId()
            addSkip(app, m.optString("test"), if (same) "crashed" else "the unit restarted during it")
            r.put("previousRunDiedIn", m.optString("test"))
            Marker.clear(app)
        }
        for (f in d.listFiles().orEmpty()) {
            if (f.name.startsWith("r-") || f.name.startsWith("plan") || f.name == "cancel" || f.name == "write-test.bin") f.delete()
        }
    }

    // ---- the step where keys are pressed, with the two things that take the same 10 s anyway: the GPS and the network
    private fun wheelStep(app: Context, texts: Context, r: JSONObject) {
        if (!pageResumed) return cancel("left") // (he left before it asked for anything)
        var keyLine = ""
        var secondsLeft = WHEEL_SECONDS
        val keys = Collect.KeyWatch(app) {
            keyLine = keyWatch?.line().orEmpty()
            progress(texts, R.string.diag_s_wheel, secondsLeft, keyLine)
        }
        val gps = Collect.GpsWatch(app)
        try {
            gps.start()
            keys.start()
            keyWatch = keys
        } catch (t: Throwable) {
            Log.w(TAG, "wheel: $t")
        }
        // (the music keys would open the music screen over this page)
        MusicKeys.quietUntil = SystemClock.elapsedRealtime() + (WHEEL_SECONDS + 2) * 1000L
        var net: JSONObject? = null
        val netThread = Thread({ net = part { Collect.network(app) } }, "aura-diag-net").apply { isDaemon = true; start() }
        var raw = ""
        val rawThread = Thread({ raw = try { Collect.rawKeys(WHEEL_SECONDS) } catch (_: Throwable) { "" } }, "aura-diag-keys").apply { isDaemon = true; start() }
        val until = SystemClock.elapsedRealtime() + WHEEL_SECONDS * 1000L
        while (cancelWhy == null) {
            giveWay()
            val left = until - SystemClock.elapsedRealtime()
            if (left <= 0) break
            secondsLeft = ((left + 999) / 1000).toInt()
            progress(texts, R.string.diag_s_wheel, secondsLeft, keyLine)
            Thread.sleep(left.coerceAtMost(500L))
        }
        secondsLeft = 0
        MusicKeys.quietUntil = 0L
        keyWatch = null
        // a key he was asked to press may have opened its screen (the firmware's own, for most): the page comes back
        if (!pageResumed && cancelWhy == null) main.post { bringBack(app) }
        rawThread.join(5000L)
        netThread.join(9000L)
        r.put("h_network", net ?: JSONObject().put("error", "no answer in time"))
        r.put("i_gps", part { gps.stop() })
        val car = part { Collect.car(app) }
        car.put("wheel", part { keys.stop(raw) })
        r.put("j_car", car)
    }

    // ---- a group of tests in :diag, watched from here
    private enum class Ended { DONE, CANCELLED, DIED, HUNG }
    private var deaths = JSONArray()

    private fun group(app: Context, texts: Context, tests: List<Checks.Test>) {
        var left = tests.map { it.name }.filter { !skipped(app, it) }
        var startsWithoutWork = 0
        while (left.isNotEmpty() && cancelWhy == null) {
            // the page first: a screen that came over it (a key that opened something) has its moment to go
            if (!waitForPage()) { cancel("left"); return }
            waitGone(app)
            val after = laterSeconds(tests)
            DiagFiles.planDone(app).delete()
            Marker.clear(app)
            DiagFiles.write(DiagFiles.plan(app), JSONObject().put("tests", JSONArray(left)).put("afterSec", after))
            progress(texts, Checks.of(left.first())?.label ?: R.string.diag_s_logs)
            val before = left
            main.post { startProbe() }
            val (how, test) = watch(app, left)
            when (how) {
                Ended.DONE, Ended.CANCELLED -> return
                Ended.DIED, Ended.HUNG -> {
                    if (test != null) {
                        val why = if (how == Ended.HUNG) "hung" else "crashed"
                        Log.w(TAG, "$test $why")
                        addSkip(app, test, why)
                        DiagFiles.write(DiagFiles.result(app, test), JSONObject().put("test", test).put("status", why).put("evidence", evidence()))
                        Marker.clear(app)
                    } else {
                        // no test to blame (between two of them, or something else ended it): the report still says it happened
                        deaths.put(JSONObject().put("beforeTests", JSONArray(left)).put("evidence", evidence()))
                    }
                    left = left.filter { !DiagFiles.result(app, it).isFile && !skipped(app, it) }
                    // a process that died with no test running, twice with nothing new to show for it: enough
                    if (test == null && left == before && ++startsWithoutWork >= 2) {
                        for (n in left) DiagFiles.write(DiagFiles.result(app, n), JSONObject().put("test", n).put("status", "not run").put("why", "its process did not start"))
                        return
                    }
                }
            }
        }
    }

    /** Seconds of the groups after the one of [tests], and the rest of the check. */
    private fun laterSeconds(tests: List<Checks.Test>): Int {
        val at = Checks.GROUPS.indexOf(tests)
        return Checks.GROUPS.drop(at + 1).sumOf { g -> g.sumOf { it.seconds } + Checks.START_SECONDS } + SECONDS_AFTER
    }

    private fun waitForPage(): Boolean {
        val until = SystemClock.elapsedRealtime() + 30_000L
        while (SystemClock.elapsedRealtime() < until) {
            if (cancelWhy != null) return false
            if (pageResumed) return true
            Thread.sleep(100L)
        }
        return false
    }

    private fun bringBack(app: Context) {
        val a = page ?: return
        try {
            // (the same page in its task, not a second one on top: REORDER_TO_FRONT does not do that from outside an activity)
            app.startActivity(Intent(app, a.javaClass).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } catch (t: Throwable) {
            Log.w(TAG, "page: $t")
        }
    }

    private fun startProbe() {
        val a = page ?: return cancel("left")
        try {
            a.startActivity(Intent(a, ProbeActivity::class.java))
            a.overridePendingTransition(0, 0)
        } catch (t: Throwable) {
            Log.w(TAG, "probe: $t")
        }
    }

    private fun diagPid(app: Context): Int? = try {
        val name = app.packageName + ":diag"
        (app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.runningAppProcesses?.firstOrNull { it.processName == name }?.pid
    } catch (_: Throwable) {
        null
    }

    /** The plan goes first: the system may start the killed process's top activity again, and that must find nothing to do. */
    private fun killDiag(app: Context) {
        DiagFiles.plan(app).delete()
        diagPid(app)?.let { Process.killProcess(it) }
    }

    /**
     * Until the last process is really gone. The system lists a dying process for a moment: taken for the next group's, its
     * going looked like that one dying before it had started (on the emulator, one run in five, with the last test "not run").
     */
    private fun waitGone(app: Context) {
        val until = SystemClock.elapsedRealtime() + 5000L
        while (diagPid(app) != null && SystemClock.elapsedRealtime() < until) Thread.sleep(50L)
        diagPid(app)?.let { Process.killProcess(it); Thread.sleep(300L) }
    }

    /** Until :diag has ended its plan or something ended it. Which test it was in, when that is known. */
    private fun watch(app: Context, tests: List<String>): Pair<Ended, String?> {
        val startedAt = SystemClock.elapsedRealtime()
        // (however long the tests may take, and a new process's start on top: a last line under the markers' own)
        val latest = startedAt + tests.sumOf { Checks.of(it)?.budgetMs ?: 60_000L } + 30_000L
        var seen = false
        while (true) {
            Thread.sleep(300L)
            giveWay()
            val now = SystemClock.elapsedRealtime()
            if (cancelWhy != null || DiagFiles.cancel(app).exists()) {
                cancel("cancel")
                Marker.clear(app) // (not a crash: it was stopped)
                killDiag(app)
                return Ended.CANCELLED to null
            }
            DiagFiles.read(DiagFiles.planDone(app))?.let { d ->
                d.optString("interrupted").takeIf { it.isNotEmpty() }?.let { cancel(it) }
                if (d.has("error")) Log.w(TAG, "the tests: ${d.optString("error")}")
                DiagFiles.plan(app).delete()
                waitGone(app) // (it finishes its window and ends by itself; killed in the middle, the system might start it again)
                return Ended.DONE to null
            }
            val marker = DiagFiles.read(DiagFiles.marker(app))
            if (marker != null && now > marker.optLong("deadline", Long.MAX_VALUE) || now > latest) {
                killDiag(app)
                Thread.sleep(300L)
                return Ended.HUNG to marker?.optString("test")
            }
            val alive = diagPid(app) != null
            if (alive) { seen = true; continue }
            // not there: on its way up, or gone. Gone, a marker says which test it was in.
            if (seen || now - startedAt > 20_000L) {
                Thread.sleep(400L) // (its last file may just be on its way)
                if (DiagFiles.planDone(app).isFile || diagPid(app) != null) continue
                DiagFiles.plan(app).delete()
                return Ended.DIED to DiagFiles.read(DiagFiles.marker(app))?.optString("test")
            }
        }
    }

    /** What the system logged about the death, for the report. */
    private fun evidence(): JSONArray = Num.lines(Sh.text("logcat -d -t 1500 | grep -E 'aura:diag|>>> |F libc|Abort message|signal [0-9]+|F DEBUG   : +#0[0-9] |am_crash|lowmemorykiller|lmkd' | tail -n 30 | cut -c1-200", 6000))

    // ---- tests that are not run again
    private fun addSkip(app: Context, test: String, why: String) {
        if (test.isEmpty()) return
        val o = DiagFiles.read(DiagFiles.skip(app))?.takeIf { it.optInt("appCode") == BuildConfig.VERSION_CODE } ?: JSONObject().put("appCode", BuildConfig.VERSION_CODE)
        val tests = o.optJSONObject("tests") ?: JSONObject()
        tests.put(test, JSONObject().put("why", why).put("at", System.currentTimeMillis()))
        o.put("tests", tests)
        DiagFiles.write(DiagFiles.skip(app), o)
    }

    /** The tests skipped for now, with why; empty once a newer version is here (it may have fixed them). */
    private fun skips(app: Context): JSONObject =
        DiagFiles.read(DiagFiles.skip(app))?.takeIf { it.optInt("appCode") == BuildConfig.VERSION_CODE }?.optJSONObject("tests") ?: JSONObject()

    /** Their names, for the page. Not on the main thread. */
    fun skippedNames(c: Context): List<String> = skips(c.applicationContext).keys().asSequence().toList()

    fun forgetSkipped(c: Context) { DiagFiles.skip(c.applicationContext).delete() }

    private fun skipped(app: Context, test: String): Boolean {
        val s = skips(app)
        val own = s.optJSONObject(test)
        val why = when {
            own != null -> own.optString("why")
            Checks.needsMap(test) && s.has("map.surface") -> "needs the map, which died"
            else -> return false
        }
        // (this run's own crash has its result already: with the evidence)
        val f = DiagFiles.result(app, test)
        if (!f.isFile) DiagFiles.write(f, JSONObject().put("test", test).put("status", "skipped").put("why", why))
        return true
    }

    // ------------------------------------------------------------------------------------------ the report
    private val utc get() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }

    private fun finishReport(app: Context, r: JSONObject, startedAt: Long, tookMs: Long, stopped: String?) {
        r.put("kind", "amri-screen-check").put("schema", 1)
        r.put("started", utc.format(Date(startedAt))).put("startedMs", startedAt).put("tookSec", tookMs / 1000)
        r.put("realUnit", Device.isNwd)
        if (stopped != null) r.put("stopped", stopped)
        r.put("d_logs", part { Collect.logs(app) })
        val tests = JSONObject()
        val gl = JSONObject()
        val map = JSONObject()
        val motion = JSONObject()
        for (g in Checks.GROUPS) for (t in g) {
            val o = DiagFiles.read(DiagFiles.result(app, t.name))
            tests.put(t.name, JSONObject().put("status", o?.optString("status") ?: "not run").apply {
                o?.optString("why")?.takeIf { it.isNotEmpty() }?.let { put("why", it) }
                o?.optString("error")?.takeIf { it.isNotEmpty() }?.let { put("error", it) }
                o?.optJSONArray("evidence")?.let { put("evidence", it) }
            })
            if (o != null) when {
                t.name.startsWith("gl.") -> gl.put(t.name.removePrefix("gl."), o)
                t.name.startsWith("map.") -> map.put(t.name.removePrefix("map."), o)
                else -> motion.put(t.name.removePrefix("ui."), o)
            }
        }
        r.put("tests", tests).put("c_gpu", gl).put("e_map", map).put("f_motion", motion)
        if (deaths.length() > 0) r.put("processDeaths", deaths)
        val trip = TripRecorder.recorded(app)
        if (trip.length() > 0) r.put("trip", trip)
        write(app, r)
    }

    /** On the disk as the page and the server get it: with the one secret this process knows taken out of every line. */
    private fun write(app: Context, r: JSONObject) {
        var text = clean(r.toString(1))
        if (text.toByteArray(Charsets.UTF_8).size > MAX_REPORT) {
            // (the server takes 2 MB; the longest thing there can be is the trip's lines)
            r.remove("trip")
            r.put("trimmed", "the trip's lines did not fit")
            text = clean(r.toString(1))
        }
        DiagFiles.writeBytes(DiagFiles.report(app), text.toByteArray(Charsets.UTF_8))
        DiagFiles.sent(app).delete()
    }

    private const val MAX_REPORT = 1_950_000

    private fun clean(text: String): String {
        val key = try { LyricsServer.key } catch (_: Throwable) { "" }
        return if (key.length >= 4) text.replace(key, "***") else text
    }

    /** A run this process did not live to end. */
    private fun endDeadRun(app: Context) {
        val run = DiagFiles.read(DiagFiles.run(app))
        val startedAt = run?.optLong("at") ?: System.currentTimeMillis()
        if (diagPid(app) != null) Marker.clear(app) // (still at it: not its fault, and its marker is not a crash then)
        killDiag(app)
        val m = DiagFiles.read(DiagFiles.marker(app))
        if (m != null) {
            val why = if (m.optString("boot") == Marker.bootId()) "crashed" else "the unit restarted during it"
            addSkip(app, m.optString("test"), why)
            DiagFiles.write(DiagFiles.result(app, m.optString("test")), JSONObject().put("test", m.optString("test")).put("status", why))
            Marker.clear(app)
        }
        val r = JSONObject()
        r.put("a_identity", part { Collect.identity(app) })
        finishReport(app, r, startedAt, 0L, if (run?.optString("boot") == Marker.bootId()) DIED_MAIN else DIED_UNIT)
        DiagFiles.run(app).delete()
    }

    // ------------------------------------------------------------------------------------------ sending
    /** To the owner's server, with its key (also the page's "send again"). Off the main thread by itself. */
    fun send(c: Context) {
        val app = c.applicationContext
        if (sending) return
        sending = true
        show(last.let { Status(it.running, it.line, it.frac, it.wheelLeft, it.keys, true) })
        Thread({
            val out = JSONObject().put("at", System.currentTimeMillis())
            try {
                val body = DiagFiles.report(app).readBytes()
                when {
                    LyricsServer.base() == null || LyricsServer.key.isBlank() -> out.put("result", "no_server")
                    LyricsServer.cleartextBlocked() -> out.put("result", "cleartext")
                    else -> {
                        val code = LyricsServer.sendReport(body)
                        out.put("code", code)
                        out.put("result", when (code) { in 200..299 -> "ok"; 401 -> "wrong_key"; 404 -> "old_server"; else -> "http" })
                    }
                }
            } catch (t: Throwable) {
                Log.i(TAG, "send: $t")
                out.put("result", "unreachable").put("error", t.javaClass.simpleName)
            }
            try {
                DiagFiles.write(DiagFiles.sent(app), out)
                // its lines went with it (a recording still on starts a new file); lines newer than the report wait for the next
                val trip = DiagFiles.trip(app)
                if (out.optString("result") == "ok" && trip.lastModified() <= DiagFiles.report(app).lastModified()) trip.delete()
            } catch (t: Throwable) {
                Log.w(TAG, "sent: $t")
            }
            sending = false
            show(last.let { Status(it.running, it.line, it.frac, it.wheelLeft, it.keys, false) })
        }, "aura-diag-send").apply { isDaemon = true; start() }
    }

    /** The kept report and how sending it went, or null when there is none. Not on the main thread. */
    fun lastReport(c: Context): Pair<JSONObject, JSONObject?>? {
        val app = c.applicationContext
        val r = DiagFiles.read(DiagFiles.report(app)) ?: return null
        return r to DiagFiles.read(DiagFiles.sent(app))
    }
}
