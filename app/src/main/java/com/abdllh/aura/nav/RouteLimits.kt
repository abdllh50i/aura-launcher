package com.abdllh.aura.nav

import kotlin.math.abs

/**
 * The speed limits along a route as OpenStreetMap has them: `maxspeed`, on 49-97 % of the driven length of real routes
 * here, because the highways are the tagged ones. They come in BRouter's answer (its `messages`; OSRM offers none, and the
 * tiles have no such field), and they are map data, not the owner's word: [RoadWatch] takes his limits ([MapExtras.limitAt])
 * first and these only where he drew none. A street with no tag has no limit here: nothing is guessed from its class.
 *
 * Found by place, not by distance along the route: a route from OSRM takes the limits of the BRouter answer that raced
 * it ([Router.route]), and the two lines need not be the same road all the way.
 */
class RouteLimits private constructor(private val pieces: List<Piece>) {
    private class Piece(val a: LatLon, val b: LatLon, val kmh: Int, val bearing: Double)

    /** How much road the limits cover (metres), for the log. */
    val metres: Double = pieces.sumOf { Geo.distance(it.a, it.b) }

    /**
     * The limit where the car is (0: none known). [heading]: the car's course when it moves. A route is driven one way, so
     * unlike the owner's streets a stretch only counts in its own direction: a way back on the other carriageway, or a
     * road crossing under it, does not lend its limit.
     */
    fun at(p: LatLon, heading: Double?): Int {
        var best = 0
        var bestD = REACH_M
        for (s in pieces) {
            // (a cheap box first: most of a route is far away)
            if (abs(s.a.lat - p.lat) > BOX_DEG && abs(s.b.lat - p.lat) > BOX_DEG && (s.a.lat - p.lat) * (s.b.lat - p.lat) > 0) continue
            if (abs(s.a.lon - p.lon) > BOX_DEG && abs(s.b.lon - p.lon) > BOX_DEG && (s.a.lon - p.lon) * (s.b.lon - p.lon) > 0) continue
            if (heading != null && abs(Geo.angleDiff(heading, s.bearing)) > ALONG_DEG) continue
            val d = Geo.project(p, s.a, s.b).second
            if (d < bestD) { bestD = d; best = s.kmh }
        }
        return best
    }

    /** One row of BRouter's `messages`: where its stretch of road ends (a point of the line), and that road's tags. */
    class Row(val end: LatLon, val tags: String)

    companion object {
        /** As near as the owner's streets ([MapExtras]): the middle of the road and a car in one of its lanes. */
        private const val REACH_M = 24.0
        private const val ALONG_DEG = 38.0
        private const val BOX_DEG = 0.001
        /** A row's end and the line's point are the same node of the same answer. */
        private const val SAME_POINT_M = 1.0
        /**
         * BRouter's lookup table (assets/offline/profiles2/lookups.dat) keeps values in buckets named after their first
         * value: 45 reads 40, 55 reads 50, 65 reads 60 and so on, always down, and the tens are what Saudi roads have. The
         * bucket "10" holds everything from 5 to 15: too rough to put on a sign. "urban", "rural" and values the table does
         * not know (140, say) come as words: no number, no sign.
         */
        private const val LOWEST_KMH = 20
        private const val HIGHEST_KMH = 200

        /** The limit in one row's tags for the direction it is driven in; 0 for none. */
        internal fun kmh(tags: String): Int {
            var any: String? = null
            var forward: String? = null
            var backward: String? = null
            var reverse = false
            for (kv in tags.split(' ')) {
                when {
                    kv.startsWith("maxspeed=") -> any = kv.substring(9)
                    kv.startsWith("maxspeed:forward=") -> forward = kv.substring(17)
                    kv.startsWith("maxspeed:backward=") -> backward = kv.substring(18)
                    kv == "reversedirection=yes" -> reverse = true
                }
            }
            val v = (if (reverse) backward else forward) ?: any
            return v?.toIntOrNull()?.takeIf { it in LOWEST_KMH..HIGHEST_KMH } ?: 0
        }

        /**
         * The limits of BRouter's answer: [points] its whole line, [rows] its `messages` in order, each stretch running from
         * where the row before ended. Null when no row has a limit. A row that does not end on the line (never seen; a
         * format this code does not know) ends the reading there: what is read so far is right, what follows cannot be placed.
         */
        fun of(points: List<LatLon>, rows: List<Row>): RouteLimits? {
            val out = ArrayList<Piece>()
            var i = 0
            for (r in rows) {
                var j = i
                while (j < points.size && Geo.distance(points[j], r.end) > SAME_POINT_M) j++
                if (j >= points.size) break
                val kmh = kmh(r.tags)
                if (kmh > 0) for (k in i until j) {
                    if (Geo.distance(points[k], points[k + 1]) > 0.3) out.add(Piece(points[k], points[k + 1], kmh, Geo.bearing(points[k], points[k + 1])))
                }
                i = j
            }
            return if (out.isEmpty()) null else RouteLimits(out)
        }
    }
}

/**
 * Which limit the sign shows: the owner's while there is one, else the map's, each held [holdMs] over a gap in its line (a
 * wide junction, a fix or two beside the road). His comes first also through such a gap: a stretch of his with a hole in it
 * must not flash the map's number for a moment.
 */
internal class LimitHold(private val holdMs: Long) {
    var kmh = 0
        private set
    /** [kmh] is OpenStreetMap's, not the owner's. */
    var fromMap = false
        private set
    private var owner = 0
    private var ownerAt = 0L
    private var map = 0
    private var mapAt = 0L

    fun update(ownerNow: Int, mapNow: Int, now: Long) {
        if (ownerNow > 0) { owner = ownerNow; ownerAt = now }
        else if (owner > 0 && now - ownerAt > holdMs) owner = 0
        if (mapNow > 0) { map = mapNow; mapAt = now }
        else if (map > 0 && now - mapAt > holdMs) map = 0
        fromMap = owner == 0 && map > 0
        kmh = if (owner > 0) owner else map
    }
}
