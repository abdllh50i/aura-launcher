package com.abdllh.aura.nav.offline

import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

/**
 * Is this tile safe to hand to MapLibre? 10.3.7 reads a vector tile on a worker thread of the app's own process, and a damaged protobuf inside good gzip throws out of it:
 * protozero's unknown_pbf_wire_type_exception, not caught, SIGABRT, and the home screen is gone (seen with one flipped bit, on the real renderer). Disk rot does not get here: a stored
 * gzip's CRC catches that, and MapLibre takes a failed gunzip well. What gets here is damage made before the gzip, by a build or a strip, and no hash of a pack sees that.
 *
 * So the whole of a tile is walked, as strictly as the reader on the other side reads it (mapbox::vector_tile over protozero): every field's wire type, every length, each packed list to its end,
 * a layer's three required fields, tag pairs that point at a key and a value of the layer, geometry commands with all their numbers, and text that is UTF-8. Nothing is kept. No Android in here, so src/test runs it.
 * On a tile thread; about the time of its inflate, which MapLibre then does again.
 */
internal object TileCheck {
    /** Far more than the largest tile of the pack (a 1.5 MB z14 unpacked): a gzip bomb is no tile. */
    private const val MAX_RAW = 32 * 1024 * 1024

    private val buffers = object : ThreadLocal<ByteArray>() { override fun initialValue() = ByteArray(1024 * 1024) }

    /** Null when the tile is sound, else what is wrong with it. */
    fun problem(tile: ByteArray): String? {
        val raw: ByteArray
        val size: Int
        try {
            if (tile.size >= 2 && tile[0] == 0x1f.toByte() && tile[1] == 0x8b.toByte()) {
                var out = buffers.get()!!
                var n = 0
                GZIPInputStream(ByteArrayInputStream(tile), 8192).use { inp ->
                    while (true) {
                        if (n == out.size) {
                            if (out.size >= MAX_RAW) return "unpacks to more than $MAX_RAW bytes"
                            out = out.copyOf(out.size * 2)
                        }
                        val k = inp.read(out, n, out.size - n)
                        if (k < 0) break
                        n += k
                    }
                }
                buffers.set(if (out.size <= 4 * 1024 * 1024) out else ByteArray(1024 * 1024))
                raw = out
                size = n
            } else if (tile.size >= 2 && tile[0] == 0x78.toByte()) {
                raw = InflaterInputStream(ByteArrayInputStream(tile)).use { it.readBytes() }
                size = raw.size
            } else {
                raw = tile
                size = tile.size
            }
        } catch (t: Exception) { // (java.io and java.util.zip: a cut or a wrong CRC)
            return "cannot be unpacked: ${t.message}"
        }
        return try {
            Reader(raw, size).tile()
            null
        } catch (b: Bad) {
            b.message
        }
    }

    private class Bad(what: String) : Exception(what) {
        override fun fillInStackTrace(): Throwable = this // (a plain value here: no stack wanted)
    }

    private class Reader(private val b: ByteArray, private val size: Int) {
        private var at = 0

        private fun varint(end: Int): Long {
            var v = 0L
            var shift = 0
            while (true) {
                if (at >= end) throw Bad("a number runs past its end at $at")
                val x = b[at++].toInt()
                if (shift == 63 && (x and 0xFF) > 1) throw Bad("a number of over 64 bits at $at")
                v = v or ((x and 0x7F).toLong() shl shift)
                if (x >= 0) return v
                shift += 7
                if (shift > 63) throw Bad("a number of over 10 bytes at $at")
            }
        }

        /** The end of a length-delimited field whose length comes next. */
        private fun lengthEnd(end: Int): Int {
            val n = varint(end)
            if (n < 0 || n > end - at) throw Bad("a length of $n past its end at $at")
            return at + n.toInt()
        }

        private fun skip(wire: Int, end: Int) {
            when (wire) {
                0 -> varint(end)
                1 -> { if (end - at < 8) throw Bad("8 bytes past the end at $at"); at += 8 }
                2 -> at = lengthEnd(end)
                5 -> { if (end - at < 4) throw Bad("4 bytes past the end at $at"); at += 4 }
                else -> throw Bad("wire type $wire at $at")
            }
        }

        /** The next field's tag: number shl 3 or wire type. */
        private fun tag(end: Int): Int {
            val t = varint(end)
            if (t < 8 || t > Int.MAX_VALUE) throw Bad("field tag $t at $at")
            return t.toInt()
        }

        private fun want(t: Int, wire: Int) {
            if (t and 7 != wire) throw Bad("field ${t ushr 3} with wire type ${t and 7}, wanted $wire, at $at")
        }

        fun tile() {
            while (at < size) {
                val t = tag(size)
                if (t ushr 3 == 3) {
                    want(t, 2)
                    val e = lengthEnd(size)
                    layer(e)
                    at = e
                } else skip(t and 7, size)
            }
        }

        private fun layer(end: Int) {
            var hasName = false
            var hasExtent = false
            var hasVersion = false
            var keys = 0
            var values = 0
            // a feature's tags point at keys and values that may come after it in the layer: one pass for those, one for the features
            val start = at
            while (at < end) {
                val t = tag(end)
                when (t ushr 3) {
                    1 -> { want(t, 2); val e = lengthEnd(end); text(e); hasName = true; at = e }
                    2 -> { want(t, 2); at = lengthEnd(end) }
                    3 -> { want(t, 2); val e = lengthEnd(end); text(e); keys++; at = e }
                    4 -> { want(t, 2); val e = lengthEnd(end); value(e); values++; at = e }
                    5 -> { want(t, 0); varint(end); hasExtent = true }
                    15 -> { want(t, 0); varint(end); hasVersion = true }
                    else -> skip(t and 7, end)
                }
            }
            if (!hasName || !hasExtent || !hasVersion) throw Bad("a layer without name, extent or version")
            at = start
            while (at < end) {
                val t = tag(end)
                if (t ushr 3 == 2) {
                    val e = lengthEnd(end)
                    feature(e, keys, values)
                    at = e
                } else skip(t and 7, end)
            }
        }

        private fun value(end: Int) {
            while (at < end) {
                val t = tag(end)
                when (t ushr 3) {
                    1 -> { want(t, 2); val e = lengthEnd(end); text(e); at = e }
                    2 -> { want(t, 5); skip(5, end) }
                    3 -> { want(t, 1); skip(1, end) }
                    4, 5, 6, 7 -> { want(t, 0); varint(end) }
                    else -> skip(t and 7, end)
                }
            }
        }

        private fun feature(end: Int, keys: Int, values: Int) {
            while (at < end) {
                val t = tag(end)
                when (t ushr 3) {
                    1, 3 -> { want(t, 0); varint(end) }
                    2 -> {
                        want(t, 2)
                        val e = lengthEnd(end)
                        var odd = false
                        while (at < e) {
                            val i = varint(e)
                            if (i < 0 || i >= (if (odd) values else keys)) throw Bad("a tag points past the layer's ${if (odd) "values" else "keys"}: $i")
                            odd = !odd
                        }
                        if (odd) throw Bad("a key with no value")
                    }
                    4 -> { want(t, 2); val e = lengthEnd(end); geometry(e) }
                    else -> skip(t and 7, end)
                }
            }
        }

        private fun geometry(end: Int) {
            while (at < end) {
                val c = varint(end)
                if (c < 0 || c > 0xFFFFFFFFL) throw Bad("a geometry command of $c")
                val cmd = (c and 7).toInt()
                val count = c ushr 3
                when (cmd) {
                    1, 2 -> {
                        var i = 0L
                        while (i < count * 2) {
                            val v = varint(end)
                            if (v < 0 || v > 0xFFFFFFFFL) throw Bad("a coordinate of $v")
                            i++
                        }
                    }
                    7 -> if (count != 1L) throw Bad("close path with count $count")
                    else -> throw Bad("geometry command $cmd")
                }
            }
        }

        /** Strict UTF-8 from [at] to [end]: a label that cannot be turned into text would throw on the other side too. */
        private fun text(end: Int) {
            var i = at
            while (i < end) {
                val x = b[i++].toInt() and 0xFF
                val more = when {
                    x < 0x80 -> 0
                    x in 0xC2..0xDF -> 1
                    x in 0xE0..0xEF -> 2
                    x in 0xF0..0xF4 -> 3
                    else -> throw Bad("not UTF-8 at $i")
                }
                if (end - i < more) throw Bad("UTF-8 cut short at $i")
                for (k in 0 until more) {
                    val y = b[i + k].toInt() and 0xFF
                    if (y and 0xC0 != 0x80) throw Bad("not UTF-8 at $i")
                    if (k == 0) {
                        if ((x == 0xE0 && y < 0xA0) || (x == 0xED && y > 0x9F) || (x == 0xF0 && y < 0x90) || (x == 0xF4 && y > 0x8F)) throw Bad("not UTF-8 at $i")
                    }
                }
                i += more
            }
        }
    }
}
