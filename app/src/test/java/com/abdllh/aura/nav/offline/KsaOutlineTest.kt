package com.abdllh.aura.nav.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KsaOutlineTest {
    // the places tools/mapbuild checked its polygon with (tiles.json), lat, lon
    private val inside = listOf(
        "causeway island" to (26.186 to 50.334), "Khobar" to (26.2885 to 50.2185), "Salwa" to (24.74 to 50.77), "Al Batha" to (24.1 to 51.52),
        "Khafji" to (28.44 to 48.49), "Jazan" to (16.89 to 42.56), "Abha" to (18.216 to 42.505), "Riyadh" to (24.7136 to 46.6753),
        "Jeddah" to (21.54 to 39.17), "Tabuk" to (28.38 to 36.57), "Farasan" to (16.7 to 42.1), "Jubail" to (27.0046 to 49.6586),
        "Makkah" to (21.4225 to 39.826), "the Empty Quarter" to (20.5 to 50.5)
    )
    private val outside = listOf(
        "Manama" to (26.2235 to 50.5876), "Doha" to (25.2854 to 51.531), "Dubai" to (25.2048 to 55.2708), "Muscat" to (23.588 to 58.3829),
        "Cairo" to (30.0444 to 31.2357), "Baghdad" to (33.3152 to 44.3661), "Sanaa" to (15.3694 to 44.191), "Tehran" to (35.6892 to 51.389)
    )

    @Test
    fun places() {
        for ((name, p) in inside) assertTrue(name, KsaOutline.contains(p.first, p.second))
        for ((name, p) in outside) assertFalse(name, KsaOutline.contains(p.first, p.second))
    }

    @Test
    fun aTileUnderAPlaceTouches() {
        for (z in 0..14) for ((name, p) in inside) {
            assertTrue("$name z$z", KsaOutline.touchesTile(z, TileMath.x(p.second, z), TileMath.y(p.first, z)))
        }
        for (z in 11..14) for ((name, p) in outside) {
            assertFalse("$name z$z", KsaOutline.touchesTile(z, TileMath.x(p.second, z), TileMath.y(p.first, z)))
        }
    }

    @Test
    fun aTileInsideWithNoEdgeInItTouches() {
        // deep in the country: no edge of the outline passes through this tile, only the last test can say yes
        assertTrue(KsaOutline.touchesTile(10, TileMath.x(45.0, 10), TileMath.y(24.0, 10)))
    }

    @Test
    fun anEdgeThroughATileTouchesThoughItsMiddleIsOutside() {
        // the border at Hafr Al-Batin's north, the tile's middle in Iraq or Kuwait or the Kingdom: a whole z6 row along the line says yes
        val z = 6
        val y = TileMath.y(29.0, z)
        assertTrue((0 until (1 shl z)).count { KsaOutline.touchesTile(z, it, y) } >= 3)
    }

    @Test
    fun farAwayNeverTouches() {
        assertFalse(KsaOutline.touchesTile(5, TileMath.x(2.3, 5), TileMath.y(48.8, 5))) // Paris
        assertEquals(1, if (KsaOutline.touchesTile(0, 0, 0)) 1 else 0) // the whole world holds it
    }
}
