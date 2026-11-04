package com.abdllh.aura.nav

import android.os.Handler
import android.os.Looper
import com.abdllh.aura.BuildConfig
import com.abdllh.aura.util.Prefs
import com.abdllh.aura.util.TextMatch
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** A place to drive to. */
data class Place(val name: String, val detail: String, val pos: LatLon) {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("detail", detail).put("lat", pos.lat).put("lon", pos.lon)

    companion object {
        fun fromJson(o: JSONObject?): Place? = try {
            if (o == null) null else Place(o.getString("name"), o.optString("detail"), LatLon(o.getDouble("lat"), o.getDouble("lon")))
        } catch (_: Throwable) {
            null
        }
    }
}

/**
 * Place search (Photon, OpenStreetMap data), with the offline map's own index ([com.abdllh.aura.nav.offline.PlaceIndex])
 * beside it when there is one, and the saved places: Home, Work and recent destinations.
 */
object Places {
    private val io = Executors.newSingleThreadExecutor()
    private val offlineIo = Executors.newSingleThreadExecutor()
    private val lookupIo = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    /** Search results with the same name closer together than this are one place (metres). */
    private const val SAME_PLACE_M = 100.0
    /** [clearWinner]: how well a name must answer ([TextMatch.score]), and how close two answers are the same answer. */
    private const val STRONG_NAME = 0.85
    private const val SAME_ANSWER = 0.02
    private const val SAME_NAME = 0.95
    private const val SAME_SPOT_M = 300.0
    private const val PARTLY = 0.4
    /** Words that say what kind of place and not which ([TextMatch.norm]ed, without "ال"): alone they are a list to choose from. */
    private val KIND_WORDS = setOf("شارع", "طريق", "حي", "دوار", "جسر", "كوبري", "سوق", "مول", "مجمع", "مركز", "مدرسه", "جامعه", "كليه", "مطار",
        "ميناء", "نادي", "حديقه", "منتزه", "شاطي", "كورنيش", "شركه", "محل", "مكتبه", "مستوصف", "عياده", "street", "road", "mall", "market",
        "airport", "school", "university", "park", "beach", "station")
    private val generation = AtomicInteger()
    const val USER_AGENT = "Aura/${BuildConfig.VERSION_NAME} (car head unit; github.com/abdllh50i/aura-launcher)"

    /**
     * Searches places by text, biased to [near]. Older searches still running are dropped (type-ahead). An Arabic
     * query is also searched in Latin letters ([ArabicQuery]): many places only have a Latin name in the map data.
     * With the offline map, its results come at once and Photon's join them when they arrive ([done] may run twice);
     * without internet the offline ones stay. null = no answer at all (no internet and nothing offline).
     */
    fun search(query: String, near: LatLon?, arabic: Boolean, done: (List<Place>?) -> Unit) {
        val gen = generation.incrementAndGet()
        find(query, near, arabic, io, { gen == generation.get() }) { list, _ -> done(list) }
    }

    /**
     * The same search for someone other than the Maps screen's search field (the voice, the phone): it cancels nothing
     * and nothing cancels it, and a stalled hotspot under it does not hold up what the driver types there. [done]: the
     * list so far, and whether it is the last one.
     */
    fun lookup(query: String, near: LatLon?, arabic: Boolean, done: (List<Place>?, Boolean) -> Unit) {
        find(query, near, arabic, lookupIo, { true }, done)
    }

    private fun find(query: String, near: LatLon?, arabic: Boolean, online: java.util.concurrent.Executor, current: () -> Boolean,
                     done: (List<Place>?, Boolean) -> Unit) {
        val store = com.abdllh.aura.nav.offline.OfflineMaps.searchStore()
        // main thread state of this search
        var offline: List<Place>? = null
        var offlineDone = store == null
        var answer: List<Place>? = null
        var onlineDone = false
        fun show() {
            if (!current()) return
            val on = answer
            val off = offline.orEmpty()
            val last = onlineDone && offlineDone
            when {
                on != null -> done(merge(on, off), last)
                last -> done(if (store != null) off else null, true)
                off.isNotEmpty() -> done(off, false)
            }
        }
        if (store != null) offlineIo.execute {
            if (!current()) return@execute
            val r = try { com.abdllh.aura.nav.offline.PlaceIndex.search(store, query, near, arabic) } catch (_: Throwable) { emptyList() }
            main.post { offline = r; offlineDone = true; show() }
        }
        online.execute {
            if (!current()) return@execute // typed further meanwhile: only the newest search goes out
            val variants = listOfNotNull(query.trim(), ArabicQuery.latin(query)).distinct()
            val found = ArrayList<Place>()
            var failed = 0
            for (q in variants) {
                if (!current()) return@execute
                try { found += photon(q, near, arabic) } catch (_: Throwable) { failed++ }
            }
            // the strong pull towards the car fills the list with local namesakes ("Jeddah Street" in Jubail): the
            // engine's best answers anywhere are asked for too, so a far city or airport is still at the list's end
            var wide = emptyList<Place>()
            if (near != null && failed < variants.size && current()) {
                try { wide = photon(variants[0], null, arabic, 3) } catch (_: Throwable) { }
            }
            val result = if (failed == variants.size) null else rank(found, wide, query, variants.getOrNull(1), near)
            main.post { answer = result; onlineDone = true; show() }
        }
    }

    /**
     * The one place a spoken name clearly means among [list] (a search's ranked answer), or null when it should stay a
     * list to choose from. Clear: its name answers the query well (the query or a good part of it, "الراشد" for "الراشد
     * مول"), and no other name answers it as well at about the same distance (branches of one name are one answer: the
     * nearest). Beyond the region only a name that is exactly what was said: the namesake of a street or a shop 500 km
     * away is not where a driver asks to be taken. A word for a kind of place alone ([KIND_WORDS]) never is: "ودني
     * المطار" must not end in the district called Al Matar, or "شارع" in the nearest Street 9.
     */
    fun clearWinner(query: String, list: List<Place>, near: LatLon?): Place? {
        if (near == null || TextMatch.norm(query).length < 3) return null
        val bare = TextMatch.norm(query).split(' ').joinToString(" ") { it.removePrefix("ال").ifEmpty { it } }
        if (bare in KIND_WORDS) return null
        val latin = ArabicQuery.latin(query)
        fun score(p: Place): Double {
            val name = TextMatch.norm(p.name)
            if (name.length * 2 < TextMatch.norm(query).length) return 0.0 // (a word of the query is not its answer)
            return maxOf(TextMatch.score(query, p.name), latin?.let { TextMatch.score(it, p.name) } ?: 0.0,
                TextMatch.score(bare, p.name) - 0.01)
        }
        fun band(p: Place) = (Geo.distance(near, p.pos) / 1000.0).let { when { it < 5 -> 0; it < 30 -> 1; it < 150 -> 2; else -> 3 } }
        val strong = list.map { it to score(it) }.filter { it.second >= STRONG_NAME }
        val nearest = strong.minOfOrNull { band(it.first) } ?: return null
        val here = strong.filter { band(it.first) == nearest }
        val best = here.maxOf { it.second }
        // (and only when nothing nearer answers even partly: "مكتبة جرير" in another city, with "Jarir Bookstore" down the road, is a list)
        if (nearest == 3 && (best < 0.99 || list.any { band(it) < 3 && score(it) >= PARTLY })) return null
        val rivals = here.filter { it.second >= best - SAME_ANSWER }.map { it.first }
        val winner = rivals.minByOrNull { Geo.distance(near, it.pos) } ?: return null
        // (another spelling of the name, or the map data's second entry for the same spot, is no rival)
        val clear = rivals.all { TextMatch.score(it.name, winner.name) >= SAME_NAME || Geo.distance(it.pos, winner.pos) < SAME_SPOT_M }
        return if (clear) winner else null
    }

    /** Photon's results first, then the offline ones it did not have (the same name within 200 m is the same place). */
    private fun merge(on: List<Place>, off: List<Place>): List<Place> {
        val out = ArrayList(on)
        for (p in off) {
            if (out.size >= 14) break
            if (out.none { TextMatch.norm(it.name) == TextMatch.norm(p.name) && Geo.distance(it.pos, p.pos) < 200 }) out.add(p)
        }
        return out
    }

    private fun photon(q: String, near: LatLon?, arabic: Boolean, limit: Int = 10): List<Place> {
        val enc = URLEncoder.encode(q, "UTF-8")
        // a strong pull towards the car (0.3 still put Toronto and Rwanda before the pharmacy round the corner)
        val bias = near?.let { "&lat=${it.lat}&lon=${it.lon}&zoom=12&location_bias_scale=0.1" } ?: ""
        val lang = if (arabic) "default" else "en"
        return parse(get("https://photon.komoot.io/api/?q=$enc&limit=$limit&lang=$lang$bias"), arabic)
    }

    /**
     * The results near the car and [wide] (the same query anywhere) merged: the places whose name answers the query
     * best (in either script) first; among those that answer it about as well, the nearer first, by bands (in town,
     * the area, the region, further), then the search engine's order. The best answer anywhere keeps a place at the
     * end even when local namesakes fill the list: many streets are named after the cities they lead to.
     */
    private fun rank(list: List<Place>, wide: List<Place>, query: String, latin: String?, near: LatLon?): List<Place> {
        fun key(p: Place) = "${TextMatch.norm(p.name)}|${"%.3f".format(java.util.Locale.ROOT, p.pos.lat)}|${"%.3f".format(java.util.Locale.ROOT, p.pos.lon)}"
        // One name once within [SAME_PLACE_M]: to the driver the same name a few dozen metres apart is one place (the
        // map data often has a shop twice), and listing it again read as a bug. Branches further apart stay, told
        // apart by their street and distance.
        val unique = ArrayList<Place>()
        for (p in list + wide) {
            val n = TextMatch.norm(p.name)
            if (unique.none { TextMatch.norm(it.name) == n && Geo.distance(it.pos, p.pos) < SAME_PLACE_M }) unique.add(p)
        }
        fun bucket(p: Place): Int {
            val s = maxOf(TextMatch.score(query, p.name), latin?.let { TextMatch.score(it, p.name) } ?: 0.0)
            // a name that holds the whole query answers it as well as one that is only the query: "Al Dawa Pharmacy"
            // round the corner is the "pharmacy" asked for, not a place called "Pharmacy" 90 km away
            return if (s >= 0.9) 4 else (s * 4).toInt()
        }
        val km = unique.associateWith { p -> near?.let { Geo.distance(it, p.pos) / 1000.0 } ?: 0.0 }
        fun band(p: Place): Int = km.getValue(p).let { when { it < 5 -> 0; it < 30 -> 1; it < 150 -> 2; else -> 3 } }
        val ranked = unique.withIndex()
            .sortedWith(compareByDescending<IndexedValue<Place>> { bucket(it.value) }.thenBy { band(it.value) }.thenBy { it.index })
            .map { it.value }
        val best = wide.firstOrNull()?.let(::key)
        if (ranked.size <= 12 || best == null || ranked.take(12).any { key(it) == best }) return ranked.take(12)
        return ranked.take(11) + ranked.first { key(it) == best }
    }

    fun cancelSearch() { generation.incrementAndGet() }

    /** Turns a free-text address (the 1.0 Home/Work setting) into a place (the offline index when Photon fails). */
    fun geocode(text: String, near: LatLon?, arabic: Boolean, done: (Place?) -> Unit) {
        io.execute {
            val p = try {
                val q = URLEncoder.encode(text.trim(), "UTF-8")
                parse(get("https://photon.komoot.io/api/?q=$q&limit=1&lang=${if (arabic) "default" else "en"}" +
                    (near?.let { "&lat=${it.lat}&lon=${it.lon}" } ?: "")), arabic).firstOrNull()
            } catch (_: Throwable) {
                com.abdllh.aura.nav.offline.OfflineMaps.searchStore()?.let {
                    try { com.abdllh.aura.nav.offline.PlaceIndex.search(it, text, near, arabic, 1).firstOrNull() } catch (_: Throwable) { null }
                }
            }
            main.post { done(p) }
        }
    }

    /**
     * What is at a point (a dropped pin): the nearest named place or address, or null. With the offline map its answer
     * comes first, and Photon's replaces it when it arrives ([done] may run twice).
     */
    fun reverse(pos: LatLon, arabic: Boolean, done: (Place?) -> Unit) {
        val store = com.abdllh.aura.nav.offline.OfflineMaps.searchStore()
        var onlineShown = false
        if (store != null) offlineIo.execute {
            val p = try { com.abdllh.aura.nav.offline.PlaceIndex.reverse(store, pos, arabic) } catch (_: Throwable) { null }
            if (p != null) main.post { if (!onlineShown) done(p) }
        }
        io.execute {
            val p = try {
                parse(get("https://photon.komoot.io/reverse?lat=${pos.lat}&lon=${pos.lon}&limit=1&lang=${if (arabic) "default" else "en"}"), arabic)
                    .firstOrNull()?.let { Place(it.name, it.detail, pos) } // keep the exact point that was chosen
            } catch (_: Throwable) {
                null
            }
            main.post {
                if (p != null) { onlineShown = true; done(p) }
                else if (store == null) done(null)
            }
        }
    }

    internal fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 10000
        c.setRequestProperty("User-Agent", USER_AGENT)
        c.setRequestProperty("Accept", "application/json")
        try {
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            c.disconnect()
        }
    }

    /** Photon's answer as places; the address parts joined with the UI language's comma, whatever the name's script. */
    private fun parse(body: String, arabic: Boolean): List<Place> {
        val features = JSONObject(body).optJSONArray("features") ?: return emptyList()
        val out = ArrayList<Place>()
        val seen = HashSet<String>()
        for (i in 0 until features.length()) {
            val f = features.getJSONObject(i)
            val coords = f.getJSONObject("geometry").getJSONArray("coordinates")
            val pos = LatLon(coords.getDouble(1), coords.getDouble(0))
            val p = f.getJSONObject("properties")
            val street = listOf(p.optString("street"), p.optString("housenumber")).filter { it.isNotBlank() }.joinToString(" ")
            val name = p.optString("name").ifBlank { street }
            if (name.isBlank()) continue
            val detail = listOf(if (p.optString("name").isNotBlank()) street else "", p.optString("district"), p.optString("city"), p.optString("state"))
                .filter { it.isNotBlank() && it != name }.distinct().joinToString(if (arabic) "، " else ", ")
            if (!seen.add("$name|${"%.4f".format(java.util.Locale.ROOT, pos.lat)}|${"%.4f".format(java.util.Locale.ROOT, pos.lon)}")) continue
            out.add(Place(name, detail, pos))
        }
        return out // Photon's relevance order (already biased towards the car)
    }

    /** Written in Arabic letters (an Arabic name, or one the Arabic voice can read). */
    fun isArabic(s: String) = s.any { it in '؀'..'ۿ' }

    // ---------------------------------------------------------------------------------------- saved places
    var home: Place?
        get() = saved("placeHome")
        set(v) { Prefs.raw.edit().putString("placeHome", v?.toJson()?.toString()).apply() }

    var work: Place?
        get() = saved("placeWork")
        set(v) { Prefs.raw.edit().putString("placeWork", v?.toJson()?.toString()).apply() }

    /** A saved place; a damaged value counts as not set (the home screen reads these on every resume). */
    private fun saved(key: String): Place? = try {
        Place.fromJson(Prefs.raw.getString(key, null)?.let { JSONObject(it) })
    } catch (_: Throwable) {
        null
    }

    val recents: List<Place>
        get() = savedList("placeRecents")

    private fun savedList(key: String): List<Place> = try {
        val a = JSONArray(Prefs.raw.getString(key, "[]"))
        (0 until a.length()).mapNotNull { Place.fromJson(a.optJSONObject(it)) }
    } catch (_: Throwable) {
        emptyList()
    }

    private fun keepList(key: String, list: List<Place>) {
        Prefs.raw.edit().putString(key, JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }.toString()).apply()
    }

    fun addRecent(p: Place) {
        val list = ArrayList<Place>()
        list.add(p)
        for (r in recents) if (Geo.distance(r.pos, p.pos) > 60 && list.size < MAX_RECENTS) list.add(r)
        keepList("placeRecents", list)
    }

    fun clearRecents() { Prefs.raw.edit().remove("placeRecents").apply() }

    /** The recent places as the phone says they are ([com.abdllh.aura.link.PhoneServer]'s PUT /v1/places). */
    fun setRecents(list: List<Place>) = keepList("placeRecents", list.take(MAX_RECENTS))

    /** Places kept for the phone's list ("the unit is the source of truth"); no screen of the unit shows them yet. */
    var favourites: List<Place>
        get() = savedList("placeFavourites")
        set(v) = keepList("placeFavourites", v.take(MAX_FAVOURITES))

    private const val MAX_RECENTS = 8
    private const val MAX_FAVOURITES = 50
}
