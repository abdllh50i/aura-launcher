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
 * What the offline map holds: the Eastern Province of Saudi Arabia. Street level (zoom 14, the most detailed level of
 * the map data) where people live and drive, the main roads around at zoom 12, the whole province (and a margin) as an
 * overview; plus BRouter's routing data for the 5°x5° squares around it (Riyadh included, for the road there).
 */
object Region {
    class Box(val west: Double, val south: Double, val east: Double, val north: Double, val minZoom: Int, val maxZoom: Int)

    val BOXES = listOf(
        Box(45.00, 17.00, 56.00, 29.00, 0, 10),  // the province and around it: overview
        Box(48.30, 25.10, 50.40, 27.70, 11, 14), // Jubail, Ras Tanura, Qatif, Dammam, Khobar, Dhahran, Abqaiq, Al-Ahsa, Nairyah
        Box(45.85, 28.33, 46.10, 28.52, 11, 14), // Hafr Al-Batin
        Box(48.40, 28.35, 48.55, 28.48, 11, 14), // Khafji
        Box(47.62, 27.48, 47.76, 27.60, 11, 14), // Qaryat Al-Ulya
        Box(45.80, 27.00, 49.70, 28.60, 11, 12), // the roads north: Hafr Al-Batin, Khafji, Kuwait
        Box(47.00, 24.40, 49.60, 25.50, 11, 12), // the road to Riyadh (up to the edge of Riyadh, not the city)
        Box(49.00, 24.00, 50.90, 25.20, 11, 12)  // Haradh, Salwa
    )

    /** BRouter's routing data: lon 45..55, lat 20..30 (file names are the squares' south-west corners). */
    val SEGMENTS = listOf("E45_N25", "E50_N25", "E45_N20", "E50_N20")

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
