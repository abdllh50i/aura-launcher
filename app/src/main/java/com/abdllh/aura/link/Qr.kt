package com.abdllh.aura.link

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.abs

/**
 * A QR code (ISO/IEC 18004), written here so the pairing screen needs no library: byte mode, error correction level M,
 * versions 1 to 10 (up to 213 bytes: the pairing address is about 35). [encode] gives the modules, [bitmap] and [QrView]
 * draw them, always dark on light with the quiet zone the standard asks for: a phone's camera reads that best whatever
 * the screen's theme is.
 */
object Qr {
    /** The modules of a code: [size] × [size], true = dark. */
    class Matrix(val size: Int) {
        internal val dark = BooleanArray(size * size)
        internal val fixed = BooleanArray(size * size) // function patterns: no data there, and no mask over them
        operator fun get(x: Int, y: Int) = dark[y * size + x]
        internal fun put(x: Int, y: Int, on: Boolean, function: Boolean = true) {
            dark[y * size + x] = on
            if (function) fixed[y * size + x] = true
        }
    }

    private const val MAX_VERSION = 10
    /** The empty modules around a code. */
    const val QUIET = 4

    // level M, for versions 1..10 (index 0 unused): all codewords, error correction per block, blocks
    private val CODEWORDS = intArrayOf(0, 26, 44, 70, 100, 134, 172, 196, 242, 292, 346)
    private val ECC_PER_BLOCK = intArrayOf(0, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26)
    private val BLOCKS = intArrayOf(0, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5)
    // the alignment patterns' centres
    private val ALIGN = arrayOf(intArrayOf(), intArrayOf(), intArrayOf(6, 18), intArrayOf(6, 22), intArrayOf(6, 26), intArrayOf(6, 30),
        intArrayOf(6, 34), intArrayOf(6, 22, 38), intArrayOf(6, 24, 42), intArrayOf(6, 26, 46), intArrayOf(6, 28, 50))

    /** The code of [text] (UTF-8); null when it does not fit version 10. */
    fun encode(text: String): Matrix? = encode(text.toByteArray(Charsets.UTF_8))

    fun encode(data: ByteArray): Matrix? {
        val version = (1..MAX_VERSION).firstOrNull { v ->
            val capacity = CODEWORDS[v] - ECC_PER_BLOCK[v] * BLOCKS[v]
            4 + (if (v < 10) 8 else 16) + data.size * 8 <= capacity * 8
        } ?: return null
        val codewords = interleave(segment(data, version), version)
        val base = Matrix(17 + 4 * version)
        drawFunctions(base, version)
        val order = zigzag(base)
        var best: Matrix? = null
        var bestPenalty = Int.MAX_VALUE
        // every mask is tried on a copy, with its own format bits (they count for the penalty), the lowest kept
        for (mask in 0 until 8) {
            val m = copy(base)
            placeData(m, codewords, order, mask)
            drawFormat(m, mask)
            val p = penalty(m)
            if (p < bestPenalty) { bestPenalty = p; best = m }
        }
        return best
    }

    private fun copy(a: Matrix) = Matrix(a.size).also {
        a.dark.copyInto(it.dark)
        a.fixed.copyInto(it.fixed)
    }

    // ------------------------------------------------------------------------------------------ the data
    /** Mode, length, bytes, terminator and padding: the version's data codewords. */
    private fun segment(data: ByteArray, version: Int): ByteArray {
        val capacity = CODEWORDS[version] - ECC_PER_BLOCK[version] * BLOCKS[version]
        val out = ByteArray(capacity)
        var bit = 0
        fun put(value: Int, count: Int) {
            for (i in count - 1 downTo 0) {
                if (value ushr i and 1 == 1) out[bit / 8] = (out[bit / 8].toInt() or (0x80 ushr (bit % 8))).toByte()
                bit++
            }
        }
        put(0b0100, 4) // byte mode
        put(data.size, if (version < 10) 8 else 16)
        for (b in data) put(b.toInt() and 0xFF, 8)
        // terminator (up to 4 bits, what room is left), then whole bytes, then the two pad bytes in turn
        put(0, minOf(4, capacity * 8 - bit))
        if (bit % 8 != 0) bit += 8 - bit % 8
        var i = bit / 8
        var pad = 0xEC
        while (i < capacity) {
            out[i++] = pad.toByte()
            pad = pad xor 0xEC xor 0x11
        }
        return out
    }

    /** The data split in its blocks, each with its error correction, read out across the blocks. */
    private fun interleave(data: ByteArray, version: Int): ByteArray {
        val total = CODEWORDS[version]
        val eccLen = ECC_PER_BLOCK[version]
        val blocks = BLOCKS[version]
        val shortLen = (total - eccLen * blocks) / blocks // data codewords of a short block
        val longBlocks = (total - eccLen * blocks) % blocks // the last blocks are one longer
        val generator = generator(eccLen)
        val dataBlocks = ArrayList<ByteArray>(blocks)
        val eccBlocks = ArrayList<ByteArray>(blocks)
        var at = 0
        for (b in 0 until blocks) {
            val len = shortLen + if (b >= blocks - longBlocks) 1 else 0
            val block = data.copyOfRange(at, at + len)
            at += len
            dataBlocks.add(block)
            eccBlocks.add(remainder(block, generator))
        }
        val out = ByteArray(total)
        var o = 0
        for (i in 0..shortLen) for (b in 0 until blocks) {
            if (i < dataBlocks[b].size) out[o++] = dataBlocks[b][i]
        }
        for (i in 0 until eccLen) for (b in 0 until blocks) out[o++] = eccBlocks[b][i]
        return out
    }

    // GF(256) with the standard's polynomial x^8 + x^4 + x^3 + x^2 + 1
    private fun mul(a: Int, b: Int): Int {
        var r = 0
        var x = a
        var y = b
        while (y != 0) {
            if (y and 1 != 0) r = r xor x
            x = x shl 1
            if (x and 0x100 != 0) x = x xor 0x11D
            y = y ushr 1
        }
        return r
    }

    /** The Reed-Solomon generator of [degree], highest term (always 1) left out. */
    private fun generator(degree: Int): IntArray {
        val g = IntArray(degree)
        g[degree - 1] = 1
        var root = 1
        for (i in 0 until degree) {
            for (j in 0 until degree) {
                g[j] = mul(g[j], root)
                if (j + 1 < degree) g[j] = g[j] xor g[j + 1]
            }
            root = mul(root, 2)
        }
        return g
    }

    private fun remainder(block: ByteArray, g: IntArray): ByteArray {
        val r = IntArray(g.size)
        for (b in block) {
            val factor = (b.toInt() and 0xFF) xor r[0]
            for (i in 0 until r.size - 1) r[i] = r[i + 1]
            r[r.size - 1] = 0
            for (i in r.indices) r[i] = r[i] xor mul(g[i], factor)
        }
        return ByteArray(r.size) { r[it].toByte() }
    }

    // ------------------------------------------------------------------------------------------ the patterns
    private fun drawFunctions(m: Matrix, version: Int) {
        val n = m.size
        for (i in 0 until n) { // timing
            m.put(6, i, i % 2 == 0)
            m.put(i, 6, i % 2 == 0)
        }
        finder(m, 3, 3)
        finder(m, n - 4, 3)
        finder(m, 3, n - 4)
        val align = ALIGN[version]
        for (a in align) for (b in align) {
            // (not on the finders; the ones on the timing lines are drawn: they fit them)
            if ((a == 6 && b == 6) || (a == 6 && b == align.last()) || (a == align.last() && b == 6)) continue
            for (dy in -2..2) for (dx in -2..2) m.put(a + dx, b + dy, maxOf(abs(dx), abs(dy)) != 1)
        }
        drawFormat(m, 0) // (reserves its modules; each mask draws its own)
        if (version >= 7) {
            val bits = bch(version, 0x1F25, 12)
            for (i in 0 until 18) {
                val on = bits ushr i and 1 == 1
                val a = n - 11 + i % 3
                val b = i / 3
                m.put(a, b, on)
                m.put(b, a, on)
            }
        }
    }

    private fun finder(m: Matrix, cx: Int, cy: Int) {
        for (dy in -4..4) for (dx in -4..4) {
            val x = cx + dx
            val y = cy + dy
            if (x !in 0 until m.size || y !in 0 until m.size) continue
            val d = maxOf(abs(dx), abs(dy))
            m.put(x, y, d != 2 && d != 4)
        }
    }

    /** [value] with its BCH remainder after it. */
    private fun bch(value: Int, poly: Int, length: Int): Int {
        var r = value shl length
        val top = 31 - Integer.numberOfLeadingZeros(poly)
        for (i in (length + 31 - Integer.numberOfLeadingZeros(value.coerceAtLeast(1))) downTo top) {
            if (r ushr i and 1 == 1) r = r xor (poly shl (i - top))
        }
        return value shl length or r
    }

    /** The 15 format bits (level M is 00, and the mask), both copies, and the dark module. */
    private fun drawFormat(m: Matrix, mask: Int) {
        val bits = bch(mask, 0x537, 10) xor 0x5412
        val n = m.size
        fun bit(i: Int) = bits ushr i and 1 == 1
        for (i in 0..5) m.put(8, i, bit(i))
        m.put(8, 7, bit(6))
        m.put(8, 8, bit(7))
        m.put(7, 8, bit(8))
        for (i in 9..14) m.put(14 - i, 8, bit(i))
        for (i in 0..7) m.put(n - 1 - i, 8, bit(i))
        for (i in 8..14) m.put(8, n - 15 + i, bit(i))
        m.put(8, n - 8, true)
    }

    /** The data modules in the order the codewords' bits go in: up and down in columns of two, from the right. */
    private fun zigzag(m: Matrix): IntArray {
        val out = IntArray(m.size * m.size)
        var count = 0
        val n = m.size
        var right = n - 1
        while (right >= 1) {
            if (right == 6) right = 5 // (the timing column)
            for (step in 0 until n) {
                for (j in 0..1) {
                    val x = right - j
                    val upward = (right + 1) and 2 == 0
                    val y = if (upward) n - 1 - step else step
                    if (!m.fixed[y * n + x]) out[count++] = y * n + x
                }
            }
            right -= 2
        }
        return out.copyOf(count)
    }

    private fun placeData(m: Matrix, codewords: ByteArray, order: IntArray, mask: Int) {
        val n = m.size
        for (k in order.indices) {
            val on = k / 8 < codewords.size && (codewords[k / 8].toInt() ushr (7 - k % 8)) and 1 == 1
            val y = order[k] / n
            val x = order[k] % n
            val flip = when (mask) {
                0 -> (x + y) % 2 == 0
                1 -> y % 2 == 0
                2 -> x % 3 == 0
                3 -> (x + y) % 3 == 0
                4 -> (x / 3 + y / 2) % 2 == 0
                5 -> x * y % 2 + x * y % 3 == 0
                6 -> (x * y % 2 + x * y % 3) % 2 == 0
                else -> ((x + y) % 2 + x * y % 3) % 2 == 0
            }
            m.dark[order[k]] = on != flip
        }
    }

    /** The standard's four penalty rules: the mask with the lowest is the one a scanner reads best. */
    private fun penalty(m: Matrix): Int {
        val n = m.size
        var result = 0
        fun line(get: (Int) -> Boolean) {
            var run = 0
            var last = false
            var window = 0 // the last 11 modules, as bits
            for (i in 0 until n) {
                val d = get(i)
                if (i > 0 && d == last) run++ else { if (run >= 5) result += run - 2; run = 1 }
                last = d
                window = window shl 1 and 0x7FF or (if (d) 1 else 0)
                // finder-like: 1011101 with four light modules on either side (and outside the code counts as light)
                if (i >= 10 && (window == 0b10111010000 || window == 0b00001011101)) result += 40
            }
            if (run >= 5) result += run - 2
        }
        for (y in 0 until n) line { m[it, y] }
        for (x in 0 until n) line { m[x, it] }
        var dark = 0
        for (y in 0 until n) for (x in 0 until n) {
            if (m[x, y]) dark++
            if (x + 1 < n && y + 1 < n && m[x, y] == m[x + 1, y] && m[x, y] == m[x, y + 1] && m[x, y] == m[x + 1, y + 1]) result += 3
        }
        val percent = dark * 100 / (n * n)
        result += minOf(abs(percent - 50), abs(percent + 1 - 50)) / 5 * 10
        return result
    }

    // ------------------------------------------------------------------------------------------ drawing
    /** The code as a bitmap, [scale] pixels a module, with the quiet zone. */
    fun bitmap(m: Matrix, scale: Int): Bitmap {
        val side = (m.size + 2 * QUIET) * scale
        val pixels = IntArray(side * side) { Color.WHITE }
        for (y in 0 until m.size) for (x in 0 until m.size) {
            if (!m[x, y]) continue
            for (py in 0 until scale) {
                val row = (y + QUIET) * scale + py
                for (px in 0 until scale) pixels[row * side + (x + QUIET) * scale + px] = Color.BLACK
            }
        }
        return Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888)
    }
}

/**
 * A code on a screen: whole pixels a module (a scaled one would blur its edges), centred on white. [text] null: nothing
 * but the white square.
 */
class QrView(c: Context) : View(c) {
    private val paint = Paint().apply { color = Color.BLACK; isAntiAlias = false }
    private var matrix: Qr.Matrix? = null

    var text: String? = null
        set(v) {
            if (v == field) return
            field = v
            matrix = v?.let { try { Qr.encode(it) } catch (_: Throwable) { null } }
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        val m = matrix ?: return
        val modules = m.size + 2 * Qr.QUIET
        val cell = minOf(width, height) / modules
        if (cell < 1) return
        val x0 = (width - m.size * cell) / 2f
        val y0 = (height - m.size * cell) / 2f
        for (y in 0 until m.size) for (x in 0 until m.size) {
            if (m[x, y]) canvas.drawRect(x0 + x * cell, y0 + y * cell, x0 + (x + 1) * cell, y0 + (y + 1) * cell, paint)
        }
    }
}
