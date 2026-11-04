package com.abdllh.aura.nav.offline

/**
 * Inside the Kingdom or not ([KSA_RING], tools/mapbuild/polygon/ksa.poly): the tile server's choice between the pack and
 * the internet, and which credit stands under the map. The pack holds roads only inside this line (it was cut with it),
 * though its tiles go on over the rectangle around it for the sea's sake. No Android in here, so a unit test reads it.
 */
object KsaOutline {
    private val ring = KSA_RING
    private val n = ring.size / 2
    private val west: Double
    private val east: Double
    private val south: Double
    private val north: Double

    init {
        var w = 180.0; var e = -180.0; var s = 90.0; var nn = -90.0
        for (i in 0 until n) {
            val lon = ring[2 * i]; val lat = ring[2 * i + 1]
            if (lon < w) w = lon
            if (lon > e) e = lon
            if (lat < s) s = lat
            if (lat > nn) nn = lat
        }
        west = w; east = e; south = s; north = nn
    }

    /** Whether a point lies inside the outline (an even number of crossings to the east of it means outside). */
    fun contains(lat: Double, lon: Double): Boolean {
        if (lat < south || lat > north || lon < west || lon > east) return false
        var inside = false
        var j = n - 1
        for (i in 0 until n) {
            val xi = ring[2 * i]; val yi = ring[2 * i + 1]
            val xj = ring[2 * j]; val yj = ring[2 * j + 1]
            if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    /**
     * Whether any of the tile z/x/y lies inside the outline: then the pack answers for it. At the unit's slowest about
     * 300 short steps per request of a tile, next to a read from the disk: nothing is kept per tile.
     */
    fun touchesTile(z: Int, x: Int, y: Int): Boolean {
        val w = TileMath.lon(x.toDouble(), z)
        val e = TileMath.lon(x + 1.0, z)
        val nn = TileMath.lat(y.toDouble(), z)
        val s = TileMath.lat(y + 1.0, z)
        if (e < west || w > east || nn < south || s > north) return false
        var j = n - 1
        for (i in 0 until n) {
            if (crosses(ring[2 * j], ring[2 * j + 1], ring[2 * i], ring[2 * i + 1], w, s, e, nn)) return true
            j = i
        }
        // no edge passes through it: all of it inside, or all of it outside
        return contains((s + nn) / 2, (w + e) / 2)
    }

    /** Whether the segment a-b passes through the box (Liang-Barsky: what is left of it after each side's cut). */
    private fun crosses(ax: Double, ay: Double, bx: Double, by: Double, w: Double, s: Double, e: Double, nn: Double): Boolean {
        val dx = bx - ax
        val dy = by - ay
        var t0 = 0.0
        var t1 = 1.0
        fun clip(p: Double, q: Double): Boolean {
            if (p == 0.0) return q >= 0
            val r = q / p
            if (p < 0) { if (r > t1) return false; if (r > t0) t0 = r }
            else { if (r < t0) return false; if (r < t1) t1 = r }
            return true
        }
        return clip(-dx, ax - w) && clip(dx, e - ax) && clip(-dy, ay - s) && clip(dy, nn - ay)
    }
}
