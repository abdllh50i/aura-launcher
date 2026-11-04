package com.abdllh.aura.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArabicQueryTest {
    @Test fun carWashIsNotLaundry() {
        // the assistant's own query for "وين أقرب مغسلة" (Commands.kt), and what it used to become: "laundry syarat"
        assertEquals("car wash", ArabicQuery.latin("مغسلة سيارات"))
        assertEquals("car wash", ArabicQuery.latin("مغسلة"))
        assertEquals("car wash", ArabicQuery.latin("غسيل سيارات"))
        assertEquals("laundry", ArabicQuery.latin("مغسلة ملابس"))
    }

    @Test fun everyNearbyKindReadsAsAPlaceTheMapKnows() {
        // Command.Place's Arabic queries, as the assistant sends them to the search
        val expected = mapOf(
            "محطة بنزين" to "petrol station", "صيدلية" to "pharmacy", "مطعم" to "restaurant", "كوفي" to "coffee",
            "مستشفى" to "hospital", "مسجد" to "mosque", "صراف" to "atm", "سوبرماركت" to "supermarket", "فندق" to "hotel",
            "مغسلة سيارات" to "car wash", "ورشة سيارات" to "car repair"
        )
        for ((ar, en) in expected) assertEquals(ar, en, ArabicQuery.latin(ar))
    }

    @Test fun keysWithAHamzaMatch() {
        // ئ normalises to ي in the query: keys written with it used to fall through to the transliteration
        assertEquals("hail", ArabicQuery.latin("حائل"))
        assertEquals("taif", ArabicQuery.latin("الطائف"))
        assertEquals("beach", ArabicQuery.latin("شاطئ"))
    }

    @Test fun phrasesAreFoundInsideALongerQuery() {
        assertEquals("car wash jubail", ArabicQuery.latin("مغسلة سيارات الجبيل"))
        assertEquals("health center dammam", ArabicQuery.latin("مركز صحي الدمام"))
    }

    @Test fun whatWorkedStillWorks() {
        assertEquals("mall", ArabicQuery.latin("مول"))
        assertEquals("mall", ArabicQuery.latin("المول"))
        assertEquals("jubail mall", ArabicQuery.latin("الجبيل مول"))
        assertEquals("khamis mushait", ArabicQuery.latin("خميس مشيط"))
        assertEquals("fanateer", ArabicQuery.latin("فناتير"))
        assertNull(ArabicQuery.latin("jubail mall"))
    }
}
