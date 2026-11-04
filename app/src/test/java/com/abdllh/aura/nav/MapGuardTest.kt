package com.abdllh.aura.nav

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a mark "the map was up" left by a process that is gone may mean: only the same boot and a short life are the map's fault. */
class MapGuardTest {
    private val boot = "3583a007"

    @Test fun goneSoonAfterItCameUp() = assertTrue(MapGuard.liveMarkCounts("$boot 5000000", boot, 5_030_000))

    @Test fun justInsideTheWindow() = assertTrue(MapGuard.liveMarkCounts("$boot 5000000", boot, 5_180_000))

    @Test fun aMapThatLivedLongerIsNotCounted() = assertFalse(MapGuard.liveMarkCounts("$boot 5000000", boot, 5_180_001))

    @Test fun aCarLosingItsPowerIsAnotherBoot() = assertFalse(MapGuard.liveMarkCounts("aaaaaaaa 5000000", boot, 5_030_000))

    @Test fun aClockFromAnotherLifeIsNotCounted() = assertFalse(MapGuard.liveMarkCounts("$boot 5000000", boot, 4_000_000))

    @Test fun whatCannotBeReadIsNotCounted() {
        assertFalse(MapGuard.liveMarkCounts("", boot, 5_030_000))
        assertFalse(MapGuard.liveMarkCounts(boot, boot, 5_030_000))
        assertFalse(MapGuard.liveMarkCounts("$boot soon", boot, 5_030_000))
    }

    @Test fun aNewlineAtTheEndIsNothing() = assertTrue(MapGuard.liveMarkCounts("$boot 5000000\n", boot, 5_030_000))
}
