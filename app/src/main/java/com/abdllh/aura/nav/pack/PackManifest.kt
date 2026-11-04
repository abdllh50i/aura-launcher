package com.abdllh.aura.nav.pack

import org.json.JSONArray
import org.json.JSONObject

/** One gzip chunk: [url] beside the manifest, [bytes] long with hash [sha256], [raw] long unpacked, written at [offset] of its piece. */
class PackChunk(val url: String, val offset: Long, val raw: Long, val bytes: Long, val gz: Boolean, val sha256: String)

class PackPiece(val name: String, val size: Long, val sha256: String, val chunks: List<PackChunk>)

/** A notice or a licence text that goes with the data (NOTICE-PACK.md): kept beside the pieces, shown by the notices page. */
class PackNotice(val url: String, val bytes: Long, val sha256: String)

/** Why a manifest, a pack or a piece of work was refused or stopped. The screen says it in plain words: [PackManager.text]. */
enum class PackProblem { NET, CLOCK, NOT_PUBLISHED, SPACE, BAD_DATA, APP_TOO_OLD, PACK_TOO_OLD, BAD_MANIFEST, USB_GONE, USB_DAMAGED, NO_USB, DISK, TRIP }

internal class PackRefused(val why: PackProblem, message: String) : Exception(message)

/** A pack as manifest.json describes it (make_pack.py). Parsing refuses everything it does not fully understand: no half-read pack gets near a working map. */
class PackManifest(
    val id: String,
    val built: String,
    val osm: String,
    val valhalla: String,
    val normVersion: Int,
    val placesSchema: Int,
    val fonts: List<String>,
    val attribution: String,
    val pieces: List<PackPiece>,
    val notices: List<PackNotice>,
    /** Free space needed before starting (make_pack.py's needBytes, or more if the pieces call for it). */
    val needBytes: Long,
    /** The file as it came: kept in the staging folder, so a restart needs no server to know the chunks. */
    val text: String
) {
    val diskBytes: Long = pieces.sumOf { it.size }
    /** What must be fetched or copied: every chunk and every notice. */
    val downloadBytes: Long = pieces.sumOf { p -> p.chunks.sumOf { it.bytes } } + notices.sumOf { it.bytes }
    val chunkCount: Int = pieces.sumOf { it.chunks.size }
    fun piece(name: String): PackPiece? = pieces.firstOrNull { it.name == name }
    /** Are these the same bytes as [o]? Its text can differ: a stick's manifest read through the root shell has no line ends to lose, but it is not the file itself. */
    fun sameAs(o: PackManifest): Boolean = id == o.id && pieces.size == o.pieces.size && pieces.all { p -> o.piece(p.name)?.sha256 == p.sha256 } &&
        pieces.all { p -> o.piece(p.name)?.chunks?.map { it.sha256 } == p.chunks.map { it.sha256 } }

    companion object {
        /** A manifest is small (six kilobytes): anything over this is not one (a portal's page, a wrong file). */
        const val MAX_TEXT = 1024 * 1024

        /** The pack [text] describes, for the app with versionCode [app]; [PackRefused] with the plain reason if it is not for this app or not whole. */
        fun parse(text: String, app: Long): PackManifest {
            val o = try {
                JSONObject(text)
            } catch (t: Throwable) {
                throw PackRefused(PackProblem.BAD_MANIFEST, "not JSON: $t")
            }
            val format = o.optInt("format", -1)
            if (format != PackRules.FORMAT) throw PackRefused(PackProblem.APP_TOO_OLD, "format $format")
            when (PackRules.fit(app, o.optLong("minApp", 0L), o.optLong("maxApp", 0L))) {
                PackRules.Fit.APP_TOO_OLD -> throw PackRefused(PackProblem.APP_TOO_OLD, "minApp ${o.optLong("minApp")} > $app")
                PackRules.Fit.PACK_TOO_OLD -> throw PackRefused(PackProblem.PACK_TOO_OLD, "maxApp ${o.optLong("maxApp")} < $app")
                PackRules.Fit.OK -> { }
            }
            try {
                return read(o, text)
            } catch (r: PackRefused) {
                throw r
            } catch (t: Throwable) { // a missing field, a wrong type
                throw PackRefused(PackProblem.BAD_MANIFEST, "$t")
            }
        }

        private fun bad(why: String): Nothing = throw PackRefused(PackProblem.BAD_MANIFEST, why)

        private fun read(o: JSONObject, text: String): PackManifest {
            val id = o.getString("id")
            if (!PackRules.safeName(id)) bad("id $id")
            val seen = HashSet<String>()
            fun name(s: String): String {
                if (!PackRules.safeName(s)) bad("name $s")
                if (!seen.add(s)) bad("twice: $s")
                return s
            }
            val pieces = ArrayList<PackPiece>()
            val arr = o.getJSONArray("pieces")
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                val pname = name(p.getString("name"))
                val size = p.getLong("size")
                if (!PackRules.pieceSizeOk(size)) bad("$pname: size $size")
                val sha = p.getString("sha256").lowercase()
                if (!PackRules.isSha256(sha)) bad("$pname: hash")
                val cs = ArrayList<PackChunk>()
                val carr = p.getJSONArray("chunks")
                var at = 0L
                for (k in 0 until carr.length()) {
                    val c = carr.getJSONObject(k)
                    val url = name(c.getString("url"))
                    val bytes = c.getLong("bytes")
                    val gz = c.getBoolean("gz")
                    val raw = if (gz) c.getLong("raw") else c.optLong("raw", bytes)
                    if (!PackRules.chunkSizeOk(bytes, raw, gz)) bad("$url: size")
                    if (c.optLong("offset", at) != at) bad("$url: offset") // one after the other, no hole, no overlap
                    val h = c.getString("sha256").lowercase()
                    if (!PackRules.isSha256(h)) bad("$url: hash")
                    cs.add(PackChunk(url, at, raw, bytes, gz, h))
                    at += raw
                }
                if (at != size) bad("$pname: chunks make $at of $size")
                pieces.add(PackPiece(pname, size, sha, cs))
            }
            for (n in PackRules.NEEDED) if (pieces.none { it.name == n }) bad("no $n")
            val notices = ArrayList<PackNotice>()
            val narr = o.optJSONArray("notices") ?: JSONArray()
            for (i in 0 until narr.length()) {
                val n = narr.getJSONObject(i)
                val url = name(n.getString("url"))
                val bytes = n.getLong("bytes")
                if (bytes !in 1..(4L * 1024 * 1024)) bad("$url: size")
                val h = n.getString("sha256").lowercase()
                if (!PackRules.isSha256(h)) bad("$url: hash")
                notices.add(PackNotice(url, bytes, h))
            }
            val sources = o.optJSONObject("sources")
            val fonts = ArrayList<String>()
            o.optJSONArray("fonts")?.let { f -> for (i in 0 until f.length()) fonts.add(f.getString(i)) }
            return PackManifest(
                id = id,
                built = o.getString("built"),
                osm = sources?.optString("osm").orEmpty(),
                valhalla = sources?.optString("valhalla").orEmpty(),
                normVersion = o.optInt("normVersion", 0),
                placesSchema = o.optInt("placesSchema", 0),
                fonts = fonts,
                attribution = o.optString("attribution"),
                pieces = pieces,
                notices = notices,
                needBytes = PackRules.need(pieces.sumOf { it.size }, o.optLong("needBytes", 0L)),
                text = text
            )
        }
    }
}
