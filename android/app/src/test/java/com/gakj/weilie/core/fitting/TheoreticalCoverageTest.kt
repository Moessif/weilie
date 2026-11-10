package com.gakj.weilie.core.fitting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TheoreticalCoverageTest {

    private val lat0 = 39.9
    private val lon0 = 116.4

    private fun pointAt(dNorthM: Double, dEastM: Double): Pair<Double, Double> =
        Geo.fromLocal(dEastM, dNorthM, lat0, lon0)

    @Test
    fun omniCircleOutlineAndContains() {
        val spec = TheorySpec(lat = lat0, lon = lon0, radiusM = 1000.0)
        val outline = TheoreticalCoverage.outline(spec)
        assertTrue("outline.size=${outline.size}", outline.size >= 12)

        assertTrue(TheoreticalCoverage.contains(spec, lat0, lon0))

        val far = pointAt(1500.0, 0.0)
        assertTrue(!TheoreticalCoverage.contains(spec, far.first, far.second))
    }

    @Test
    fun sectorKeepsEastAndRejectsWest() {
        val spec = TheorySpec(
            lat = lat0,
            lon = lon0,
            azimuthDeg = 90.0,
            sectorWidthDeg = 120.0,
            radiusM = 1000.0,
        )
        val east = pointAt(0.0, 500.0)
        val west = pointAt(0.0, -500.0)
        assertTrue(TheoreticalCoverage.contains(spec, east.first, east.second))
        assertTrue(!TheoreticalCoverage.contains(spec, west.first, west.second))
    }

    @Test
    fun innerRadiusCreatesHole() {
        val spec = TheorySpec(lat = lat0, lon = lon0, radiusM = 1000.0, innerRadiusM = 300.0)
        assertTrue(!TheoreticalCoverage.contains(spec, lat0, lon0))
        val p = pointAt(0.0, 500.0)
        assertTrue(TheoreticalCoverage.contains(spec, p.first, p.second))
    }

    @Test
    fun bearingOfEastPointIsNinety() {
        val spec = TheorySpec(lat = lat0, lon = lon0)
        val east = pointAt(0.0, 500.0)
        val b = TheoreticalCoverage.bearingOf(spec, east.first, east.second)
        assertEquals(90.0, b, 5.0)
    }

    @Test
    fun outlineHasNoDuplicatedClosingVertex() {
        val spec = TheorySpec(lat = lat0, lon = lon0, radiusM = 800.0)
        val outline = TheoreticalCoverage.outline(spec)
        assertTrue(outline.size >= 3)
        val first = outline.first()
        val last = outline.last()
        assertTrue(first.first != last.first || first.second != last.second)
    }
}
