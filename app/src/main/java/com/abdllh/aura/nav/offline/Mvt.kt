package com.abdllh.aura.nav.offline

import java.util.zip.GZIPInputStream

/**
 * A minimal Mapbox Vector Tile reader (protobuf by hand): for the layers asked for, each feature's tags and one
 * representative point in tile units (a point itself, the middle vertex of a line, the vertex average of a polygon).
 * Enough to index the names a tile carries; not a renderer.
 */
class Mvt private constructor(private val buf: ByteArray) {
    class Feature(val tags: Map<String, Any>, val type: Int, val px: Double, val py: Double, val parts: List<IntArray> = emptyList())
    class Layer(val name: String, val extent: Int, val features: List<Feature>)

    private var pos = 0

    companion object {
        const val POINT = 1
        const val LINE = 2
        const val POLYGON = 3

        /** The [wanted] layers of a tile (gzip or plain). */
        fun read(data: ByteArray, wanted: Set<String>): List<Layer> = Mvt(unzip(data)).tile(wanted)

        /**
         * Every line of [layer] whose tags pass [keep], as vertex lists in tile units (x0, y0, x1, y1, ...), and the
         * layer's extent: the roads of a tile, for instance.
         */
        fun lines(data: ByteArray, layer: String, keep: (Map<String, Any>) -> Boolean): Pair<Int, List<IntArray>> {
            val m = Mvt(unzip(data))
            m.wantLines = true
            val layers = m.tile(setOf(layer))
            val l = layers.firstOrNull() ?: return 4096 to emptyList()
            return l.extent to l.features.filter { it.type == LINE && keep(it.tags) }.flatMap { it.parts }
        }

        private fun unzip(data: ByteArray) = if (data.size > 2 && data[0] == 0x1f.toByte() && data[1] == 0x8b.toByte())
            GZIPInputStream(data.inputStream()).use { it.readBytes() } else data // (closed: its native inflater goes now)
    }

    /** When set, features keep all their parts' vertices ([Feature.parts]). */
    private var wantLines = false

    private fun varint(): Long {
        var shift = 0
        var r = 0L
        while (true) {
            val b = buf[pos++].toInt() and 0xFF
            r = r or ((b and 0x7F).toLong() shl shift)
            if (b < 0x80) return r
            shift += 7
        }
    }

    private fun skip(wire: Int) {
        when (wire) {
            0 -> varint()
            1 -> pos += 8
            2 -> { val n = varint().toInt(); pos += n }
            5 -> pos += 4
            else -> throw IllegalStateException("wire type $wire")
        }
    }

    private fun tile(wanted: Set<String>): List<Layer> {
        val out = ArrayList<Layer>()
        while (pos < buf.size) {
            val key = varint().toInt()
            if (key shr 3 == 3 && key and 7 == 2) {
                val len = varint().toInt()
                val end = pos + len
                layer(end, wanted)?.let { out.add(it) }
                pos = end
            } else {
                skip(key and 7)
            }
        }
        return out
    }

    /** First pass over a layer: its name, keys, values, extent and where its features are; features only if wanted. */
    private fun layer(end: Int, wanted: Set<String>): Layer? {
        var name = ""
        var extent = 4096
        val keys = ArrayList<String>()
        val values = ArrayList<Any>()
        val features = ArrayList<IntArray>() // start, end
        while (pos < end) {
            val key = varint().toInt()
            when (key shr 3) {
                1 -> name = string()
                2 -> { val len = varint().toInt(); features.add(intArrayOf(pos, pos + len)); pos += len }
                3 -> keys.add(string())
                4 -> { val len = varint().toInt(); val e = pos + len; values.add(value(e)); pos = e }
                5 -> extent = varint().toInt()
                else -> skip(key and 7)
            }
        }
        if (name !in wanted) return null
        val list = ArrayList<Feature>(features.size)
        for (f in features) {
            pos = f[0]
            feature(f[1], keys, values)?.let { list.add(it) }
        }
        return Layer(name, extent, list)
    }

    private fun string(): String {
        val len = varint().toInt()
        val s = String(buf, pos, len, Charsets.UTF_8)
        pos += len
        return s
    }

    private fun value(end: Int): Any {
        var v: Any = ""
        while (pos < end) {
            val key = varint().toInt()
            v = when (key shr 3) {
                1 -> string()
                2 -> { val b = (buf[pos].toInt() and 0xFF) or ((buf[pos + 1].toInt() and 0xFF) shl 8) or
                    ((buf[pos + 2].toInt() and 0xFF) shl 16) or ((buf[pos + 3].toInt() and 0xFF) shl 24); pos += 4; java.lang.Float.intBitsToFloat(b).toDouble() }
                3 -> { var b = 0L; for (i in 7 downTo 0) b = (b shl 8) or (buf[pos + i].toLong() and 0xFF); pos += 8; java.lang.Double.longBitsToDouble(b) }
                4, 5 -> varint()
                6 -> { val z = varint(); (z ushr 1) xor -(z and 1) }
                7 -> varint() != 0L
                else -> { skip(key and 7); v }
            }
        }
        return v
    }

    private fun feature(end: Int, keys: List<String>, values: List<Any>): Feature? {
        var type = 0
        var tags: IntArray? = null
        var geom: IntArray? = null
        while (pos < end) {
            val key = varint().toInt()
            when (key shr 3) {
                2 -> tags = packed()
                3 -> type = varint().toInt()
                4 -> geom = packed()
                else -> skip(key and 7)
            }
        }
        val t = tags ?: return null
        val map = HashMap<String, Any>(t.size)
        var i = 0
        while (i + 1 < t.size) {
            val k = keys.getOrNull(t[i]); val v = values.getOrNull(t[i + 1])
            if (k != null && v != null) map[k] = v
            i += 2
        }
        val g = geom ?: return null
        val pt = point(type, g) ?: return null
        return Feature(map, type, pt.first, pt.second, if (wantLines) parts(g) else emptyList())
    }

    /** All parts of a geometry, each as x0, y0, x1, y1, ... in tile units. */
    private fun parts(g: IntArray): List<IntArray> {
        val out = ArrayList<IntArray>()
        var cur = ArrayList<Int>()
        var x = 0
        var y = 0
        var i = 0
        while (i < g.size) {
            val cmd = g[i] and 7
            val count = g[i] ushr 3
            i++
            when (cmd) {
                1, 2 -> {
                    if (cmd == 1 && cur.isNotEmpty()) { out.add(cur.toIntArray()); cur = ArrayList() }
                    repeat(count) {
                        if (i + 1 >= g.size) return@repeat
                        x += (g[i] ushr 1) xor -(g[i] and 1)
                        y += (g[i + 1] ushr 1) xor -(g[i + 1] and 1)
                        i += 2
                        cur.add(x); cur.add(y)
                    }
                }
                7 -> Unit
                else -> break
            }
        }
        if (cur.isNotEmpty()) out.add(cur.toIntArray())
        return out
    }

    private fun packed(): IntArray {
        val len = varint().toInt()
        val end = pos + len
        val out = ArrayList<Int>()
        while (pos < end) out.add(varint().toInt())
        return out.toIntArray()
    }

    /** The vertices of the first part of a geometry, then one point for the feature. */
    private fun point(type: Int, g: IntArray): Pair<Double, Double>? {
        val xs = ArrayList<Int>()
        val ys = ArrayList<Int>()
        var x = 0
        var y = 0
        var i = 0
        var parts = 0
        loop@ while (i < g.size) {
            val cmd = g[i] and 7
            val count = g[i] ushr 3
            i++
            when (cmd) {
                1, 2 -> {
                    if (cmd == 1) { parts++; if (parts > 1) break@loop }
                    repeat(count) {
                        if (i + 1 >= g.size) return@repeat
                        x += (g[i] ushr 1) xor -(g[i] and 1)
                        y += (g[i + 1] ushr 1) xor -(g[i + 1] and 1)
                        i += 2
                        xs.add(x); ys.add(y)
                    }
                }
                7 -> Unit
                else -> break@loop
            }
        }
        if (xs.isEmpty()) return null
        return when (type) {
            LINE -> xs[xs.size / 2].toDouble() to ys[ys.size / 2].toDouble()
            POLYGON -> xs.average() to ys.average()
            else -> xs[0].toDouble() to ys[0].toDouble()
        }
    }
}
