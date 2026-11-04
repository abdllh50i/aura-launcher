package com.abdllh.aura.nav.guide

import com.abdllh.aura.nav.LatLon
import com.abdllh.aura.nav.Polyline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The zones' rule with real road classes: real lines with their real trace_attributes (tools/mapbuild/tests/guide_zone_fixture.py). The golden file's trips have facts for only 6 of 30, and that hid this:
 * secondary roads counted as side roads inside the coarse rings, and an ordinary trip in Jubail town was told it enters a military area. Since the rings are OSM's own line (osm_roadside.py) the trips below went quiet
 * for a better reason: the town, Ithra and the hospital are outside the fence.
 */
class ZonesOnRealRoadsTest {
    @Suppress("UNCHECKED_CAST")
    private val doc = MiniJson.parse(Fixtures.raw("zones-real.json")) as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private val zones = (doc["zones"] as List<List<Any?>>).map { r ->
        ZoneRow((r[0] as Double).toInt(), (r[1] as Double).toInt(), r[2] as? String, r[3] as? String, GeoBox(r[4] as Double, r[6] as Double, r[5] as Double, r[7] as Double),
            (r[8] as List<List<Double>>).map { c -> LatLon(c[1], c[0]) })
    }

    @Suppress("UNCHECKED_CAST")
    private fun trip(id: String): GuideRoute {
        val t = (doc["trips"] as List<Map<String, Any?>>).first { it["id"] == id }
        val line = Polyline((t["line"] as List<List<Double>>).map { LatLon(it[0], it[1]) })
        val c = line.cumulative
        val stretches = (t["edges"] as List<List<Any?>>).map { e ->
            val a = (e[0] as Double).toInt()
            val b = (e[1] as Double).toInt()
            RoadStretch(c[a], c[b], 0, e[2] as String, e[3] as String, false, false, false, b)
        }
        return GuideRoute(line, emptyList(), line.length, 0.0, setOf("SA")).apply { facts = RoadFacts(stretches) }
    }

    private fun warned(id: String) = Guidance.build(trip(id), true, FakeWorld(emptyList(), emptyList(), emptyList(), zones, emptyMap())).warnings.map { it.kind }

    @Test fun ordinaryTripsInTownStaySilent() {
        // King Abdul Aziz street and Madeena Street, Jubail town: a tester's 374 m and 689 m "the road enters a military area".
        for (id in listOf("j6", "gate-jana", "khafji", "airport", "j1")) assertEquals(id, emptyList<TripWarning.Kind>(), warned(id))
    }

    @Test fun aRouteIntoABaseStillWarns() {
        // the air base itself, inside its fence
        assertEquals(listOf(TripWarning.Kind.MILITARY_END), warned("base-air"))
    }

    @Test fun whatTheCoarseRingsHeldIsOutsideTheFence() {
        // Ithra and the air base's hospital: false alarms the owner had been told of, gone with the 23-point ring
        assertEquals(emptyList<TripWarning.Kind>(), warned("ithra"))
        assertEquals(emptyList<TripWarning.Kind>(), warned("gate-air"))
    }

    /** Set ZONES_TRIPS to out/route-tests and ZONES_JSON to all rows of table zone as [[id,kind,name,en,s,n,w,e,ring],...]: every kept trip, with what it warns of. Not a test of the repo's data: a measure, printed. */
    @Suppress("UNCHECKED_CAST")
    @Test fun everyKeptTrip() {
        val root = System.getenv("ZONES_TRIPS")
        org.junit.Assume.assumeTrue(root != null)
        val all = (MiniJson.parse(java.io.File(System.getenv("ZONES_JSON")).readText(Charsets.UTF_8)) as List<List<Any?>>).map { r ->
            ZoneRow((r[0] as Double).toInt(), (r[1] as Double).toInt(), r[2] as? String, r[3] as? String, GeoBox(r[4] as Double, r[6] as Double, r[5] as Double, r[7] as Double),
                (r[8] as List<List<Double>>).map { c -> LatLon(c[1], c[0]) })
        }
        val world = FakeWorld(emptyList(), emptyList(), emptyList(), all, emptyMap())
        var trips = 0
        val warned = ArrayList<String>()
        for (f in java.io.File(root, "valhalla").listFiles()!!.sortedBy { it.name }) {
            val id = f.name.removeSuffix(".json")
            val e = java.io.File(root, "edges/$id.json")
            if (!e.exists()) continue
            val r = GuideAnswer.parse(f.readText(Charsets.UTF_8))?.routes?.firstOrNull() ?: continue
            r.facts = RoadFacts.parse(e.readText(Charsets.UTF_8), r.line) ?: continue
            trips++
            val w = Guidance.build(r, true, world).warnings.filter { it.kind in setOf(TripWarning.Kind.MILITARY_ROAD, TripWarning.Kind.PRIVATE_ROAD, TripWarning.Kind.MILITARY_END, TripWarning.Kind.PRIVATE_END) }
            if (w.isNotEmpty()) warned.add("$id: ${w.joinToString { "${it.kind} ${it.name} ${it.metres.toInt()} m" }}")
        }
        println("ZONES: $trips trips, ${warned.size} warn: $warned")
    }

    @Test fun theRowsOfOneTripAreRealOnes() {
        assertTrue(zones.any { it.name == "King Abdulaziz Naval Base" || it.en == "King Abdulaziz Naval Base" })
        assertFalse(trip("j6").facts!!.stretches.isEmpty())
    }
}
