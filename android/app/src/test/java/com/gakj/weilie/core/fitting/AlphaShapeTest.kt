package com.gakj.weilie.core.fitting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AlphaShapeTest {

    /** L 形点集：竖臂 x∈{0,10}，y=0..70；横臂 y∈{0,10}，x=0..70（各 8 点） */
    private fun lShape(): List<Pt> {
        val pts = ArrayList<Pt>()
        var y = 0.0
        while (y <= 70.0) {
            pts.add(Pt(0.0, y))
            pts.add(Pt(10.0, y))
            y += 10.0
        }
        var x = 0.0
        while (x <= 70.0) {
            pts.add(Pt(x, 0.0))
            pts.add(Pt(x, 10.0))
            x += 10.0
        }
        return pts
    }

    @Test
    fun alphaShapeIsSmallerThanConvexHull() {
        val pts = lShape()
        val hull = AlphaShape.hull(pts)
        val convex = ConvexHull.hull(pts)

        assertTrue("hull.size=${hull.size}", hull.size >= 3)
        assertTrue("convex hull size=${convex.size}", convex.size >= 3)

        val alphaArea = ConvexHull.area(hull)
        val convexArea = ConvexHull.area(convex)
        assertTrue("convexArea=$convexArea", convexArea > 0.0)
        assertTrue(
            "alphaArea=$alphaArea convexArea=$convexArea",
            alphaArea < 0.8 * convexArea,
        )
        assertTrue("outline must be a simple polygon", isSimple(hull))
    }

    @Test
    fun tooFewPointsGivesEmpty() {
        assertTrue(AlphaShape.hull(emptyList()).isEmpty())
        assertTrue(AlphaShape.hull(listOf(Pt(0.0, 0.0))).isEmpty())
        assertTrue(AlphaShape.hull(listOf(Pt(0.0, 0.0), Pt(10.0, 0.0))).isEmpty())
    }

    @Test
    fun hullHasNoDuplicatedVertices() {
        val hull = AlphaShape.hull(lShape())
        assertTrue(hull.size >= 3)
        assertEquals(hull.size, hull.map { it.x to it.y }.distinct().size)
    }

    // ---------- 简单多边形判定（相邻边不相交，非相邻边不交叉） ----------

    private fun isSimple(poly: List<Pt>): Boolean {
        val n = poly.size
        if (n < 3) return false
        for (i in 0 until n) {
            val a1 = poly[i]
            val a2 = poly[(i + 1) % n]
            for (j in i + 1 until n) {
                if (j == (i + 1) % n) continue          // 相邻边
                if ((j + 1) % n == i) continue          // 相邻边（最后一与第一条）
                val b1 = poly[j]
                val b2 = poly[(j + 1) % n]
                if (segmentsCross(a1, a2, b1, b2)) return false
            }
        }
        return true
    }

    private fun cross(a: Pt, b: Pt): Double = a.x * b.y - a.y * b.x

    private fun orient(a: Pt, b: Pt, c: Pt): Double =
        cross(Pt(b.x - a.x, b.y - a.y), Pt(c.x - a.x, c.y - a.y))

    private fun segmentsCross(a: Pt, b: Pt, c: Pt, d: Pt): Boolean {
        val eps = 1e-9
        val d1 = orient(a, b, c)
        val d2 = orient(a, b, d)
        val d3 = orient(c, d, a)
        val d4 = orient(c, d, b)
        val proper1 = (d1 > eps && d2 < -eps) || (d1 < -eps && d2 > eps)
        val proper2 = (d3 > eps && d4 < -eps) || (d3 < -eps && d4 > eps)
        return proper1 && proper2
    }
}
