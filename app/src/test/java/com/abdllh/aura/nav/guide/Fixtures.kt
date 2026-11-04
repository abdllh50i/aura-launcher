package com.abdllh.aura.nav.guide

import com.abdllh.aura.nav.LatLon

/** One reference trip, as tools/mapbuild/tests/guide_fixtures.py cut it from a real answer and places.db. */
internal class Trip(val id: String, val label: String, val reference: Boolean, val from: LatLon, val to: LatLon, val answer: GuideAnswer, val world: FakeWorld) {
    val route: GuideRoute get() = answer.routes[0]
    fun guidance(arabic: Boolean, w: RouteWorld = world) = Guidance.build(route, arabic, w, answer.endSnapMetres)
}

/** What the search part answers from places.db, over the rows the cutter kept. */
internal class FakeWorld(val spots: List<SpotRow>, val gates: List<GateRow>, val pois: List<PoiRow>, val zones: List<ZoneRow>, private val names: Map<String, String>) : RouteWorld {
    private fun in_(boxes: List<GeoBox>, p: LatLon) = boxes.any { it.contains(p) }
    override fun spots(boxes: List<GeoBox>) = spots.filter { in_(boxes, it.pos) }
    override fun pois(boxes: List<GeoBox>, cats: Set<String>, minRank: Int) = pois.filter { it.cat in cats && it.rank >= minRank && in_(boxes, it.pos) }
    override fun gates(boxes: List<GeoBox>) = gates.filter { in_(boxes, it.pos) }
    override fun zones(boxes: List<GeoBox>) = zones.filter { z -> boxes.any { it.touches(z.box) } }
    override fun arabicRoadName(latin: String, near: LatLon) = names[latin]
}

internal object Fixtures {
    private fun text(path: String): String =
        Fixtures::class.java.getResourceAsStream("/guide/$path")!!.use { String(it.readBytes(), Charsets.UTF_8) }

    fun raw(path: String): String = text(path)

    val ids: List<String> by lazy { text("index.txt").lines().filter { it.isNotBlank() } }

    private val cache = HashMap<String, Trip>()

    fun trip(id: String): Trip = cache.getOrPut(id) { read(id) }

    private fun at(v: List<Any?>) = LatLon(v[0] as Double, v[1] as Double)
    private fun int(v: Any?) = (v as Double).toInt()

    @Suppress("UNCHECKED_CAST")
    private fun read(id: String): Trip {
        val root = MiniJson.parse(text("$id.json"))
        val answer = GuideAnswer.of(root.obj("answer"))!!
        val edges = root.obj("edges")
        if (edges != null) answer.routes[0].facts = RoadFacts.of(edges, answer.routes[0].line)
        val w = root.obj("world")
        val spots = w.list("spots").map { r -> r as List<Any?>
            SpotRow(int(r[0]), LatLon(int(r[1]) / 1e6, int(r[2]) / 1e6), (r[3] as? Double)?.toInt(), (r[4] as? Double)?.toInt() ?: 0, int(r[5]) == 2, (r[6] as? Double)?.toInt() ?: 0)
        }
        val gates = w.list("gates").map { r -> r as List<Any?>
            GateRow(LatLon(int(r[0]) / 1e6, int(r[1]) / 1e6), int(r[2]), int(r[3]))
        }
        val pois = w.list("pois").map { r -> r as List<Any?>
            PoiRow((r[0] as Double).toLong(), r[1] as String, r[2] as? String, r[3] as String, LatLon(int(r[4]) / 1e6, int(r[5]) / 1e6), int(r[6]), int(r[7]) and 4 != 0)
        }
        val zones = w.list("zones").map { r -> r as List<Any?>
            ZoneRow(int(r[0]), int(r[1]), r[2] as? String, r[3] as? String, GeoBox(r[4] as Double, r[6] as Double, r[5] as Double, r[7] as Double),
                (r[8] as List<List<Double>>).map { c -> LatLon(c[1], c[0]) })
        }
        val names = (w.obj("names") ?: emptyMap()).mapValues { it.value as String }
        return Trip(id, root.str("label"), root.bool("reference"), at(root.list("from")), at(root.list("to")), answer, FakeWorld(spots, gates, pois, zones, names))
    }
}
