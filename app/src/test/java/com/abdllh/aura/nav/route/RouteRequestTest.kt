package com.abdllh.aura.nav.route

import com.abdllh.aura.nav.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RouteRequestTest {
    private val a = LatLon(27.0046, 49.6586)
    private val b = LatLon(26.4207, 50.0888)

    @Test
    fun plainRequestIsTheTestedOne() {
        val j = RouteRequest(a, b).json()
        assertEquals(
            "{\"locations\":[{\"lat\":27.004600,\"lon\":49.658600,\"type\":\"break\"},{\"lat\":26.420700,\"lon\":50.088800,\"type\":\"break\",\"search_cutoff\":1000}]," +
                "\"costing\":\"auto\",\"costing_options\":{\"auto\":{\"top_speed\":160,\"use_distance\":0.5,\"use_tolls\":0.5,\"use_tracks\":0.0}}," +
                "\"format\":\"osrm\",\"shape_format\":\"geojson\",\"alternates\":2,\"units\":\"kilometers\",\"language\":\"en-US\",\"turn_lanes\":true}",
            j
        )
    }

    /** The tools' file is the truth: its request, key by key. */
    @Test
    fun agreesWithRouteRequestJson() {
        val f = File("../tools/mapbuild/route-request.json")
        if (!f.isFile) return
        val tool = f.readText().replace(Regex("\\s+"), "")
        val j = RouteRequest(a, b).json()
        for (piece in listOf(
            "\"costing\":\"auto\"",
            "\"costing_options\":{\"auto\":{\"top_speed\":160,\"use_distance\":0.5,\"use_tolls\":0.5,\"use_tracks\":0.0}}",
            "\"format\":\"osrm\"", "\"shape_format\":\"geojson\"", "\"alternates\":2", "\"units\":\"kilometers\"", "\"language\":\"en-US\"", "\"turn_lanes\":true"
        )) {
            assertTrue(piece, tool.contains(piece))
            assertTrue(piece, j.contains(piece))
        }
        assertTrue(tool.contains("\"search_cutoff\":1000"))
        assertTrue(tool.contains("\"heading_tolerance\":45"))
    }

    @Test
    fun headingOnlyWhenKnown() {
        assertFalse(RouteRequest(a, b).json().contains("heading"))
        val j = RouteRequest(a, b, heading = 123.7).json()
        assertTrue(j, j.contains("\"type\":\"break\",\"heading\":123,\"heading_tolerance\":45}"))
        assertTrue(RouteRequest(a, b, heading = 360.0).json().contains("\"heading\":359"))
    }

    @Test
    fun viaKinds() {
        val j = RouteRequest(a, b, via = listOf(RouteRequest.Via(LatLon(26.7, 50.0)), RouteRequest.Via(LatLon(26.6, 50.0), stop = true))).json()
        assertTrue(j, j.contains("{\"lat\":26.700000,\"lon\":50.000000,\"type\":\"through\"}"))
        assertTrue(j, j.contains("{\"lat\":26.600000,\"lon\":50.000000,\"type\":\"break\"}"))
        // the cutoff stays on the destination alone
        assertEquals(1, Regex("search_cutoff").findAll(j).count())
    }

    @Test
    fun avoidRingIsLonLat() {
        val ring = listOf(LatLon(21.0, 39.0), LatLon(21.0, 40.0), LatLon(22.0, 40.0))
        val j = RouteRequest(a, b, avoid = listOf(ring)).json()
        assertTrue(j, j.endsWith(",\"exclude_polygons\":[[[39.000000,21.000000],[40.000000,21.000000],[40.000000,22.000000]]]}"))
    }

    @Test
    fun serviceRoadSwitch() {
        val main = RouteRequest(a, b, startRoad = RouteRequest.RoadClasses.onMain("trunk")).json()
        assertTrue(main, main.contains("\"search_filter\":{\"min_road_class\":\"trunk\"}"))
        val side = RouteRequest(a, b, startRoad = RouteRequest.RoadClasses.onSide("trunk")).json()
        assertTrue(side, side.contains("\"search_filter\":{\"max_road_class\":\"primary\"}"))
        val known = RouteRequest(a, b, startRoad = RouteRequest.RoadClasses.onSide("trunk", "residential")).json()
        assertTrue(known, known.contains("\"search_filter\":{\"max_road_class\":\"residential\"}"))
        // the lowest class has nothing below it
        assertTrue(RouteRequest(a, b, startRoad = RouteRequest.RoadClasses.onSide("service_other")).json().contains("\"max_road_class\":\"service_other\""))
    }

    /** A word Valhalla does not know makes the filter do nothing, with no error (tester: main:bogus, side:trunk_link all as unfiltered). */
    @Test
    fun aRoadClassInOtherWords() {
        fun j(c: RouteRequest.RoadClasses) = RouteRequest(a, b, startRoad = c).json()
        assertTrue(j(RouteRequest.RoadClasses.onMain("trunk_link")).contains("\"min_road_class\":\"trunk\""))
        assertTrue(j(RouteRequest.RoadClasses.onMain("service")).contains("\"min_road_class\":\"service_other\""))
        assertTrue(j(RouteRequest.RoadClasses.onSide("trunk_link")).contains("\"max_road_class\":\"primary\""))
        assertFalse(j(RouteRequest.RoadClasses.onMain("bogus")).contains("search_filter"))
        assertFalse(j(RouteRequest.RoadClasses.onSide("bogus")).contains("search_filter"))
    }

    @Test
    fun aRingValhallaWouldRefuseOrIgnore() {
        val p = LatLon(21.0, 39.0)
        assertEquals(null, RouteRequest(a, b).problem())
        assertEquals(null, RouteRequest(a, b, avoid = listOf(List(100) { p })).problem())
        assertTrue(RouteRequest(a, b, avoid = listOf(List(101) { p })).problem() != null)
        assertTrue(RouteRequest(a, b, avoid = listOf(List(2) { p })).problem() != null)
        assertTrue(RouteRequest(a, b, avoid = listOf(emptyList())).problem() != null)
    }

    @Test
    fun whatOnlyValhallaCanKeep() {
        assertFalse(RouteRequest(a, b).needsValhalla)
        assertFalse("the road switch only helps with the first metres", RouteRequest(a, b, startRoad = RouteRequest.RoadClasses.onMain("trunk")).needsValhalla)
        assertTrue(RouteRequest(a, b, avoid = listOf(listOf(a, b, a))).needsValhalla)
        assertTrue(RouteRequest(a, b, via = listOf(RouteRequest.Via(a))).needsValhalla)
    }

    private val ring = listOf(LatLon(21.0, 39.0), LatLon(21.0, 40.0), LatLon(22.0, 40.0), LatLon(22.0, 39.0))

    @Test
    fun aLineThroughARing() {
        assertTrue("a point inside", RingCheck.crosses(listOf(LatLon(20.0, 39.5), LatLon(21.5, 39.5)), ring))
        assertTrue("through, with no point inside", RingCheck.crosses(listOf(LatLon(20.0, 39.5), LatLon(23.0, 39.5)), ring))
        assertFalse("beside it", RingCheck.crosses(listOf(LatLon(20.0, 38.5), LatLon(23.0, 38.5)), ring))
        assertFalse("past a corner", RingCheck.crosses(listOf(LatLon(20.0, 38.0), LatLon(21.0, 38.9)), ring))
        assertFalse("no ring", RingCheck.crosses(listOf(LatLon(20.0, 39.5), LatLon(23.0, 39.5)), ring.take(2)))
    }
}
