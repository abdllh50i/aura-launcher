package com.abdllh.aura.nav.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NamesTest {
    @Test fun aRingRoadIsNoStreet() {
        assertEquals("الطريق الدائري الشمالي", Names.pick("الدائري الشمالي", "", true, 80.0) { null }.spoken)
        assertEquals("الطريق الدائري الرابع", Names.pick("الدائري الرابع", "", true, 5000.0) { null }.spoken)
    }

    @Test fun byScriptForTheLanguage() {
        val joined = "طريق الملك فهد; King Fahd Road"
        assertEquals("طريق الملك فهد", Names.pick(joined, "", true).shown)
        assertEquals("طريق الملك فهد", Names.pick(joined, "", true).spoken)
        assertEquals("King Fahd Road", Names.pick(joined, "", false).shown)
        assertEquals("King Fahd Road", Names.pick(joined, "", false).spoken)
    }

    @Test fun aLongListGivesItsFirst() {
        // (real: the ferry to Farasan)
        val n = "جيزان; فرسان; جيزان - فرسان; Jazan - Farasan"
        assertEquals("جيزان", Names.pick(n, "", true).shown)
        assertEquals("جيزان - فرسان", Names.ferryName(n, true))
        assertEquals("Jazan - Farasan", Names.ferryName(n, false))
    }

    @Test fun theVoiceDoesNotReadTheOtherScript() {
        val latin = Names.pick("Prince Mishal Street", "", true)
        assertEquals("Prince Mishal Street", latin.shown)
        assertNull(latin.spoken)
        assertEquals(RoadName.Kind.LATIN, latin.kind)
        val arabic = Names.pick("شارع الحافظ الاموي", "", false)
        assertEquals("شارع الحافظ الاموي", arabic.shown)
        assertNull(arabic.spoken)
    }

    @Test fun theIndexsArabicMakesALatinNameReadable() {
        val n = Names.pick("King Faisal Street", "", true) { if (it == "King Faisal Street") "شارع الملك فيصل" else null }
        assertEquals("شارع الملك فيصل", n.shown)
        assertEquals("شارع الملك فيصل", n.spoken)
        assertEquals(RoadName.Kind.ARABIC, n.kind)
        // (and an answer that is no Arabic at all changes nothing)
        assertEquals("King Faisal Street", Names.pick("King Faisal Street", "", true) { "King Faisal" }.shown)
    }

    @Test fun aRefThatIsNoNumberIsNoRoadNumber() {
        // (real, on 3 ways in Jubail)
        val n = Names.pick("King Abdul Aziz street", "King Abdul Aziz street, Jubail, saudi arabia", true)
        assertEquals("", n.ref)
        assertEquals(emptyList<String>(), Names.refs("jeddah Street, Jubail, Saudi arabia"))
        assertEquals(listOf("95", "M5"), Names.refs("M5; 95"))
        assertEquals(listOf("م 80"), Names.refs("م 80"))
    }

    @Test fun onlyANumber() {
        val n = Names.pick("", "95; M5", true)
        assertEquals("طريق 95", n.shown)
        assertEquals("طريق 95", n.spoken)
        assertEquals(RoadName.Kind.NUMBER, n.kind)
        assertEquals("Route 95", Names.pick("", "95; M5", false).shown)
        // a letter is not for the Arabic voice
        assertEquals("طريق M5", Names.pick("", "M5", true).shown)
        assertNull(Names.pick("", "M5", true).spoken)
    }

    @Test fun aNameThatIsANumberIsANumber() {
        assertEquals("طريق 4650", Names.pick("Route 4650", "4650", true).shown)
        assertEquals("طريق 613", Names.pick("613", "", true).shown)
        // the number beside a real name stays for the shield
        val n = Names.pick("613; طريق الظهران الجبيل السريع; Dhahran Jubail Expressway", "", true)
        assertEquals("طريق الظهران الجبيل السريع", n.shown)
        assertEquals("613", n.ref)
    }

    @Test fun nothingUsable() {
        assertEquals(RoadName.Kind.NONE, Names.pick("", "", true).kind)
        assertEquals(RoadName.Kind.NONE, Names.pick("Exit", "", true).kind)
        assertEquals(RoadName.Kind.NONE, Names.pick(" ; ", "", true).kind)
    }

    @Test fun whatTheVoiceCallsAName() {
        assertEquals("شارع الخرمة", Names.pick("الخرمة; Al Kharma", "", true, 325.0).spoken)
        assertEquals("الخرمة", Names.pick("الخرمة; Al Kharma", "", true, 325.0).shown)
        assertEquals("طريق الرياض", Names.pick("الرياض", "", true, 34_796.0).spoken)
        assertEquals("شارع العليا", Names.pick("شارع العليا; Al Olaya Street", "", true).spoken)
        assertEquals("جسر الملك فهد", Names.pick("جسر الملك فهد; King Fahad Causeway", "", true).spoken)
        // too long to hear in time: the sign still shows it
        val long = Names.pick("الأمير فيصل بن سعد بن عبدالرحمن بن محمد آل سعود", "", true)
        assertNull(long.spoken)
        assertEquals("الأمير فيصل بن سعد بن عبدالرحمن بن محمد آل سعود", long.shown)
    }

    @Test fun aTypoOnAMainRoad() {
        assertEquals("طريق الأمير فيصل بن فهد", Names.pick("طريف الأمير فيصل بن فهد; Prince Faisal Bin Fahd Rd", "", true).shown)
        assertEquals("طريق الأمير فيصل بن فهد", Names.pick("طريف الأمير فيصل بن فهد; Prince Faisal Bin Fahd Rd", "", true).spoken)
        // the town stays
        assertEquals("شارع طريف", Names.pick("طريف", "", true).spoken)
    }

    @Test fun arabicDigits() {
        assertEquals("٨".let { Names.westernDigits(it) }, "8")
        assertEquals("11", Names.westernDigits("١١"))
    }
}
