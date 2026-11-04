package com.abdllh.aura.nav.pack

import java.net.URL

/** The pack's rules with no Android in them, so the JVM tests run them (src/test). What they follow: tools/mapbuild/make_pack.py, maps.md section 15. */
internal object PackRules {
    /** The one manifest layout this app reads. Anything else is a pack made for a newer app. */
    const val FORMAT = 1
    /** What every pack must hold to work with nothing else on the unit (make_pack.py's NEEDED). */
    val NEEDED = listOf(PackPaths.BASE, PackPaths.ROUTE, PackPaths.PLACES, PackPaths.POIS, PackPaths.GLYPHS)
    /** A chunk before compression, and what make_pack.py adds on top of the pieces for needBytes: one chunk of work space, and 200 MB to spare. */
    const val CHUNK = 64L * 1024 * 1024
    const val MARGIN = 200L * 1024 * 1024
    /** While a chunk comes this much must stay free beside it: the home screen and Android write to this partition too. */
    const val FLOOR = 64L * 1024 * 1024
    private const val MAX_PIECE = 8L * 1024 * 1024 * 1024
    private const val MAX_CHUNK = 256L * 1024 * 1024
    /** The bytes kept before a drop are not all trusted: this much of the end is fetched again (the hash would catch it, at the price of a whole chunk). */
    const val OVERLAP = 64L * 1024
    /** Written data is pushed to the disk this often: a 64 MB flush at once stalls every other reader of the eMMC. */
    const val SYNC_EVERY = 8L * 1024 * 1024

    /** Files the pack manager keeps beside the pieces: a manifest may not name them. */
    private val RESERVED = setOf("manifest.json", "progress.json", "ok", "worked", "current")
    private val NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
    private val HEX = Regex("^[0-9a-f]{64}$")

    /** A manifest's names become file names: one folder down, nothing else (a stick or a server could otherwise write over the app's files). */
    fun safeName(s: String): Boolean = NAME.matches(s) && !s.contains("..") && s !in RESERVED && !s.endsWith(".part") && !s.endsWith(".tmp")

    fun isSha256(s: String): Boolean = HEX.matches(s)

    enum class Fit { OK, APP_TOO_OLD, PACK_TOO_OLD }

    /** [app] is the app's versionCode, the one number that keeps the order of the versions (app/build.gradle.kts); no [max] when 0. */
    fun fit(app: Long, min: Long, max: Long): Fit = when {
        min > 0 && app < min -> Fit.APP_TOO_OLD
        max > 0 && app > max -> Fit.PACK_TOO_OLD
        else -> Fit.OK
    }

    fun pieceSizeOk(size: Long) = size in 1..MAX_PIECE
    fun chunkSizeOk(bytes: Long, raw: Long, gz: Boolean) = bytes in 1..MAX_CHUNK && raw in 1..MAX_CHUNK && (gz || raw == bytes)

    /** All pieces unpacked + work space + margin; never less than what the manifest's own pieces call for. */
    fun need(rawBytes: Long, stated: Long): Long = maxOf(stated, rawBytes + CHUNK + MARGIN)

    /** Is there room? What the staging already took counts as found ([already]: it is in [need] and no longer in [free]). */
    fun fits(free: Long, need: Long, already: Long): Boolean = free + already >= need

    /**
     * A start's check, or a stopped job's going on? Once all pieces hold their room ([staged] is [disk]) what is still to come is only chunks' work space, and each
     * chunk checks that for itself. The whole need again (it holds a margin the start alone asks for) would stop an install that lacks nothing, after a pause or a trip.
     */
    fun mayStart(free: Long, need: Long, disk: Long, staged: Long): Boolean = staged >= disk || fits(free, need, staged)

    /**
     * Is this answer the chunk that was asked for? A hotspot's sign-in page answers 200 for anything. With a length, a wrong one tells; with none (chunked) only a page's type
     * tells, and a 200 of unknown length must not wipe what is kept: after [patience] refusals it is taken, for a proxy that does this to every answer. The hash judges it in the end.
     */
    fun bodyFits(code: Int, length: Long, left: Long, kept: Long, contentType: String?, refusals: Int, patience: Int = 3): Boolean {
        if (length >= 0) return length == left
        if (contentType != null && contentType.startsWith("text/", ignoreCase = true)) return false
        return !(code == 200 && kept > 0) || refusals >= patience
    }

    /** Can this be a manifest at all? A portal or a proxy's JSON error is no internet yet, not a pack for a newer app. */
    fun looksLikeManifest(text: String): Boolean = text.trimStart().startsWith("{") && text.contains("\"format\"") && text.contains("\"pieces\"")

    /**
     * Is a pack on offer worth starting, with [cur]rent in use? Newer, and another: a pack rebuilt under the same id counts as another, if its bytes differ ([sameBytes]).
     */
    fun worthOffering(curId: String?, curBuilt: String?, id: String, built: String, sameBytes: Boolean): Boolean =
        (curId != id || !sameBytes) && newer(built, curBuilt)

    /** After a drop: 2, 4, 8, 16, 32, then every 60 seconds. attempt counts from 1. */
    fun backoffMs(attempt: Int): Long {
        val n = attempt.coerceIn(1, 7)
        return (1000L shl n).coerceAtMost(60_000L)
    }

    /** Where to ask from when [have] bytes of a part are on the disk. */
    fun resumeAt(have: Long, overlap: Long = OVERLAP): Long = (have - overlap).coerceAtLeast(0L)

    class ContentRange(val start: Long, val end: Long, val total: Long)

    /** "bytes 100-199/200" or "bytes 100-199/&#42;" (total -1); null for anything else. */
    fun contentRange(s: String?): ContentRange? {
        val r = s?.trim() ?: return null
        if (!r.startsWith("bytes ", ignoreCase = true)) return null
        val body = r.substring(6).trim()
        val slash = body.indexOf('/')
        val dash = body.indexOf('-')
        if (slash <= 0 || dash <= 0 || dash > slash) return null
        val start = body.substring(0, dash).trim().toLongOrNull() ?: return null
        val end = body.substring(dash + 1, slash).trim().toLongOrNull() ?: return null
        val tot = body.substring(slash + 1).trim()
        val total = if (tot == "*") -1L else tot.toLongOrNull() ?: return null
        return ContentRange(start, end, total)
    }

    /** A 206 is added to the part only if it starts where the part ends, runs to the end of a chunk of the size the manifest says. */
    fun rangeFits(r: ContentRange?, from: Long, bytes: Long): Boolean =
        r != null && r.start == from && r.end == bytes - 1 && (r.total == -1L || r.total == bytes)

    /** Is a pack built at [built] newer than one built at [than]? make_pack.py's "2026-11-04T15:24Z" sorts as text. */
    fun newer(built: String, than: String?): Boolean = than == null || built > than

    /** Release builds: https only, on every hop. A debug build may use the test server. */
    fun urlAllowed(url: String, debug: Boolean): Boolean = url.startsWith("https://") || (debug && url.startsWith("http://"))

    /** A redirect's Location against the address that gave it. */
    fun resolve(from: String, location: String): String = URL(URL(from), location).toString()

    fun join(base: String, name: String): String = base.trimEnd('/') + "/" + name

    /**
     * "releases/latest/download/x" says 302 to "releases/download/<tag>/x": chunks are then fetched under that tag, so a newer release
     * published in the middle of a download does not change the bytes under the manifest in hand. Null when no hop says.
     */
    fun releaseBase(hops: List<String>): String? {
        for (h in hops) {
            val at = h.indexOf("/releases/download/")
            if (at < 0) continue
            val tagStart = at + "/releases/download/".length
            val tagEnd = h.indexOf('/', tagStart)
            if (tagEnd > tagStart) return h.substring(0, tagEnd + 1)
        }
        return null
    }

    fun percent(done: Long, total: Long): Int = if (total <= 0) 0 else (done * 100 / total).coerceIn(0L, 100L).toInt()

    fun noSpace(message: String?): Boolean = message != null && (message.contains("ENOSPC") || message.contains("No space left", ignoreCase = true))
}

/** Bytes a second over the last few seconds, for the screen. [add] and [rate] from any thread. */
internal class SpeedMeter(private val windowMs: Long = 4000L) {
    private var from = 0L
    private var count = 0L
    private var shown = 0L

    @Synchronized fun add(n: Long, now: Long) {
        if (from == 0L) from = now
        if (n > 0) count += n
        if (now - from >= windowMs) {
            shown = count * 1000 / (now - from)
            from = now
            count = 0
        }
    }

    @Synchronized fun rate(now: Long): Long {
        if (from != 0L && now - from > 2 * windowMs) { shown = 0; from = now; count = 0 } // nothing has come for a while
        return shown
    }

    @Synchronized fun reset() { from = 0; count = 0; shown = 0 }
}
