package com.abdllh.aura.nav.route

/** What Valhalla's steps need before they are placed on the line ([com.abdllh.aura.nav.Router.parseRoute]). */
internal class StepTune(private val arabic: Boolean) {
    fun name(name: String, ref: String) = RoadName.pick(name, ref, arabic)
    fun ref(ref: String) = RoadName.ref(ref)
    fun seconds(metres: Double, seconds: Double) = EtaScale.seconds(metres, seconds)
}

/**
 * The name to show and say. Valhalla joins all of a road's names with ';' ("613; طريق الظهران الجبيل السريع; Dhahran Jubail
 * Expressway"), and the tests saw that reach the banner and the voice whole. One name, in the screen's script when it has one,
 * else in the other, else the road's number: tools/mapbuild/route_fields.py pick_name, the picker the tests were run with.
 */
internal object RoadName {
    /** A longer `ref` is no road number: OSM has a few like "King Abdul Aziz street, Jubail, saudi arabia". */
    private const val REF_MAX = 12

    fun pick(name: String, ref: String, arabic: Boolean): String {
        val all = parts(name)
        val words = all.filterNot(::isNumber)
        val (ar, other) = words.partition(::hasArabic)
        return (if (arabic) ar + other else other + ar).firstOrNull()
            ?: refs(ref).firstOrNull()
            ?: all.firstOrNull()
            ?: ""
    }

    /** The first of a road's numbers. */
    fun ref(ref: String) = refs(ref).firstOrNull() ?: ""

    private fun refs(ref: String) = parts(ref).filter { it.length <= REF_MAX }
    private fun parts(s: String) = s.split(';').map { it.trim() }.filter { it.isNotEmpty() }
    private fun isNumber(s: String) = s.all { it.isDigit() || it.isWhitespace() || it == '-' || it == '/' }
    private fun hasArabic(s: String) = s.any { it in '؀'..'ۿ' || it in 'ﭐ'..'﷿' || it in 'ﹰ'..'﻿' }
}

/**
 * Valhalla's times run 14-20 % short: it drives at the posted limit and adds almost nothing for turns and signals (Dammam-Riyadh
 * 191.8 minutes against OSRM's 237.7). Each step's time is multiplied by the factor for its own speed. AN ESTIMATE: the table in
 * tools/mapbuild/route-request.json was fitted on 34 same-road trips against public OSRM, not against one real drive, and
 * is to be replaced from the owner's logged drives. Signal waits are not measured at all. [com.abdllh.aura.nav.Route.etaEstimate]
 * says a route's time came through here.
 */
internal object EtaScale {
    fun factor(kmh: Double): Double = when {
        kmh >= 110 -> 1.27
        kmh >= 85 -> 1.127
        kmh >= 60 -> 1.178
        kmh >= 35 -> 1.038
        else -> 1.27
    }

    fun seconds(metres: Double, seconds: Double): Double = if (seconds <= 0.0) seconds else seconds * factor(metres / seconds * 3.6)
}

/**
 * Which route goes first. Valhalla's first is the cheapest by its own cost, which is not always the fastest (the tests: 26.2 minutes
 * first, 24.9 behind it, and shorter too; of 30 trips, three alternates agreed better with OSRM's way than the first did). So the
 * fastest by scaled time goes first, unless it is more than [LONGER] longer than Valhalla's first: Riyadh-Salwa offered 523 km against
 * 474 to save 1 % of a time that is only an estimate ([EtaScale]).
 * Returns the order, as indices; Valhalla's order is by index.
 */
internal object RouteOrder {
    private const val LONGER = 1.03

    fun of(metres: DoubleArray, seconds: DoubleArray): List<Int> {
        val all = metres.indices
        val first = all.filter { metres[it] <= metres[0] * LONGER }.minByOrNull { seconds[it] } ?: 0
        return listOf(first) + all.filter { it != first }.sortedBy { seconds[it] }
    }
}
