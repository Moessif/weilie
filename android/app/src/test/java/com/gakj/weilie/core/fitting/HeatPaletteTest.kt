package com.gakj.weilie.core.fitting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeatPaletteTest {

    @Test
    fun strongValuesUseDifferentColorsWithAlpha() {
        val opt = HeatPalette.Options()
        val c70 = HeatPalette.colorFor(-70.0, opt)
        val c80 = HeatPalette.colorFor(-80.0, opt)
        assertNotEquals(c70, c80)
        val alpha = (c70 ushr 24) and 0xFF
        assertTrue("alpha=$alpha", alpha in 100..255)
        assertEquals(150, alpha)
    }

    @Test
    fun weakValueIsReddish() {
        val c = HeatPalette.colorFor(-120.0)
        val r = (c shr 16) and 0xFF
        val b = c and 0xFF
        assertTrue("r=$r b=$b", r > b)
    }

    @Test
    fun middleValueUsesYellow() {
        val c = HeatPalette.colorFor(-95.0)
        assertEquals(0xFDE047, c and 0xFFFFFF)
    }

    @Test
    fun nanCellIsTransparent() {
        assertEquals(0, HeatPalette.colorFor(Double.NaN))
    }

    @Test
    fun renderHasWidthTimesHeightSize() {
        val values = doubleArrayOf(Double.NaN, -70.0, -100.0, -120.0)
        val out = HeatPalette.render(values, 2, 2)
        assertEquals(4, out.size)
        assertEquals(0, out[0])
        assertNotEquals(0, out[1])
    }

    @Test
    fun customAlphaIsRespected() {
        val opt = HeatPalette.Options(alpha = 200)
        val c = HeatPalette.colorFor(-95.0, opt)
        assertEquals(200, (c ushr 24) and 0xFF)
    }
}
