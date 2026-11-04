package com.abdllh.aura.nav.pack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PackRulesTest {
    @Test fun aManifestNameIsOneFileInOneFolder() {
        assertTrue(PackRules.safeName("base.mbtiles.000.gz"))
        assertTrue(PackRules.safeName("NOTICE-PACK.md"))
        assertTrue(PackRules.safeName("ksa-2026.11"))
        for (bad in listOf("", "..", "../x", "a/b", "a\\b", ".hidden", "a..b", "/etc/passwd", "x y", "x\u0000y", "نقطة", "a".repeat(200))) {
            assertFalse(bad, PackRules.safeName(bad))
        }
    }

    @Test fun aManifestCannotNameTheManagersOwnFiles() {
        for (bad in listOf("manifest.json", "progress.json", "ok", "worked", "current", "x.part", "x.tmp")) assertFalse(bad, PackRules.safeName(bad))
    }

    @Test fun hashesAreLowercaseHexOf64() {
        assertTrue(PackRules.isSha256("7bed5b86b9950fe11192ca1a1d44a1d613c9df78f8a296be9c102420b3e00cb7"))
        assertFalse(PackRules.isSha256("7BED5B86b9950fe11192ca1a1d44a1d613c9df78f8a296be9c102420b3e00cb7"))
        assertFalse(PackRules.isSha256("7bed5b"))
    }

    @Test fun appVersionWindow() {
        // versionCode: 2.0.0-beta.1 is 2000001, 2.0.0 is 2000099 (app/build.gradle.kts)
        assertEquals(PackRules.Fit.OK, PackRules.fit(2000099, 2000001, 0))
        assertEquals(PackRules.Fit.OK, PackRules.fit(2000001, 2000001, 2000099))
        assertEquals(PackRules.Fit.APP_TOO_OLD, PackRules.fit(1190199, 2000001, 0))
        assertEquals(PackRules.Fit.PACK_TOO_OLD, PackRules.fit(2010099, 0, 2000099))
        assertEquals(PackRules.Fit.OK, PackRules.fit(1, 0, 0))
        // the manifest of 2026-11-04 says minApp 200000: under every version since 0.20, so it refuses nothing (reported)
        assertEquals(PackRules.Fit.OK, PackRules.fit(1190199, 200000, 0))
    }

    @Test fun theSpaceRule() {
        val need = 989947904L // the real manifest
        assertEquals(need, PackRules.need(713123840L, need))
        assertEquals(need, PackRules.need(713123840L, 5L)) // a manifest that says too little is not believed
        assertFalse(PackRules.fits(need - 1, need, 0))
        assertTrue(PackRules.fits(need, need, 0))
        // going on: the 713 MB already in the staging is in need and no longer in free
        assertTrue(PackRules.fits(need - 713123840L, need, 713123840L))
        assertFalse(PackRules.fits(need - 713123840L - 1, need, 713123840L))
    }

    @Test fun backoffGrowsAndStopsGrowing() {
        assertEquals(2000L, PackRules.backoffMs(1))
        assertEquals(4000L, PackRules.backoffMs(2))
        assertEquals(32000L, PackRules.backoffMs(5))
        assertEquals(60000L, PackRules.backoffMs(6))
        assertEquals(60000L, PackRules.backoffMs(100))
        assertEquals(2000L, PackRules.backoffMs(0))
    }

    @Test fun resumeStartsBeforeTheEndOfWhatIsKept() {
        assertEquals(0L, PackRules.resumeAt(0))
        assertEquals(0L, PackRules.resumeAt(1000))
        assertEquals(100_000L - PackRules.OVERLAP, PackRules.resumeAt(100_000))
    }

    @Test fun contentRangeIsRead() {
        val r = PackRules.contentRange("bytes 100-199/200")!!
        assertEquals(100L, r.start)
        assertEquals(199L, r.end)
        assertEquals(200L, r.total)
        assertEquals(-1L, PackRules.contentRange("bytes 100-199/*")!!.total)
        assertNull(PackRules.contentRange(null))
        assertNull(PackRules.contentRange("bytes */200"))
        assertNull(PackRules.contentRange("100-199/200"))
        assertNull(PackRules.contentRange("bytes a-b/c"))
    }

    @Test fun a206GoesOnlyWhereItBelongs() {
        assertTrue(PackRules.rangeFits(PackRules.contentRange("bytes 100-199/200"), 100, 200))
        assertTrue(PackRules.rangeFits(PackRules.contentRange("bytes 100-199/*"), 100, 200))
        assertFalse(PackRules.rangeFits(PackRules.contentRange("bytes 0-199/200"), 100, 200)) // the whole file under a 206
        assertFalse(PackRules.rangeFits(PackRules.contentRange("bytes 101-199/200"), 100, 200))
        assertFalse(PackRules.rangeFits(PackRules.contentRange("bytes 100-150/200"), 100, 200)) // stops short
        assertFalse(PackRules.rangeFits(PackRules.contentRange("bytes 100-199/300"), 100, 200)) // another file
        assertFalse(PackRules.rangeFits(null, 100, 200))
    }

    @Test fun newerByBuildTime() {
        assertTrue(PackRules.newer("2026-12-01T10:00Z", "2026-11-04T15:24Z"))
        assertFalse(PackRules.newer("2026-11-04T15:24Z", "2026-11-04T15:24Z"))
        assertFalse(PackRules.newer("2026-10-01T10:00Z", "2026-11-04T15:24Z"))
        assertTrue(PackRules.newer("2026-10-01T10:00Z", null))
    }

    @Test fun onlyHttpsInRelease() {
        assertTrue(PackRules.urlAllowed("https://github.com/x", false))
        assertFalse(PackRules.urlAllowed("http://github.com/x", false))
        assertTrue(PackRules.urlAllowed("http://127.0.0.1:8791/x", true))
        assertFalse(PackRules.urlAllowed("ftp://x", true))
        assertFalse(PackRules.urlAllowed("file:///data/x", true))
    }

    @Test fun redirects() {
        assertEquals("https://cdn.example/a/b?sig=1", PackRules.resolve("https://github.com/x/y", "https://cdn.example/a/b?sig=1"))
        assertEquals("https://github.com/cdn/f", PackRules.resolve("https://github.com/x/y", "/cdn/f"))
        assertEquals("https://github.com/x/z", PackRules.resolve("https://github.com/x/y", "z"))
    }

    @Test fun theTagIsReadFromTheRedirect() {
        val hops = listOf("https://github.com/a/b/releases/download/data-2026.12/manifest.json", "https://release-assets.githubusercontent.com/x?sig=1")
        assertEquals("https://github.com/a/b/releases/download/data-2026.12/", PackRules.releaseBase(hops))
        assertNull(PackRules.releaseBase(listOf("https://cdn.example/a")))
        assertNull(PackRules.releaseBase(emptyList()))
        assertEquals("https://x/a/b/manifest.json", PackRules.join("https://x/a/b/", "manifest.json"))
        assertEquals("https://x/a/b/manifest.json", PackRules.join("https://x/a/b", "manifest.json"))
    }

    @Test fun percents() {
        assertEquals(0, PackRules.percent(0, 0))
        assertEquals(50, PackRules.percent(193295684, 386591369))
        assertEquals(100, PackRules.percent(386591369, 386591369))
        assertEquals(100, PackRules.percent(400000000, 386591369))
        assertEquals(0, PackRules.percent(-5, 100))
    }

    @Test fun aFullDiskIsToldFromADrop() {
        assertTrue(PackRules.noSpace("write failed: ENOSPC (No space left on device)"))
        assertTrue(PackRules.noSpace("No space left on device"))
        assertFalse(PackRules.noSpace("unexpected end of stream"))
        assertFalse(PackRules.noSpace(null))
    }

    @Test fun speedOverAWindow() {
        val m = SpeedMeter(1000L)
        assertEquals(0L, m.rate(100))
        m.add(1000, 100)
        m.add(1000, 600)
        assertEquals(0L, m.rate(600))
        m.add(1000, 1100) // 3000 bytes in a second
        assertEquals(3000L, m.rate(1100))
        m.add(-500, 1200) // bytes dropped do not count against the speed
        assertEquals(3000L, m.rate(1200))
        assertEquals(0L, m.rate(5000)) // nothing has come for a while
    }
}
