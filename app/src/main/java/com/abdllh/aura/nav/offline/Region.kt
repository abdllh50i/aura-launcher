package com.abdllh.aura.nav.offline

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/** Web-mercator tile numbers, and a tile packed into one Long (z, x, y). */
object TileMath {
    fun x(lon: Double, z: Int): Int = floor((lon + 180.0) / 360.0 * (1 shl z)).toInt().coerceIn(0, (1 shl z) - 1)

    fun y(lat: Double, z: Int): Int {
        val r = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
        return floor((1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * (1 shl z)).toInt().coerceIn(0, (1 shl z) - 1)
    }

    /** The longitude of a tile's west edge (x may be fractional: a point inside the tile). */
    fun lon(x: Double, z: Int): Double = x / (1 shl z) * 360.0 - 180.0

    /** The latitude of a tile's north edge (y may be fractional). */
    fun lat(y: Double, z: Int): Double {
        val n = PI - 2.0 * PI * y / (1 shl z)
        return Math.toDegrees(kotlin.math.atan(kotlin.math.sinh(n)))
    }

    fun key(z: Int, x: Int, y: Int): Long = (z.toLong() shl 48) or (x.toLong() shl 24) or y.toLong()
    fun z(k: Long): Int = (k ushr 48).toInt()
    fun x(k: Long): Int = ((k ushr 24) and 0xFFFFFF).toInt()
    fun y(k: Long): Int = (k and 0xFFFFFF).toInt()
}

/**
 * What the offline map holds, since 1.8: Jubail, and Dammam with Dhahran and Khobar, at street level (zoom 14, the
 * most detailed level of the map data: every street and every place of the tiles), King Fahd airport, the main
 * roads between them, and the country around as an overview; plus BRouter's routing data for the two 5°x5° squares
 * they lie in. Up to 1.7 it was the whole Eastern Province (Qatif, Al-Ahsa, Hafr Al-Batin, Khafji, the roads to
 * Kuwait, Riyadh and Salwa, about 150 MB); the owner asked for the three cities only, and a smaller download.
 */
object Region {
    class Box(val west: Double, val south: Double, val east: Double, val north: Double, val minZoom: Int, val maxZoom: Int)

    /** The area's name for the store: a map kept for another area is trimmed to this one ([OfflineMaps]). */
    const val ID = "jubail-dammam-khobar-1"

    val BOXES = listOf(
        Box(46.00, 24.00, 52.00, 29.00, 0, 10),  // around them: overview
        Box(49.40, 26.85, 49.82, 27.25, 11, 14), // Jubail: the town, the industrial city, Fanateer, the ports
        Box(49.88, 26.10, 50.30, 26.52, 11, 14), // Dammam, Dhahran, Khobar (Half Moon Bay up to Dammam's port)
        Box(49.72, 26.40, 49.90, 26.53, 11, 14), // King Fahd airport
        Box(49.50, 26.40, 50.20, 26.95, 11, 12)  // the roads between them (street level along the main ones: roadTiles)
    )

    /** BRouter's routing data: lon 45..55, lat 25..30 (file names are the squares' south-west corners). */
    val SEGMENTS = listOf("E45_N25", "E50_N25")

    /** The routing square a point falls in, e.g. "E50_N25". */
    fun segmentOf(lat: Double, lon: Double): String {
        val lo = floor(lon / 5.0).toInt() * 5
        val la = floor(lat / 5.0).toInt() * 5
        return (if (lo < 0) "W${-lo}" else "E$lo") + "_" + (if (la < 0) "S${-la}" else "N$la")
    }

    /** Debug builds' test area (setprop debug.aura.offline small): Jubail at street level, the province coarsely. */
    val TEST_BOXES = listOf(
        Box(45.00, 17.00, 56.00, 29.00, 0, 8),
        Box(49.55, 26.90, 49.72, 27.12, 11, 14),
        Box(49.70, 26.50, 50.05, 26.90, 11, 12)  // a stretch of the Jubail - Dammam highway: road tiles
    )

    /** Every tile of the area, without repeats (the boxes overlap). */
    fun tiles(small: Boolean = false): LongArray {
        val set = HashSet<Long>(32_768)
        for (b in if (small) TEST_BOXES else BOXES) for (z in b.minZoom..b.maxZoom) {
            val x0 = TileMath.x(b.west, z)
            val x1 = TileMath.x(b.east, z)
            val y0 = TileMath.y(b.north, z)
            val y1 = TileMath.y(b.south, z)
            for (x in x0..x1) for (y in y0..y1) set.add(TileMath.key(z, x, y))
        }
        // most general first: the overview is usable early, street level comes last
        return set.toLongArray().also { it.sort() }
    }

    /** The roads between the towns: classes of the map data's transportation layer that get street-level tiles. */
    private val MAIN_ROADS = setOf("motorway", "trunk", "primary", "secondary")

    /**
     * Street-level tiles (zoom 13 and 14, with their neighbours) along the main roads of the boxes that are only
     * downloaded down to zoom 12: found in those zoom-12 tiles themselves. Without them the map would be empty around
     * the car on the highways between the towns, where navigation zooms in.
     */
    fun roadTiles(st: TileStore, small: Boolean = false): LongArray {
        val boxes = (if (small) TEST_BOXES else BOXES).filter { it.maxZoom in 11..13 }
        val out = HashSet<Long>()
        for (b in boxes) {
            val x0 = TileMath.x(b.west, 12); val x1 = TileMath.x(b.east, 12)
            val y0 = TileMath.y(b.north, 12); val y1 = TileMath.y(b.south, 12)
            for (tx in x0..x1) for (ty in y0..y1) {
                val data = try { st.tile(TileMath.key(12, tx, ty)) } catch (_: Throwable) { null } ?: continue
                if (data.isEmpty()) continue
                val (extent, lines) = try {
                    Mvt.lines(data, "transportation") { it["class"] in MAIN_ROADS }
                } catch (_: Throwable) { continue }
                for (line in lines) {
                    var px = Double.NaN; var py = Double.NaN
                    var i = 0
                    while (i + 1 < line.size) {
                        val x = tx + line[i] / extent.toDouble()
                        val y = ty + line[i + 1] / extent.toDouble()
                        if (!px.isNaN()) {
                            // points along the segment, closer than a zoom-14 tile (1/4 of a zoom-12 tile)
                            val steps = (maxOf(kotlin.math.abs(x - px), kotlin.math.abs(y - py)) * 16).toInt() + 1
                            for (s in 0..steps) mark(out, px + (x - px) * s / steps, py + (y - py) * s / steps)
                        } else mark(out, x, y)
                        px = x; py = y
                        i += 2
                    }
                }
            }
        }
        return out.toLongArray().also { it.sort() }
    }

    /** The zoom 13 and 14 tiles (with neighbours) around a point given in zoom-12 tile coordinates. */
    private fun mark(out: HashSet<Long>, x12: Double, y12: Double) {
        for ((z, f) in listOf(13 to 2.0, 14 to 4.0)) {
            val cx = kotlin.math.floor(x12 * f).toInt()
            val cy = kotlin.math.floor(y12 * f).toInt()
            for (dx in -1..1) for (dy in -1..1) out.add(TileMath.key(z, cx + dx, cy + dy))
        }
    }

    /** A point the offline map covers at street level (where offline search and the detailed map exist). */
    fun detailed(lat: Double, lon: Double): Boolean =
        BOXES.any { it.maxZoom >= 14 && lon in it.west..it.east && lat in it.south..it.north }
}
