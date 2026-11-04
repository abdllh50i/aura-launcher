package com.abdllh.aura.nav.guide

import com.abdllh.aura.nav.Polyline

/** One road of a route from `trace_attributes` (`shape_match: edge_walk` over the route's own line). [limit]: `speed_limit`, 0 when none. */
class RoadStretch(val from: Double, val to: Double, val limit: Int, val roadClass: String, val use: String, val bridge: Boolean, val tunnel: Boolean, val toll: Boolean, val endIndex: Int = -1)

/**
 * What the route answer does not say about its roads and `trace_attributes` does: limits, class, flyovers. Guidance works
 * without it, only plainer (no flyover in the words, no estimated limit, tracks and side roads inside a zone not seen). Found
 * by distance along the line, so it must come from this very line: one that does not end where the line ends is refused.
 */
class RoadFacts internal constructor(val stretches: List<RoadStretch>) {
    fun at(along: Double): RoadStretch? {
        var lo = 0
        var hi = stretches.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val s = stretches[mid]
            when {
                along < s.from -> hi = mid - 1
                along >= s.to -> lo = mid + 1
                else -> return s
            }
        }
        return stretches.lastOrNull()?.takeIf { along >= it.to }
    }

    companion object {
        /** The EDGE attributes to ask for, to go with `route-request.json`. */
        val ATTRIBUTES = listOf("edge.speed_limit", "edge.road_class", "edge.use", "edge.bridge", "edge.tunnel", "edge.toll", "edge.begin_shape_index", "edge.end_shape_index")

        fun parse(json: String, line: Polyline): RoadFacts? = try {
            of(MiniJson.parse(json) { k, _ -> k == "names" || k == "end_node" || k == "sign" }, line)
        } catch (_: Throwable) {
            null
        }

        internal fun of(root: Any?, line: Polyline): RoadFacts? {
            val edges = root.list("edges")
            if (edges.isEmpty() || edges.last().num("end_shape_index")?.toInt() != line.points.size - 1) return null
            val c = line.cumulative
            return RoadFacts(edges.mapNotNull { e ->
                val a = e.num("begin_shape_index")?.toInt() ?: return@mapNotNull null
                val b = e.num("end_shape_index")?.toInt() ?: return@mapNotNull null
                if (a !in c.indices || b !in c.indices) return@mapNotNull null
                RoadStretch(c[a], c[b], e.num("speed_limit")?.toInt() ?: 0, e.str("road_class"), e.str("use"), e.bool("bridge"), e.bool("tunnel"), e.bool("toll"), b)
            })
        }
    }
}

/** A flyover or a tunnel the route goes through, metres along it. */
class Structure(val tunnel: Boolean, val from: Double, val to: Double) {
    val length get() = to - from
}

internal object Structures {
    /** Under this a bridge is a culvert and a tunnel an underpass nobody would name. */
    private const val MIN_M = 60.0

    fun of(route: GuideRoute): List<Structure> {
        val out = ArrayList<Structure>()
        val facts = route.facts
        if (facts != null) {
            var open: RoadStretch? = null
            var start = 0.0
            var end = 0.0
            fun close() {
                val o = open ?: return
                if (end - start >= MIN_M) out.add(Structure(o.tunnel, start, end))
                open = null
            }
            for (s in facts.stretches) {
                val o = open
                if (o != null && o.tunnel == s.tunnel && o.bridge == s.bridge) { end = s.to; continue }
                close()
                if (s.bridge || s.tunnel) { open = s; start = s.from; end = s.to }
            }
            close()
            return out
        }
        // without it: the answer's own class, which knows tunnels and not bridges
        val line = route.line
        if (line.points.isEmpty()) return out
        var start = -1.0
        for (s in route.steps) for (c in s.crossings) {
            val here = if (c.lineIndex in line.cumulative.indices) line.cumulative[c.lineIndex] else continue
            val inside = "tunnel" in c.classes
            if (inside && start < 0) start = here
            else if (!inside && start >= 0) {
                if (here - start >= MIN_M) out.add(Structure(true, start, here))
                start = -1.0
            }
        }
        return out
    }
}

/** A speed limit for the sign, and whose word it is. */
class LimitNow(val kmh: Int, val source: Source) {
    enum class Source { MINE, KNOWN, ESTIMATED, NONE }
    /** The table's word for the sign: nothing for his own, "من الخريطة", "تقريبي". */
    val label: String? get() = when (source) {
        Source.KNOWN -> "limit.known"
        Source.ESTIMATED -> "limit.estimated"
        else -> null
    }
}

/**
 * Which limit the sign shows: the owner's own first, then OSM's known limit, then one estimated by the road's class, shown
 * as such: a guess must never read as the law (maps.md section 11). The table is what OSM says most often per class over the 56 test
 * trips, by length (motorway 140 on 932 km and 120 on 927, so the lower; trunk 100, primary 80), and common sense below that. To be
 * confirmed by him; no class means no number.
 */
object Limits {
    private val BY_CLASS = mapOf("motorway" to 120, "trunk" to 100, "primary" to 80, "secondary" to 60, "tertiary" to 50, "unclassified" to 40, "residential" to 40, "service_other" to 20)
    private val BY_USE = mapOf("ramp" to 60, "turn_channel" to 40, "parking_aisle" to 20, "living_street" to 20, "culdesac" to 20, "track" to 30)

    fun estimated(roadClass: String?, use: String? = null): Int = BY_USE[use] ?: BY_CLASS[roadClass] ?: 0

    /** [owner]: `MapExtras.limitAt`; [known]: the road's `speed_limit`, or `RouteLimits.at` on a BRouter route; 0 for none. */
    fun pick(owner: Int, known: Int, roadClass: String?, use: String? = null): LimitNow {
        if (owner > 0) return LimitNow(owner, LimitNow.Source.MINE)
        if (known > 0) return LimitNow(known, LimitNow.Source.KNOWN)
        val e = estimated(roadClass, use)
        return if (e > 0) LimitNow(e, LimitNow.Source.ESTIMATED) else LimitNow(0, LimitNow.Source.NONE)
    }

    fun at(route: GuideRoute, along: Double, owner: Int): LimitNow {
        val s = route.facts?.at(along)
        return pick(owner, s?.limit ?: 0, s?.roadClass ?: if (motorwayAt(route, along)) "motorway" else null, s?.use)
    }

    /** The answer's own class at a place along the line, where no facts are at hand. */
    private fun motorwayAt(route: GuideRoute, along: Double): Boolean {
        val c = route.line.cumulative
        var last: Crossing? = null
        for (s in route.steps) {
            if (s.at > along) break
            for (x in s.crossings) {
                if (x.lineIndex !in c.indices || c[x.lineIndex] > along) break
                last = x
            }
        }
        return last?.classes?.contains("motorway") == true
    }
}
