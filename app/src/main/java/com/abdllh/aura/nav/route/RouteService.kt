package com.abdllh.aura.nav.route

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Debug
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.abdllh.aura.BuildConfig
import com.abdllh.aura.nav.LatLon
import com.abdllh.aura.nav.Polyline
import com.abdllh.aura.nav.Route
import com.abdllh.aura.nav.RouteLimits
import com.abdllh.aura.nav.Router
import com.valhalla.valhalla.ValhallaKotlin
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Valhalla's engine, in :route ([RouteProcess]). Bound by [RouteClient] alone. One request at a time on one thread: the library's
 * actor is not shared, and the native call cannot be stopped. A request that comes while one is worked on waits, and only the
 * newest does (the one waiting before it gets its pipe closed: nobody is listening any more).
 *
 * The answer goes through the pipe that came with the request: [RouteCodec], then the speed limits along the first route, late,
 * since a route must not wait for them. Never through Binder: only the request itself goes that way, one-way, so nothing here can
 * ever hold up the main process.
 */
class RouteService : Service() {
    internal class Ask(val json: String, val arabic: Boolean, val tar: String, val delayMs: Long, val pipe: ParcelFileDescriptor)

    private val lock = Object()
    private var waiting: Ask? = null
    private var worker: Thread? = null

    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != ASK) return super.onTransact(code, data, reply, flags)
            val ask = Ask(data.readString().orEmpty(), data.readInt() != 0, data.readString().orEmpty(), data.readLong(), data.readFileDescriptor() ?: return false)
            synchronized(lock) {
                try { waiting?.pipe?.close() } catch (_: Throwable) { }
                waiting = ask
                lock.notifyAll()
            }
            return true
        }
    }

    override fun onCreate() {
        super.onCreate()
        RouteProcess.enter()
    }

    override fun onBind(i: Intent): IBinder {
        synchronized(lock) {
            if (worker == null) worker = Thread(null, { work() }, "aura-route", RouteProcess.STACK_BYTES).apply { start() }
        }
        return binder
    }

    override fun onDestroy() {
        RouteProcess.leave()
        super.onDestroy()
    }

    private fun work() {
        val engine = RouteEngine(applicationContext)
        while (true) {
            val ask = synchronized(lock) {
                while (waiting == null) lock.wait()
                waiting!!.also { waiting = null }
            }
            RouteGuard.working(applicationContext, "request")
            try {
                engine.serve(ask) { synchronized(lock) { waiting != null } }
            } catch (t: Throwable) {
                // (nothing that can be caught ends this thread: the next request is a new try)
                Log.w(TAG, "$t")
            } finally {
                try { ask.pipe.close() } catch (_: Throwable) { }
                RouteGuard.notWorking(applicationContext)
            }
        }
    }

    internal companion object {
        const val ASK = 1
        const val TAG = "AuraRouteService"
        const val WALK_SPLITS = 7
    }
}

/** The library and its actor, in :route. On the service's thread. */
internal class RouteEngine(private val c: Context) {
    private var lib: ValhallaKotlin? = null
    private var handle = 0L
    private var tarKey = ""
    private var checked = ""
    private var loadMs = 0L
    private var actorMs = 0L

    /** Answers on the pipe. [newer]: a newer request waits, so the limits, which only this request's route wants, are left out. */
    fun serve(ask: RouteService.Ask, newer: () -> Boolean) {
        val out = ParcelFileDescriptor.AutoCloseOutputStream(ask.pipe)
        if (BuildConfig.DEBUG && ask.delayMs > 0) SystemClock.sleep(ask.delayMs)
        val answer = try {
            answer(ask)
        } catch (t: Throwable) {
            // (OutOfMemoryError as well: a Java heap too small for a long answer is a failed request, not a crash)
            Log.w(RouteService.TAG, "$t")
            RouteAnswer(RouteAnswer.FAILED, t.toString().take(200))
        }
        try {
            RouteCodec.write(out, answer)
            answer.route?.let { limits(it, out, newer) }
        } catch (e: IOException) {
            Log.i(RouteService.TAG, "nobody listens any more: ${e.message}") // a newer request, or the timeout
        }
    }

    private fun answer(ask: RouteService.Ask): RouteAnswer {
        val tar = File(ask.tar)
        val key = "${tar.path} ${tar.length()} ${tar.lastModified()}"
        if (key != checked) {
            TarCheck.problem(tar)?.let { return RouteAnswer(RouteAnswer.BAD_PACK, it) }
            checked = key
        }
        ready(tar, key)
        val request = ask.json.toByteArray(Charsets.UTF_8)
        var t0 = SystemClock.elapsedRealtime()
        val bytes = lib!!.route(handle, request)
        val routeMs = SystemClock.elapsedRealtime() - t0
        t0 = SystemClock.elapsedRealtime()
        val read = ValhallaAnswer.read(String(bytes, Charsets.UTF_8), ask.arabic)
        return read.with(stats(routeMs, SystemClock.elapsedRealtime() - t0, bytes.size.toLong()))
    }

    private fun ready(tar: File, key: String) {
        if (handle != 0L && key == tarKey) return
        var t0 = SystemClock.elapsedRealtime()
        if (lib == null) {
            ValhallaKotlin.load()
            lib = ValhallaKotlin()
            loadMs = SystemClock.elapsedRealtime() - t0
        }
        if (handle != 0L) { lib!!.deleteActor(handle); handle = 0L }
        t0 = SystemClock.elapsedRealtime()
        handle = lib!!.createActor(config(tar).path, null)
        actorMs = SystemClock.elapsedRealtime() - t0
        if (handle == 0L) throw IllegalStateException("no actor")
        tarKey = key
    }

    /** tools/mapbuild/device-config.json with this tar. As text: the tool's file is the truth, and org.json would rewrite its numbers. */
    private fun config(tar: File): File {
        val text = c.assets.open("route/device-config.json").use { it.readBytes().toString(Charsets.UTF_8) }
        val f = File(File(c.cacheDir, "route").apply { mkdirs() }, "config.json")
        f.writeText(text.replace(Regex("\"tile_extract\"\\s*:\\s*\"[^\"]*\""), Regex.escapeReplacement("\"tile_extract\": ${JSONObject.quote(tar.path)}")))
        return f
    }

    private fun stats(routeMs: Long, parseMs: Long, bytes: Long): RouteAnswer.Stats {
        var peak = 0L
        var hwm = 0L
        try {
            for (l in File("/proc/self/status").readLines()) {
                if (l.startsWith("VmPeak:")) peak = l.split(Regex("\\s+"))[1].toLong()
                if (l.startsWith("VmHWM:")) hwm = l.split(Regex("\\s+"))[1].toLong()
            }
        } catch (_: Throwable) { }
        val pss = if (BuildConfig.DEBUG) Debug.getPss() else 0L
        return RouteAnswer.Stats(routeMs, parseMs, bytes, loadMs, actorMs, peak, hwm, pss, Process.myPid().toLong())
    }

    /**
     * What the road's limit is along [r], from the library's own map (trace_attributes on the route's shape, which is cheap:
     * 18 ms for 84 km on the PC), for the same sign BRouter's `messages` fed ([RouteLimits]). In pieces, for the limits of the
     * request (device-config.json: trace, 200 km, 16,000 points). Never a reason to lose the route: what fails is left out.
     */
    private fun limits(r: Route, out: OutputStream, newer: () -> Boolean) {
        val rows = ArrayList<RouteLimits.Row>()
        try {
            val line = r.line
            var from = 0
            while (from < line.points.size - 1) {
                if (newer()) return
                val to = sliceEnd(line, from)
                walk(line, from, to, rows, RouteService.WALK_SPLITS)
                from = to
            }
        } catch (t: Throwable) {
            Log.w(RouteService.TAG, "limits: $t")
        }
        RouteCodec.writeLimits(out, RouteTrace.merged(rows))
    }

    /**
     * One stretch. The walk needs the road's exact shape, and a point in it can stop that (the line of a route with via points
     * failed at the via: code 443): then each half on its own, so one such point costs only a little of the line. A stretch it
     * cannot walk at all has no limit: the next row must not lend it its own.
     */
    private fun walk(line: Polyline, from: Int, to: Int, rows: MutableList<RouteLimits.Row>, splits: Int) {
        val text = String(lib!!.traceAttributes(handle, RouteTrace.request(line.points.subList(from, to + 1)).toByteArray()), Charsets.UTF_8)
        if (RouteTrace.rows(text, rows)) return
        if (splits > 0 && to - from >= 4) {
            val mid = (from + to) / 2
            walk(line, from, mid, rows, splits - 1)
            walk(line, mid, to, rows, splits - 1)
        } else {
            Log.i(RouteService.TAG, "limits: none for points $from to $to: ${text.take(100)}")
            rows.add(RouteLimits.Row(line.points[to], ""))
        }
    }

    private fun sliceEnd(line: Polyline, from: Int): Int {
        var i = from + 1
        val last = line.points.size - 1
        while (i < last && i - from < RouteTrace.SLICE_POINTS && line.cumulative[i + 1] - line.cumulative[from] < RouteTrace.SLICE_METRES) i++
        return i
    }
}

/** Valhalla's answer into [RouteAnswer], with Router's own parsing. */
internal object ValhallaAnswer {
    fun read(body: String, arabic: Boolean): RouteAnswer {
        val o = JSONObject(body)
        val code = o.opt("code")
        if (code != "Ok") return RouteAnswer(status(code), o.optString("message").ifBlank { code.toString() }.take(200))
        o.optJSONArray("warnings")?.let { Log.i(RouteService.TAG, "warnings: ${it.toString().take(200)}") } // (401: a second search; the routes were found right in the tests)
        val tune = StepTune(arabic)
        val routes = o.getJSONArray("routes")
        val all = ArrayList<Route>()
        val raw = ArrayList<Double>()
        for (i in 0 until routes.length()) {
            val r = routes.getJSONObject(i)
            Router.parseRoute(r, tune)?.let { all.add(it); raw.add(r.optDouble("duration", 0.0)) }
        }
        if (all.isEmpty()) return RouteAnswer(RouteAnswer.FAILED, "no route in the answer")
        val order = RouteOrder.of(DoubleArray(all.size) { all[it].distance }, DoubleArray(all.size) { all[it].duration })
        val w = o.optJSONArray("waypoints")
        val off = w?.optJSONObject(w.length() - 1)?.optDouble("distance", -1.0) ?: -1.0
        return RouteAnswer(RouteAnswer.OK, "", order.map { all[it] }, DoubleArray(all.size) { raw[order[it]] }, if (off.isNaN()) -1.0 else off)
    }

    /** Through JNI the code is a number ({"code":171,...}: measured); the HTTP service has a word. Both are understood. */
    private fun status(code: Any?): Int = when (code) {
        170, 171, "NoSegment" -> RouteAnswer.NO_ROAD
        442, 443, "NoRoute" -> RouteAnswer.NO_ROUTE
        else -> RouteAnswer.FAILED
    }
}

private fun RouteAnswer.with(s: RouteAnswer.Stats) = RouteAnswer(status, message, routes, rawSeconds, endOffRoad, s)

/** The request and answer of trace_attributes for the limits. */
internal object RouteTrace {
    const val SLICE_POINTS = 15_000
    const val SLICE_METRES = 190_000.0

    fun request(points: List<LatLon>): String = buildString {
        append("{\"shape\":[")
        for ((i, p) in points.withIndex()) {
            if (i > 0) append(',')
            append("{\"lat\":").append(p.lat).append(",\"lon\":").append(p.lon).append('}')
        }
        append("],\"costing\":\"auto\",\"shape_match\":\"edge_walk\",\"filters\":{\"attributes\":[\"edge.speed_limit\",\"edge.end_shape_index\",\"shape\"],\"action\":\"include\"}}")
    }

    /** One row for each edge: where it ends on the answer's own shape, and its limit (key absent when unknown; "unlimited" reads as none). */
    fun rows(body: String, into: MutableList<RouteLimits.Row>): Boolean {
        val o = JSONObject(body)
        val edges = o.optJSONArray("edges") ?: return false
        val shape = decode(o.optString("shape"))
        for (i in 0 until edges.length()) {
            val e = edges.getJSONObject(i)
            val end = shape.getOrNull(e.optInt("end_shape_index", -1)) ?: continue
            val kmh = e.optInt("speed_limit", 0)
            into.add(RouteLimits.Row(end, if (kmh > 0) "maxspeed=$kmh" else ""))
        }
        return true
    }

    /** Runs of one limit as one row: the frame stays small, and [RouteLimits.of] walks the line only once per row. */
    fun merged(rows: List<RouteLimits.Row>): List<RouteLimits.Row> {
        val out = ArrayList<RouteLimits.Row>()
        for ((i, r) in rows.withIndex()) if (i == rows.size - 1 || rows[i + 1].tags != r.tags) out.add(r)
        return out
    }

    /** Google's polyline format with 6 digits, which Valhalla uses. */
    fun decode(s: String): List<LatLon> {
        val out = ArrayList<LatLon>(s.length / 4)
        var i = 0
        var lat = 0
        var lon = 0
        fun next(): Int {
            var shift = 0
            var v = 0
            while (true) {
                val b = s[i++].code - 63
                v = v or ((b and 0x1f) shl shift)
                shift += 5
                if (b < 0x20) break
            }
            return if (v and 1 != 0) (v ushr 1).inv() else v ushr 1
        }
        while (i < s.length) {
            lat += next()
            lon += next()
            out.add(LatLon(lat / 1e6, lon / 1e6))
        }
        return out
    }
}

/**
 * Is this the whole tar? The library maps the file and trusts its index (scan_tar false): a tile past the end of a file cut short
 * would be a SIGBUS in the middle of a search. Reads only the index, the file's first entry: 44 KB for the whole Kingdom. The pack
 * manager's hash is the real check; this is the cheap one, once for each file.
 */
internal object TarCheck {
    /** Null when fine, else what is wrong. */
    fun problem(f: File): String? {
        if (!f.isFile) return "no file: ${f.path}"
        return try {
            RandomAccessFile(f, "r").use { raf ->
                val length = raf.length()
                if (length < 1024) return "too short: $length bytes"
                val head = ByteArray(512)
                raf.readFully(head)
                val name = String(head, 0, 100, Charsets.US_ASCII).substringBefore('\u0000')
                if (name != "index.bin") return "the tar does not start with its index: '${name.take(40)}'"
                val size = String(head, 124, 12, Charsets.US_ASCII).trim('\u0000', ' ').toLongOrNull(8) ?: return "bad header"
                if (size <= 0 || size % 16 != 0L || size > length - 512 || size > 16 * 1024 * 1024) return "bad index size: $size"
                val index = ByteArray(size.toInt())
                raf.readFully(index)
                val b = ByteBuffer.wrap(index).order(ByteOrder.LITTLE_ENDIAN)
                var end = 0L
                while (b.remaining() >= 16) {
                    // offset (u64), tile id (u32), size (u32), as in Valhalla's tar index
                    val offset = b.long
                    b.int
                    end = maxOf(end, offset + (b.int.toLong() and 0xffffffffL))
                }
                if (end > length) "cut short: $length bytes, its index needs $end" else null
            }
        } catch (t: Throwable) {
            "unreadable: $t"
        }
    }
}
