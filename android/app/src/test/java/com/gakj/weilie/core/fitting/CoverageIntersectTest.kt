package com.gakj.weilie.core.fitting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CoverageIntersectTest {

    private fun rect(lat0: Double, lon0: Double, dLat: Double, dLon: Double) = listOf(
        lat0 - dLat to lon0 - dLon,
        lat0 - dLat to lon0 + dLon,
        lat0 + dLat to lon0 + dLon,
        lat0 + dLat to lon0 - dLon,
    )

    @Test
    fun overlappingSquaresGiveOverlapSquare() {
        val a = rect(0.0, 0.0, 0.001, 0.001)     // [-0.001, 0.001]²
        val b = rect(0.001, 0.001, 0.001, 0.001) // [0, 0.002]²
        val inter = CoverageIntersect.intersect(a, b)
        assertTrue("交集应为多边形，实际 ${inter.size} 点", inter.size >= 3)
        val area = abs(CoverageIntersect.signedArea(inter))
        // 交集是 [0, 0.001]² 的正方形
        assertEquals(0.001 * 0.001, area, 1e-9)
    }

    @Test
    fun disjointSquaresGiveEmpty() {
        val a = rect(0.0, 0.0, 0.001, 0.001)
        val b = rect(1.0, 1.0, 0.001, 0.001)
        assertTrue(CoverageIntersect.intersect(a, b).isEmpty())
    }

    @Test
    fun subjectInsideClipIsPreserved() {
        val clip = rect(39.9, 116.4, 0.01, 0.01)
        val subject = rect(39.9, 116.4, 0.002, 0.002)
        val inter = CoverageIntersect.intersect(subject, clip)
        assertTrue(inter.size >= 3)
        assertEquals(abs(CoverageIntersect.signedArea(subject)), abs(CoverageIntersect.signedArea(inter)), 1e-12)
    }

    @Test
    fun circlePolygonIsConvexAndClosed() {
        val poly = CoverageIntersect.circlePolygon(39.9, 116.4, 300.0, 48)
        assertEquals(48, poly.size)
        // 顶点顺序一个方向绕行，有向面积应为正
        assertTrue(CoverageIntersect.signedArea(poly) > 0.0)
    }

    @Test
    fun invalidInputReturnsEmpty() {
        assertTrue(CoverageIntersect.intersect(emptyList(), rect(0.0, 0.0, 1.0, 1.0)).isEmpty())
        assertTrue(CoverageIntersect.intersect(rect(0.0, 0.0, 1.0, 1.0), listOf(0.0 to 0.0)).isEmpty())
    }
}
