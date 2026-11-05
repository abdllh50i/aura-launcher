package com.abdllh.aura.nav.places

import android.content.Context
import android.content.res.Configuration
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteException
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.OperationCanceledException
import android.util.Log
import com.abdllh.aura.BuildConfig
import com.abdllh.aura.R
import com.abdllh.aura.nav.LatLon
import com.abdllh.aura.nav.Place
import com.abdllh.aura.nav.Polyline
import com.abdllh.aura.nav.guide.GuideRoute
import com.abdllh.aura.nav.guide.Guidance
import com.abdllh.aura.nav.guide.NoWorld
import com.abdllh.aura.nav.guide.RouteWorld
import com.abdllh.aura.util.Prefs
import java.io.File
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 2.0's place search and what guidance asks of places.db: the way in for the screens. All the work is on one background thread, one thing at a time; a newer search stops the one
 * under way at its next read (the app's `generation`, and the framework's CancellationSignal, which also interrupts SQLite). Answers come on the main thread. A file that
 * is missing, cut short or of another version is a status and an empty answer, never a crash, and it is looked at again when the file changes.
 */
object PlaceSearch {
    private const val TAG = "AuraPlaces"
    /** Today's search box over this engine ([com.abdllh.aura.nav.Places]). On; a debug build can say off, to see the old road. */
    const val PREF_SWAP = "placesEngine"
    /** While typing (SEARCH_SPEC.md section 7). */
    private const val DEBOUNCE_MS = 250L
    /** Then the file's memory goes back: the page cache, the statements, table syn. */
    private const val IDLE_CLOSE_MS = 120_000L

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "aura-places").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private val main = Handler(Looper.getMainLooper())
    private val generation = AtomicInteger()
    @Volatile private var signal: CancellationSignal? = null
    private var typed: Runnable? = null

    private var app: Context? = null
    /** Debug builds: another file than the pack's. */
    @Volatile internal var testFile: File? = null

    // the engine's thread only
    private var engine: PlaceEngine? = null
    private var stamp: String? = null
    private var ringCache = HashMap<Int, ZoneRing>()

    /** The last that was found out. [PlacesState.UNKNOWN] until the first use has looked. */
    @Volatile var status = PlacesStatus(PlacesState.UNKNOWN, "not looked at yet")
        private set

    /** Debug builds: searches begun, answered, stopped on the way, dropped before they began. */
    internal val counters = IntArray(4)
    /** Debug builds: how long the last open took: the file's checks, table cat, table syn. */
    @Volatile internal var openNs = 0L

    /**
     * Where the file is, asked every time it is used: a new pack gives another path and the file is opened again. Set once at start by whoever knows (the pack manager's `PackPaths.placesDb`); null
     * from it means no pack holds one. Until then `files/maps2/places.db`.
     */
    @Volatile var locate: ((Context) -> File?)? = null

    /** Told, off the main thread, once per open file after a search found something: the pack manager's `PackPaths.noteWorked`. */
    @Volatile var worked: ((Context) -> Unit)? = null
    private var workedTold = false

    fun init(ctx: Context) { app = ctx.applicationContext }

    /** Off the main thread: filesDir can touch the disk. */
    private fun file(): File? {
        testFile?.let { return it }
        val c = app ?: return null
        val l = locate ?: return File(File(c.filesDir, "maps2"), "places.db")
        return l(c) ?: File(File(c.filesDir, "maps2"), "no-pack-holds-places.db")
    }

    /** The preference, default on. */
    val swapOn: Boolean get() = try { Prefs.raw.getBoolean(PREF_SWAP, true) } catch (_: Throwable) { true }

    /** The answer to a search. [hits] empty and [status] usable: nothing found. Not usable: the file could not be used, and [status] says why. */
    class Answer internal constructor(val hits: List<PlaceHit>, val status: PlacesStatus) {
        val usable: Boolean get() = status.usable
    }

    /** The open engine for the file as it is now, or null and [status] says why. Engine's thread. */
    private fun current(): PlaceEngine? {
        val f = file()
        if (f == null) {
            status = PlacesStatus(PlacesState.MISSING, "not initialised")
            return null
        }
        val s = "${f.path}|${f.length()}|${f.lastModified()}"
        if (s == stamp) return engine
        closeNow()
        stamp = s
        val t0 = System.nanoTime()
        val (db, st) = PlacesDb.open(f)
        openNs = System.nanoTime() - t0
        status = st
        if (db == null) {
            Log.w(TAG, "${f.path}: ${st.state}: ${st.why}")
            return null
        }
        engine = PlaceEngine(db)
        workedTold = false
        return engine
    }

    private fun closeNow() {
        engine?.db?.close()
        engine = null
        ringCache.clear()
    }

    private val closeIdle = Runnable {
        io.execute {
            closeNow()
            // the next use opens it again; the file's verdict is only kept when it was a refusal
            if (status.usable) stamp = null
        }
    }

    private fun used() {
        main.removeCallbacks(closeIdle)
        main.postDelayed(closeIdle, IDLE_CLOSE_MS)
    }

    /** Runs a read on the engine's thread with the error rules of the file: an SQLite error is an empty answer, corruption also closes it until the file changes. */
    private fun <T> guarded(fallback: T, block: (PlaceEngine) -> T): T {
        val e = try { current() } catch (t: Throwable) { Log.w(TAG, "open: $t"); null } ?: return fallback
        try {
            return block(e)
        } catch (t: OperationCanceledException) {
            throw t
        } catch (t: SQLiteDatabaseCorruptException) {
            refuse(t)
        } catch (t: SQLiteException) {
            Log.w(TAG, "read: $t")
            if (e.db.corrupt) refuse(t)
        } catch (t: Throwable) {
            Log.w(TAG, "read: $t")
        }
        return fallback
    }

    private fun refuse(t: Throwable) {
        Log.w(TAG, "corrupt: $t")
        status = PlacesStatus(PlacesState.BROKEN, "$t")
        closeNow()
    }

    /** What the file's state is, looked at now: at start or before a screen says anything about it. */
    fun check(done: (PlacesStatus) -> Unit) {
        io.execute {
            try { current() } catch (t: Throwable) { Log.w(TAG, "check: $t") }
            val s = status
            main.post { done(s) }
            used()
        }
    }

    /** A search near a place (or anywhere with null). Older searches still running are stopped and never answer. The query as typed, with its last space: "بنك " is finished, "بنك" may go on. */
    fun search(query: String, near: LatLon?, limit: Int = 12, done: (Answer) -> Unit) {
        typed?.let { main.removeCallbacks(it) }
        typed = null
        start(query, near, limit, done)
    }

    /** The same while a driver types: the search under way stops at once, this one waits 250 ms for the next letter. Main thread. */
    fun typing(query: String, near: LatLon?, limit: Int = 12, done: (Answer) -> Unit) {
        typed?.let { main.removeCallbacks(it) }
        val gen = generation.incrementAndGet()
        signal?.cancel()
        val r = Runnable { if (gen == generation.get()) start(query, near, limit, done) }
        typed = r
        main.postDelayed(r, DEBOUNCE_MS)
    }

    private fun start(query: String, near: LatLon?, limit: Int, done: (Answer) -> Unit) {
        val gen = generation.incrementAndGet()
        signal?.cancel()
        val cs = CancellationSignal()
        signal = cs
        io.execute {
            if (gen != generation.get()) {
                counters[3]++
                return@execute
            }
            counters[0]++
            val a = try {
                val hits = guarded(emptyList<PlaceHit>()) { it.search(query, near, limit, cs) }
                counters[1]++
                if (hits.isNotEmpty() && !workedTold) {
                    workedTold = true
                    try { app?.let { worked?.invoke(it) } } catch (t: Throwable) { Log.w(TAG, "worked: $t") }
                }
                Answer(hits, status)
            } catch (t: OperationCanceledException) {
                counters[2]++
                return@execute
            }
            used()
            main.post { if (gen == generation.get()) done(a) }
        }
    }

    /** A search for someone other than the search field (the voice, the phone, a saved address): it stops nothing and nothing stops it. */
    fun lookup(query: String, near: LatLon?, limit: Int = 12, done: (Answer) -> Unit) {
        io.execute {
            val hits = try { guarded(emptyList<PlaceHit>()) { it.search(query, near, limit, null) } } catch (t: Throwable) { Log.w(TAG, "$t"); emptyList() }
            val a = Answer(hits, status)
            used()
            main.post { done(a) }
        }
    }

    /** Stops whatever search is under way or waiting. */
    fun cancel() {
        typed?.let { main.removeCallbacks(it) }
        typed = null
        generation.incrementAndGet()
        signal?.cancel()
    }

    /** What is at a point: a dropped pin, "where am I". Null: nothing, or no file. */
    fun reverse(lat: Double, lon: Double, done: (ReverseHit?) -> Unit) = later(null, done) { it.reverse(lat, lon) }

    /** A row of table p by its id: a place tapped on the map ([com.abdllh.aura.nav.AuraMap.onPlaceRow]), for its card. Null: not there, or no file. */
    fun row(id: Int, done: (PlaceHit?) -> Unit) = later(null, done) { it.row(id) }

    /** The cameras, junctions with signals and bumps a route goes by, in order. Once for a route. */
    fun spotsAlong(line: Polyline, done: (RouteSpots?) -> Unit) = later(null, done) { it.spotsAlong(line) }

    /**
     * What to tell before a route starts. [roadClass]: the class of a segment of the line, numbered as `gate.road` ([roadClassNumber] for Valhalla's names, from trace_attributes); without
     * it, of the zones of kinds 3 and 4 only a route that ends inside is known to warn.
     */
    fun routeWarnings(line: Polyline, roadClass: ((Int) -> Int)?, done: (RouteWarnings?) -> Unit) = later(null, done) { it.warnings(line, roadClass) }

    /** The number of `gate.road` for Valhalla's road_class. */
    fun roadClassNumber(valhallaClass: String): Int = RoadAhead.classNumber(valhallaClass)

    /** The nearest of a kind (a key of table cat: "fuel", "mosque") ahead on a route, first the nearest along it; with [rightOnly] only on the driver's side. */
    fun nearestAhead(line: Polyline, fromAlong: Double, catKey: String, limit: Int = 3, rightOnly: Boolean = false, done: (List<RoutePlace>) -> Unit) =
        later(emptyList(), done) { it.nearestAhead(line, fromAlong, catKey, limit, rightOnly) }

    /** The nearest of a kind ("parking") near a point. */
    fun nearestTo(lat: Double, lon: Double, catKey: String, limit: Int = 5, radiusM: Double = 3000.0, done: (List<PlaceHit>) -> Unit) =
        later(emptyList(), done) { it.nearestTo(lat, lon, catKey, limit, radiusM) }

    /** A Haram's ring for exclude_polygons: kind 1 Makkah (route-request.json's avoid_haram), 2 Madinah. */
    fun haramRing(kind: Int = 1, done: (ZoneRing?) -> Unit) = later(null, done) { ringOf(it, kind) }

    /** The same for the routing thread, which waits for it anyway. Not on the main thread; null when there is no file or it takes over 2 s. */
    fun haramRingBlocking(kind: Int = 1): ZoneRing? {
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        return try {
            val r = io.submit(Callable { guarded<ZoneRing?>(null) { ringOf(it, kind) } }).get(2, java.util.concurrent.TimeUnit.SECONDS)
            used()
            r
        } catch (t: Throwable) {
            Log.w(TAG, "ring: $t")
            null
        }
    }

    private fun ringOf(e: PlaceEngine, kind: Int): ZoneRing? = ringCache[kind] ?: e.haramRing(kind)?.also { ringCache[kind] = it }

    private fun <T> later(fallback: T, done: (T) -> Unit, block: (PlaceEngine) -> T) {
        io.execute {
            val r = try { guarded(fallback, block) } catch (t: Throwable) { Log.w(TAG, "$t"); fallback }
            used()
            main.post { done(r) }
        }
    }

    /**
     * The words and what lies along one route ([Guidance]), over this file ([PlacesWorld]); plainer ones with no file. Once for a route, on the engine's thread, and [done] on that thread: [Guidance] is
     * made once and read from then on. Not on the main thread's account: a long trip asks some hundreds of small boxes.
     */
    fun guidance(route: GuideRoute, arabic: Boolean, endSnap: Double, destination: LatLon?, done: (Guidance?) -> Unit) {
        io.execute {
            val world: RouteWorld = try { current()?.let { PlacesWorld(it.db) } } catch (t: Throwable) { Log.w(TAG, "guidance: $t"); null } ?: NoWorld
            val g = Guidance.safely(route, arabic, world, endSnap, destination)
            used()
            done(g)
        }
    }

    /** Debug builds and tests: a job on the engine's thread, with the open engine. */
    internal fun onEngine(block: (PlaceEngine?) -> Unit) {
        io.execute { block(try { current() } catch (t: Throwable) { Log.w(TAG, "$t"); null }) }
    }

    /** Debug builds: lets the file go now, as the idle close does. */
    internal fun closeForTest() { io.execute { closeNow(); stamp = null; if (BuildConfig.DEBUG) Log.i(TAG, "closed") } }

    /** A result as the Place the screens of 1.x know: a coarse row's position is told in its detail ("موقع تقريبي"). */
    fun place(h: PlaceHit, arabic: Boolean): Place {
        val d = h.detail(arabic)
        if (!h.approx) return Place(h.shown(arabic), d, LatLon(h.lat, h.lon))
        val label = approxLabel(arabic)
        // (first: a one-line detail cuts its end, and this is the part that must be read)
        return Place(h.shown(arabic), if (d.isEmpty()) label else label + (if (arabic) "، " else ", ") + d, LatLon(h.lat, h.lon))
    }

    private val approxLabels = arrayOfNulls<String>(2)

    /** In the language of the answer, as the rest of the detail, whatever the app's own is. */
    private fun approxLabel(arabic: Boolean): String {
        val at = if (arabic) 1 else 0
        approxLabels[at]?.let { return it }
        val c = app ?: return ""
        val cfg = Configuration(c.resources.configuration)
        cfg.setLocale(Locale(if (arabic) "ar" else "en"))
        return c.createConfigurationContext(cfg).getString(R.string.places_approx).also { approxLabels[at] = it }
    }
}
