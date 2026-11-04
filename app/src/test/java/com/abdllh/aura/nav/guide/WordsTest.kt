package com.abdllh.aura.nav.guide

import com.abdllh.aura.nav.Units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WordsTest {
    @Test fun aDistancesClipSaysWhatUnitsSays() {
        val metres = listOf(10.0, 30.0, 50.0, 80.0, 149.0, 150.0, 151.0, 290.0, 300.0, 349.0, 350.0, 900.0, 949.0, 950.0, 1000.0, 1499.0, 1500.0, 2000.0, 2600.0, 2999.0, 3000.0, 3400.0, 4600.0, 12_000.0, 38_400.0, 100_000.0, 343_000.0)
        for (m in metres) for (arabic in listOf(true, false)) {
            assertEquals("$m", Units.spokenDistance(m, arabic), Words.text(Words.distanceId(m), arabic))
        }
    }

    @Test fun numberGrammarAsUnitsHasIt() {
        assertEquals("كيلومتر ونصف", Words.text(Words.distanceId(1500.0), true))
        assertEquals("كيلومترين", Words.text(Words.distanceId(2000.0), true))
        assertEquals("5 كيلومترات", Words.text(Words.distanceId(5000.0), true))
        assertEquals("12 كيلومترًا", Words.text(Words.distanceId(12_000.0), true))
        assertEquals("300 متر", Words.text(Words.distanceId(300.0), true))
        assertEquals("150 مترًا", Words.text(Words.distanceId(150.0), true))
    }

    @Test fun exits() {
        assertEquals("المخرج الثالث", Words.text("ring.exit.3", true))
        assertEquals("take the 3rd exit", Words.text("ring.exit.3", false))
        assertEquals("المخرج رقم 9", Words.text("ring.exit.9", true))
    }

    @Test fun unknownIsEmptyAndNoCrash() {
        assertEquals("", Words.text("nothing.here", true))
        assertEquals("", Words.text("dist.m.x", true))
    }

    @Test fun everyWordHasBothLanguages() {
        for ((id, w) in Words.TABLE) {
            if (id == "pause.en") continue
            assertTrue(id, w.ar.isNotBlank() && w.en.isNotBlank() && w.arSaid.isNotBlank() && w.enSaid.isNotBlank())
        }
    }

    @Test fun aSentence() {
        val parts = listOf(Part.Clip("in"), Part.Clip(Words.distanceId(300.0)), Part.Clip("pause"), Part.Clip("turn.right"), Part.Name("طريق الملك فهد", "onto"))
        assertEquals("بعد 300 متر، لف يمين على طريق الملك فهد", Speech.text(parts, true))
        assertEquals("In 300 metres, turn right onto طريق الملك فهد", Speech.text(parts, false))
    }

    @Test fun withoutAnEngineANameGoesAndTheSentenceStands() {
        val parts = listOf(Part.Clip("turn.right"), Part.Name("طريق الملك فهد", "onto"))
        val clips = Speech.withoutNames(parts)
        assertEquals("لف يمين", Speech.text(clips, true))
        assertEquals(listOf("turn.right"), Speech.clipIds(parts))
        assertFalse(Speech.text(clips, true).contains("على"))
    }

    @Test fun englishCommaOnlyInEnglish() {
        val parts = listOf(Part.Clip("poi.at"), Part.Clip("brand.aldrees"), Part.Clip("pause.en"), Part.Clip("turn.left"))
        assertEquals("عند محطة الدريس لف يسار", Speech.text(parts, true))
        assertEquals("At the Aldrees station, turn left", Speech.text(parts, false))
    }
}
