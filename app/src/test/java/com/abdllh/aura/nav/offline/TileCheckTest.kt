package com.abdllh.aura.nav.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.util.Random
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class TileCheckTest {
    // ---- a small writer of the tile format
    private class Pb {
        val out = ByteArrayOutputStream()
        fun varint(v: Long): Pb { var x = v; while (true) { val b = (x and 0x7F).toInt(); x = x ushr 7; if (x == 0L) { out.write(b); return this }; out.write(b or 0x80) } }
        fun tag(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())
        fun bytes(field: Int, b: ByteArray): Pb { tag(field, 2); varint(b.size.toLong()); out.write(b); return this }
        fun num(field: Int, v: Long): Pb { tag(field, 0); return varint(v) }
        fun raw(b: ByteArray): Pb { out.write(b); return this }
        fun done(): ByteArray = out.toByteArray()
    }

    private fun packed(vararg v: Long) = Pb().apply { v.forEach { varint(it) } }.done()
    private fun feature(tags: ByteArray = packed(0, 0), geometry: ByteArray = packed(9, 50, 34, 10, 2, 4)) = Pb().num(1, 7).bytes(2, tags).num(3, 2).bytes(4, geometry).done()
    private fun value() = Pb().bytes(1, "طريق".toByteArray()).done()
    private fun layer(f: ByteArray = feature(), name: Boolean = true, extent: Boolean = true, version: Boolean = true, key: ByteArray = "name".toByteArray(), v: ByteArray = value()): ByteArray {
        val p = Pb()
        if (version) p.num(15, 2)
        if (name) p.bytes(1, "transportation".toByteArray())
        p.bytes(2, f).bytes(3, key).bytes(4, v)
        if (extent) p.num(5, 4096)
        return p.done()
    }
    private fun tile(l: ByteArray = layer()) = Pb().bytes(3, l).done()
    private fun gz(b: ByteArray) = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()

    @Test fun aSoundTileIsFine() {
        assertNull(TileCheck.problem(tile()))
        assertNull(TileCheck.problem(gz(tile())))
        assertNull(TileCheck.problem(ByteArray(0))) // (no layers: an empty tile)
    }

    @Test fun theWireTypeThatKilledTheProcess() {
        // field 2 of a feature with wire type 3 or 7, what one flipped bit made of it: protozero's unknown_pbf_wire_type_exception, uncaught, SIGABRT
        val f = Pb().num(1, 7).raw(Pb().tag(2, 7).done()).done()
        assertNotNull(TileCheck.problem(gz(tile(layer(f)))))
        val g = Pb().tag(9, 3).done()
        assertNotNull(TileCheck.problem(gz(tile(layer(g)))))
        assertNotNull(TileCheck.problem(gz(Pb().tag(9, 6).done())))
    }

    @Test fun aKnownFieldOfTheWrongWireType() {
        val l = Pb().num(15, 2).num(1, 5).num(5, 4096).done() // the name as a number
        assertNotNull(TileCheck.problem(tile(l)))
    }

    @Test fun lengthsMustFit() {
        val l = Pb().tag(3, 2).varint(1000).done()
        assertNotNull(TileCheck.problem(l))
        val cut = tile().let { it.copyOf(it.size - 3) }
        assertNotNull(TileCheck.problem(cut))
        // a number that never ends
        assertNotNull(TileCheck.problem(ByteArray(12) { 0xFF.toByte() }))
    }

    @Test fun aLayerNeedsItsThreeFields() {
        assertNotNull(TileCheck.problem(tile(layer(name = false))))
        assertNotNull(TileCheck.problem(tile(layer(extent = false))))
        assertNotNull(TileCheck.problem(tile(layer(version = false))))
    }

    @Test fun tagsMustPointIntoTheLayer() {
        assertNotNull(TileCheck.problem(tile(layer(feature(tags = packed(1, 0))))))
        assertNotNull(TileCheck.problem(tile(layer(feature(tags = packed(0, 1))))))
        assertNotNull(TileCheck.problem(tile(layer(feature(tags = packed(0))))))
        // keys and values may come after the feature that uses them
        val later = Pb().num(15, 2).bytes(1, "x".toByteArray()).bytes(2, feature()).num(5, 4096).bytes(3, "k".toByteArray()).bytes(4, value()).done()
        assertNull(TileCheck.problem(tile(later)))
    }

    @Test fun geometryCommands() {
        assertNotNull(TileCheck.problem(tile(layer(feature(geometry = packed(11, 2, 2)))))) // command 3
        assertNotNull(TileCheck.problem(tile(layer(feature(geometry = packed(9, 50))))))   // one number of a pair
        assertNull(TileCheck.problem(tile(layer(feature(geometry = packed(9, 50, 34, 15)))))) // a close path, count 1
        assertNotNull(TileCheck.problem(tile(layer(feature(geometry = packed(23))))))       // a close path, count 2
    }

    @Test fun textMustBeUtf8() {
        assertNotNull(TileCheck.problem(tile(layer(key = byteArrayOf(0x61, 0xC0.toByte(), 0x80.toByte())))))
        assertNotNull(TileCheck.problem(tile(layer(v = Pb().bytes(1, byteArrayOf(0xE0.toByte(), 0x80.toByte())).done()))))
    }

    @Test fun aGzipThatIsDamagedIsTold() {
        val g = gz(tile())
        g[g.size / 2] = (g[g.size / 2].toInt() xor 0x40).toByte()
        assertNotNull(TileCheck.problem(g))
        assertNotNull(TileCheck.problem(g.copyOf(g.size - 6)))
    }

    private fun resource(name: String) = javaClass.getResourceAsStream("/tiles/$name")!!.use { it.readBytes() }
    private fun gunzip(b: ByteArray) = GZIPInputStream(b.inputStream()).use { it.readBytes() }

    @Test fun realTilesPass() {
        for (n in listOf("base-14.pbf.gz", "places-14.pbf.gz")) {
            val t = resource(n)
            assertNull(n, TileCheck.problem(t))
            assertNull(n, TileCheck.problem(gunzip(t)))
        }
    }

    /** One flipped bit anywhere in a real tile, packed again as a bad build would: an answer, never an exception. What MapLibre would die of must be among the refused. */
    @Test fun flippedBitsInARealTile() {
        for (n in listOf("base-14.pbf.gz", "places-14.pbf.gz")) {
            val raw = gunzip(resource(n))
            val rnd = Random(2501)
            var refused = 0
            val tries = 3000
            repeat(tries) {
                val b = raw.clone()
                val at = rnd.nextInt(b.size)
                b[at] = (b[at].toInt() xor (1 shl rnd.nextInt(8))).toByte()
                if (TileCheck.problem(b) != null) refused++
            }
            assertTrue("$n: $refused of $tries refused", refused > 0)
        }
    }

    /** Set TILECHECK_ALL to a flat file of every row of the pack (the scratch script): every real tile must pass, or the map would have holes. */
    @Test fun everyRowOfThePack() {
        val path = System.getenv("TILECHECK_ALL")
        assumeTrue(path != null)
        var rows = 0
        var refused = 0
        var raw = 0L
        val t0 = System.nanoTime()
        DataInputStream(File(path!!).inputStream().buffered(1 shl 20)).use { d ->
            while (true) {
                val z = try { d.readInt() } catch (_: java.io.EOFException) { break }
                val x = d.readInt()
                val y = d.readInt()
                val b = ByteArray(d.readInt())
                d.readFully(b)
                val p = TileCheck.problem(b)
                if (p != null) { refused++; println("refused $z/$x/$y: $p") }
                raw += b.size
                rows++
            }
        }
        println("TileCheck: $rows rows, ${raw / 1_000_000} MB packed, refused $refused, ${(System.nanoTime() - t0) / 1_000_000} ms")
        assertEquals(0, refused)
    }
}
