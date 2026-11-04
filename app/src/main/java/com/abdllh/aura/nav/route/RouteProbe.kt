package com.abdllh.aura.nav.route

import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import com.abdllh.aura.BuildConfig
import com.abdllh.aura.system.LocalAdb
import com.valhalla.valhalla.ValhallaKotlin
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream

/**
 * The scout release's question, the project's top risk: does Valhalla run on the unit's real ARM32 userspace at all, and if it
 * does not, does the kernel run a static AArch64 program (the way out of maps.md 7.5)? None of this can be known on the PC: qemu
 * ran the library's 34 answers right, but not on the unit's kernel, CPU or eMMC.
 *
 * [start], from the main process: [RouteProbeService] in :route does the work, one stage after the other. Before each stage a
 * marker goes on the disk, and what each stage found goes into files/diag/route-probe.json at once, so a native crash loses
 * nothing: the stage it died in is the one the marker names, and it is reported as `died`. The watcher here binds the service
 * again and the next process goes on from the stage after it, up to [MAX_DEATHS]; not before [REST_MS] have passed, since a second
 * crash of one process within a minute makes Android show "AMRI OS keeps stopping" over the home screen. A stage that hangs is
 * killed after [STAGE_MS] and reported the same way, with `hung`. A failure is reported, never thrown. Nothing reads the report
 * here: the Screen check page shows it and sends it.
 *
 * Costs the APK the library (6,017,176 B on armeabi-v7a), the tar (1,517,794 B), the test program (664 B) and the config.
 */
internal object RouteProbe {
    private const val TAG = "AuraRouteProbe"
    private const val ACTION = "com.abdllh.aura.debug.ROUTE_PROBE"
    private const val MAX_DEATHS = 3
    private const val STAGE_MS = 90_000L
    private const val REST_MS = 65_000L
    private const val WHOLE_RUN_MS = 480_000L
    private const val EXE_TIMEOUT_MS = 8_000L
    /** The tar in the asset (out/route-probe.sha256): main roads of the Eastern Province, what route.tar's own tools make. */
    private const val TAR_SHA256 = "a7446fa524fdf40d92b4b8eb813cb78f58ab2f9d949837cfd29cc236d28f8a33"
    /** A little more than route.tar's 378 MB: does a mapping that long still fit in this process, after the library and its tiles? */
    private const val MAP_BYTES = 400L * 1024 * 1024

    /** [refKm]: Jubail-Khobar is the PC service's on this tar; the rest the same request on the x86_64 library, whose answers equal the service's. */
    private class Trip(val id: String, val a: Double, val b: Double, val c: Double, val d: Double, val refKm: Double)

    private val TRIPS = listOf(
        Trip("jubail-khobar", 27.0046, 49.6586, 26.2885, 50.2185, 104.85),
        Trip("jubail-dammam", 27.0046, 49.6586, 26.4207, 50.0888, 83.54),
        Trip("khobar-dammam", 26.29442, 50.22216, 26.46067, 50.10672, 25.19),
        Trip("jubail-industrial", 27.0046, 49.6586, 27.09386, 49.56874, 16.07),
    )

    private val running = AtomicBoolean(false)
    private var crashIn: String? = null
    private var hangIn: String? = null

    fun resultFile(c: Context) = File(dir(c), "route-probe.json")
    private fun dir(c: Context) = File(c.filesDir, "diag").apply { mkdirs() }
    private fun marker(c: Context) = File(dir(c), "route-probe.marker")
    private fun doneFile(c: Context) = File(dir(c), "route-probe.done")
    private fun work(c: Context) = File(c.cacheDir, "route-probe").apply { mkdirs() }

    /**
     * Debug builds: `am broadcast -a com.abdllh.aura.debug.ROUTE_PROBE`, with `--es exe <path>` another test program (for the
     * emulator's CPU), `--es crash <stage>` a real SIGSEGV in that stage, `--es hang <stage>` that stage never ending.
     */
    fun debugInit(ctx: Context) {
        val app = ctx.applicationContext
        try {
            app.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) = start(app, i.extras) { Log.i(TAG, "report: ${it.path}") }
            }, IntentFilter(ACTION))
        } catch (t: Throwable) {
            Log.w(TAG, "$t")
        }
    }

    /**
     * Runs the probe, or does nothing while one runs. Any thread; returns at once. [done] on the main thread with the report
     * (files/diag/route-probe.json, `finished` true), when there is one.
     */
    fun start(ctx: Context, debug: Bundle? = null, done: ((File) -> Unit)? = null) {
        val app = ctx.applicationContext
        if (!running.compareAndSet(false, true)) return
        Thread({
            try {
                watch(app, debug)
            } catch (t: Throwable) {
                Log.w(TAG, "$t")
                giveUp(app, t.toString())
            } finally {
                running.set(false)
                if (done != null) Handler(Looper.getMainLooper()).post { done(resultFile(app)) }
            }
        }, "aura-route-probe-watch").start()
    }

    // ------------------------------------------------------------------------------------------ the main process's part

    private fun watch(c: Context, debug: Bundle?) {
        resultFile(c).delete(); marker(c).delete(); doneFile(c).delete()
        val deadline = SystemClock.elapsedRealtime() + WHOLE_RUN_MS
        var runs = 0
        while (true) {
            val lost = AtomicBoolean(false)
            val conn = object : ServiceConnection {
                private fun gone() {
                    lost.set(true)
                    // (at once: the system would start it again by itself for a connection that stays)
                    try { c.unbindService(this) } catch (_: Throwable) { }
                }
                override fun onServiceConnected(n: ComponentName?, b: IBinder?) { }
                override fun onServiceDisconnected(n: ComponentName?) = gone()
                override fun onBindingDied(n: ComponentName?) = gone()
                override fun onNullBinding(n: ComponentName?) = gone()
            }
            val intent = Intent(c, RouteProbeService::class.java).putExtra("adbHost", LocalAdb.host).putExtra("adbPort", LocalAdb.port)
            if (debug != null) intent.putExtras(debug)
            if (!c.bindService(intent, conn, Context.BIND_AUTO_CREATE)) return giveUp(c, "the service would not bind")
            runs++
            while (!lost.get() && !doneFile(c).exists() && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(250)
                killIfStuck(c)
            }
            if (!lost.get()) try { c.unbindService(conn) } catch (_: Throwable) { }
            if (doneFile(c).exists()) return
            if (SystemClock.elapsedRealtime() >= deadline) return giveUp(c, "out of time")
            if (runs > MAX_DEATHS) return giveUp(c, "died $runs times")
            SystemClock.sleep(REST_MS)
        }
    }

    /** A stage past its time: killed, and the marker says why, for the next process's report. */
    private fun killIfStuck(c: Context) {
        val m = marker(c)
        if (!m.exists() || System.currentTimeMillis() - m.lastModified() < STAGE_MS) return
        val o = try { JSONObject(m.readText()) } catch (_: Throwable) { return }
        try { m.writeText(o.put("hung", true).toString()) } catch (_: Throwable) { }
        Log.w(TAG, "stage ${o.optString("stage")} hung")
        Process.killProcess(o.optInt("pid"))
    }

    /** The end of a run with no process to end it: what is there stands, and says why it stopped. */
    private fun giveUp(c: Context, why: String) {
        val r = read(c) ?: JSONObject()
        try { save(c, r.put("finished", true).put("gaveUp", why)) } catch (t: Throwable) { Log.w(TAG, "$t") }
    }

    private fun read(c: Context): JSONObject? = try {
        JSONObject(resultFile(c).readText())
    } catch (_: Throwable) {
        null
    }

    private fun save(c: Context, r: JSONObject) {
        val f = resultFile(c)
        val tmp = File(f.path + ".tmp")
        tmp.writeText(r.toString(1))
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    // ------------------------------------------------------------------------------------------ :route's part

    /** All stages not yet in the report. On the service's thread; a new process after a death comes back here. */
    fun run(c: Context, debug: Bundle?) {
        crashIn = debug?.getString("crash")
        hangIn = debug?.getString("hang")
        val r = read(c) ?: fresh(c)
        // (a stage's marker left on the disk: the process before this one died in it)
        val deaths = r.optJSONArray("deaths") ?: org.json.JSONArray().also { r.put("deaths", it) }
        val left = marker(c)
        if (left.exists()) {
            val m = try { JSONObject(left.readText()) } catch (_: Throwable) { JSONObject() }
            val stage = m.optString("stage")
            val hung = m.optBoolean("hung")
            deaths.put(stage)
            holder(r, stage)?.put(stage.substringAfter('/'), JSONObject().put("ok", false).put("died", true).put("hung", hung))
            left.delete()
            Log.w(TAG, "stage $stage killed its process (hung: $hung)")
        }
        val v = Engine()
        try {
            save(c, r)
            if (deaths.length() <= MAX_DEATHS) {
                stage(c, r, "device") { device(c, it) }
                val program = stageFile(c, r, debug?.getString("exe"))
                stage(c, r, "direct") { direct(program, it) }
                stage(c, r, "root") { root(program, it) }
                stage(c, r, "unpack") { unpack(c, it) }
                stage(c, r, "load") { o -> o.put("ms", v.load()); o.put("ok", true) }
                stage(c, r, "actor") { o -> o.put("ms", v.actor(config(c))); o.put("ok", true) }
                if (ok(r, "load") && ok(r, "actor")) for (t in TRIPS) stage(c, r, "trips/${t.id}") { trip(c, v, t, it) }
                stage(c, r, "map") { mapLong(c, it) }
            }
        } catch (t: Throwable) {
            r.put("error", t.toString().take(300))
        }
        r.put("finished", true)
        save(c, r)
        try { work(c).deleteRecursively(); File(c.filesDir, "probe").deleteRecursively() } catch (_: Throwable) { }
        doneFile(c).writeText("")
    }

    private fun fresh(c: Context): JSONObject = JSONObject()
        .put("format", 1)
        .put("version", BuildConfig.VERSION_NAME)
        .put("code", BuildConfig.VERSION_CODE)
        .put("at", System.currentTimeMillis())
        .put("finished", false)
        .put("trips", JSONObject())

    private fun ok(r: JSONObject, key: String) = r.optJSONObject(key)?.optBoolean("ok") == true

    private fun holder(r: JSONObject, stage: String): JSONObject? = if (stage.isEmpty()) null else if (stage.contains('/')) r.optJSONObject(stage.substringBefore('/')) else r

    /** One stage, unless the report has it: the marker first, and nothing it throws goes further. */
    private inline fun stage(c: Context, r: JSONObject, stage: String, work: (JSONObject) -> Unit) {
        val into = holder(r, stage) ?: return
        val key = stage.substringAfter('/')
        if (into.has(key)) return
        marker(c).writeText(JSONObject().put("stage", stage).put("pid", Process.myPid()).toString())
        val o = JSONObject()
        val t0 = SystemClock.elapsedRealtimeNanos()
        try {
            if (BuildConfig.DEBUG && stage == crashIn) Process.sendSignal(Process.myPid(), 11).also { SystemClock.sleep(5000) }
            if (BuildConfig.DEBUG && stage == hangIn) SystemClock.sleep(STAGE_MS * 3)
            work(o)
        } catch (t: Throwable) {
            Log.w(TAG, "$stage: $t")
            o.put("ok", false).put("error", t.toString().take(300))
        }
        o.put("stageMs", (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000)
        into.put(key, o)
        save(c, r)
        marker(c).delete()
    }

    /** What the plans need to know of the unit and were guessing: RAM, space, the kernel's word width. */
    private fun device(c: Context, o: JSONObject) {
        o.put("abis", Build.SUPPORTED_ABIS.joinToString(","))
        o.put("is64bit", Process.is64Bit())
        o.put("osArch", System.getProperty("os.arch"))
        o.put("kernel", System.getProperty("os.version"))
        o.put("memTotalKb", meminfo("MemTotal"))
        o.put("memAvailableKb", meminfo("MemAvailable"))
        o.put("dataFreeMb", StatFs(c.filesDir.path).availableBytes / (1024 * 1024))
        o.put("cores", Runtime.getRuntime().availableProcessors())
        o.put("heapMb", Runtime.getRuntime().maxMemory() / (1024 * 1024))
        o.put("ok", true)
    }

    private fun meminfo(key: String): Long = try {
        File("/proc/meminfo").readLines().first { it.startsWith("$key:") }.split(Regex("\\s+"))[1].toLong()
    } catch (_: Throwable) {
        -1L
    }

    /** The test program in app storage, ready to run. */
    private fun stageFile(c: Context, r: JSONObject, exe: String?): File {
        val f = File(File(c.filesDir, "probe").apply { mkdirs() }, "hello")
        try {
            f.delete()
            (if (exe != null) File(exe).inputStream() else c.assets.open("probe/hello-aarch64")).use { inp -> f.outputStream().use { inp.copyTo(it) } }
            f.setExecutable(true, false)
            f.setReadable(true, false)
        } catch (t: Throwable) {
            r.put("programError", t.toString().take(300))
        }
        return f
    }

    /** Straight from app storage: at targetSdk 29 Android's SELinux policy forbids it (from memory), if it is enforcing. */
    private fun direct(program: File, o: JSONObject) {
        o.put("path", program.path)
        val p = try {
            ProcessBuilder(program.path).redirectErrorStream(true).start()
        } catch (t: Throwable) {
            o.put("ran", false).put("ok", false).put("error", t.message ?: t.toString())
            return
        }
        val out = StringBuffer()
        val reader = Thread({
            try {
                val b = ByteArray(1024)
                p.inputStream.use { inp -> while (out.length < 4096) { val n = inp.read(b); if (n < 0) break; out.append(String(b, 0, n, Charsets.UTF_8)) } }
            } catch (_: Throwable) { }
        }, "aura-route-probe-out").apply { isDaemon = true; start() }
        val ended = p.waitFor(EXE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!ended) p.destroyForcibly()
        reader.join(500)
        o.put("ran", ended).put("timedOut", !ended).put("output", out.toString().trim().take(500))
        if (ended) o.put("exit", p.exitValue())
        o.put("ok", ended && p.exitValue() == 0 && out.contains("ran"))
    }

    /** Through the app's root shell, with a hard end ([LocalAdb]'s timeout is per read). Permission to run comes from root, not from the app's domain. */
    private fun root(program: File, o: JSONObject) {
        o.put("path", program.path)
        var res: LocalAdb.Result? = null
        val cmd = "chmod 755 ${program.path}; id -u; getenforce; uname -a; ${program.path}; echo __exit=\$?"
        val t = Thread({ res = LocalAdb.run(cmd, EXE_TIMEOUT_MS.toInt()) }, "aura-route-probe-adb").apply { isDaemon = true; start() }
        t.join(EXE_TIMEOUT_MS + 2000)
        val r = res
        if (r == null) { o.put("ran", false).put("timedOut", true).put("ok", false); return }
        o.put("shell", r.ok)
        o.put("output", r.output.take(800))
        val exit = Regex("__exit=(\\d+)").find(r.output)?.groupValues?.get(1)?.toIntOrNull()
        o.put("ran", exit != null)
        if (exit != null) o.put("exit", exit)
        o.put("ok", exit == 0 && r.output.contains("probe: static"))
    }

    private fun unpack(c: Context, o: JSONObject) {
        val t0 = SystemClock.elapsedRealtimeNanos()
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        // (out/route-probe.tar.gz under the name .tgz: the build's asset merger unpacks a .gz, and the APK's own deflate then
        // makes the tar 180 KB larger than this file)
        GZIPInputStream(c.assets.open("probe/route-probe.tgz")).use { inp ->
            FileOutputStream(tar(c)).use { out ->
                val b = ByteArray(64 * 1024)
                while (true) {
                    val n = inp.read(b)
                    if (n < 0) break
                    digest.update(b, 0, n)
                    out.write(b, 0, n)
                    bytes += n
                }
            }
        }
        val sha = digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
        o.put("ms", (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000).put("bytes", bytes).put("sha256ok", sha == TAR_SHA256).put("ok", sha == TAR_SHA256)
    }

    private fun tar(c: Context) = File(work(c), "route-probe.tar")

    private fun config(c: Context): File {
        val text = c.assets.open("route/device-config.json").use { it.readBytes().toString(Charsets.UTF_8) }
        // (as text: the tool's file is the truth, and org.json would rewrite its numbers)
        val f = File(work(c), "config.json")
        f.writeText(text.replace(Regex("\"tile_extract\"\\s*:\\s*\"[^\"]*\""), Regex.escapeReplacement("\"tile_extract\": ${JSONObject.quote(tar(c).path)}")))
        return f
    }

    /** The library, once in this process (a new process after a death loads it again, under the stage that needs it). */
    private class Engine {
        private var v: ValhallaKotlin? = null
        private var handle = 0L

        fun load(): Long {
            val t0 = SystemClock.elapsedRealtimeNanos()
            ValhallaKotlin.load()
            v = ValhallaKotlin()
            return (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000
        }

        fun actor(config: File): Long {
            if (v == null) load()
            val t0 = SystemClock.elapsedRealtimeNanos()
            handle = v!!.createActor(config.path, null)
            if (handle == 0L) throw IllegalStateException("no actor")
            return (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000
        }

        fun route(c: Context, request: ByteArray): ByteArray {
            if (handle == 0L) actor(config(c))
            return v!!.route(handle, request)
        }
    }

    private fun trip(c: Context, v: Engine, t: Trip, o: JSONObject) {
        val req = String.format(Locale.ROOT, REQUEST, t.a, t.b, t.c, t.d).toByteArray()
        var t0 = SystemClock.elapsedRealtimeNanos()
        val first = v.route(c, req)
        o.put("ms", (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000)
        t0 = SystemClock.elapsedRealtimeNanos()
        v.route(c, req)
        o.put("msAgain", (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000)
        o.put("bytes", first.size)
        // (how long the JSON takes here says what the 2.35 MB answer will: it is parsed in this process)
        t0 = SystemClock.elapsedRealtimeNanos()
        val a = JSONObject(String(first, Charsets.UTF_8))
        o.put("parseMs", (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000)
        o.put("code", a.opt("code") ?: JSONObject.NULL)
        val routes = a.optJSONArray("routes")
        if (routes != null && routes.length() > 0) {
            val r0 = routes.getJSONObject(0)
            val km = r0.optDouble("distance", 0.0) / 1000
            o.put("routes", routes.length()).put("km", Math.round(km * 100) / 100.0).put("refKm", t.refKm)
            o.put("minutes", Math.round(r0.optDouble("duration", 0.0) / 6) / 10.0)
            o.put("offPct", Math.round((km - t.refKm) / t.refKm * 1000) / 10.0)
            o.put("ok", Math.abs(km - t.refKm) / t.refKm < 0.01)
        } else {
            o.put("ok", false).put("message", a.optString("message").take(200))
        }
        memory(o)
    }

    private fun memory(o: JSONObject) {
        try {
            for (l in File("/proc/self/status").readLines()) {
                if (l.startsWith("VmPeak:")) o.put("vmPeakKb", l.split(Regex("\\s+"))[1].toLong())
                if (l.startsWith("VmHWM:")) o.put("vmHwmKb", l.split(Regex("\\s+"))[1].toLong())
            }
            o.put("pssKb", Debug.getPss())
        } catch (t: Throwable) {
            o.put("memoryError", t.toString())
        }
    }

    /** A sparse file mapped whole: nothing is read, no disk taken. The mapping stays until the process ends, so last. */
    private fun mapLong(c: Context, o: JSONObject) {
        val f = File(work(c), "sparse")
        val t0 = SystemClock.elapsedRealtimeNanos()
        try {
            RandomAccessFile(f, "rw").use { raf ->
                raf.setLength(MAP_BYTES)
                raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, MAP_BYTES)
            }
            o.put("ok", true)
        } finally {
            f.delete()
        }
        o.put("bytes", MAP_BYTES).put("ms", (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000)
        memory(o)
    }

    /** tools/mapbuild/route-request.json. */
    private const val REQUEST = "{\"locations\":[{\"lat\":%.5f,\"lon\":%.5f,\"type\":\"break\"},{\"lat\":%.5f,\"lon\":%.5f,\"type\":\"break\"}],\"costing\":\"auto\"," +
        "\"costing_options\":{\"auto\":{\"top_speed\":160,\"use_distance\":0.5,\"use_tolls\":0.5,\"use_tracks\":0.0}},\"format\":\"osrm\",\"shape_format\":\"geojson\"," +
        "\"alternates\":2,\"units\":\"kilometers\",\"language\":\"en-US\",\"turn_lanes\":true}"
}

/** [RouteProbe]'s work, in :route. Bound by the probe's watcher only: its binder says nothing, its death says enough. */
class RouteProbeService : Service() {
    private val binder = Binder()
    private val started = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        RouteProcess.enter()
    }

    override fun onBind(i: Intent): IBinder {
        // (a debug build's root shell stand-in is set in the main process's start, which this process skips)
        i.getStringExtra("adbHost")?.let { LocalAdb.host = it }
        LocalAdb.port = i.getIntExtra("adbPort", LocalAdb.port)
        val debug = i.extras
        if (started.compareAndSet(false, true)) {
            Thread(null, {
                try {
                    RouteProbe.run(applicationContext, debug)
                } catch (t: Throwable) {
                    Log.w("AuraRouteProbe", "$t")
                }
            }, "aura-route-probe", RouteProcess.STACK_BYTES).start()
        }
        return binder
    }

    override fun onDestroy() {
        RouteProcess.leave()
        super.onDestroy()
    }
}
