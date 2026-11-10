package com.gakj.weilie.core.fitting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 需求 3.2：Douglas–Peucker 抽稀 + 边缘内缩。
 * 这些用例只锁行为（点数、面积方向），不锁具体 epsilon 取值，
 * 方便以后换算法（比如换成 Topology-preserving 简化）。
 */
class PolylineSimplifyTest {

    @Test
    fun `抽稀去掉共线中间点`() {
        val pts = listOf(
            Pt(0.0, 0.0),
            Pt(1.0, 0.0),
            Pt(2.0, 0.0),
            Pt(2.0, 2.0),
        )
        val out = PolylineSimplify.douglasPeucker(pts, 0.5)
        assertEquals(3, out.size)
        assertEquals(Pt(0.0, 0.0), out.first())
        assertEquals(Pt(2.0, 2.0), out.last())
    }

    @Test
    fun `抽稀保留真实拐点`() {
        val pts = listOf(Pt(0.0, 0.0), Pt(1.0, 3.0), Pt(2.0, 0.0))
        val out = PolylineSimplify.douglasPeucker(pts, 0.5)
        assertEquals(3, out.size)
        assertTrue(out.contains(Pt(1.0, 3.0)))
    }

    @Test
    fun `首尾点永远保留`() {
        val pts = listOf(Pt(0.0, 0.0), Pt(1.0, 0.1), Pt(2.0, 0.0))
        val out = PolylineSimplify.douglasPeucker(pts, 100.0)
        assertEquals(2, out.size)
        assertEquals(Pt(0.0, 0.0), out.first())
        assertEquals(Pt(2.0, 0.0), out.last())
    }

    @Test
    fun `闭合环抽稀后仍闭合且不少于三点`() {
        val square = listOf(
            Pt(0.0, 0.0),
            Pt(5.0, 0.0),
            Pt(10.0, 0.0),
            Pt(10.0, 10.0),
            Pt(5.0, 10.0),
            Pt(0.0, 10.0),
        )
        val out = PolylineSimplify.simplifyRing(square, 0.5)
        assertEquals(4, out.size)
    }

    @Test
    fun `内缩之后面积变小且顶点数不变`() {
        val square = listOf(
            Pt(-10.0, -10.0),
            Pt(10.0, -10.0),
            Pt(10.0, 10.0),
            Pt(-10.0, 10.0),
        )
        val inset = PolylineSimplify.insetRing(square, 2.0)
        assertEquals(square.size, inset.size)
        assertTrue("内缩后面积必须变小", absArea(inset) < absArea(square))
        assertTrue("内缩幅度不应超过 35%", absArea(inset) > absArea(square) * 0.6)
    }

    @Test
    fun `内缩量为零时原样返回`() {
        val square = listOf(Pt(0.0, 0.0), Pt(4.0, 0.0), Pt(4.0, 4.0), Pt(0.0, 4.0))
        val out = PolylineSimplify.insetRing(square, 0.0)
        assertEquals(square, out)
    }

    private fun absArea(ring: List<Pt>): Double {
        var acc = 0.0
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[(i + 1) % ring.size]
            acc += a.x * b.y - b.x * a.y
        }
        return kotlin.math.abs(acc) / 2.0
    }
}
