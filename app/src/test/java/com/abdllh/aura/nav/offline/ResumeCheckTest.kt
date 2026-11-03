package com.abdllh.aura.nav.offline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeCheckTest {
    private val tag = "\"abc-123\""
    private val date = "Tue, 03 Nov 2026 10:00:00 GMT"

    @Test fun theSameFileGoesOn() {
        assertTrue(ResumeCheck.fits(tag, tag, date, "bytes 1000-1999/2000", 1000))
        assertTrue(ResumeCheck.fits(date, null, date, "bytes 1000-1999/2000", 1000))
        assertTrue(ResumeCheck.fits(date, tag, date, "bytes 1000-1999/*", 1000))
    }

    @Test fun aServerThatIgnoredIfRangeIsCaught() {
        // GitHub's CDN: a 206 with the new file's ETag, whatever validator was asked with
        assertFalse(ResumeCheck.fits(tag, "\"new-456\"", date, "bytes 1000-1999/2000", 1000))
        assertFalse(ResumeCheck.fits(date, tag, "Wed, 04 Nov 2026 10:00:00 GMT", "bytes 1000-1999/2000", 1000))
    }

    @Test fun aRangeThatStartsElsewhereIsNotOurs() {
        assertFalse(ResumeCheck.fits(tag, tag, date, "bytes 0-1999/2000", 1000))
        assertFalse(ResumeCheck.fits(tag, tag, date, "bytes 1001-1999/2000", 1000))
    }

    @Test fun whatCannotBeCheckedIsNotTrusted() {
        assertFalse(ResumeCheck.fits(tag, null, date, "bytes 1000-1999/2000", 1000))
        assertFalse(ResumeCheck.fits(date, tag, null, "bytes 1000-1999/2000", 1000))
        assertFalse(ResumeCheck.fits(tag, tag, date, null, 1000))
        assertFalse(ResumeCheck.fits(tag, tag, date, "garbage", 1000))
    }
}
