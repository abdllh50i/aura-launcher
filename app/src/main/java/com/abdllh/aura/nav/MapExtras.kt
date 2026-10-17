package com.abdllh.aura.nav

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.abdllh.aura.BuildConfig
import com.abdllh.aura.util.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.floor

/**
 * What the owner adds to the map with the map editor (tools/map-editor, a page on his PC): the speed limit of a
 * street, speed cameras ("ساهر"), traffic signals and speed bumps. The editor publishes them as one JSON file,
 * `additions.json` on the repository's `mapdata` branch; Settings → Navigation fetches it (and the app by itself once
 * a day, when there is internet) and keeps it on the unit, so everything here works without internet afterwards.
 *
 *     {"version":1,"updated":<ms>,
 *      "speeds":[{"name":"...","kmh":80,"lines":[[[lon,lat],...],...]}],
 *      "cameras":[{"lat":..,"lon":..,"kmh":80}],"signals":[{"lat":..,"lon":..}],"bumps":[{"lat":..,"lon":..}]}
 */
object MapExtras {
    private const val TAG = "AuraExtras"
    private const val FILE = "map-extras.json"
    private const val BRANCH = "mapdata"
    private const val NAME = "additions.json"
    private const val KEY_FETCHED = "mapExtrasFetched"
    private const val MAX_BYTES = 4 * 1024 * 1024
    private const val DAY_MS = 24 * 3600_000L
    private const val AUTO_CHECK_MS = 10 * 60_000L

    enum class Kind { CAMERA, SIGNAL, BUMP }

    /**
     * A camera, a signal or a bump; [kmh] is a camera's limit (0 when not given). [mine]: a camera marked on this unit
     * with the map's "camera here" button ([addMine]), not one the editor published.
     */
    class Spot(val kind: Kind, val pos: LatLon, val kmh: Int, val mine: Boolean = false)

    /** A street (or a stretch of one) and its limit. */
    class Speed(val name: String, val kmh: Int, val lines: List<List<LatLon>>)

    // Every field below is written on the main thread only (the io thread reads and parses, then hands the result
    // over): a fetch that finished while a camera was being marked could otherwise put the old set back.

    /** Everything the map and the road watch use: what was published, and the cameras marked on this unit. */
    @Volatile var spots: List<Spot> = emptyList(); private set
    /** Counts every change of [spots] or [speeds]: a map draws a set again only when it has not drawn this one. */
    @Volatile var version = 0; private set
    @Volatile private var published: List<Spot> = emptyList()
    /** The cameras marked on this unit (Settings → Navigation lists and clears them). */
    @Volatile var mine: List<Spot> = emptyList(); private set
    @Volatile var speeds: List<Speed> = emptyList(); private set
    /** When the owner last published what is here (0: nothing fetched yet). */
    @Volatile var updatedAt = 0L; private set
    /** When this unit last fetched it. */
    val fetchedAt: Long get() = Prefs.raw.getLong(KEY_FETCHED, 0L)

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var app: Context? = null
    @Volatile private var busy = false

    // the streets' pieces by square of [CELL] degrees (about 220 m): which limit applies where the car is
    private const val CELL = 0.002
    private class Piece(val a: LatLon, val b: LatLon, val kmh: Int, val bearing: Double)
    @Volatile private var grid: Map<Long, List<Piece>> = emptyMap()

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    /** What the editor published, of one kind (the cameras marked on this unit are [mine]). */
    fun count(kind: Kind) = published.count { it.kind == kind }

    fun init(ctx: Context) {
        if (app != null) return
        val c = ctx.applicationContext
        app = c
        io.execute {
            try {
                val f = File(c.filesDir, FILE)
                if (f.isFile) apply(f.readText())
            } catch (t: Throwable) {
                Log.w(TAG, "kept additions unreadable: $t")
            }
            readMine(c)
            main.postDelayed(auto, 20_000L)
        }
    }

    /**
     * By itself once a day (a few kilobytes): what the owner added shows without anybody asking. A fetch that failed
     * leaves [fetchedAt] alone, so the next look tries again: the unit's internet is a phone that connects when it does.
     */
    private val auto = object : Runnable {
        override fun run() {
            if (System.currentTimeMillis() - fetchedAt > DAY_MS) refresh(null)
            main.postDelayed(this, AUTO_CHECK_MS)
        }
    }

    /** Fetches the published additions; [done] on the main thread: whether it worked, and the reason when it did not. */
    fun refresh(done: ((ok: Boolean, error: String?) -> Unit)?) {
        val c = app
        if (c == null || busy) { done?.let { d -> main.post { d(false, if (c == null) "not started" else "busy") } }; return }
        busy = true
        io.execute {
            var error: String? = null
            try {
                val text = fetch()
                if (text == null) {
                    // nothing published yet: not a failure, there is just nothing to show
                    Prefs.raw.edit().putLong(KEY_FETCHED, System.currentTimeMillis()).apply()
                } else {
                    val got = apply(text) // (throws on anything that is not the format above: the kept file stays)
                    File(c.filesDir, "$FILE.tmp").apply { writeText(text) }.renameTo(File(c.filesDir, FILE))
                    Prefs.raw.edit().putLong(KEY_FETCHED, System.currentTimeMillis()).apply()
                    Log.i(TAG, "additions: $got")
                }
            } catch (t: Throwable) {
                error = t.message ?: t.javaClass.simpleName
                Log.w(TAG, "additions not fetched: $t")
            }
            busy = false
            main.post { done?.invoke(error == null, error) }
        }
    }

    /** The published file's text, or null when there is none (no branch, no file). */
    private fun fetch(): String? {
        val repo = Prefs.updateRepo.ifBlank { BuildConfig.UPDATE_REPO }
        // debug builds: another address (the editor's own server from the emulator: setprop debug.aura.extras http://10.0.2.2:8765/api/data)
        val test = if (BuildConfig.DEBUG) com.abdllh.aura.system.SystemProps.get("debug.aura.extras") else ""
        // the API first (never stale), then the plain file address (a CDN that may lag a few minutes)
        val urls = if (test.startsWith("http")) listOf(test) else listOf(
            "https://api.github.com/repos/$repo/contents/$NAME?ref=$BRANCH",
            "https://raw.githubusercontent.com/$repo/$BRANCH/$NAME")
        var last: Throwable? = null
        for (u in urls) {
            val c = URL(u).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 10_000
                c.readTimeout = 20_000
                c.setRequestProperty("User-Agent", Places.USER_AGENT)
                c.setRequestProperty("Accept", "application/vnd.github.raw+json")
                when (val code = c.responseCode) {
                    200 -> {
                        val bytes = c.inputStream.use { it.readBytes() }
                        if (bytes.size > MAX_BYTES) throw IllegalStateException("too large")
                        return bytes.toString(Charsets.UTF_8)
                    }
                    404 -> return null
                    else -> throw IllegalStateException("HTTP $code")
                }
            } catch (t: Throwable) {
                last = t
            } finally {
                c.disconnect()
            }
        }
        throw last ?: IllegalStateException("no answer")
    }

    /**
     * Reads the format (io thread) and makes it what the map and the road watch use (on the main thread). Throws on a
     * file that is not the format; returns what it holds, for the log.
     */
    private fun apply(text: String): String {
        val o = JSONObject(text)
        if (o.optInt("version", 0) != 1) throw IllegalStateException("unknown version")
        fun pos(j: JSONObject): LatLon {
            val lat = j.getDouble("lat")
            val lon = j.getDouble("lon")
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) throw IllegalStateException("bad position")
            return LatLon(lat, lon)
        }
        fun each(a: JSONArray?, f: (JSONObject) -> Unit) { if (a != null) for (i in 0 until a.length()) f(a.getJSONObject(i)) }
        val newSpots = ArrayList<Spot>()
        each(o.optJSONArray("cameras")) { newSpots.add(Spot(Kind.CAMERA, pos(it), it.optInt("kmh", 0).coerceIn(0, 200))) }
        each(o.optJSONArray("signals")) { newSpots.add(Spot(Kind.SIGNAL, pos(it), 0)) }
        each(o.optJSONArray("bumps")) { newSpots.add(Spot(Kind.BUMP, pos(it), 0)) }
        val newSpeeds = ArrayList<Speed>()
        each(o.optJSONArray("speeds")) { s ->
            val kmh = s.getInt("kmh")
            if (kmh !in 5..200) throw IllegalStateException("bad speed")
            val lines = ArrayList<List<LatLon>>()
            val ls = s.getJSONArray("lines")
            for (i in 0 until ls.length()) {
                val l = ls.getJSONArray(i)
                val pts = ArrayList<LatLon>(l.length())
                for (k in 0 until l.length()) {
                    val p = l.getJSONArray(k)
                    pts.add(LatLon(p.getDouble(1), p.getDouble(0)))
                }
                if (pts.size >= 2) lines.add(pts)
            }
            if (lines.isNotEmpty()) newSpeeds.add(Speed(s.optString("name"), kmh, lines))
        }
        val g = HashMap<Long, ArrayList<Piece>>()
        for (s in newSpeeds) for (line in s.lines) for (i in 0 until line.size - 1) {
            val a = line[i]
            val b = line[i + 1]
            val piece = Piece(a, b, s.kmh, Geo.bearing(a, b))
            // every square the piece passes (pieces are short; a step of half a square misses none)
            val steps = (maxOf(abs(b.lat - a.lat), abs(b.lon - a.lon)) / (CELL / 2)).toInt() + 1
            var lastCell = Long.MIN_VALUE
            for (k in 0..steps) {
                val p = Geo.lerp(a, b, k.toDouble() / steps)
                val cell = cell(p.lat, p.lon)
                if (cell != lastCell) { g.getOrPut(cell) { ArrayList() }.add(piece); lastCell = cell }
            }
        }
        val upd = o.optLong("updated", 0L)
        main.post {
            published = newSpots
            spots = newSpots + mine
            speeds = newSpeeds
            grid = g
            updatedAt = upd
            version++
            for (l in listeners) l()
        }
        return "${newSpots.size} spots, ${newSpeeds.size} streets"
    }

    // ------------------------------------------------------------------------------------------ cameras marked here
    private const val MINE = "my-cameras.json"
    /** A camera this close to one already known is that one. */
    private const val SAME_M = 40.0

    /**
     * A camera marked where the car is (the map's "camera here" button): kept on this unit, drawn and announced like
     * the published ones. Returns it, or the known camera already there (then nothing is added). Main thread.
     */
    fun addMine(p: LatLon, kmh: Int): Pair<Spot, Boolean> {
        spots.firstOrNull { it.kind == Kind.CAMERA && Geo.distance(it.pos, p) < SAME_M }?.let { return it to false }
        val s = Spot(Kind.CAMERA, p, kmh.coerceIn(0, 200), mine = true)
        mine = mine + s
        changedMine()
        return s to true
    }

    /** Takes back a camera marked here (the "undo" right after, or the list in the settings). Main thread. */
    fun removeMine(s: Spot) {
        if (s !in mine) return
        mine = mine - s
        changedMine()
    }

    fun clearMine() {
        if (mine.isEmpty()) return
        mine = emptyList()
        changedMine()
    }

    private fun changedMine() {
        spots = published + mine
        version++
        // (at once, on this thread: a road watch told now does not announce the camera the car is standing at)
        for (l in listeners) l()
        val c = app ?: return
        val list = mine
        io.execute {
            try {
                val a = JSONArray()
                for (s in list) a.put(JSONObject().put("lat", s.pos.lat).put("lon", s.pos.lon).put("kmh", s.kmh))
                val f = File(c.filesDir, MINE)
                val tmp = File(c.filesDir, "$MINE.tmp")
                tmp.writeText(JSONObject().put("version", 1).put("cameras", a).toString())
                tmp.renameTo(f)
            } catch (t: Throwable) {
                Log.w(TAG, "my cameras: $t")
            }
        }
    }

    private fun readMine(c: Context) {
        try {
            val f = File(c.filesDir, MINE)
            if (!f.isFile) return
            val a = JSONObject(f.readText()).optJSONArray("cameras") ?: return
            val out = ArrayList<Spot>()
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val lat = o.getDouble("lat")
                val lon = o.getDouble("lon")
                if (lat in -90.0..90.0 && lon in -180.0..180.0) out.add(Spot(Kind.CAMERA, LatLon(lat, lon), o.optInt("kmh", 0).coerceIn(0, 200), mine = true))
            }
            main.post {
                mine = out
                spots = published + out
                version++
                for (l in listeners) l()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "my cameras unreadable: $t")
        }
    }

    private fun cell(lat: Double, lon: Double): Long =
        (floor(lat / CELL).toLong() shl 32) xor (floor(lon / CELL).toLong() and 0xFFFFFFFFL)

    /**
     * The limit of the street the car is on (0: none set). [bearing]: the car's heading when it moves, so a street
     * crossing overhead or beside does not lend its limit (null when standing: the nearest street within reach).
     */
    fun limitAt(p: LatLon, bearing: Double?): Int {
        val g = grid
        if (g.isEmpty()) return 0
        var best = 0
        var bestD = REACH_M
        val cy = floor(p.lat / CELL).toLong()
        val cx = floor(p.lon / CELL).toLong()
        for (dy in -1..1) for (dx in -1..1) {
            val list = g[((cy + dy) shl 32) xor ((cx + dx) and 0xFFFFFFFFL)] ?: continue
            for (s in list) {
                if (bearing != null) {
                    val turn = abs(Geo.angleDiff(bearing, s.bearing))
                    if (turn > ALONG_DEG && turn < 180 - ALONG_DEG) continue // (a street is driven both ways)
                }
                val d = Geo.project(p, s.a, s.b).second
                if (d < bestD) { bestD = d; best = s.kmh }
            }
        }
        return best
    }

    private const val REACH_M = 24.0
    private const val ALONG_DEG = 38.0

    // the text of the last set asked for: the home map and Maps both ask at every start, mostly for the same set
    private var jsonKey = -1 to false
    private var json = ""

    /**
     * Everything as GeoJSON for the map's layers ([MapStyle]): `kind` camera / signal / bump / speed, and a `label`.
     * Main thread; made once per set and language (the owner's streets can be thousands of points).
     */
    fun geoJson(arabic: Boolean): String {
        val key = version to arabic
        if (key != jsonKey) {
            json = makeJson(arabic)
            jsonKey = key
        }
        return json
    }

    private fun makeJson(arabic: Boolean): String = buildString {
        append("""{"type":"FeatureCollection","features":[""")
        var first = true
        fun sep() { if (first) first = false else append(',') }
        for (s in spots) {
            sep()
            val kind = when (s.kind) { Kind.CAMERA -> "camera"; Kind.SIGNAL -> "signal"; Kind.BUMP -> "bump" }
            val label = when (s.kind) {
                Kind.CAMERA -> (if (arabic) "ساهر" else "Camera") + (if (s.kmh > 0) " ${s.kmh}" else "")
                Kind.BUMP -> if (arabic) "مطب" else "Bump"
                Kind.SIGNAL -> ""
            }
            append("""{"type":"Feature","properties":{"kind":"$kind","label":"$label"},"geometry":{"type":"Point","coordinates":[${s.pos.lon},${s.pos.lat}]}}""")
        }
        for (s in speeds) for (line in s.lines) {
            sep()
            append("""{"type":"Feature","properties":{"kind":"speed","label":"${s.kmh}"},"geometry":{"type":"LineString","coordinates":[""")
            line.forEachIndexed { i, p -> if (i > 0) append(','); append('[').append(p.lon).append(',').append(p.lat).append(']') }
            append("]}}")
        }
        append("]}")
    }
}
