package com.abdllh.aura.nav.route

import com.abdllh.aura.nav.Lane
import com.abdllh.aura.nav.LatLon
import com.abdllh.aura.nav.Polyline
import com.abdllh.aura.nav.Route
import com.abdllh.aura.nav.RouteLimits
import com.abdllh.aura.nav.Step
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * An answer between :route and the main process, through a pipe. Valhalla's JSON reaches 2.35 MB, which no Binder transaction
 * carries (1 MB, shared by the whole process), and it is parsed in :route so its garbage stays there. This is what is left, the
 * same [Route] and [Step] the parser made with everything they hold, whatever [com.abdllh.aura.nav.Router.prepare] measured
 * included: nothing is worked out twice, so the two processes cannot disagree.
 *
 * A frame: magic, length, the bytes. A process dying halfway leaves a frame that cannot be read to its end, and that is how it
 * is noticed: [read] answers null.
 */
internal object RouteCodec {
    private const val ANSWER = 0x414D5241
    private const val LIMITS = 0x414D524C
    private const val MAX_FRAME = 64 * 1024 * 1024

    fun write(out: OutputStream, a: RouteAnswer) {
        val b = ByteArrayOutputStream(64 * 1024)
        val d = DataOutputStream(b)
        d.writeInt(a.status)
        text(d, a.message)
        d.writeDouble(a.endOffRoad)
        val s = a.stats
        for (v in longArrayOf(s.routeMs, s.parseMs, s.answerBytes, s.loadMs, s.actorMs, s.vmPeakKb, s.vmHwmKb, s.pssKb, s.pid)) d.writeLong(v)
        d.writeInt(a.rawSeconds.size)
        for (v in a.rawSeconds) d.writeDouble(v)
        d.writeInt(a.routes.size)
        for (r in a.routes) route(d, r)
        frame(out, ANSWER, b.toByteArray())
    }

    /** The answer, or null when the stream ended before it did. */
    fun read(inp: InputStream): RouteAnswer? {
        val p = ByteBuffer.wrap(frame(inp, ANSWER) ?: return null)
        val status = p.int
        val message = text(p)
        val off = p.double
        val stats = RouteAnswer.Stats(p.long, p.long, p.long, p.long, p.long, p.long, p.long, p.long, p.long)
        val raw = DoubleArray(p.int) { p.double }
        val routes = ArrayList<Route>()
        repeat(p.int) { routes.add(route(p)) }
        return RouteAnswer(status, message, routes, raw, off, stats)
    }

    /** The speed limits along the first route, later than the answer: where each stretch ends, and the limit up to there (0: none known). */
    fun writeLimits(out: OutputStream, rows: List<RouteLimits.Row>) {
        val b = ByteArrayOutputStream(rows.size * 16 + 16)
        val d = DataOutputStream(b)
        d.writeInt(rows.size)
        for (r in rows) {
            d.writeDouble(r.end.lat)
            d.writeDouble(r.end.lon)
            text(d, r.tags)
        }
        frame(out, LIMITS, b.toByteArray())
    }

    fun readLimits(inp: InputStream): List<RouteLimits.Row>? {
        val p = ByteBuffer.wrap(frame(inp, LIMITS) ?: return null)
        val rows = ArrayList<RouteLimits.Row>()
        repeat(p.int) { rows.add(RouteLimits.Row(LatLon(p.double, p.double), text(p))) }
        return rows
    }

    private fun frame(out: OutputStream, magic: Int, payload: ByteArray) {
        val d = DataOutputStream(out)
        d.writeInt(magic)
        d.writeInt(payload.size)
        d.write(payload)
        d.flush()
    }

    private fun frame(inp: InputStream, magic: Int): ByteArray? = try {
        val d = DataInputStream(inp)
        val m = d.readInt()
        val n = d.readInt()
        if (m != magic || n < 0 || n > MAX_FRAME) null else ByteArray(n).also { d.readFully(it) }
    } catch (_: EOFException) {
        null
    }

    private fun route(d: DataOutputStream, r: Route) {
        d.writeDouble(r.distance)
        d.writeDouble(r.duration)
        d.writeBoolean(r.etaEstimate)
        val pts = r.line.points
        // (the line in one go: the longest has 40,000 points, and this process's code is often still interpreted)
        val a = DoubleArray(pts.size * 2)
        for (i in pts.indices) { a[i * 2] = pts[i].lat; a[i * 2 + 1] = pts[i].lon }
        val bytes = ByteBuffer.allocate(a.size * 8)
        bytes.asDoubleBuffer().put(a)
        d.writeInt(pts.size)
        d.write(bytes.array())
        d.writeInt(r.steps.size)
        for (s in r.steps) step(d, s)
    }

    private fun route(p: ByteBuffer): Route {
        val distance = p.double
        val duration = p.double
        val estimate = p.get().toInt() != 0
        val n = p.int
        val a = DoubleArray(n * 2)
        p.asDoubleBuffer().get(a)
        p.position(p.position() + a.size * 8)
        val pts = ArrayList<LatLon>(n)
        for (i in 0 until n) pts.add(LatLon(a[i * 2], a[i * 2 + 1]))
        val steps = ArrayList<Step>()
        repeat(p.int) { steps.add(step(p)) }
        return Route(Polyline(pts), steps, distance, duration).also { it.etaEstimate = estimate }
    }

    private fun step(d: DataOutputStream, s: Step) {
        text(d, s.type)
        text(d, s.modifier)
        d.writeInt(s.exit)
        text(d, s.name)
        text(d, s.ref)
        d.writeDouble(s.location.lat)
        d.writeDouble(s.location.lon)
        d.writeDouble(s.distance)
        d.writeDouble(s.duration)
        d.writeDouble(s.at)
        d.writeInt(s.lanes.size)
        for (l in s.lanes) {
            d.writeInt(l.indications.size)
            for (i in l.indications) text(d, i)
            d.writeBoolean(l.valid)
            text(d, l.validIndication)
        }
        text(d, s.destinations)
        text(d, s.exits)
        d.writeDouble(s.angle)
        d.writeDouble(s.span)
        d.writeBoolean(s.silent)
    }

    private fun step(p: ByteBuffer): Step {
        val type = text(p)
        val modifier = text(p)
        val exit = p.int
        val name = text(p)
        val ref = text(p)
        val location = LatLon(p.double, p.double)
        val distance = p.double
        val duration = p.double
        val at = p.double
        val lanes = ArrayList<Lane>()
        repeat(p.int) {
            val ind = ArrayList<String>()
            repeat(p.int) { ind.add(text(p)) }
            val valid = p.get().toInt() != 0
            lanes.add(Lane(ind, valid, text(p)))
        }
        val destinations = text(p)
        val exits = text(p)
        return Step(type, modifier, exit, name, ref, location, distance, duration, at, lanes, destinations, exits).also {
            it.angle = p.double
            it.span = p.double
            it.silent = p.get().toInt() != 0
        }
    }

    private fun text(d: DataOutputStream, s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        d.writeInt(b.size)
        d.write(b)
    }

    private fun text(p: ByteBuffer): String {
        val n = p.int
        val s = String(p.array(), p.arrayOffset() + p.position(), n, Charsets.UTF_8)
        p.position(p.position() + n)
        return s
    }
}
