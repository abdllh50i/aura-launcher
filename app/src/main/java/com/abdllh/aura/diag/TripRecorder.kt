package com.abdllh.aura.diag

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.location.Location
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import com.abdllh.aura.nav.CarLocation
import com.abdllh.aura.system.Front
import com.abdllh.aura.util.Prefs
import com.mapbox.mapboxsdk.maps.MapView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * "Record performance on the next trip": a parked screen check cannot show the load of a moving car, and the emulator
 * cannot either. While its switch is on, once a minute one short line goes into files/diag/trip.jsonl: what is in front,
 * the frames since the last line (a histogram, through FrameMetrics), the map's frames per second if a map is live, this
 * process's CPU and memory, the GPS rate and the speed, the temperature, the memory free, and the route requests made
 * meanwhile. The file goes with the next report. The switch is off by default, and with it off nothing here exists but
 * one preference read at the process's start.
 *
 * It stays below 1 % of a core: everything runs on one background-priority thread, 60 s apart, but for one counter per
 * map frame and one small copy per window frame; each line says what the thread itself cost ("selfPct"). It turns itself
 * off after two hours of lines, across restarts of the unit ([MAX_LINES]: the owner's unit starts cold at every drive, so
 * the switch must outlive a power-off and the count with it). Three failures in a row turn it off too.
 *
 * No position goes in the line, only a speed; no route, only how long the answer took.
 */
object TripRecorder {
    private const val TAG = "AuraTrip"
    private const val KEY_ON = "tripRecOn"
    private const val KEY_LINES = "tripRecLines"
    private const val EVERY_MS = 60_000L
    private const val MAX_LINES = 120 // two hours
    private const val MAX_FILE = 300 * 1024L

    /** The histogram's upper edges in ms; the last bucket is everything above. */
    private val EDGES = doubleArrayOf(8.0, 16.7, 25.0, 33.4, 50.0, 100.0)

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var app: Context? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var fails = 0

    // what happened since the last line (the frame listener and the tick run on [thread])
    private val hist = IntArray(EDGES.size + 1)
    private var droppedFrames = 0
    private var lastCpuMs = 0L
    private var lastSelfMs = 0L
    private var lastAt = 0L
    private var thermalFile: File? = null
    private val pending = ArrayList<JSONObject>() // guarded by itself
    @Volatile private var mapFrames = 0
    @Volatile private var fixes = 0
    @Volatile private var speed = Double.NaN
    @Volatile private var speedAt = 0L
    // which window is watched, and which maps (main thread)
    private var watched: Window? = null
    private val maps = HashMap<MapView, MapView.OnDidFinishRenderingFrameListener>()

    val on: Boolean get() = try { Prefs.raw.getBoolean(KEY_ON, false) } catch (_: Throwable) { false }
    /** Lines written since the switch went on. */
    val lines: Int get() = try { Prefs.raw.getInt(KEY_LINES, 0) } catch (_: Throwable) { 0 }
    val maxLines: Int get() = MAX_LINES

    /** From AuraApp, in the main process: comes up only when the switch was left on. */
    fun init(ctx: Context) {
        if (!on) return
        try { start(ctx.applicationContext) } catch (t: Throwable) { Log.w(TAG, "start: $t") }
    }

    /** The switch. Main thread. */
    fun set(ctx: Context, enabled: Boolean) {
        try {
            Prefs.raw.edit().putBoolean(KEY_ON, enabled).putInt(KEY_LINES, 0).apply()
            if (enabled) start(ctx.applicationContext) else stop()
        } catch (t: Throwable) {
            Log.w(TAG, "set: $t")
        }
    }

    private fun start(c: Context) {
        if (app != null) return
        app = c
        val t = HandlerThread("aura-trip", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        thread = t
        val h = Handler(t.looper)
        handler = h
        fails = 0
        (c as? Application)?.registerActivityLifecycleCallbacks(screens)
        CarLocation.addListener(onFix)
        CarLocation.acquire(c, "trip") // (the rate and the speed, also while another app is in front)
        h.post {
            lastAt = SystemClock.elapsedRealtime()
            lastCpuMs = Process.getElapsedCpuTime()
            lastSelfMs = SystemClock.currentThreadTimeMillis()
            thermalFile = hottestZone()
        }
        h.postDelayed(tick, EVERY_MS)
        Log.i(TAG, "recording")
    }

    private fun stop() {
        if (app == null) return
        (app as? Application)?.unregisterActivityLifecycleCallbacks(screens)
        CarLocation.removeListener(onFix)
        CarLocation.release("trip")
        unwatch()
        for ((v, l) in maps) try { v.removeOnDidFinishRenderingFrameListener(l) } catch (_: Throwable) { }
        maps.clear()
        handler?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        thread = null
        handler = null
        app = null
        Log.i(TAG, "off")
    }

    // ------------------------------------------------------------------------------------------ what is watched
    /** A map came on the screen ([com.abdllh.aura.nav.AuraMap.onStart]). Nothing happens while the recorder is off. Main thread. */
    fun mapOn(v: MapView) {
        if (app == null || maps.containsKey(v)) return
        try {
            val l = MapView.OnDidFinishRenderingFrameListener { mapFrames++ }
            v.addOnDidFinishRenderingFrameListener(l)
            maps[v] = l
        } catch (t: Throwable) {
            Log.w(TAG, "map: $t")
        }
    }

    fun mapOff(v: MapView) {
        val l = maps.remove(v) ?: return
        try { v.removeOnDidFinishRenderingFrameListener(l) } catch (_: Throwable) { }
    }

    /** A route was asked for and the answer came ([kind]: "route", "reroute"). Any thread. Nothing happens while off. */
    fun route(kind: String, ms: Long, found: Boolean) {
        if (app == null) return
        synchronized(pending) { pending.add(JSONObject().put("k", kind).put("ms", ms).put("ok", found)) }
    }

    private val onFix: (Location) -> Unit = { l ->
        fixes++
        if (l.hasSpeed()) { speed = l.speed * 3.6; speedAt = SystemClock.elapsedRealtime() }
    }

    private val frames = Window.OnFrameMetricsAvailableListener { _, m, dropped ->
        droppedFrames += dropped
        val ms = m.getMetric(FrameMetrics.TOTAL_DURATION) / 1e6
        var i = 0
        while (i < EDGES.size && ms > EDGES[i]) i++
        hist[i]++
    }

    private fun unwatch() {
        val w = watched ?: return
        watched = null
        try { w.removeOnFrameMetricsAvailableListener(frames) } catch (_: Throwable) { }
    }

    private val screens = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(a: Activity) {
            val h = handler ?: return
            unwatch()
            try {
                a.window.addOnFrameMetricsAvailableListener(frames, h)
                watched = a.window
            } catch (t: Throwable) {
                Log.w(TAG, "frames: $t")
            }
        }
        override fun onActivityPaused(a: Activity) { if (watched === a.window) unwatch() }
        override fun onActivityCreated(a: Activity, b: Bundle?) {}
        override fun onActivityStarted(a: Activity) {}
        override fun onActivityStopped(a: Activity) {}
        override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
        override fun onActivityDestroyed(a: Activity) {}
    }

    // ------------------------------------------------------------------------------------------ one line a minute
    private val tick = object : Runnable {
        override fun run() {
            val c = app ?: return
            try {
                val n = lines + 1
                if (n > MAX_LINES) { main.post { set(c, false) }; return }
                write(c)
                Prefs.raw.edit().putInt(KEY_LINES, n).apply()
                fails = 0
            } catch (t: Throwable) {
                Log.w(TAG, "line: $t")
                if (++fails >= 3) { main.post { set(c, false) }; return }
            }
            handler?.postDelayed(this, EVERY_MS)
        }
    }

    private val stamp by lazy { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") } }

    private fun write(c: Context) {
        val now = SystemClock.elapsedRealtime()
        val seconds = ((now - lastAt) / 1000.0).coerceAtLeast(1.0)
        val cpu = Process.getElapsedCpuTime()
        val o = JSONObject()
        o.put("t", stamp.format(Date())) // (the unit's clock: it may be late after a cold start)
        o.put("up", now / 1000)
        o.put("front", "${Front.pkg}/${Front.cls.substringAfterLast('.')}")
        o.put("own", if (Front.ownResumed) Front.own.substringAfterLast('.') else "")
        val hh = JSONArray()
        var frameCount = 0
        for (i in hist.indices) { hh.put(hist[i]); frameCount += hist[i]; hist[i] = 0 }
        o.put("frames", frameCount).put("hist", hh)
        if (droppedFrames > 0) { o.put("framesLost", droppedFrames); droppedFrames = 0 }
        val map = mapFrames
        mapFrames = 0
        if (map > 0) o.put("mapFps", Num.of(map / seconds))
        o.put("cpuPct", Num.of(100.0 * (cpu - lastCpuMs) / (now - lastAt).coerceAtLeast(1L)))
        o.put("pssKb", Debug.getPss())
        o.put("gpsHz", Num.of(fixes / seconds, 2))
        fixes = 0
        if (now - speedAt < 5000L) o.put("kmh", Num.of(speed))
        thermalFile?.let { f -> try { f.readText().trim().toDoubleOrNull()?.let { t -> o.put("tempC", Num.of(if (t > 1000) t / 1000 else t)) } } catch (_: Throwable) { } }
        (c.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.let { am ->
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            o.put("availMb", mi.availMem / 1_048_576)
        }
        synchronized(pending) {
            if (pending.isNotEmpty()) {
                o.put("routes", JSONArray().apply { for (p in pending) put(p) })
                pending.clear()
            }
        }
        val self = SystemClock.currentThreadTimeMillis()
        o.put("selfPct", Num.of(100.0 * (self - lastSelfMs) / (now - lastAt).coerceAtLeast(1L), 3))
        lastSelfMs = self
        lastAt = now
        lastCpuMs = cpu
        val f = DiagFiles.trip(c)
        if (f.length() < MAX_FILE) FileOutputStream(f, true).use { it.write((o.toString() + "\n").toByteArray(Charsets.UTF_8)) }
    }

    /** The zone that says most about the processor: named so, else the first there is. */
    private fun hottestZone(): File? = try {
        val zones = File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }.orEmpty().sortedBy { it.name }
        val cpu = zones.firstOrNull { z -> try { File(z, "type").readText().contains("cpu", ignoreCase = true) } catch (_: Throwable) { false } }
        (cpu ?: zones.firstOrNull())?.let { File(it, "temp") }?.takeIf { it.canRead() }
    } catch (_: Throwable) {
        null
    }

    /** What was recorded so far, as a list, for the report; at most what fits it. Not on the main thread. */
    internal fun recorded(c: Context): JSONArray {
        val out = JSONArray()
        val f = DiagFiles.trip(c)
        try {
            if (f.isFile) f.forEachLine { l -> if (l.isNotBlank()) try { out.put(JSONObject(l)) } catch (_: Throwable) { } }
        } catch (t: Throwable) {
            Log.w(TAG, "read: $t")
        }
        return out
    }
}
