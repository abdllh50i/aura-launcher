package com.abdllh.aura.nav.guide

import com.abdllh.aura.nav.Units
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Time scaled by each step's own speed. Valhalla drives at the posted limit and adds almost nothing for turns and signals: raw it
 * runs 14-20 % short (Jubail-Dammam 50 min against OSRM's 59; d4 crosses 19 signals in 19.5 km and adds under 2 min). The factors are
 * `eta_scale_by_step_speed_kmh` of tools/mapbuild/route-request.json, fitted on 34 same-road trips against public OSRM and not
 * against any real drive: an ESTIMATE, to be replaced from the owner's own logged drives by [parse], in the same form. This is the one
 * place. Signal wait is not measured. A ferry's time is its timetable's: never scaled.
 */
class EtaTable(rows: List<Row>) {
    class Row(val fromKmh: Double, val factor: Double)

    private val rows = rows.sortedByDescending { it.fromKmh }

    fun factor(kmh: Double): Double = rows.firstOrNull { kmh >= it.fromKmh }?.factor ?: 1.0

    fun seconds(s: GuideStep): Double {
        if (s.onFerry || s.duration <= 0) return s.duration
        return s.duration * factor(s.distance / s.duration * 3.6)
    }

    fun seconds(r: GuideRoute): Double = r.steps.sumOf { seconds(it) }

    /** Seconds left from a place along the route, by the same table. */
    fun secondsLeft(r: GuideRoute, along: Double): Double {
        var t = 0.0
        for ((i, s) in r.steps.withIndex()) {
            val end = r.steps.getOrNull(i + 1)?.at ?: r.line.length
            if (end <= along) continue
            val whole = seconds(s)
            t += if (s.at >= along) whole else whole * ((end - along) / (end - s.at).coerceAtLeast(1.0))
        }
        return t
    }

    companion object {
        /** Says so wherever a time from this table is shown ([Words] "eta.estimate"). */
        const val LABEL = "estimate"

        val DEFAULT = EtaTable(listOf(Row(110.0, 1.27), Row(85.0, 1.127), Row(60.0, 1.178), Row(35.0, 1.038), Row(0.0, 1.27)))

        /** A table as route-request.json has it: `[{"from":110,"factor":1.27},...]`. Null when it does not read, so a bad file leaves [DEFAULT] in place. */
        fun parse(json: String): EtaTable? = try {
            val rows = (MiniJson.parse(json) as List<*>).map { Row(it.num("from")!!, it.num("factor")!!) }
            if (rows.isEmpty() || rows.any { it.factor <= 0 || it.factor > 5 }) null else EtaTable(rows)
        } catch (_: Throwable) {
            null
        }
    }
}

/**
 * One route of a choice, by the scaled time. [index]: which route of the answer. [slowerBy], [longerBy]: against the first of the
 * list, which is the fastest (the router's own first may not be: on the test trips an alternate ran over 3 % faster on five of them).
 */
class Choice(val index: Int, val route: GuideRoute, val seconds: Double, val metres: Double, val slowerBy: Double, val longerBy: Double) {
    val fastest get() = slowerBy == 0.0
    val hasFerry get() = route.hasFerry
    val hasToll get() = route.facts?.stretches?.any { it.toll } == true || route.steps.any { s -> s.crossings.any { "toll" in it.classes || it.tollBooth } }

    /** The difference to show beside it: "الأسرع", or "أبطأ بـ 5 دقائق، أطول بـ 2 كم". */
    fun note(arabic: Boolean): String {
        if (fastest) return Words.text("choice.fastest", arabic, false)
        val time = if (slowerBy < SAME_SEC) Words.text("choice.same", arabic, false)
            else slowerText(arabic)
        val far = when {
            abs(longerBy) < SAME_M -> ""
            longerBy > 0 -> "${Words.text("choice.longer", arabic, false)} ${Units.distance(longerBy, arabic)}"
            else -> "${Words.text("choice.shorter", arabic, false)} ${Units.distance(-longerBy, arabic)}"
        }
        return if (far.isEmpty()) time else "$time${if (arabic) "،" else ","} $far"
    }

    /** ("أبطأ بـ 1 دقيقة" is how a clock speaks: "أبطأ بدقيقة", "أبطأ بدقيقتين".) */
    private fun slowerText(arabic: Boolean): String {
        val minutes = (slowerBy / 60).roundToInt()
        if (arabic && minutes == 1) return "أبطأ بدقيقة"
        if (arabic && minutes == 2) return "أبطأ بدقيقتين"
        return "${Words.text("choice.slower", arabic, false)} ${Units.duration(slowerBy, arabic)}"
    }

    private companion object {
        const val SAME_SEC = 45.0
        const val SAME_M = 300.0
    }
}

object Choices {
    /** The routes of an answer, fastest first by [table]. A route with no steps' time left out. */
    fun rank(routes: List<GuideRoute>, table: EtaTable = EtaTable.DEFAULT): List<Choice> {
        val timed = routes.mapIndexed { i, r -> Triple(i, r, table.seconds(r)) }.sortedBy { it.third }
        val best = timed.firstOrNull() ?: return emptyList()
        return timed.map { (i, r, sec) -> Choice(i, r, sec, r.distance, sec - best.third, r.distance - best.second.distance) }
    }
}
