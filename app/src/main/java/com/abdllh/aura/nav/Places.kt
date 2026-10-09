package com.abdllh.aura.nav

import android.os.Handler
import android.os.Looper
import com.abdllh.aura.BuildConfig
import com.abdllh.aura.util.Prefs
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

/** Place search (Photon, OpenStreetMap data), and the saved places: Home, Work and recent destinations. */
object Places {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val generation = AtomicInteger()
    const val USER_AGENT = "Aura/${BuildConfig.VERSION_NAME} (car head unit; github.com/abdllh50i/aura-launcher)"

    /** Searches places by text, biased to [near]. Older searches still running are dropped (type-ahead). */
    fun search(query: String, near: LatLon?, arabic: Boolean, done: (List<Place>?) -> Unit) {
        val gen = generation.incrementAndGet()
        io.execute {
            if (gen != generation.get()) return@execute // typed further meanwhile: only the newest search goes out
            val result = try {
                val q = URLEncoder.encode(query.trim(), "UTF-8")
                val bias = near?.let { "&lat=${it.lat}&lon=${it.lon}&location_bias_scale=0.3" } ?: ""
                val lang = if (arabic) "default" else "en"
                parse(get("https://photon.komoot.io/api/?q=$q&limit=10&lang=$lang$bias"), near)
            } catch (_: Throwable) {
                null
            }
            if (gen == generation.get()) main.post { if (gen == generation.get()) done(result) }
        }
    }

    fun cancelSearch() { generation.incrementAndGet() }

    /** Turns a free-text address (the 1.0 Home/Work setting) into a place. */
    fun geocode(text: String, near: LatLon?, arabic: Boolean, done: (Place?) -> Unit) {
        io.execute {
            val p = try {
                val q = URLEncoder.encode(text.trim(), "UTF-8")
                parse(get("https://photon.komoot.io/api/?q=$q&limit=1&lang=${if (arabic) "default" else "en"}" +
                    (near?.let { "&lat=${it.lat}&lon=${it.lon}" } ?: "")), near).firstOrNull()
            } catch (_: Throwable) {
                null
            }
            main.post { done(p) }
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

    private fun parse(body: String, near: LatLon?): List<Place> {
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
                .filter { it.isNotBlank() && it != name }.distinct().joinToString("، ".takeIf { isArabic(name) } ?: ", ")
            if (!seen.add("$name|${"%.4f".format(java.util.Locale.ROOT, pos.lat)}|${"%.4f".format(java.util.Locale.ROOT, pos.lon)}")) continue
            out.add(Place(name, detail, pos))
        }
        return out // Photon's relevance order (already biased towards the car)
    }

    private fun isArabic(s: String) = s.any { it in '؀'..'ۿ' }

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
        get() = try {
            val a = JSONArray(Prefs.raw.getString("placeRecents", "[]"))
            (0 until a.length()).mapNotNull { Place.fromJson(a.optJSONObject(it)) }
        } catch (_: Throwable) {
            emptyList()
        }

    fun addRecent(p: Place) {
        val list = ArrayList<Place>()
        list.add(p)
        for (r in recents) if (Geo.distance(r.pos, p.pos) > 60 && list.size < 8) list.add(r)
        Prefs.raw.edit().putString("placeRecents", JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }.toString()).apply()
    }

    fun clearRecents() { Prefs.raw.edit().remove("placeRecents").apply() }
}
