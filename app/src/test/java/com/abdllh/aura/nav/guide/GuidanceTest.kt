package com.abdllh.aura.nav.guide

import com.abdllh.aura.nav.Turn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidanceTest {
    private fun guide(world: RouteWorld, vararg steps: GuideStep) = Guidance.build(Kit.route(*steps), true, world)
    /** The point of the line nearest 3 km: a step stands on a point. */
    private val turnM = Kit.line.cumulative[30]
    private val turnAt3km get() = Kit.step(turnM, "turn", "right")

    // ---------------------------------------------------------------- signals
    private val threeJunctions = listOf(Kit.signal(1000.0, 1), Kit.signal(2000.0, 2), Kit.signal(turnM, 3))

    @Test fun theSignalsAreCountedFromWhereTheCarIs() {
        val g = guide(Kit.world(threeJunctions), turnAt3km)
        val at = g.told[1].step.at
        assertEquals(3, g.signalCount(g.told[1], 500.0))
        assertTrue(Kit.said(g, 1, 500.0).contains("عند الإشارة الثالثة لف يمين"))
        assertEquals(2, g.signalCount(g.told[1], 1500.0))
        assertTrue(Kit.said(g, 1, 1500.0).contains("عند الإشارة الثانية لف يمين"))
        // past the second, the next is simply the signal
        assertEquals(1, g.signalCount(g.told[1], 2500.0))
        assertEquals("بعد 500 متر، عند الإشارة لف يمين", Kit.said(g, 1, at - 500))
    }

    @Test fun aLandmarkInSightNeedsNoDistance() {
        val g = guide(Kit.world(threeJunctions), turnAt3km)
        val at = g.told[1].step.at
        assertEquals("عند الإشارة لف يمين", Kit.said(g, 1, at - 150))
        assertEquals("عند الإشارة لف يمين", g.banner(1, at - 150).action)
    }

    @Test fun nodesOfOneJunctionCountOnce() {
        // (427 nodes are 137 junctions in his box)
        val spots = listOf(Kit.signal(1000.0, 1), Kit.signal(1030.0, 1), Kit.signal(1040.0, 1, right = 10.0), Kit.signal(turnM, 3))
        val g = guide(Kit.world(spots), turnAt3km)
        assertEquals(2, g.signalCount(g.told[1], 500.0))
    }

    @Test fun signalsOnTheOtherCarriagewayDoNotCount() {
        val spots = listOf(Kit.signal(1000.0, 1, dir = 270), Kit.signal(turnM, 3))
        val g = guide(Kit.world(spots), turnAt3km)
        assertEquals(1, g.signalCount(g.told[1], 500.0))
    }

    @Test fun aSignalOnTheRoadAcrossIsTheJunctions() {
        val spots = listOf(Kit.signal(1000.0, 1, right = 10.0, dir = 0), Kit.signal(turnM, 3))
        val g = guide(Kit.world(spots), turnAt3km)
        assertEquals(2, g.signalCount(g.told[1], 500.0))
    }

    @Test fun aTurnJustShortOfTheSignalIsNotAtIt() {
        val at = Kit.route(turnAt3km).steps[1].at
        val g = guide(Kit.world(listOf(Kit.signal(at + 30, 1))), turnAt3km)
        assertEquals(0, g.signalCount(g.told[1], 500.0))
        assertEquals("لف يمين", g.banner(1, 500.0).action)
        // its bearing is unknown, or it is the junction's own: at it
        guide(Kit.world(listOf(Kit.signal(at + 30, 1, dir = null))), turnAt3km).let { assertEquals(1, it.signalCount(it.told[1], 500.0)) }
        guide(Kit.world(listOf(Kit.signal(at + 5, 1))), turnAt3km).let { assertEquals(1, it.signalCount(it.told[1], 500.0)) }
    }

    @Test fun manySignalsAreNotCountedAloud() {
        val spots = (1..5).map { Kit.signal(it * 450.0, it) } + Kit.signal(turnM, 6)
        val g = guide(Kit.world(spots), turnAt3km)
        assertEquals(6, g.signalCount(g.told[1], 100.0))
        val s = Kit.said(g, 1, 100.0)
        assertTrue(s, s.contains("عند الإشارة لف يمين"))
        assertFalse(s, s.contains("السادسة"))
    }

    @Test fun aSignalAwayFromTheTurnIsNoLandmark() {
        val g = guide(Kit.world(listOf(Kit.signal(2700.0, 1))), turnAt3km)
        assertEquals(0, g.signalCount(g.told[1], 500.0))
        assertEquals("بعد 500 متر، لف يمين", Kit.said(g, 1, g.told[1].step.at - 500))
    }

    @Test fun inEnglishToo() {
        val r = Kit.route(turnAt3km)
        val g = Guidance.build(r, false, Kit.world(threeJunctions))
        assertEquals("In 1.5 kilometres, at the second signal, turn right", Speech.text(g.spoken(1, g.told[1].step.at - 1500, false), false))
    }

    // ---------------------------------------------------------------- the order of the rule
    @Test fun aSignalComesBeforeAPlace() {
        val g = guide(Kit.world(listOf(Kit.signal(turnM, 1)), listOf(Kit.poi(2980.0, 20.0, "محطة الدريس", "fuel"))), turnAt3km)
        assertEquals("عند الإشارة لف يمين", g.banner(1, 2500.0).action)
    }

    @Test fun aRoundaboutIsItsOwnLandmark() {
        val s = Kit.step(3000.0, "roundabout", "right", exit = 2)
        val g = guide(Kit.world(listOf(Kit.signal(turnM, 1))), s)
        assertTrue(g.banner(1, 2000.0).action.startsWith("عند الدوار"))
        assertEquals(0, g.signalCount(g.told[1], 2000.0))
    }

    @Test fun aPlaceOnlyWhenThereIsNoNameToRead() {
        val place = listOf(Kit.poi(2980.0, 20.0, "محطة الدريس", "fuel"))
        val named = guide(Kit.world(pois = place), Kit.step(3000.0, "turn", "right", name = "شارع الأمير تركي"))
        assertEquals("لف يمين", named.banner(1, 2000.0).action)
        assertEquals("بعد 500 متر، لف يمين على شارع الأمير تركي", Kit.said(named, 1, 2500.0))
        val latin = guide(Kit.world(pois = place), Kit.step(3000.0, "turn", "right", name = "Prince Turki Street"))
        assertEquals("Prince Turki Street", latin.banner(1, 2000.0).road)
        assertTrue(latin.banner(1, 2000.0).action.contains("محطة الدريس"))
        assertFalse(Kit.said(latin, 1, 2500.0).contains("Prince"))
    }

    @Test fun whereThePlaceIsAgainstTheTurn() {
        fun words(metres: Double) = guide(Kit.world(pois = listOf(Kit.poi(metres, 20.0, "محطة الدريس", "fuel"))), turnAt3km).banner(1, 2000.0).action
        val at = Kit.route(turnAt3km).steps[1].at
        assertEquals("بعد محطة الدريس لف يمين", words(at - 25))
        assertEquals("عند محطة الدريس لف يمين", words(at + 3))
        assertEquals("قبل محطة الدريس لف يمين", words(at + 25))
    }

    @Test fun twoBadSoundingAftersAreAvoided() {
        val at = Kit.route(turnAt3km).steps[1].at
        val g = guide(Kit.world(pois = listOf(Kit.poi(at - 25, 20.0, "محطة الدريس", "fuel"))), turnAt3km)
        assertEquals("بعد 800 متر، لف يمين بعد محطة الدريس", Kit.said(g, 1, at - 800))
        assertEquals("بعد محطة الدريس لف يمين", Kit.said(g, 1, at - 100))
    }

    @Test fun onlyAPlaceOnTheSideOfTheTurn() {
        val at = Kit.route(turnAt3km).steps[1].at
        val left = Kit.poi(at - 10, -20.0, "محطة الدريس", "fuel")
        assertEquals("لف يمين", guide(Kit.world(pois = listOf(left)), turnAt3km).banner(1, 2000.0).action)
        assertTrue(guide(Kit.world(pois = listOf(left)), Kit.step(3000.0, "turn", "left")).banner(1, 2000.0).action.contains("الدريس"))
    }

    @Test fun placesThatDoNotCount() {
        val at = Kit.route(turnAt3km).steps[1].at
        fun words(p: PoiRow) = guide(Kit.world(pois = listOf(p)), turnAt3km).banner(1, 2000.0).action
        assertEquals("a geocoder's pile", "لف يمين", words(Kit.poi(at, 20.0, "محطة الدريس", "fuel", coarse = true)))
        assertEquals("a low rank", "لف يمين", words(Kit.poi(at, 20.0, "محطة الدريس", "fuel", rank = 30)))
        assertEquals("too far", "لف يمين", words(Kit.poi(at, 70.0, "محطة الدريس", "fuel")))
        assertEquals("a shop inside the mall", "لف يمين", words(Kit.poi(at, 20.0, "Vans", "shoes")))
        assertEquals("no chain", "لف يمين", words(Kit.poi(at, 20.0, "مطعم الأصيل", "restaurant")))
    }

    @Test fun phrasesOfPlaces() {
        fun words(p: PoiRow) = Speech.text(Landmarks.phrase(p, true)!!, true)
        assertEquals("محطة الدريس", words(Kit.poi(0.0, 0.0, "الدريس للخدمات البترولية", "fuel")))
        assertEquals("محطة درب", words(Kit.poi(0.0, 0.0, "محطة درب", "fuel")))
        assertEquals("محطة الوطنية", words(Kit.poi(0.0, 0.0, "الوطنية", "fuel")))
        assertEquals("ستاربكس", words(Kit.poi(0.0, 0.0, "Starbucks", "coffee")))
        assertEquals("صيدلية النهدي", words(Kit.poi(0.0, 0.0, "Nahdi Pharmacy", "pharmacy")))
        assertEquals("اسواق المزرعة", words(Kit.poi(0.0, 0.0, "اسواق المزرعة 3", "supermarket")))
        assertEquals("المسجد", words(Kit.poi(0.0, 0.0, "Main Mosque", "mosque")))
        assertEquals("محطة البنزين", words(Kit.poi(0.0, 0.0, "Station 44", "fuel")))
        assertEquals("المسجد", words(Kit.poi(0.0, 0.0, "جامع الأمير عبدالله بن محمد بن سعود آل سعود", "mosque")))
        assertNull(Landmarks.phrase(Kit.poi(0.0, 0.0, "Asia Restaurant", "restaurant"), true))
        assertEquals("The Aldrees station".lowercase(), Speech.text(Landmarks.phrase(Kit.poi(0.0, 0.0, "الدريس", "fuel"), false)!!, false).lowercase())
    }

    @Test fun flyoversAndTunnelsFromTheFacts() {
        val line = Kit.line
        fun facts(bridgeFrom: Double, bridgeTo: Double, tunnel: Boolean) = RoadFacts(listOf(
            RoadStretch(0.0, bridgeFrom, 80, "primary", "road", false, false, false),
            RoadStretch(bridgeFrom, bridgeTo, 80, "primary", "road", !tunnel, tunnel, false),
            RoadStretch(bridgeTo, line.length, 80, "primary", "road", false, false, false)))
        val at = Kit.route(turnAt3km).steps[1].at
        val r = Kit.route(turnAt3km).apply { this.facts = facts(at - 500, at - 100, false) }
        assertEquals("بعد الجسر لف يمين", Guidance.build(r, true).banner(1, 1000.0).action)
        val t = Kit.route(turnAt3km).apply { this.facts = facts(at - 500, at - 100, true) }
        assertEquals("بعد النفق لف يمين", Guidance.build(t, true).banner(1, 1000.0).action)
        val fork = Kit.route(Kit.step(3000.0, "fork", "slight left")).apply { this.facts = facts(at + 50, at + 500, false) }
        assertEquals("الزم اليسار واطلع الجسر", Guidance.build(fork, true).banner(1, 1000.0).action)
        // a culvert is nothing to name
        val small = Kit.route(turnAt3km).apply { this.facts = facts(at - 130, at - 100, false) }
        assertEquals("لف يمين", Guidance.build(small, true).banner(1, 1000.0).action)
    }

    @Test fun aTunnelFromTheAnswersOwnClasses() {
        val line = Kit.route(turnAt3km).steps[1].at
        val c = listOf(Kit.crossing(line - 500, listOf("tunnel")), Kit.crossing(line - 100))
        val r = Kit.route(Kit.step(line - 500, "new name", "straight", crossings = c), turnAt3km)
        assertEquals("بعد النفق لف يمين", Guidance.build(r, true).banner(1, 1000.0).action)
    }

    // ---------------------------------------------------------------- ramps, forks: no side the modifier does not say
    @Test fun straightOnARampSaysNoSide() {
        fun words(type: String, m: String) = guide(NoWorld, Kit.step(3000.0, type, m)).banner(1, 2000.0)
        assertEquals("خذ المدخل", words("on ramp", "straight").action)
        assertEquals("خذ المخرج", words("off ramp", "straight").action)
        assertEquals("خذ المخرج", words("off ramp", "").action)
        assertEquals("كمّل على طول", words("fork", "straight").action)
        assertEquals("اندمج مع الطريق", words("merge", "straight").action)
        assertEquals(Turn.STRAIGHT, words("on ramp", "straight").turn)
        assertEquals("خذ المدخل على اليمين", words("on ramp", "slight right").action)
        assertEquals("خذ المخرج على اليسار", words("off ramp", "slight left").action)
        assertEquals("الزم اليمين", words("fork", "slight right").action)
        assertEquals("اندمج يسار", words("merge", "slight left").action)
    }

    @Test fun anExitsSignWhenItIsReal() {
        val g = guide(NoWorld, Kit.step(3000.0, "off ramp", "slight right", exits = "14", destinations = "40: Dammam, Jubail"))
        val at = g.told[1].step.at
        assertEquals("خذ مخرج 14 على اليمين", g.banner(1, at - 500).action)
        assertEquals("طريق 40، الدمام", g.banner(1, at - 500).toward)
        assertEquals("بعد 500 متر، خذ مخرج 14 على اليمين باتجاه الدمام", Kit.said(g, 1, at - 500))
        val plain = guide(NoWorld, Kit.step(3000.0, "off ramp", "slight right", destinations = "Exit"))
        assertEquals("خذ المخرج على اليمين", plain.banner(1, 2000.0).action)
        assertEquals("", plain.banner(1, 2000.0).toward)
    }

    @Test fun anExitNumberTheVoiceCannotRead() {
        val g = guide(NoWorld, Kit.step(3000.0, "off ramp", "slight right", exits = "3B"))
        val at = g.told[1].step.at
        assertEquals("خذ المخرج على اليمين", g.banner(1, at - 500).action)
        assertEquals("3B", g.banner(1, at - 500).exit)
        assertEquals("بعد 500 متر، خذ المخرج على اليمين", Kit.said(g, 1, at - 500))
    }

    @Test fun aBadIndexIsNoCrash() {
        val g = guide(NoWorld)
        assertEquals("", g.banner(99, 0.0).action)
        assertTrue(g.spoken(99, 0.0).isEmpty())
        assertNull(g.junction(99))
        assertEquals(-1, g.toldOfStep(99))
    }

    // ---------------------------------------------------------------- the U-turn, the ferry
    @Test fun aUTurnIsSaidAsAPlaceAhead() {
        val g = guide(NoWorld, Kit.step(3000.0, "continue", "uturn", name = "طريق الملك فهد"))
        val at = g.told[1].step.at
        assertEquals("الدوران بعد 600 متر", Kit.said(g, 1, at - 600))
        assertEquals("بعد 200 متر، لف وارجع من الدوران", Kit.said(g, 1, at - 200))
        assertEquals("لف وارجع من الدوران", Kit.said(g, 1, at - 30))
        assertEquals(Turn.UTURN, g.banner(1, at - 600).turn)
        assertEquals("U-turn in 600 metres", Speech.text(Guidance.build(g.route, false).spoken(1, at - 600), false))
    }

    @Test fun aFerryIsTold() {
        val g = guide(NoWorld, Kit.step(3000.0, "notification", "straight", name = "جيزان; فرسان; جيزان - فرسان; Jazan - Farasan", mode = "ferry"),
            Kit.step(5000.0, "notification", "straight"), Kit.step(7000.0, "turn", "right"))
        assertEquals(listOf("depart", "notification", "notification", "turn", "arrive"), g.told.map { it.step.type })
        assertEquals("اركب العبّارة", g.banner(1, 2000.0).action)
        assertEquals("جيزان - فرسان", g.banner(1, 2000.0).road)
        assertEquals("انزل من العبّارة وكمّل", g.banner(2, 4000.0).action)
        assertTrue(g.route.hasFerry)
        assertTrue("nobody drives a ferry on", g.keepOn(1, 20_000.0).isEmpty())
    }

    @Test fun theRealFerry() {
        val g = Fixtures.trip("ferry").guidance(true)
        val i = g.told.indexOfFirst { it.step.onFerry }
        assertEquals("اركب العبّارة", g.banner(i, 0.0).action)
        assertEquals("انزل من العبّارة وكمّل", g.banner(i + 1, 0.0).action)
        assertTrue(g.warnings.any { it.kind == TripWarning.Kind.FERRY })
    }

    @Test fun aPlainNotificationStaysQuiet() {
        val g = guide(NoWorld, Kit.step(3000.0, "notification", "straight"), Kit.step(5000.0, "new name", "straight", name = "شارع"))
        assertEquals(listOf("depart", "arrive"), g.told.map { it.step.type })
    }

    // ---------------------------------------------------------------- the voice
    @Test fun whatFollowsCloselyIsSaidToo() {
        val g = guide(NoWorld, Kit.step(3000.0, "turn", "right"), Kit.step(3100.0, "turn", "left"))
        val at = g.told[1].step.at
        assertEquals("بعد 300 متر، لف يمين، ثم لف يسار", Kit.said(g, 1, at - 300, then = true))
        val far = guide(NoWorld, Kit.step(3000.0, "turn", "right"), Kit.step(4000.0, "turn", "left"))
        assertEquals("بعد 300 متر، لف يمين", Kit.said(far, 1, at - 300, then = true))
    }

    @Test fun aRoadTheCarIsOnIsNotNamedAgain() {
        val g = guide(NoWorld, Kit.step(1000.0, "turn", "left", name = "طريق الملك فهد"), Kit.step(3000.0, "fork", "slight left", name = "طريق الملك فهد"))
        assertEquals("بعد 300 متر، الزم اليسار", Kit.said(g, 2, g.told[2].step.at - 300))
        assertEquals("طريق الملك فهد", g.banner(2, 0.0).road)
    }

    @Test fun aLongStretch() {
        val g = guide(NoWorld, Kit.step(1000.0, "turn", "left", name = "طريق الجبيل"))
        assertEquals("كمّل على طريق الجبيل لمسافة 12 كيلومترًا", Speech.text(g.keepOn(1, 12_000.0), true))
        val none = guide(NoWorld, Kit.step(1000.0, "turn", "left"))
        assertEquals("كمّل لمسافة 12 كيلومترًا", Speech.text(none.keepOn(1, 12_000.0), true))
    }

    @Test fun arrivals() {
        val g = guide(NoWorld)
        assertEquals("وصلت إلى مستشفى الجبيل", Speech.text(g.arrived("مستشفى الجبيل"), true))
        assertEquals("وصلت إلى وجهتك", Speech.text(g.arrived("Jubail Hospital"), true))
        assertEquals("وصلت إلى وجهتك", Speech.text(g.arrived(null), true))
    }

    @Test fun nextTold() {
        val g = guide(NoWorld, Kit.step(1000.0, "turn", "left"), Kit.step(2000.0, "turn", "right"))
        assertEquals(1, g.nextTold(500.0))
        assertEquals(2, g.nextTold(1500.0))
        assertEquals(3, g.nextTold(2500.0))
        assertEquals(-1, g.nextTold(g.route.line.length + 10))
    }

    // ---------------------------------------------------------------- it never stops a trip
    @Test fun aWorldThatFailsIsNoWorld() {
        val bad = object : RouteWorld {
            override fun spots(boxes: List<GeoBox>): List<SpotRow> = throw IllegalStateException()
            override fun pois(boxes: List<GeoBox>, cats: Set<String>, minRank: Int): List<PoiRow> = throw IllegalStateException()
            override fun gates(boxes: List<GeoBox>): List<GateRow> = throw IllegalStateException()
            override fun zones(boxes: List<GeoBox>): List<ZoneRow> = throw IllegalStateException()
            override fun arabicRoadName(latin: String, near: com.abdllh.aura.nav.LatLon): String? = throw IllegalStateException()
        }
        val g = Guidance.build(Kit.route(Kit.step(3000.0, "turn", "right", name = "Some Street")), true, bad)
        assertEquals("لف يمين", g.banner(1, 0.0).action)
        assertEquals("Some Street", g.banner(1, 0.0).road)
        assertTrue(g.warnings.isEmpty())
        assertNull(g.nextAlert(0.0))
        assertNull(g.nextFuel(0.0))
    }

    @Test fun anEmptyLineDoesNotStopIt() {
        val r = GuideRoute(com.abdllh.aura.nav.Polyline(emptyList()), listOf(Kit.step(0.0, "depart"), Kit.step(0.0, "arrive")), 0.0, 0.0, emptySet())
        GuideAnswer.prepare(r)
        val g = Guidance.build(r, true, Fixtures.trip("j1").world)
        assertNotNull(g.banner(0, 0.0))
        assertEquals(2, g.told.size)
    }

    // ---------------------------------------------------------------- real trips
    @Test fun latinNamesOnTheRealTrips() {
        // (d2: "fatma alzahra street" has no Arabic anywhere)
        val g = Fixtures.trip("d2").guidance(true)
        val i = g.told.indexOfFirst { it.road.shown == "fatma alzahra street" }
        assertTrue(i > 0)
        assertFalse(Speech.text(g.spoken(i, g.told[i].step.at - 200), true).contains("fatma"))
    }

    @Test fun theRealRingsSpeak() {
        val g = Fixtures.trip("d6").guidance(true)
        val rotary = g.told.first { it.step.rotaryName.isNotEmpty() }
        assertTrue(g.banner(g.told.indexOf(rotary), 0.0).action.startsWith("عند دوار"))
    }
}
