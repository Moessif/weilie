package com.gakj.weilie.core.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 边界精度评估器（v0.13.1）。
 *
 * 用「米 → 度」的小工具构造已知形状，避免直接堆经纬度数字。
 */
class BoundaryEvaluatorTest {

    private val lat0 = 39.90
    private val lon0 = 116.30
    private val mPerDegLat = 111_320.0
    private val mPerDegLon = 111_320.0 * kotlin.math.cos(Math.toRadians(lat0))

    /** (north, east) 米 → 经纬度 */
    private fun p(north: Double, east: Double): Pair<Double, Double> =
        (lat0 + north / mPerDegLat) to (lon0 + east / mPerDegLon)

    /** 以 (n,e) 左下角、边长 side 的正方形；offset 整体平移 */
    private fun square(n: Double, e: Double, side: Double): List<Pair<Double, Double>> = listOf(
        p(n, e), p(n, e + side), p(n + side, e + side), p(n + side, e)
    )

    @Test
    fun identical_polygons_give_perfect_score() {
        val poly = square(0.0, 0.0, 100.0)
        val r = BoundaryEvaluator.evaluate(poly, poly)

        assertEquals(1.0, r.iou, 0.02)
        assertEquals(0.0, r.areaBias, 1e-6)
        assertTrue("medianOffset=${r.medianOffsetM}", r.medianOffsetM < 0.5)
        assertTrue("hausdorff=${r.hausdorffApproxM}", r.hausdorffApproxM < 1.0)
        assertEquals(10_000.0, r.referenceAreaM2, 200.0)
    }

    @Test
    fun square_shifted_by_10m_matches_expected_iou_and_area_bias() {
        // 真值 100×100；实测整体向东、向北各偏 10 m
        val ref = square(0.0, 0.0, 100.0)
        val mea = square(10.0, 10.0, 100.0)
        val r = BoundaryEvaluator.evaluate(ref, mea)

        // 理论 IoU = 90×90 / (2×10000 − 8100) = 8100/11900 ≈ 0.681
        assertTrue("iou=${r.iou}", r.iou > 0.62 && r.iou < 0.74)
        // 面积不变
        assertTrue("areaBias=${r.areaBias}", abs(r.areaBias) < 0.02)
        // 边界偏移中位数应接近 10 m
        assertTrue("medianOffset=${r.medianOffsetM}", r.medianOffsetM > 6.0 && r.medianOffsetM < 14.0)
    }

    @Test
    fun half_truncated_triangle_reports_low_iou() {
        val ref = listOf(p(0.0, 0.0), p(0.0, 100.0), p(100.0, 50.0))
        // 只保留三角形下半部分（y < 50）
        val mea = listOf(p(0.0, 0.0), p(0.0, 100.0), p(50.0, 50.0))
        val r = BoundaryEvaluator.evaluate(ref, mea)

        assertTrue("iou=${r.iou}", r.iou > 0.30 && r.iou < 0.70)
        assertTrue("areaBias=${r.areaBias}", r.areaBias < 0.0)
    }

    @Test
    fun degenerate_input_returns_zero_not_crash() {
        val r = BoundaryEvaluator.evaluate(listOf(p(0.0, 0.0), p(1.0, 1.0)), square(0.0, 0.0, 10.0))
        assertEquals(0.0, r.iou, 0.0)
        assertTrue(r.medianOffsetM.isNaN())
    }
}
