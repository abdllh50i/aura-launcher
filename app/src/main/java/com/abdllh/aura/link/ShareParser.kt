package com.abdllh.aura.link

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/**
 * What the owner shared from his phone (the text and the link of Apple Maps' or Google Maps' share button, a WhatsApp
 * location, a pasted "lat,lon", a geo: URI), as one place. The same cases are in tools/link-fixtures/share.json, in a
 * form the phone's Swift parser can test against later.
 *
 * Nothing is guessed. A place comes only from coordinates the link or the text states ([Confidence.EXACT]; a Google
 * link's viewport, "/@lat,lon", is where the map was looked at, not the place: [Confidence.GUESS], which always asks the
 * driver). A name with no coordinates is a [Result.Search]; a link that cannot be read is a [Result.Unresolved] that
 * says why. The only network step is the short link's own redirect (maps.app.goo.gl, maps.apple/p/...): one request per
 * hop, its Location header read and nothing else, never the page behind it, whose body holds the requester's region and
 * not the place (companion.md, measured). A browser's User-Agent gets Google's preview page instead of the redirect: an
 * app's is sent ([USER_AGENT]). Only the hosts in [isShort] are ever asked, over https.
 *
 * No Android here: only java.net and strings.
 */
internal object ShareParser {
    enum class Confidence { EXACT, GUESS }

    sealed class Result {
        /** [src]: where it came from (share.apple, share.google, share.waze, share.geo, share.text), for the log and for what may be kept. */
        class Found(val lat: Double, val lon: Double, val name: String, val detail: String, val src: String, val confidence: Confidence) : Result()
        /** A name, no coordinates: to search for, and let him pick. */
        class Search(val q: String, val src: String) : Result()
        /**
         * A link that gave no place: [reason] dead_link (the short link answers 404), no_redirect (another answer),
         * network (not reached), not_a_map (a link to something else), no_place (a map's link that states none).
         * [q]: the text shared beside it, if any, to search for instead.
         */
        class Unresolved(val reason: String, val url: String, val q: String?) : Result()
        class Nothing(val reason: String) : Result()
    }

    /** One request to a short link: its status and Location, redirects not followed. Throws when it cannot be reached. */
    fun interface Fetch { fun hop(url: String): Hop }
    class Hop(val code: Int, val location: String?)

    const val USER_AGENT = "AMRI (car head unit)"
    private const val MAX_HOPS = 2
    private const val MAX_TEXT = 4096
    private const val MAX_NAME = 120

    /** The real thing: a GET, no redirect followed, nothing read. A short link answers in well under a second. */
    val network = Fetch { url ->
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.instanceFollowRedirects = false
            c.connectTimeout = 3000
            c.readTimeout = 3000
            c.setRequestProperty("User-Agent", USER_AGENT)
            c.setRequestProperty("Accept", "*/*")
            Hop(c.responseCode, c.getHeaderField("Location"))
        } finally {
            c.disconnect()
        }
    }

    /** [text]: all that was shared, the link included. [fetch] null: no network, a short link stays unresolved. */
    fun parse(text: String, fetch: Fetch? = network): Result {
        val clean = normalize(text.take(MAX_TEXT))
        if (clean.isBlank()) return Result.Nothing("empty")
        val links = urls(clean)
        val around = aroundLinks(clean)
        var fallback: Result? = null
        for (u in links) {
            val r = fromUrl(u, around, fetch, 0)
            if (r is Result.Found) return r
            if (fallback == null || (fallback is Result.Unresolved && r is Result.Search)) fallback = r
        }
        // no link that gave a place: coordinates in the text, then what the links said, then a name
        coordinates(URL_RE.replace(clean, "\n"))?.let { (lat, lon, rest) ->
            return Result.Found(lat, lon, nameIn(rest), "", "share.text", Confidence.EXACT)
        }
        fallback?.let { return it }
        val line = clean.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        return if (line.length < 2) Result.Nothing("empty") else Result.Search(line.take(MAX_NAME), "share.text")
    }

    // ------------------------------------------------------------------------------------------ the text
    /** Western digits, a plain comma and point, no direction marks: what the phone's Arabic keyboard and apps put in. */
    fun normalize(s: String): String {
        val b = StringBuilder(s.length)
        for (ch in s) {
            when (ch) {
                in '٠'..'٩' -> b.append('0' + (ch - '٠'))
                in '۰'..'۹' -> b.append('0' + (ch - '۰'))
                '،' -> b.append(',')
                '٫' -> b.append('.')
                '٬' -> {}
                ' ', ' ', ' ' -> b.append(' ')
                '‎', '‏', '؜', '​', '﻿', in '‪'..'‮', in '⁦'..'⁩' -> {}
                '\r' -> {}
                else -> b.append(ch)
            }
        }
        return b.toString().trim()
    }

    private val URL_RE = Regex("""(?i)(?:https?://|geo:|\bmaps\.app\.goo\.gl/|\bgoo\.gl/maps|\bmaps\.apple/|\bmaps\.apple\.com/|\bmaps\.google\.[a-z.]+/|\bwww\.google\.[a-z.]+/maps|\bwaze\.com/ul)[^\s<>"'«»]*""")

    private fun urls(s: String): List<String> = URL_RE.findAll(s).map { m ->
        val u = m.value.trimEnd('.', ',', ';', ':', ')', ']', '!', '?', '،')
        if (u.startsWith("http", ignoreCase = true) || u.startsWith("geo:", ignoreCase = true)) u else "https://$u"
    }.toList()

    /** The shared text without its links: mostly the place's name ("Al Rashid Mall\nhttps://maps.app.goo.gl/..."). */
    private fun aroundLinks(s: String): String = nameIn(URL_RE.replace(s, "\n"))

    private fun nameIn(rest: String): String =
        rest.lineSequence().map { it.trim(' ', ',', ':', '-', '–', '(', ')') }.firstOrNull { it.any(Char::isLetter) }.orEmpty().take(MAX_NAME)

    private val DECIMAL = Regex("""(?<![\d.])([-+]?\d{1,2}\.\d{2,})\s*[,;\s/]\s*([-+]?\d{1,3}\.\d{2,})(?![\d.])""")
    private val HEMI = Regex("""(?i)(?<![\d.])(\d{1,2}\.\d{2,})\s*°?\s*([NS])\s*[,\s]\s*(\d{1,3}\.\d{2,})\s*°?\s*([EW])\b""")
    private val HEMI_FIRST = Regex("""(?i)\b([NS])\s*(\d{1,2}\.\d{2,})\s*°?\s*[,\s]\s*([EW])\s*(\d{1,3}\.\d{2,})""")
    private val DMS = Regex("""(?i)(\d{1,2})\s*[°\s]\s*(\d{1,2})\s*['′ʼ\s]\s*(\d{1,2}(?:\.\d+)?)\s*(?:["″]|'')?\s*([NSشج])[^\s,\d]*\s*[,\s]\s*(\d{1,3})\s*[°\s]\s*(\d{1,2})\s*['′ʼ\s]\s*(\d{1,2}(?:\.\d+)?)\s*(?:["″]|'')?\s*([EWشغ])""")

    /** Coordinates stated in free text, and the text without them. */
    private fun coordinates(s: String): Triple<Double, Double, String>? {
        DMS.find(s)?.let { m ->
            val g = m.groupValues
            val lat = dms(g[1], g[2], g[3]) * (if (g[4].uppercase() == "S" || g[4] == "ج") -1 else 1)
            val lon = dms(g[5], g[6], g[7]) * (if (g[8].uppercase() == "W" || g[8] == "غ") -1 else 1)
            if (valid(lat, lon)) return Triple(lat, lon, s.removeRange(m.range))
        }
        HEMI.find(s)?.let { m ->
            val g = m.groupValues
            val lat = g[1].toDouble() * (if (g[2].uppercase() == "S") -1 else 1)
            val lon = g[3].toDouble() * (if (g[4].uppercase() == "W") -1 else 1)
            if (valid(lat, lon)) return Triple(lat, lon, s.removeRange(m.range))
        }
        HEMI_FIRST.find(s)?.let { m ->
            val g = m.groupValues
            val lat = g[2].toDouble() * (if (g[1].uppercase() == "S") -1 else 1)
            val lon = g[4].toDouble() * (if (g[3].uppercase() == "W") -1 else 1)
            if (valid(lat, lon)) return Triple(lat, lon, s.removeRange(m.range))
        }
        DECIMAL.find(s)?.let { m ->
            val lat = m.groupValues[1].toDouble()
            val lon = m.groupValues[2].toDouble()
            if (valid(lat, lon)) return Triple(lat, lon, s.removeRange(m.range))
        }
        return null
    }

    private fun dms(d: String, m: String, s: String) = d.toDouble() + m.toDouble() / 60 + s.toDouble() / 3600

    private fun valid(lat: Double, lon: Double) = lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0)

    private val PAIR = Regex("""^\s*(?:loc:)?\s*([-+]?\d{1,2}(?:\.\d+)?)\s*,\s*([-+]?\d{1,3}(?:\.\d+)?)\s*(?:\((.*)\))?\s*$""")

    /** "lat,lon", "loc:lat,lon" or "lat,lon(Label)" as a link's value: the whole value, not a part of it. */
    private fun pair(v: String?): Triple<Double, Double, String>? {
        val m = PAIR.find(v ?: return null) ?: return null
        val lat = m.groupValues[1].toDoubleOrNull() ?: return null
        val lon = m.groupValues[2].toDoubleOrNull() ?: return null
        return if (valid(lat, lon)) Triple(lat, lon, m.groupValues[3].trim()) else null
    }

    // ------------------------------------------------------------------------------------------ the links
    private class Link(val raw: String, val scheme: String, val host: String, val path: String, val query: Map<String, String>)

    private fun decode(s: String): String = try { URLDecoder.decode(s, "UTF-8") } catch (_: Throwable) { s }

    /** By hand: a shared link may hold raw Arabic and spaces, which java.net.URI refuses. */
    private fun link(u: String): Link? {
        val scheme = u.substringBefore("://", "").lowercase()
        if (scheme != "http" && scheme != "https") return null
        val rest = u.substringAfter("://").substringBefore('#')
        val hostPort = rest.substringBefore('/').substringBefore('?').substringAfter('@')
        val host = hostPort.substringBefore(':').lowercase()
        val afterHost = rest.substring(rest.indexOf(hostPort) + hostPort.length)
        val path = afterHost.substringBefore('?')
        val query = LinkedHashMap<String, String>()
        afterHost.substringAfter('?', "").split('&').forEach { kv ->
            if (kv.isEmpty()) return@forEach
            val k = kv.substringBefore('=')
            // (the first value of a name counts: a later one is tracking or a repeat)
            if (k !in query) query[k] = decode(kv.substringAfter('=', ""))
        }
        return if (host.isEmpty()) null else Link(u, scheme, host, path, query)
    }

    /** A place's or a query's name, tidy: "Al Rashid Mall, King Khalid St" to the name and the rest. */
    private fun split(raw: String): Pair<String, String> {
        val name = normalize(raw)
        val cut = name.indexOf(',')
        if (cut <= 0 || cut >= name.length - 1) return name.take(MAX_NAME) to ""
        return name.substring(0, cut).trim().take(MAX_NAME) to name.substring(cut + 1).trim().take(MAX_NAME * 2)
    }

    private fun isGoogleHost(h: String) = Regex("""^(?:www\.|maps\.)?google\.[a-z]{2,3}(?:\.[a-z]{2})?$""").matches(h)

    private fun isShort(l: Link): Boolean = when (l.host) {
        "maps.app.goo.gl", "share.google", "maps.apple" -> true
        "goo.gl", "www.goo.gl", "app.goo.gl" -> l.path.startsWith("/maps") || l.host == "app.goo.gl"
        "g.co" -> l.path.startsWith("/kgs")
        else -> false
    }

    private fun srcOf(l: Link) = if (l.host == "maps.apple" || l.host.endsWith("maps.apple.com")) "share.apple" else "share.google"

    private fun fromUrl(u: String, around: String, fetch: Fetch?, hops: Int): Result {
        if (u.startsWith("geo:", ignoreCase = true)) return geo(u, around)
        val l = link(u) ?: return Result.Unresolved("not_a_map", u, around.ifEmpty { null })
        // links inside links: a short link's own, Google's consent page, a redirect wrapper, WhatsApp's "send this"
        (l.query["link"] ?: l.query["continue"]?.takeIf { l.host.startsWith("consent.") }
            ?: l.query["q"]?.takeIf { l.path == "/url" && isGoogleHost(l.host) })?.takeIf { it.startsWith("http") }
            ?.let { if (hops <= MAX_HOPS) return fromUrl(it, around, fetch, hops + 1) }
        if (l.host == "wa.me" || l.host == "api.whatsapp.com") {
            val inner = l.query["text"].orEmpty()
            if (inner.isNotBlank() && hops <= MAX_HOPS) return parse(inner, fetch)
            return Result.Unresolved("not_a_map", u, around.ifEmpty { null })
        }
        return when {
            isShort(l) -> follow(l, around, fetch, hops)
            l.host == "maps.apple.com" || l.host.endsWith(".maps.apple.com") -> apple(l, around)
            isGoogleHost(l.host) && (l.path.startsWith("/maps") || l.host.startsWith("maps.")) -> google(l, around)
            l.host == "waze.com" || l.host.endsWith(".waze.com") -> waze(l, around)
            else -> Result.Unresolved("not_a_map", u, around.ifEmpty { null })
        }
    }

    /** One request, its Location read, and that link parsed as if it had been the one shared. */
    private fun follow(l: Link, around: String, fetch: Fetch?, hops: Int): Result {
        val q = around.ifEmpty { null }
        if (fetch == null) return Result.Unresolved("network", l.raw, q)
        if (hops >= MAX_HOPS) return Result.Unresolved("no_redirect", l.raw, q)
        val secure = if (l.scheme == "https") l.raw else "https://" + l.raw.substringAfter("://")
        val hop = try { fetch.hop(secure) } catch (_: Throwable) { return Result.Unresolved("network", l.raw, q) }
        val to = hop.location?.takeIf { hop.code in 300..399 && it.isNotBlank() }
            ?: return Result.Unresolved(if (hop.code == 404 || hop.code == 410) "dead_link" else "no_redirect", l.raw, q)
        val next = if (to.startsWith("/")) "https://${l.host}$to" else to
        val r = fromUrl(next, around, fetch, hops + 1)
        // (a short link that leads elsewhere: the link he shared is what the report names)
        return if (r is Result.Unresolved) Result.Unresolved(r.reason, l.raw, q) else r
    }

    private fun apple(l: Link, around: String): Result {
        val q = l.query
        val label = (q["name"] ?: q["q"]?.takeIf { pair(it) == null } ?: "").trim()
        val detail = q["address"].orEmpty().trim()
        for (key in listOf("coordinate", "ll", "q", "destination", "daddr")) {
            pair(q[key])?.let { (lat, lon, inner) ->
                val name = label.ifEmpty { inner }.ifEmpty { around }
                return Result.Found(lat, lon, name.take(MAX_NAME), detail.take(MAX_NAME * 2), "share.apple", Confidence.EXACT)
            }
        }
        val words = label.ifEmpty { (q["destination"] ?: q["daddr"] ?: q["query"] ?: detail).trim() }
        // (a place-id alone needs Apple's own lookup, on the phone: there is no coordinate here to read)
        return if (words.isNotEmpty()) Result.Search(words.take(MAX_NAME), "share.apple")
        else Result.Unresolved("no_place", l.raw, around.ifEmpty { null })
    }

    private val PLACE_PIN = Regex("""!3d(-?\d+(?:\.\d+)?)!4d(-?\d+(?:\.\d+)?)""")
    private val PATH_PAIR = Regex("""/(-?\d{1,2}\.\d+),\+?(-?\d{1,3}\.\d+)(?:/|$)""")
    private val VIEWPORT = Regex("""/@(-?\d{1,2}\.\d+),(-?\d{1,3}\.\d+)""")

    private fun google(l: Link, around: String): Result {
        val q = l.query
        val path = decode(l.path.replace("+", "%20"))
        val inData = (l.path + "?" + l.raw.substringAfter('?', ""))
        val fromPath = Regex("""/place/([^/]+)""").find(path)?.groupValues?.get(1)?.takeIf { pair(it) == null }
        val lastDir = if (path.contains("/dir/")) path.substringAfter("/dir/").split('/').lastOrNull { it.isNotBlank() && !it.startsWith("@") && !it.startsWith("data=") } else null
        val fromSearch = Regex("""/search/([^/]+)""").find(path)?.groupValues?.get(1)?.takeIf { pair(it) == null }
        val named = listOfNotNull(fromPath, q["q"]?.takeIf { pair(it) == null }, q["query"]?.takeIf { pair(it) == null },
            fromSearch, q["destination"]?.takeIf { pair(it) == null }, q["daddr"]?.takeIf { pair(it) == null },
            lastDir?.takeIf { pair(it) == null }).firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val (name, detail) = split(named.ifEmpty { around })
        // the pin of the place itself, the last one: the data part can name the viewport's as well
        PLACE_PIN.findAll(inData).lastOrNull()?.let { m ->
            val lat = m.groupValues[1].toDouble()
            val lon = m.groupValues[2].toDouble()
            if (valid(lat, lon)) return Result.Found(lat, lon, name, detail, "share.google", Confidence.EXACT)
        }
        for (key in listOf("q", "query", "ll", "destination", "daddr")) {
            pair(q[key])?.let { (lat, lon, inner) ->
                val n = inner.ifEmpty { name }
                return Result.Found(lat, lon, n, if (inner.isEmpty()) detail else "", "share.google", Confidence.EXACT)
            }
        }
        pair(lastDir)?.let { (lat, lon, _) -> return Result.Found(lat, lon, name, detail, "share.google", Confidence.EXACT) }
        PATH_PAIR.find(path.substringBefore("/@") + "/")?.let { m ->
            val lat = m.groupValues[1].toDouble()
            val lon = m.groupValues[2].toDouble()
            if (valid(lat, lon)) return Result.Found(lat, lon, name, detail, "share.google", Confidence.EXACT)
        }
        // where the map happened to be looked at: the weakest there is, and it always asks
        VIEWPORT.find(path)?.let { m ->
            val lat = m.groupValues[1].toDouble()
            val lon = m.groupValues[2].toDouble()
            if (valid(lat, lon)) return Result.Found(lat, lon, name, detail, "share.google", Confidence.GUESS)
        }
        return if (named.isNotEmpty()) Result.Search(named.take(MAX_NAME), "share.google")
        else Result.Unresolved("no_place", l.raw, around.ifEmpty { null })
    }

    private fun waze(l: Link, around: String): Result {
        pair(l.query["ll"] ?: l.query["to"]?.removePrefix("ll."))?.let { (lat, lon, _) ->
            return Result.Found(lat, lon, around, "", "share.waze", Confidence.EXACT)
        }
        Regex("""/ul/h([0-9a-z]+)""").find(l.path)?.let { m ->
            geohash(m.groupValues[1])?.let { (lat, lon) -> return Result.Found(lat, lon, around, "", "share.waze", Confidence.EXACT) }
        }
        val q = l.query["q"].orEmpty().trim()
        return if (q.isNotEmpty()) Result.Search(q.take(MAX_NAME), "share.waze")
        else Result.Unresolved("no_place", l.raw, around.ifEmpty { null })
    }

    private const val BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"

    /** The centre of a geohash cell (Waze's short links). */
    private fun geohash(h: String): Pair<Double, Double>? {
        var latLo = -90.0
        var latHi = 90.0
        var lonLo = -180.0
        var lonHi = 180.0
        var even = true
        for (ch in h) {
            val v = BASE32.indexOf(ch)
            if (v < 0) return null
            for (bit in 4 downTo 0) {
                val on = v ushr bit and 1 == 1
                if (even) {
                    val mid = (lonLo + lonHi) / 2
                    if (on) lonLo = mid else lonHi = mid
                } else {
                    val mid = (latLo + latHi) / 2
                    if (on) latLo = mid else latHi = mid
                }
                even = !even
            }
        }
        val lat = (latLo + latHi) / 2
        val lon = (lonLo + lonHi) / 2
        return if (valid(lat, lon)) lat to lon else null
    }

    /** geo:lat,lon[;u=..][?q=...]; "geo:0,0?q=..." has its place, or only a name, in q. */
    private fun geo(u: String, around: String): Result {
        val body = u.substring(4).removePrefix("//")
        val at = body.substringBefore('?').substringBefore(';')
        val q = decode(body.substringAfter('?', "").split('&').firstOrNull { it.startsWith("q=") }?.substring(2)?.replace("+", "%20").orEmpty()).trim()
        pair(q)?.let { (lat, lon, label) -> return Result.Found(lat, lon, label.ifEmpty { around }.take(MAX_NAME), "", "share.geo", Confidence.EXACT) }
        val here = pair(at)
        return when {
            here != null && q.isEmpty() -> Result.Found(here.first, here.second, around, "", "share.geo", Confidence.EXACT)
            // a name near a point: what the point is does not say that this is the place
            here != null -> Result.Found(here.first, here.second, q.take(MAX_NAME), "", "share.geo", Confidence.GUESS)
            q.isNotEmpty() -> Result.Search(q.take(MAX_NAME), "share.geo")
            else -> Result.Unresolved("no_place", u, around.ifEmpty { null })
        }
    }
}
