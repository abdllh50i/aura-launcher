package com.abdllh.aura.nav.places

import com.abdllh.aura.nav.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The text functions and the query plan against the reference: tools/mapbuild/tests/norm_vectors.json (hand-checked, the 76 vectors the Python must pass too), and
 * src/test/resources/places, written by Python itself (scripts/search_compare.py fixture): every character of the Arabic blocks, real names, the tester's 200 query plans, scores.
 */
@Suppress("UNCHECKED_CAST")
class PlacesTextTest {
    private val vectors = TinyJson.parse(File("../tools/mapbuild/tests/norm_vectors.json").readText(Charsets.UTF_8)) as Map<String, List<List<Any?>>>
    private val tables = TinyJson.resource("tables.json") as Map<String, List<List<Any?>>>
    private val answers = TinyJson.resource("answers.json") as Map<String, List<Any?>>

    private val cats = tables.getValue("cat").associate { (it[1] as String) to (it[0] as Double).toInt() }
    private val syn = Syn(cats["airport"] ?: -1).also { s ->
        for (r in tables.getValue("syn")) s.add(r[0] as String, r[1] as String?, (r[2] as Double).toInt(), r[3] as String?)
    }

    @Test fun normVectors() { for (v in vectors.getValue("norm")) assertEquals(v[0] as String, v[1], Norm.norm(v[0] as String)) }
    @Test fun baseVectors() { for (v in vectors.getValue("base")) assertEquals(v[0] as String, v[1], Norm.base(v[0] as String)) }
    @Test fun latinBaseVectors() { for (v in vectors.getValue("latin_base")) assertEquals(v[0] as String, v[1], Norm.latinBase(v[0] as String)) }
    @Test fun queryWordVectors() { for (v in vectors.getValue("query_words")) assertEquals(v[0] as String, v[1], Norm.queryWords(v[0] as String)) }
    @Test fun skeletonVectors() { for (v in vectors.getValue("skeletons")) assertEquals(v[0] as String, v[1], Norm.skeletons(Norm.norm(v[0] as String))) }
    @Test fun lev1Vectors() { for (v in vectors.getValue("lev1")) assertEquals("${v[0]} ${v[1]}", v[2], Norm.lev1(v[0] as String, v[1] as String)) }

    @Test fun matchStringVectors() {
        for (v in vectors.getValue("fts")) {
            var want = v[1] as String
            for ((k, id) in cats) want = want.replace("{$k}", Norm.CT + id)
            assertEquals(v[0] as String, want, QueryPlan.fts(QueryPlan.plan(v[0] as String, syn)))
        }
    }

    @Test fun thereAre76Vectors() = assertEquals(76, vectors.filterKeys { it != "about" }.values.sumOf { it.size })

    @Test fun everyCharacterOfTheArabicBlocksFoldsAsInPython() {
        for (x in answers.getValue("fold")) {
            val (c, want) = x as List<Any?>
            val ch = (c as Double).toInt().toChar()
            assertEquals("U+%04X".format(ch.code), want, Norm.norm("x" + ch + "x"))
        }
    }

    @Test fun realNamesAsInPython() {
        for (x in answers.getValue("words")) {
            val o = x as Map<String, Any?>
            val t = o["t"] as String
            assertEquals(t, o["norm"], Norm.norm(t))
            val ws = Norm.queryWords(t)
            assertEquals(t, o["words"], ws)
            assertEquals(t, o["sks"], ws.map { Norm.skeletons(it) })
            assertEquals(t, o["latin"], ws.map { Norm.latinBase(it) })
            assertEquals(t, o["edits"], ws.take(2).map { Norm.oneEditForms(it) })
        }
    }

    @Test fun theTestersPlansAsInPython() {
        for (x in answers.getValue("plans")) {
            val o = x as Map<String, Any?>
            val q = o["q"] as String
            val p = QueryPlan.plan(q, syn)
            if (o["none"] == true) {
                assertTrue(q, p.required.isEmpty())
                continue
            }
            assertEquals(q, o["first"], QueryPlan.firstStages(p))
            assertEquals(q, o["edit"], QueryPlan.ftsEdit(p))
            assertEquals(q, o["place"], QueryPlan.placeString(p))
            assertEquals(q, o["best"], QueryPlan.wantsBest(p, syn))
            assertEquals(q, o["loose3"], QueryPlan.fts(p, looseFrom = 3))
            assertEquals(q, o["loose2"], QueryPlan.fts(p, looseFrom = 2))
            val others = QueryPlan.otherPlans(p, syn)
            val want = o["others"] as List<Map<String, Any?>>
            assertEquals(q, want.map { it["other"] }, others.map { it.other })
            assertEquals(q, want.map { it["first"] }, others.map { QueryPlan.firstStages(it) })
        }
    }

    @Test fun scoresAsInPython() {
        var n = 0
        for (x in answers.getValue("scores")) {
            val o = x as Map<String, Any?>
            val r = o["row"] as List<Any?>
            fun int(i: Int) = (r[i] as Double?)?.toInt() ?: 0
            val row = Row(int(0), int(1), r[2] as String, r[3] as String?, int(4), int(5), int(6), int(7), int(8), int(9))
            val a = (o["area"] as List<Any?>?)?.let { Area((it[0] as Double).toInt(), it[1] as String, it[2] as String, it[3] as String, it[4] as String) }
            val near = o["near"] as List<Double>
            val km = Score.kmBetween(near[0], near[1], row.lat / 1e6, row.lon / 1e6)
            assertEquals("km of ${row.id}", o["km"] as Double, km, 1e-9)
            val s = Score.score(row, PlanFacts(QueryPlan.plan(o["q"] as String, syn), syn), km, a, syn)
            assertEquals("${o["q"]} on ${row.id} ${row.name}", o["score"] as Double, s, 1e-9)
            n++
        }
        assertTrue("$n", n > 200)
    }

    // ------------------------------------------------------------------------------------------- our own

    @Test fun whatTheSpecWouldGetWrong() {
        // the code, not the text: NFKD runs first, so an acute accent has become a space and a mark by the time it could be dropped
        assertEquals("a b", Norm.norm("a´b"))
        assertEquals("ab", Norm.norm("a'b"))
        assertEquals("ab", Norm.norm("a’b"))
        // and lower case runs before NFKD, so what NFKD makes stays in capitals
        assertEquals("c", Norm.norm("C"))
        assertEquals("C", Norm.norm("℃"))
        assertEquals("TM", Norm.norm("™"))
    }

    @Test fun normSplitsWhereTheScriptChanges() {
        assertEquals("قهوه 24", Norm.norm("قهوة24"))
        assertEquals("24 قهوه", Norm.norm("24قهوة"))
        assertEquals("abc عبد", Norm.norm("abcعبد"))
        assertEquals("a1b", Norm.norm("a1b"))
        assertEquals("", Norm.norm("  !!  ؟ "))
        assertEquals("ا ب", Norm.norm(" ا - ب "))
    }

    @Test fun normDropsMarksAndKeepsTheRest() {
        assertEquals("محمد", Norm.norm("مُحَمَّد"))
        assertEquals("الله", Norm.norm("ﷲ")) // the ligature, outside the fast table
        assertEquals("ابو", Norm.norm("أبو"))
        assertEquals("لا", Norm.norm("ﻻ"))
        assertEquals("fi", Norm.norm("ﬁ"))
        // a non-joiner in a name is no space
        assertEquals("میخانه".let { Norm.norm(it) }, Norm.norm("می‌خانه"))
    }

    @Test fun theFastRoadAndTheSlowRoadAgree() {
        // an Arabic name with one letter from outside the tables goes the slow road whole
        val a = "مطعم الشرق Café 24"
        assertEquals("مطعم الشرق cafe 24", Norm.norm(a))
        assertEquals(Norm.norm("مطعم الشرق cafe 24"), Norm.norm(a))
        // ٵ becomes two letters: never the table's
        assertEquals("اٴب", Norm.norm("ٵب"))
    }

    @Test fun joinedNamesComeApart() {
        assertEquals(listOf("عبد", "رحمن"), Norm.queryWords("عبدالرحمن"))
        assertEquals(listOf("عبد"), Norm.queryWords("عبد"))
        assertEquals(listOf("abd", "rahman"), Norm.queryWords("Abdulrahman"))
        assertEquals(listOf("abd", "lla"), Norm.queryWords("Abdulla")) // abdul + la is too short: abdu + lla, as the regular expression backtracks
        assertEquals(listOf("abdula"), Norm.queryWords("Abdula"))
        assertEquals(listOf("ابو", "ظبي"), Norm.queryWords("ابوظبي"))
        assertEquals(listOf("ابوه"), Norm.queryWords("ابوه"))
    }

    @Test fun lettersInARow() {
        assertEquals(listOf("ups"), Norm.queryWords("U.P.S"))
        assertEquals(listOf("kfc", "مول"), Norm.queryWords("K F C مول"))
        assertEquals(listOf("a", "21", "b"), Norm.queryWords("a 21 b"))
    }

    @Test fun skeletonRules() {
        assertEquals("kk", Norm.skeleton("kaka"))
        assertEquals("k", Norm.skeleton("kk")) // the same consonant twice in a row counts once
        assertEquals("ks", Norm.skeleton("x"))
        assertEquals("ksn", Norm.skeleton("xn"))
        assertEquals("s", Norm.skeleton("ce"))
        assertEquals("k", Norm.skeleton("ca"))
        assertEquals("", Norm.skeleton("a1"))
        assertEquals("", Norm.skeleton("h"))
        assertEquals("mm", Norm.skeleton("مم")) // doubles are kept in Arabic
    }

    @Test fun lev1Edges() {
        assertTrue(Norm.lev1("", ""))
        assertTrue(Norm.lev1("", "a"))
        assertFalse(Norm.lev1("", "ab"))
        assertTrue(Norm.lev1("ab", "ba").not())
        assertTrue(Norm.lev1("abc", "axc"))
        assertTrue(Norm.lev1("abc", "ac"))
        assertTrue(Norm.lev1("ac", "abc"))
        assertFalse(Norm.lev1("abcd", "abef"))
    }

    @Test fun roundsAsPythonDoes() {
        assertEquals(0.12, Score.pyRound(0.125, 2), 0.0) // half to even, on the exact value
        assertEquals(0.38, Score.pyRound(0.375, 2), 0.0)
        assertEquals(2.67, Score.pyRound(2.675, 2), 0.0) // 2.67499999...
        assertEquals(74.986, Score.pyRound(74.98567, 3), 0.0)
        assertEquals(-3.142, Score.pyRound(-3.14159, 3), 0.0)
        assertEquals(1.0, Score.pyRound(0.9996, 3), 0.0)
        assertEquals(264.9, Score.pyRound(264.87, 1), 0.0)
    }

    @Test fun kmBetweenIsTheReferences() {
        assertEquals(0.0, Score.kmBetween(26.0, 50.0, 26.0, 50.0), 0.0)
        assertEquals(111.19, Score.kmBetween(26.0, 50.0, 27.0, 50.0), 0.01)
    }

    @Test fun aQueryEndingInASpaceIsFinished() {
        assertTrue(QueryPlan.plan("بنك", syn).typing)
        assertFalse(QueryPlan.plan("بنك ", syn).typing)
        assertFalse(QueryPlan.plan("بنك ", syn).typing)
        assertTrue(QueryPlan.plan("", syn).typing)
        assertTrue(QueryPlan.plan("", syn).required.isEmpty())
    }

    @Test fun aBareLetterSaysNothing() {
        assertTrue(QueryPlan.plan("ا", syn).required.isEmpty())
        assertEquals(1, QueryPlan.plan("9", syn).required.size) // a digit stays
    }

    @Test fun roadWordsAndStopWordsAreNotAskedFor() {
        val p = QueryPlan.plan("طريق الملك فهد", syn)
        assertTrue(p.roadHint)
        assertEquals(listOf("فهد"), p.required.flatMap { it.words })
        assertEquals(listOf("طريق"), QueryPlan.plan("طريق", syn).required.flatMap { it.words })
    }

    @Test fun wordsOfSpeechFallAwayAtTheEnds() {
        val p = QueryPlan.plan("وديني اقرب بنزين", syn)
        assertTrue(p.required.isNotEmpty())
        assertFalse(p.required.flatMap { it.words }.contains("وديني"))
    }

    @Test fun lettersSaidOneByOneAreAReading() {
        val p = QueryPlan.plan("كي اف سي", syn)
        assertTrue(QueryPlan.otherPlans(p, syn).any { it.ws == listOf("kfc") && it.other == "letters" })
    }

    @Test fun aNameWrittenApartIsAReading() {
        val p = QueryPlan.plan("ستار بكس", syn)
        assertTrue(QueryPlan.otherPlans(p, syn).any { it.ws == listOf("ستاربكس") })
    }

    // ------------------------------------------------------------------------------------------- the file

    private fun header(pageSize: Int, pages: Int, wal: Boolean = false): ByteArray {
        val h = ByteArray(100)
        "SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1).copyInto(h)
        h[16] = (pageSize shr 8).toByte()
        h[17] = pageSize.toByte()
        h[18] = if (wal) 2 else 1
        h[19] = if (wal) 2 else 1
        h[28] = (pages ushr 24).toByte()
        h[29] = (pages ushr 16).toByte()
        h[30] = (pages ushr 8).toByte()
        h[31] = pages.toByte()
        return h
    }

    @Test fun aWholeFileIsFine() = assertNull(PlacesFile.problem(header(4096, 9552), 100, 39124992L))

    @Test fun aFileCutShortIsTold() {
        val p = PlacesFile.problem(header(4096, 9552), 100, 39124991L)
        assertNotNull(p)
        assertEquals(PlacesState.TRUNCATED, p!!.state)
        assertEquals(PlacesState.TRUNCATED, PlacesFile.problem(ByteArray(100), 40, 40L)!!.state)
    }

    @Test fun somethingElseIsTold() {
        assertEquals(PlacesState.NOT_PLACES, PlacesFile.problem(ByteArray(100), 100, 5000L)!!.state)
        assertEquals(PlacesState.NOT_PLACES, PlacesFile.problem(header(4096, 3, wal = true), 100, 12288L)!!.state)
    }

    @Test fun aPageSizeOf65536IsWrittenAsOne() {
        val h = header(4096, 2)
        h[16] = 0
        h[17] = 1
        assertNull(PlacesFile.problem(h, 100, 131072L))
        assertEquals(PlacesState.TRUNCATED, PlacesFile.problem(h, 100, 131071L)!!.state)
    }

    // ------------------------------------------------------------------------------------------- a hit

    private fun area() = Area(1, "الفناتير", "Al Fanateer", "الجبيل", "Jubail")

    @Test fun aHitHasItsNameInBothScriptsWhenKnown() {
        val h = PlaceHit(1, KIND_POI, "صيدلية النهدي", "Nahdi Pharmacy", PlaceCat(54, "pharmacy", "صيدلية", "Pharmacy", 6), 27.0, 49.6, 50, 1.2, 70.0, true, area())
        assertEquals("صيدلية النهدي", h.nameAr)
        assertEquals("Nahdi Pharmacy", h.nameEn)
        assertEquals("صيدلية النهدي", h.shown(true))
        assertEquals("Nahdi Pharmacy", h.shown(false))
        assertEquals("صيدلية، الفناتير، الجبيل", h.detail(true))
        assertEquals("Pharmacy, Al Fanateer, Jubail", h.detail(false))
        assertEquals("الجبيل", h.townAr)
        assertEquals("Jubail", h.townEn)
        assertTrue(h.approx)
    }

    @Test fun aLatinOnlyHitStaysLatin() {
        val h = PlaceHit(2, KIND_POI, "Jubail Mall", null, null, 27.0, 49.6, 50, null, 70.0, false, null)
        assertNull(h.nameAr)
        assertEquals("Jubail Mall", h.nameEn)
        assertEquals("Jubail Mall", h.shown(true))
        assertEquals("", h.detail(true))
        assertNull(h.townAr)
        assertNull(h.km)
    }

    @Test fun aTownIsNotRepeatedInItsOwnDetail() {
        val h = PlaceHit(3, KIND_TOWN, "الجبيل", "Jubail", null, 27.0, 49.6, 50, 0.5, 90.0, false, Area(2, "", "", "الجبيل", "Jubail"))
        assertEquals("", h.detail(true))
        assertEquals("", h.detail(false))
    }

    @Test fun positionsStayWhatTheyWere() = assertEquals(LatLon(27.0, 49.6), PlaceHit(4, KIND_ROAD, "x", null, null, 27.0, 49.6, 1, null, 0.0, false, null).let { LatLon(it.lat, it.lon) })
}
