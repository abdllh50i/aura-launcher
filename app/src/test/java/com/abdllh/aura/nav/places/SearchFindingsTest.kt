package com.abdllh.aura.nav.places

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the testers found in the search's answers, by the same scores and plans as run on the unit. Table syn from places.db, as src/test/resources/places holds it. */
class SearchFindingsTest {
    @Suppress("UNCHECKED_CAST")
    private val tables = TinyJson.resource("tables.json") as Map<String, List<List<Any?>>>
    private val airport = tables.getValue("cat").first { it[1] == "airport" }.let { (it[0] as Double).toInt() }
    private val travel = tables.getValue("cat").first { it[1] == "travel" }.let { (it[0] as Double).toInt() }
    private val road = tables.getValue("cat").first { it[1] == "road" }.let { (it[0] as Double).toInt() }
    private val city = tables.getValue("cat").first { it[1] == "city" }.let { (it[0] as Double).toInt() }
    private val syn = Syn(airport).also { s ->
        for (r in tables.getValue("syn")) s.add(r[0] as String, r[1] as String?, (r[2] as Double).toInt(), r[3] as String?)
    }

    private fun best(q: String, r: Row, km: Double): Double {
        val plan = QueryPlan.plan(q, syn)
        val plans = listOf(plan) + QueryPlan.otherPlans(plan, syn)
        return plans.maxOf { Score.score(r, PlanFacts(it, syn), km, null, syn) }
    }

    private fun row(id: Int, kind: Int, name: String, alt: String?, cat: Int, rank: Int) = Row(id, kind, name, alt, cat, 26_000_000, 50_000_000, 0, rank, 0)

    private val causeway = row(199691, KIND_ROAD, "جسر الملك فهد", "King Fahad Causeway", road, 66)
    private val kingFahdRoad = row(1, KIND_ROAD, "طريق الملك فهد", "King Fahd Road", road, 60)

    @Test fun theCausewayIsNotEveryRoadCalledFahd() {
        val plan = QueryPlan.plan("جسر الملك فهد", syn)
        assertTrue(QueryPlan.otherPlans(plan, syn).any { it.other == "alias" })
        // from Jubail: the road 7.8 km off, the causeway 90 km
        assertTrue(best("جسر الملك فهد", causeway, 90.0) > best("جسر الملك فهد", kingFahdRoad, 7.8))
        assertTrue(best("وديني جسر الملك فهد", causeway, 90.0) > best("وديني جسر الملك فهد", kingFahdRoad, 7.8))
    }

    @Test fun makkahByItsWholeName() {
        val makkah = row(35178, KIND_CITY, "مكة", "Mecca", city, 95)
        val street = row(2, KIND_ROAD, "شارع مكة المكرمة", "Makkah Al Mukarramah Street", road, 40)
        for (q in listOf("مكة المكرمة", "وديني مكة المكرمة")) assertTrue(q, best(q, makkah, 1174.0) > best(q, street, 1.5))
        assertTrue(best("makkah al mukarramah", makkah, 1174.0) > best("makkah al mukarramah", street, 94.0))
    }

    @Test fun anAirportByItsNameBeforeATravelOfficeOfThatName() {
        val real = row(10, KIND_POI, "مطار الملك خالد الدولي", "King Khalid International Airport", airport, 90)
        val office = row(11, KIND_POI, "مطار الملك خالد الدولي", null, travel, 50)
        assertTrue(best("مطار الملك خالد", real, 27.5) > best("مطار الملك خالد", office, 2.3))
        assertTrue(best("king khalid airport", real, 27.5) > best("king khalid airport", office, 8.3))
    }

    @Test fun aWordOfTheNameKeepsAFarRow() {
        val plan = QueryPlan.plan("ستاربكس", syn)
        val coffee = row(20, KIND_POI, "ستاربكس", "Starbucks", 0, 60)
        assertFalse(Score.farAndOnlyAlike(coffee, listOf(plan), 400.0))
        // in the other script: the consonants, long enough to be the word
        assertFalse(Score.farAndOnlyAlike(row(21, KIND_POI, "Starbucks", null, 0, 60), listOf(plan), 400.0))
        // one letter off
        assertFalse(Score.farAndOnlyAlike(row(22, KIND_POI, "ستاربوكس", null, 0, 60), listOf(plan), 400.0))
    }

    @Test fun onlyAlikeAndFarIsNoAnswer() {
        val plan = QueryPlan.plan("كاشير", syn)
        val pharmacy = row(23, KIND_POI, "Exceer Pharmacy", null, 0, 60)
        assertTrue(Score.farAndOnlyAlike(pharmacy, listOf(plan), 68.7))
        assertFalse("near, it may be what was meant", Score.farAndOnlyAlike(pharmacy, listOf(plan), 12.0))
        assertFalse("no position, no far", Score.farAndOnlyAlike(pharmacy, listOf(plan), null))
        val joined = QueryPlan.plan("دورهمياه", syn)
        assertTrue(Score.farAndOnlyAlike(row(24, KIND_DISTRICT, "الدريهمية", null, 0, 44), listOf(joined), 400.0))
    }

    @Test fun wordsOfSpeechForFillingUp() {
        val plan = QueryPlan.plan("ابي اعبي بنزين", syn)
        assertEquals(1, plan.required.size)
        assertEquals(listOf("بنزين"), plan.required[0].words)
        assertTrue(plan.required[0].cats.isNotEmpty())
    }

    @Test fun whereToPrayIsAMosque() {
        val plan = QueryPlan.plan("وين اصلي", syn)
        assertEquals(1, plan.required.size)
        assertTrue(plan.required[0].cats.isNotEmpty())
    }
}
