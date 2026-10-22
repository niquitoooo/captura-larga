package com.prieto.capturalarga.frame

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SolidColorTest {
    @Test
    fun blackScreenIsSingleColor() {
        assertTrue(SolidColor.isSingleColor(1080, 2400) { _, _ -> 0xFF000000.toInt() })
    }

    @Test
    fun blackScreenWithStatusAndNavigationBarsIsStillSingleColor() {
        // A blocked app: the window is black, the system bars are drawn normally.
        val pixel = { x: Int, y: Int -> if (y < 120 || y > 2300) x * 7919 else 0xFF000000.toInt() }
        assertTrue(SolidColor.isSingleColor(1080, 2400, pixel))
    }

    @Test
    fun ordinaryContentIsNotSingleColor() {
        assertFalse(SolidColor.isSingleColor(1080, 2400) { x, y -> x * 31 + y * 17 })
    }

    @Test
    fun oneDifferentSampleInTheMiddleIsEnough() {
        val pixel = { x: Int, y: Int -> if (x == 540 && y == 1200) 1 else 0 }
        assertFalse(SolidColor.isSingleColor(1080, 2400, pixel))
    }
}
