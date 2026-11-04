package com.abdllh.aura.nav.places

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.util.JsonWriter
import android.util.Log
import com.abdllh.aura.nav.LatLon
import com.abdllh.aura.nav.Places
import com.abdllh.aura.nav.Polyline
import com.abdllh.aura.util.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

/**
 * Debug builds only, the emulator's way into the search over places.db (the broadcast's name: ACTION; scripts/search-compare.ps1 drives it):
 *   --es db path|default       another file than the pack's, from now on
 *   --ez swap true|false       the preference that puts today's search box over this engine
 *   --ez status true           what is known about the file, in the log (tag AuraPlacesDebug)
 *   --es job in.json,out.json  searches, reverses, words, routes of a JSON file, answers to another, on the engine's thread
 *   --es pss in.json,out.json  the searches of a job with the PSS before and after, answers thrown away
 *   --es typing in.json,out.json   queries fed letter by letter through PlaceSearch.search at given speeds
 *   --es q text [--es near lat,lon]  one search, in the log; with --ez box true through Places.search, as the search box does
 *   --es rev lat,lon           one reverse, in the log
 * The log's last line of a job is "job done"; its answers are ready then.
 */
internal object PlacesDebug {
    private const val TAG = "AuraPlacesDebug"
    private const val ACTION = "com.abdllh.aura.debug.PLACES"
    private val main = Handler(Looper.getMainLooper())

    fun init(ctx: Context) {
        val app = ctx.applicationContext
        try {
            app.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    try { handle(i) } catch (t: Throwable) { Log.w(TAG, "$t") }
                }
            }, IntentFilter(ACTION))
        } catch (t: Throwable) {
            Log.w(TAG, "$t")
        }
    }

    private fun near(s: String?): LatLon? = s?.split(',')?.takeIf { it.size == 2 }?.let { LatLon(it[0].trim().toDouble(), it[1].trim().toDouble()) }
    private fun near(a: JSONArray?): LatLon? = a?.let { LatLon(it.getDouble(0), it.getDouble(1)) }

    private fun handle(i: Intent) {
        i.getStringExtra("db")?.let { p ->
            PlaceSearch.testFile = if (p == "default") null else File(p)
            PlaceSearch.closeForTest()
            Log.i(TAG, "file: ${PlaceSearch.testFile}")
        }
        if (i.hasExtra("swap")) {
            Prefs.raw.edit().putBoolean(PlaceSearch.PREF_SWAP, i.getBooleanExtra("swap", false)).apply()
            Log.i(TAG, "swap ${PlaceSearch.swapOn}")
        }
        if (i.getBooleanExtra("status", false)) PlaceSearch.check { s -> Log.i(TAG, "status ${s.state}: ${s.why}") }
        i.getStringExtra("job")?.let { s -> val (a, b) = s.split(','); job(File(a), File(b)) }
        i.getStringExtra("pss")?.let { s -> val (a, b) = s.split(','); pss(File(a), File(b)) }
        i.getStringExtra("typing")?.let { s -> val (a, b) = s.split(','); typing(File(a), File(b)) }
        i.getStringExtra("q")?.let { q ->
            val at = near(i.getStringExtra("near"))
            if (i.getBooleanExtra("box", false)) {
                val t0 = System.nanoTime()
                Places.search(q, at, true) { list ->
                    Log.i(TAG, "box '$q': ${list?.size} in ${(System.nanoTime() - t0) / 1000000} ms, swap ${PlaceSearch.swapOn}, status ${PlaceSearch.status.state}: ${PlaceSearch.status.why}")
                    list?.take(5)?.forEach { Log.i(TAG, "  ${it.name} | ${it.detail} | ${it.pos.lat},${it.pos.lon}") }
                }
            } else {
                val t0 = System.nanoTime()
                PlaceSearch.search(q, at) { a ->
                    Log.i(TAG, "search '$q': ${a.hits.size} in ${(System.nanoTime() - t0) / 1000000} ms, ${a.status.state}: ${a.status.why}")
                    for (h in a.hits) Log.i(TAG, "  ${h.id} k${h.kind} ${h.score} ${h.km} km ${h.shown(true)} | ${h.detail(true)} | approx=${h.approx}")
                }
            }
        }
        near(i.getStringExtra("rev"))?.let { p ->
            PlaceSearch.reverse(p.lat, p.lon) { r -> Log.i(TAG, "reverse $p: ${r?.what} ${r?.metres} m ${r?.shown(true)}, ${PlaceSearch.status.state}") }
        }
    }

    private fun writer(f: File): JsonWriter {
        f.parentFile?.mkdirs()
        return JsonWriter(OutputStreamWriter(FileOutputStream(f).buffered(1 shl 16), Charsets.UTF_8))
    }

    private fun doubles(w: JsonWriter, name: String, list: List<Double?>) {
        w.name(name).beginArray()
        for (x in list) if (x == null) w.nullValue() else w.value(x)
        w.endArray()
    }

    private fun strings(w: JsonWriter, name: String, list: List<String?>) {
        w.name(name).beginArray()
        for (x in list) if (x == null) w.nullValue() else w.value(x)
        w.endArray()
    }

    private fun job(input: File, out: File) {
        PlaceSearch.onEngine { e ->
            try {
                val j = JSONObject(input.readText())
                writer(out).use { w ->
                    w.beginObject()
                    w.name("status").value(PlaceSearch.status.state.name).name("why").value(PlaceSearch.status.why)
                    if (e != null) {
                        j.optJSONArray("searches")?.let { searches(e, it, w) }
                        j.optJSONArray("reverse")?.let { reverses(e, it, w) }
                        j.optJSONArray("routes")?.let { routes(e, it, w) }
                        j.optJSONArray("points")?.let { points(e, it, w) }
                        if (j.optBoolean("haram")) haram(e, w)
                    }
                    if (j.optBoolean("norm_bmp")) normBmp(w)
                    j.optJSONArray("words")?.let { words(it, w) }
                    w.endObject()
                }
                Log.i(TAG, "job done ${input.name}")
            } catch (t: Throwable) {
                Log.w(TAG, "job: $t")
                Log.i(TAG, "job done ${input.name} with an error")
            }
        }
    }

    private fun searches(e: PlaceEngine, list: JSONArray, w: JsonWriter) {
        w.name("searches").beginArray()
        for (k in 0 until list.length()) {
            val o = list.getJSONObject(k)
            val stats = SearchStats()
            w.beginObject()
            val t0 = System.nanoTime()
            try {
                val hits = e.search(o.getString("q"), near(o.optJSONArray("near")), o.optInt("limit", 12), null, stats)
                val ns = System.nanoTime() - t0
                w.name("total").value(ns)
                w.name("ids").beginArray()
                for (h in hits) w.value(h.id)
                w.endArray()
                doubles(w, "scores", hits.map { it.score })
                doubles(w, "km", hits.map { it.km })
                strings(w, "shown", hits.map { it.shown(true) })
                strings(w, "detail", hits.map { it.detail(true) })
                strings(w, "shown_en", hits.map { it.shown(false) })
                w.name("approx").beginArray()
                for (h in hits) w.value(h.approx)
                w.endArray()
                strings(w, "match", stats.matches)
                w.name("cand").value(stats.candidates)
                w.name("ns").beginArray()
                for (x in stats.ns) w.value(x)
                w.endArray()
            } catch (t: Throwable) {
                w.name("error").value("$t")
            }
            w.endObject()
        }
        w.endArray()
    }

    private fun reverses(e: PlaceEngine, list: JSONArray, w: JsonWriter) {
        w.name("reverse").beginArray()
        for (k in 0 until list.length()) {
            val a = list.getJSONArray(k)
            val t0 = System.nanoTime()
            val r = try { e.reverse(a.getDouble(0), a.getDouble(1)) } catch (t: Throwable) { w.value("$t"); continue }
            val ns = System.nanoTime() - t0
            if (r == null) {
                w.nullValue()
                continue
            }
            w.beginObject()
            w.name("ns").value(ns)
            w.name("what").value(r.what.name.lowercase())
            w.name("m").value(r.metres)
            if (r.id != null) w.name("id").value(r.id)
            if (r.what != ReverseHit.What.AREA) w.name("name").value(r.name).name("alt").value(r.alt)
            if (r.areaId != null) w.name("area").value(r.areaId).name("ar").value(r.districtAr).name("en").value(r.districtEn).name("city_ar").value(r.townAr).name("city_en").value(r.townEn)
            w.endObject()
        }
        w.endArray()
    }

    private fun routes(e: PlaceEngine, list: JSONArray, w: JsonWriter) {
        w.name("routes").beginArray()
        for (k in 0 until list.length()) {
            val o = list.getJSONObject(k)
            val c = o.getJSONArray("coords")
            val line = Polyline(List(c.length()) { n -> c.getJSONArray(n).let { LatLon(it.getDouble(1), it.getDouble(0)) } })
            val classes = o.optJSONArray("classes")?.let { a -> IntArray(a.length()) { a.getInt(it) } }
            val roadClass: ((Int) -> Int)? = classes?.let { cl -> { seg: Int -> cl.getOrElse(seg) { -1 } } }
            w.beginObject()
            w.name("id").value(o.getString("id"))
            w.name("metres").value(line.length)
            var t0 = System.nanoTime()
            val spots = e.spotsAlong(line)
            w.name("spots_ns").value(System.nanoTime() - t0)
            w.name("spots").beginArray()
            for (s in spots.hits) {
                w.beginObject().name("kind").value(s.kind).name("lat").value(s.lat).name("lon").value(s.lon).name("along").value(s.along).name("off").value(s.offset)
                if (s.dir != null) w.name("dir").value(s.dir)
                if (s.junction != null) w.name("j").value(s.junction)
                if (s.cameraKmh != null) w.name("camera_kmh").value(s.cameraKmh)
                if (s.roadKmh != null) w.name("road_kmh").value(s.roadKmh)
                w.endObject()
            }
            w.endArray()
            t0 = System.nanoTime()
            val warn = e.warnings(line, roadClass)
            w.name("warn_ns").value(System.nanoTime() - t0)
            w.name("gates").beginArray()
            for (g in warn.gates) w.beginObject().name("lat").value(g.lat).name("lon").value(g.lon).name("sub").value(g.sub).name("road").value(g.road).name("along").value(g.along).name("warn").value(g.warn).endObject()
            w.endArray()
            w.name("zones").beginArray()
            for (z in warn.zones) {
                w.beginObject().name("id").value(z.id).name("kind").value(z.kind).name("name").value(z.name ?: z.en).name("enter").value(z.enterAlong).name("last").value(z.lastAlong).name("inside_m").value(z.insideMetres)
                    .name("starts").value(z.startsInside).name("ends").value(z.endsInside).name("entered").value(z.entered).name("off_main_m").value(z.offMainMetres).name("warn").value(z.warn).endObject()
            }
            w.endArray()
            for (key in arrayOf("fuel", "mosque")) {
                t0 = System.nanoTime()
                val ahead = e.nearestAhead(line, 0.0, key, 3)
                w.name(key + "_ns").value(System.nanoTime() - t0)
                w.name(key).beginArray()
                for (a in ahead) w.beginObject().name("id").value(a.place.id).name("name").value(a.place.shown(true)).name("along").value(a.along).name("off").value(a.offset).name("right").value(a.onRight).endObject()
                w.endArray()
            }
            w.endObject()
        }
        w.endArray()
    }

    private fun points(e: PlaceEngine, list: JSONArray, w: JsonWriter) {
        w.name("points").beginArray()
        for (k in 0 until list.length()) {
            val a = list.getJSONArray(k)
            val t0 = System.nanoTime()
            val got = e.nearestTo(a.getDouble(0), a.getDouble(1), "parking")
            val ns = System.nanoTime() - t0
            w.beginObject().name("ns").value(ns).name("hits").beginArray()
            for (h in got) w.beginObject().name("id").value(h.id).name("name").value(h.shown(true)).name("km").value(h.km ?: -1.0).endObject()
            w.endArray().endObject()
        }
        w.endArray()
    }

    private fun haram(e: PlaceEngine, w: JsonWriter) {
        w.name("haram").beginArray()
        for (kind in 1..2) {
            val r = e.haramRing(kind)
            if (r == null) w.nullValue()
            else w.beginObject().name("id").value(r.id).name("kind").value(r.kind).name("name").value(r.name).name("points").value(r.points)
                .name("json").value(r.json).name("grand_mosque_inside").value(r.contains(21.4225, 39.8262)).name("riyadh_inside").value(r.contains(24.7, 46.7)).endObject()
        }
        w.endArray()
    }

    /** norm() of every character of the BMP between two letters. */
    private fun normBmp(w: JsonWriter) {
        val t0 = System.nanoTime()
        w.name("norm_bmp").beginArray()
        for (c in 0..0xFFFF) {
            if (c in 0xD800..0xDFFF) w.value("") else w.value(Norm.norm("x" + c.toChar() + "x"))
        }
        w.endArray()
        w.name("norm_bmp_ms").value((System.nanoTime() - t0) / 1000000)
    }

    /** Every text function of each text. */
    private fun words(list: JSONArray, w: JsonWriter) {
        w.name("words").beginArray()
        for (k in 0 until list.length()) {
            val t = list.getString(k)
            w.beginObject().name("norm").value(Norm.norm(t))
            val ws = Norm.queryWords(t)
            strings(w, "words", ws)
            w.name("sks").beginArray()
            for (x in ws) {
                w.beginArray()
                for (s in Norm.skeletons(x)) w.value(s)
                w.endArray()
            }
            w.endArray()
            strings(w, "latin", ws.map { Norm.latinBase(it) })
            w.name("edits").beginArray()
            for (x in ws) {
                w.beginArray()
                for (s in Norm.oneEditForms(x)) w.value(s)
                w.endArray()
            }
            w.endArray()
            w.endObject()
        }
        w.endArray()
    }

    private fun memory(w: JsonWriter, name: String) {
        System.gc()
        System.runFinalization()
        System.gc()
        val m = Debug.MemoryInfo()
        Debug.getMemoryInfo(m)
        w.name(name).beginObject().name("pss_kb").value(m.totalPss).name("java_kb").value(m.dalvikPss).name("native_kb").value(m.nativePss).name("other_kb").value(m.otherPss).endObject()
    }

    /** The memory it adds. The answers are not kept, or the test would measure them. */
    private fun pss(input: File, out: File) {
        val j = JSONObject(input.readText())
        val list = j.getJSONArray("searches")
        val queries = List(list.length()) { list.getJSONObject(it).getString("q") to near(list.getJSONObject(it).optJSONArray("near")) }
        PlaceSearch.closeForTest()
        Thread({
            try {
                writer(out).use { w ->
                    w.beginObject()
                    Thread.sleep(300)
                    memory(w, "closed")
                    val lock = Object()
                    fun onEngine(block: (PlaceEngine?) -> Unit) {
                        var done = false
                        PlaceSearch.onEngine { e ->
                            try { block(e) } catch (t: Throwable) { Log.w(TAG, "pss: $t") }
                            synchronized(lock) { done = true; lock.notifyAll() }
                        }
                        synchronized(lock) { while (!done) lock.wait(60000) }
                    }
                    onEngine { }
                    memory(w, "open")
                    onEngine { e -> if (e != null) for (q in queries) e.search(q.first, q.second) }
                    memory(w, "after_searches")
                    onEngine { e -> if (e != null) for (q in queries) e.search(q.first, q.second) }
                    memory(w, "after_searches_2")
                    PlaceSearch.closeForTest()
                    Thread.sleep(500)
                    memory(w, "closed_again")
                    w.endObject()
                }
                Log.i(TAG, "job done ${input.name} pss")
            } catch (t: Throwable) {
                Log.w(TAG, "pss: $t")
                Log.i(TAG, "job done ${input.name} with an error")
            }
        }, "aura-places-pss").start()
    }

    /**
     * Typing: each query's letters through the real entry at a speed; a newer letter stops the search under way. For each: which letters answered, how long after the last letter the answer
     * came, whether it is what the whole query gives by itself, and what became of the searches begun.
     */
    private fun typing(input: File, out: File) {
        val j = JSONObject(input.readText())
        val list = j.getJSONArray("queries")
        val speeds = j.getJSONArray("cadence_ms")
        Thread({
            try {
                writer(out).use { w ->
                    w.beginObject().name("runs").beginArray()
                    for (s in 0 until speeds.length()) {
                        val cadence = speeds.getLong(s)
                        for (k in 0 until list.length()) {
                            val o = list.getJSONObject(k)
                            val q = o.getString("q")
                            val at = near(o.optJSONArray("near"))
                            var whole: List<Int>? = null
                            val lock = Object()
                            PlaceSearch.onEngine { e ->
                                val r = try { e?.search(q, at)?.map { it.id } } catch (t: Throwable) { null }
                                synchronized(lock) { whole = r.orEmpty(); lock.notifyAll() }
                            }
                            synchronized(lock) { while (whole == null) lock.wait(60000) }
                            val before = PlaceSearch.counters.clone()
                            val answered = java.util.Collections.synchronizedList(ArrayList<Pair<String, List<Int>>>())
                            var lastAt = 0L
                            var finalAt = 0L
                            for (n in 1..q.length) {
                                val prefix = q.substring(0, n)
                                val t = System.nanoTime()
                                if (n == q.length) lastAt = t
                                main.post {
                                    PlaceSearch.search(prefix, at) { a ->
                                        answered.add(prefix to a.hits.map { it.id })
                                        if (prefix == q) synchronized(lock) { finalAt = System.nanoTime(); lock.notifyAll() }
                                    }
                                }
                                if (cadence > 0 && n < q.length) Thread.sleep(cadence)
                            }
                            synchronized(lock) { if (finalAt == 0L) lock.wait(15000) }
                            Thread.sleep(50) // (an older answer could only come before this one)
                            val c = PlaceSearch.counters
                            w.beginObject().name("q").value(q).name("cadence").value(cadence).name("letters").value(q.length)
                            w.name("answered").beginArray()
                            for (x in answered.toList()) w.value(x.first)
                            w.endArray()
                            w.name("final_ms").value(if (finalAt == 0L) -1.0 else (finalAt - lastAt) / 1e6)
                            val last = answered.lastOrNull()
                            w.name("last_is_whole").value(last != null && last.first == q && last.second == whole)
                            w.name("begun").value(c[0] - before[0]).name("finished").value(c[1] - before[1]).name("stopped").value(c[2] - before[2]).name("dropped").value(c[3] - before[3])
                            w.endObject()
                        }
                    }
                    w.endArray().endObject()
                }
                Log.i(TAG, "job done ${input.name} typing")
            } catch (t: Throwable) {
                Log.w(TAG, "typing: $t")
                Log.i(TAG, "job done ${input.name} with an error")
            }
        }, "aura-places-typing").start()
    }
}
