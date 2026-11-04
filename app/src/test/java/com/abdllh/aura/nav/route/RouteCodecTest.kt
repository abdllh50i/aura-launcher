package com.abdllh.aura.nav.route

import com.abdllh.aura.nav.Lane
import com.abdllh.aura.nav.LatLon
import com.abdllh.aura.nav.Polyline
import com.abdllh.aura.nav.Route
import com.abdllh.aura.nav.RouteLimits
import com.abdllh.aura.nav.Step
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class RouteCodecTest {
    private fun route(): Route {
        val line = Polyline(listOf(LatLon(27.004601, 49.658612), LatLon(26.9, 49.7), LatLon(26.8, 49.75000000001)))
        val a = Step("depart", "", 0, "طريق الملك فهد", "40", LatLon(27.004601, 49.658612), 1234.5, 66.25)
        val b = Step(
            "roundabout", "slight right", 3, "King Fahd Road", "", LatLon(26.9, 49.7), 800.0, 40.0, at = 1234.5,
            lanes = listOf(Lane(listOf("left", "straight"), true, "straight"), Lane(emptyList(), false, "")), destinations = "40: Dammam, Jubail", exits = "3B"
        ).also { it.angle = 91.25; it.span = 88.5; it.silent = true }
        val c = Step("arrive", "left", 0, "", "", LatLon(26.8, 49.75000000001), 0.0, 0.0, at = 2034.5)
        return Route(line, listOf(a, b, c), 2034.5, 106.25).also { it.etaEstimate = true }
    }

    private fun assertSame(x: Route, y: Route) {
        assertEquals(x.distance, y.distance, 0.0)
        assertEquals(x.duration, y.duration, 0.0)
        assertEquals(x.etaEstimate, y.etaEstimate)
        assertEquals(x.line.points, y.line.points)
        assertArrayEquals(x.line.cumulative, y.line.cumulative, 0.0)
        assertEquals(x.steps.size, y.steps.size)
        for (i in x.steps.indices) {
            val s = x.steps[i]
            val t = y.steps[i]
            assertEquals(s.type, t.type)
            assertEquals(s.modifier, t.modifier)
            assertEquals(s.exit, t.exit)
            assertEquals(s.name, t.name)
            assertEquals(s.ref, t.ref)
            assertEquals(s.location, t.location)
            assertEquals(s.distance, t.distance, 0.0)
            assertEquals(s.duration, t.duration, 0.0)
            assertEquals(s.at, t.at, 0.0)
            assertEquals(s.destinations, t.destinations)
            assertEquals(s.exits, t.exits)
            assertEquals(s.angle, t.angle, 0.0) // (NaN equals NaN here)
            assertEquals(s.span, t.span, 0.0)
            assertEquals(s.silent, t.silent)
            assertEquals(s.lanes.size, t.lanes.size)
            for (k in s.lanes.indices) {
                assertEquals(s.lanes[k].indications, t.lanes[k].indications)
                assertEquals(s.lanes[k].valid, t.lanes[k].valid)
                assertEquals(s.lanes[k].validIndication, t.lanes[k].validIndication)
            }
        }
    }

    private fun bytes(a: RouteAnswer) = ByteArrayOutputStream().also { RouteCodec.write(it, a) }.toByteArray()

    @Test
    fun nothingIsLost() {
        val a = RouteAnswer(
            RouteAnswer.OK, "", listOf(route(), route()), doubleArrayOf(90.0, 91.5), 34.959,
            RouteAnswer.Stats(1, 2, 3, 4, 5, 6, 7, 8, 9)
        )
        val b = RouteCodec.read(ByteArrayInputStream(bytes(a)))!!
        assertEquals(RouteAnswer.OK, b.status)
        assertEquals(2, b.routes.size)
        assertSame(a.routes[0], b.routes[0])
        assertSame(a.routes[1], b.routes[1])
        assertArrayEquals(a.rawSeconds, b.rawSeconds, 0.0)
        assertEquals(34.959, b.endOffRoad, 0.0)
        val s = b.stats
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L), listOf(s.routeMs, s.parseMs, s.answerBytes, s.loadMs, s.actorMs, s.vmPeakKb, s.vmHwmKb, s.pssKb, s.pid))
    }

    @Test
    fun aRefusalTravelsToo() {
        val b = RouteCodec.read(ByteArrayInputStream(bytes(RouteAnswer(RouteAnswer.NO_ROAD, "لا طريق"))))!!
        assertEquals(RouteAnswer.NO_ROAD, b.status)
        assertEquals("لا طريق", b.message)
        assertNull(b.route)
    }

    /** A process dying while it writes: at any byte, a clean nothing. */
    @Test
    fun aFrameCutAnywhereIsNull() {
        val all = bytes(RouteAnswer(RouteAnswer.OK, "", listOf(route()), doubleArrayOf(90.0)))
        for (n in 0 until all.size step 7) assertNull("cut at $n", RouteCodec.read(ByteArrayInputStream(all, 0, n)))
        assertNotNull(RouteCodec.read(ByteArrayInputStream(all)))
    }

    @Test
    fun theLimitsFollowTheAnswerOnTheSameStream() {
        val out = ByteArrayOutputStream()
        val r = route()
        RouteCodec.write(out, RouteAnswer(RouteAnswer.OK, "", listOf(r)))
        RouteCodec.writeLimits(out, listOf(RouteLimits.Row(r.line.points[1], "maxspeed=120"), RouteLimits.Row(r.line.points[2], "")))
        val inp = ByteArrayInputStream(out.toByteArray())
        val a = RouteCodec.read(inp)!!
        val rows = RouteCodec.readLimits(inp)!!
        assertEquals(2, rows.size)
        assertEquals("maxspeed=120", rows[0].tags)
        val limits = RouteLimits.of(a.route!!.line.points, rows)!!
        assertTrue(limits.metres > 0)
        assertEquals(120, limits.at(LatLon(26.95, 49.68), null))
    }

    @Test
    fun aLongLine() {
        val pts = (0 until 40_000).map { LatLon(20 + it * 1e-4, 40 + it * 1e-4) }
        val r = Route(Polyline(pts), emptyList(), 1.0, 2.0)
        val b = RouteCodec.read(ByteArrayInputStream(bytes(RouteAnswer(RouteAnswer.OK, "", listOf(r)))))!!
        assertEquals(pts, b.route!!.line.points)
    }

    @Test
    fun polylineSixDigits() {
        // Google's example, read with six digits
        val p = RouteTrace.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertEquals(listOf(LatLon(3.85, -12.02), LatLon(4.07, -12.095), LatLon(4.3252, -12.6453)), p)
    }

    @Test
    fun rowsOfOneLimitAreOne() {
        fun row(v: Double, t: String) = RouteLimits.Row(LatLon(v, v), t)
        val m = RouteTrace.merged(listOf(row(1.0, "maxspeed=80"), row(2.0, "maxspeed=80"), row(3.0, ""), row(4.0, "maxspeed=80")))
        assertEquals(listOf(2.0, 3.0, 4.0), m.map { it.end.lat })
    }
}
