package com.abdllh.aura.nav.guide

import com.abdllh.aura.nav.Units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * The words of all 30 reference trips, written out for a person to read: tools/mapbuild/tests/guide-golden.md. This test fails when
 * the words change and the file was not made anew: `-Dguide.update=true` (or the env GUIDE_GOLDEN_UPDATE=1) writes it.
 */
class GoldenTest {
    private val path: File = File(System.getProperty("guide.golden") ?: "../tools/mapbuild/tests/guide-golden.md")

    private class Tally {
        var told = 0
        var arabicName = 0
        var fromIndex = 0
        var latinOnly = 0
        var latinWithLandmark = 0
        var latinPlain = 0
        var numberOnly = 0
        var none = 0
        var noneWithLandmark = 0
        var signals = 0
        var rings = 0
        var ringNumber = 0
        var ringDirection = 0
        var structures = 0
        var places = 0
        var signs = 0
        var lanes = 0
    }

    private fun km(m: Double) = String.format(Locale.ROOT, "%.1f", m / 1000)
    private fun min(s: Double) = String.format(Locale.ROOT, "%.1f", s / 60)

    private fun tally(t: Tally, g: Guidance) {
        for (x in g.told) {
            val s = x.step
            if (s.type == "depart" || s.type == "arrive") continue
            t.told++
            val raw = Names.pick(s.name, s.ref, true)
            val landmark = g.signalCount(x, s.at - 1) > 0 || s.entersRing || x.mark != null
            when (x.road.kind) {
                RoadName.Kind.ARABIC -> { t.arabicName++; if (raw.kind == RoadName.Kind.LATIN) t.fromIndex++ }
                RoadName.Kind.LATIN -> { t.latinOnly++; if (landmark) t.latinWithLandmark++ else t.latinPlain++ }
                RoadName.Kind.NUMBER -> t.numberOnly++
                RoadName.Kind.NONE -> { t.none++; if (landmark) t.noneWithLandmark++ }
            }
            if (x.signalJunction >= 0) t.signals++
            if (s.entersRing) { t.rings++; if (x.ringNumber > 0) t.ringNumber++ else t.ringDirection++ }
            when (x.mark) {
                is Mark.AfterStructure, is Mark.OntoStructure -> t.structures++
                is Mark.Place -> t.places++
                null -> Unit
            }
            if (x.sign != null) t.signs++
            if (GuideAnswer.lanesOf(s).isNotEmpty()) t.lanes++
        }
    }

    private fun write(out: StringBuilder, trip: Trip) {
        val ar = trip.guidance(true)
        val en = trip.guidance(false)
        val r = trip.route
        val table = EtaTable.DEFAULT
        out.append("\n## ${trip.id}: ${trip.label}\n\n")
        out.append("${km(r.distance)} km, Valhalla ${min(r.duration)} min, estimate ${min(table.seconds(r))} min")
        if (trip.answer.relaxed) out.append(" (warning 401: a second search, nothing to say)")
        out.append("\n")
        val choices = Choices.rank(trip.answer.routes)
        if (choices.size > 1) for ((n, c) in choices.withIndex()) {
            out.append("- choice ${n + 1}, the router's ${c.index + 1}: ${km(c.metres)} km, ${min(c.seconds)} min: ${c.note(true)} | ${c.note(false)}\n")
        }
        for (w in ar.warnings) out.append("- before starting: ${Speech.text(w.parts(), true)}${w.name?.let { " ($it)" }.orEmpty()} | ${Speech.text(w.parts(), false)}, at ${km(w.along)} km\n")
        if (r.line.length > 30_000) {
            val fuel = ar.stopsOf("fuel")
            var gap = 0.0
            var last = 0.0
            for (f in fuel) { gap = maxOf(gap, f.along - last); last = f.along }
            gap = maxOf(gap, r.line.length - last)
            out.append("- fuel on the way: ${fuel.size}, longest gap ${km(gap)} km")
            ar.nextFuel(0.0)?.let { out.append("; at the start: ${ar.fuelText(it, 0.0)}") }
            out.append("\n")
        }
        val cams = ar.spots.count { it.kind == SpotRow.CAMERA }
        val bumps = ar.spots.count { it.kind == SpotRow.BUMP }
        val signals = ar.spots.filter { it.kind == SpotRow.SIGNAL }.map { it.junction }.distinct().size
        out.append("- on the road, going its way: $cams cameras, $bumps bumps, $signals signal junctions\n")
        val card = ar.arrival(trip.to)
        out.append("- arrival: ${Words.text(card.side.word, true, false)}${card.parkingText(true)?.let { "، $it" }.orEmpty()}\n")
        out.append("\n```\n")
        for ((i, t) in ar.told.withIndex()) {
            val s = t.step
            val prevAt = ar.told.getOrNull(i - 1)?.let { it.step.at + it.step.span } ?: 0.0
            val leg = s.at - prevAt
            val b = ar.banner(i, prevAt)
            val be = en.banner(i, prevAt)
            val type = "${s.type}${if (s.modifier.isEmpty()) "" else " | ${s.modifier}"}${if (s.exit > 0) " | exit ${s.exit}" else ""}"
            out.append("${i + 1}. km ${km(s.at)}  [$type]\n")
            out.append("   sign: ${b.action}${if (b.road.isNotEmpty()) " / ${b.road}" else ""}${if (b.shield.isNotEmpty()) " / [${b.shield}]" else ""}${if (b.toward.isNotEmpty()) " / باتجاه ${b.toward}" else ""}\n")
            if (i == 0) {
                out.append("   en:   ${be.action}${if (be.road.isNotEmpty()) " / ${be.road}" else ""}\n")
                continue
            }
            val fast = (ar.told.getOrNull(i - 1)?.step?.let { it.distance / it.duration.coerceAtLeast(1.0) * 3.6 } ?: 0.0) > 80
            val far = if (fast) 1500.0 else 500.0
            var line = ""
            if (leg > far + 150) {
                out.append("   far:  ${Speech.text(ar.spoken(i, s.at - far, false), true)}\n")
                line = Speech.text(en.spoken(i, s.at - far, false), false) + " | "
            }
            val near = s.at - minOf(150.0, leg.coerceAtLeast(0.0))
            out.append("   near: ${Speech.text(ar.spoken(i, near), true)}\n")
            out.append("   en:   ${be.action}${if (be.road.isNotEmpty()) " / ${be.road}" else ""} | $line${Speech.text(en.spoken(i, near), false)}\n")
            if (leg > 3000 && i > 0) out.append("   on:   ${Speech.text(ar.keepOn(i - 1, leg - 200), true)} | ${Speech.text(en.keepOn(i - 1, leg - 200), false)}\n")
        }
        out.append("```\n")
    }

    private fun golden(): String {
        val ids = Fixtures.ids
        val all = Tally()
        for (id in ids) if (Fixtures.trip(id).reference) tally(all, Fixtures.trip(id).guidance(true))
        val out = StringBuilder()
        out.append("# The words of the guidance on the reference trips\n\n")
        out.append("Generated by `app/src/test/.../guide/GoldenTest.kt` from real answers and the pack's own places.db (`tools/mapbuild/tests/guide_fixtures.py`). To be read: wording changes show here as a diff.\n")
        out.append("`far` and `near` are the same manoeuvre heard at 500 m (1500 m at highway speed) and at 150 m. A voice engine reads them as written; numbers read as Arabic words. Nobody has heard these on the unit.\n\n")
        out.append("Told manoeuvres on the 30 trips, start and arrival aside: ${all.told}.\n\n")
        out.append("| The road's name for the Arabic voice | Steps | Share |\n|---|---:|---:|\n")
        fun row(name: String, n: Int) = out.append("| $name | $n | ${String.format(Locale.ROOT, "%.0f", 100.0 * n / all.told)} % |\n")
        row("Arabic name (${all.fromIndex} of them through the index's Arabic for a Latin name)", all.arabicName)
        row("Latin only: the banner shows it, the voice does not read it", all.latinOnly)
        row("A road number only", all.numberOnly)
        row("No name at all", all.none)
        out.append("\nOf the ${all.latinOnly} Latin-only ones, ${all.latinWithLandmark} have a landmark instead and ${all.latinPlain} are a plain instruction. Of the ${all.none} with no name, ${all.noneWithLandmark} have a landmark.\n\n")
        out.append("Landmarks: ${all.signals} manoeuvres at a signal, ${all.rings} roundabouts (${all.ringNumber} with the exit's number, ${all.ringDirection} by direction), ${all.structures} a flyover or tunnel (flyovers only on the 6 trips with `trace_attributes` here), ${all.places} a place on the corner. Exit signs on ${all.signs}, lanes on ${all.lanes}.\n")
        for (id in ids) {
            val t = Fixtures.trip(id)
            if (t.reference) write(out, t)
        }
        out.append("\n# Beyond the 30: a ferry, guarded ends, the Haram\n")
        for (id in ids) {
            val t = Fixtures.trip(id)
            if (!t.reference) write(out, t)
        }
        return out.toString()
    }

    @Test fun theWordsAreAsWritten() {
        val made = golden()
        val update = System.getProperty("guide.update") == "true" || System.getenv("GUIDE_GOLDEN_UPDATE") == "1"
        if (update || !path.exists()) {
            path.writeText(made, Charsets.UTF_8)
            return
        }
        assertEquals("guide-golden.md is out of date: run this test with GUIDE_GOLDEN_UPDATE=1 and read the diff", path.readText(Charsets.UTF_8), made)
    }

    @Test fun everyWordHasAText() {
        for (id in Fixtures.ids) {
            val t = Fixtures.trip(id)
            for (arabic in listOf(true, false)) {
                val g = t.guidance(arabic)
                for (i in g.told.indices) for (along in listOf(g.told[i].step.at - 800, g.told[i].step.at - 100, g.told[i].step.at - 1)) {
                    for (p in g.spoken(i, along)) if (p is Part.Clip && p.id != "pause.en") {
                        assertTrue("no words for ${p.id} in $id", Words.text(p.id, arabic).isNotEmpty())
                    }
                    assertTrue(Speech.text(g.spoken(i, along), arabic).isNotBlank())
                }
                for (w in g.warnings) assertTrue(Speech.text(w.parts(), arabic).isNotBlank())
                assertTrue(Units.distance(1.0, arabic).isNotBlank())
            }
        }
    }

    @Test fun noRawListOfNamesReachesAnyone() {
        for (id in Fixtures.ids) {
            val g = Fixtures.trip(id).guidance(true)
            for (i in g.told.indices) {
                val b = g.banner(i, g.told[i].step.at - 300)
                assertTrue(b.road, ";" !in b.road && ";" !in b.action && ";" !in b.toward && ";" !in b.shield)
                val said = Speech.text(g.spoken(i, g.told[i].step.at - 300), true)
                assertTrue(said, ";" !in said)
                assertTrue("Latin in the Arabic voice: $said", said.none { it in 'a'..'z' || it in 'A'..'Z' })
            }
        }
    }
}
