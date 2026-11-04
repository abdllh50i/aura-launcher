package com.abdllh.aura.nav.route

import com.abdllh.aura.nav.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RouteGuardTest {
    private val day = 86_400_000L
    private val t0 = 1_800_000_000_000L

    @Test
    fun twoInARowTrip() {
        val s = GuardState(1)
        s.died(t0, 1000)
        assertFalse(s.tripped)
        s.died(t0 + 100_000, 200_000)
        assertTrue(s.tripped)
        assertEquals(t0 + 100_000, s.trippedAt)
    }

    @Test
    fun anAnswerEndsTheRow() {
        val s = GuardState(1)
        s.died(t0, 1000)
        s.fine()
        s.died(t0, 200_000)
        assertFalse(s.tripped)
        assertEquals(1, s.deaths)
    }

    @Test
    fun aMinuteOfRestAfterADeath() {
        val s = GuardState(1)
        assertFalse(s.resting(5000))
        s.died(t0, 1_000_000)
        assertTrue(s.resting(1_000_000))
        assertTrue(s.resting(1_000_000 + GuardState.REST_MS - 1))
        assertFalse(s.resting(1_000_000 + GuardState.REST_MS))
        // after a restart of the unit the uptime starts over, and so has Android's minute
        assertFalse(s.resting(10_000))
    }

    @Test
    fun aNewVersionClears() {
        val s = GuardState(1)
        s.died(t0, 1)
        assertFalse(s.expired(1, t0))
        assertTrue(s.expired(2, t0))
        s.died(t0, 2)
        assertTrue(s.expired(2, t0))
        // nothing to clear
        assertFalse(GuardState(1).expired(2, t0))
    }

    /** The tester's run: a state file left by the version before with nothing in it, then two real crashes 5 s apart, and "keeps stopping" over the home screen. */
    @Test
    fun aFileFromTheVersionBeforeDoesNotForgetThisVersionsDeath() {
        val old = GuardState.parse("1190100 0 0 0", 1190199)
        assertFalse(old.expired(1190199, t0))
        assertTrue(old.adopt(1190199))
        assertFalse(old.adopt(1190199))
        old.died(t0, 1_000_000, 1190199)
        assertFalse("its death stands", old.expired(1190199, t0))
        assertTrue("and so does its rest", old.resting(1_000_000 + 5000))
        // without the look first, too
        val s = GuardState.parse("1190100 0 0 0", 1190199)
        s.died(t0, 1_000_000, 1190199)
        assertFalse(s.expired(1190199, t0))
        assertTrue(s.resting(1_000_000 + 5000))
        // a death of the version before still goes with it
        val before = GuardState(1190100)
        before.died(t0, 1)
        assertFalse(before.adopt(1190199))
        assertTrue(before.expired(1190199, t0))
    }

    @Test
    fun sevenDays() {
        val s = GuardState(1)
        s.died(t0, 1)
        s.died(t0, 2)
        assertFalse(s.expired(1, t0 + 7 * day - 1))
        assertTrue(s.expired(1, t0 + 7 * day))
    }

    @Test
    fun aClockBeforeTheTripSaysNothing() {
        val s = GuardState(1)
        s.died(t0, 1)
        s.died(t0, 2)
        assertFalse(s.expired(1, t0 - 400 * day))
    }

    @Test
    fun stateOnTheDisk() {
        val s = GuardState(7)
        s.died(t0, 11)
        s.died(t0, 22)
        val r = GuardState.parse(s.text(), 7)
        assertEquals(7, r.code)
        assertEquals(2, r.deaths)
        assertEquals(t0, r.trippedAt)
        assertEquals(22, r.lastDeathUptime)
        assertTrue(r.tripped)
        // nothing there, or nothing readable: a clean start, for this version
        for (bad in listOf("", "junk", "1 2", "a b c d e")) {
            val f = GuardState.parse(bad, 9)
            assertEquals(9, f.code)
            assertFalse(f.tripped)
            assertEquals(0, f.deaths)
        }
    }

    // ---------------------------------------------------------------------------------------- the outline

    private fun outline() = Outline.parse(File("src/main/assets/route/ksa.poly").readText())

    @Test
    fun insideTheKingdom() {
        val o = outline()
        for (p in listOf(LatLon(24.7136, 46.6753), LatLon(21.5433, 39.1728), LatLon(27.0046, 49.6586), LatLon(18.2164, 42.5053), LatLon(28.3838, 36.555), LatLon(17.4933, 44.1277)))
            assertTrue("$p", o.contains(p))
    }

    @Test
    fun outsideTheKingdom() {
        val o = outline()
        for (p in listOf(LatLon(26.2285, 50.5860), LatLon(25.2048, 55.2708), LatLon(25.2854, 51.5310), LatLon(29.3759, 47.9774), LatLon(31.9539, 35.9106), LatLon(30.0444, 31.2357), LatLon(15.3694, 44.1910)))
            assertFalse("$p", o.contains(p))
    }

    @Test
    fun aFileWithNoRingIsRefused() {
        var failed = false
        try { Outline.parse("ksa\n1\nEND\nEND\n") } catch (_: IllegalArgumentException) { failed = true }
        assertTrue(failed)
    }

    // ---------------------------------------------------------------------------------------- the tar's first entry

    /** index.bin first, as valhalla_build_extract writes it: offset (u64), tile id (u32), size (u32). */
    private fun tar(fileBytes: Int, name: String = "index.bin", tiles: List<Pair<Long, Int>> = listOf(1024L to 512, 2048L to 1000)): File {
        val f = File.createTempFile("tarcheck", ".tar")
        f.deleteOnExit()
        val head = ByteArray(512)
        name.toByteArray().copyInto(head)
        val size = tiles.size * 16
        String.format("%011o", size).toByteArray().copyInto(head, 124)
        val index = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        for ((o, s) in tiles) { index.putLong(o); index.putInt(123); index.putInt(s) }
        RandomAccessFile(f, "rw").use {
            it.setLength(fileBytes.toLong())
            it.write(head)
            it.write(index.array())
        }
        return f
    }

    @Test
    fun aWholeTar() {
        assertNull(TarCheck.problem(tar(4096)))
        assertNull(TarCheck.problem(tar(2048 + 1000)))
    }

    @Test
    fun aTarCutShort() {
        assertNotNull(TarCheck.problem(tar(3000)))
        assertTrue(TarCheck.problem(tar(3000))!!.startsWith("cut short"))
    }

    @Test
    fun notOurTar() {
        assertTrue(TarCheck.problem(tar(4096, "0/002/395.gph"))!!.contains("index"))
        assertTrue(TarCheck.problem(File("/no/such/file"))!!.startsWith("no file"))
        val short = File.createTempFile("tarcheck", ".tar").also { it.deleteOnExit(); it.writeBytes(ByteArray(10)) }
        assertTrue(TarCheck.problem(short)!!.startsWith("too short"))
        val zeros = File.createTempFile("tarcheck", ".tar").also { it.deleteOnExit(); it.writeBytes(ByteArray(4096)) }
        assertNotNull(TarCheck.problem(zeros))
    }
}
