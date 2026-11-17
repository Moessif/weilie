package com.gakj.weilie.core.fitting

import com.gakj.weilie.core.model.SampleRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 双引擎自动选优（v0.13.2）。
 *
 * 规则：有效点 < 30 或 平均最近邻距 > 40 m → 轨迹缓冲；否则凹包。
 */
class CoverageFitterEngineTest {

    private val lat0 = 39.90
    private val lon0 = 116.30
    private val mPerDegLat = 111_320.0
    private val mPerDegLon = 111_320.0 * kotlin.math.cos(Math.toRadians(lat0))

    /** (north, east) 米 → 经纬度 */
    private fun p(n: Double, e: Double): Pair<Double, Double> =
        (lat0 + n / mPerDegLat) to (lon0 + e / mPerDegLon)

    private fun sample(i: Int, n: Double, e: Double, tsBase: Long): SampleRecord {
        val (lat, lon) = p(n, e)
        return SampleRecord(
            tsEpochMs = tsBase + i * 1000L,
            lat = lat,
            lon = lon,
            accuracyM = 8f,
            rsrp = -95,
            servingRat = null,
            onTarget = true,
            targetSeen = true,
        )
    }

    private fun samples(points: List<Pair<Double, Double>>): List<SampleRecord> {
        val t0 = 1_700_000_000_000L
        return points.mapIndexed { i, (n, e) -> sample(i, n, e, t0) }
    }

    @Test
    fun sparse_points_use_track_buffer() {
        // 12 个点，间隔 60 m：点数 < 30，应走轨迹缓冲
        val pts = (0 until 12).map { it * 60.0 to 0.0 }
        val fit = CoverageFitter.fit(samples(pts))

        assertNotNull("稀疏点也应能成面", fit)
        assertEquals(CoverageResult.Engine.TRACK_BUFFER, fit!!.engine)
        assertTrue("原因应说明点数不足：${fit.engineReason}", fit.engineReason.contains("有效点"))
        assertTrue("缓冲面积应大于 0", (fit.trackBufferAreaM2 ?: 0.0) > 0.0)
        assertTrue(fit.hullLatLon.size >= 3)
    }

    @Test
    fun dense_points_use_alpha_shape() {
        // 10×10 = 100 个点铺成 90×90 m 网格（间距 10 m），应走凹包
        val pts = ArrayList<Pair<Double, Double>>()
        for (i in 0 until 10) for (j in 0 until 10) pts.add(i * 10.0 to j * 10.0)
        val fit = CoverageFitter.fit(samples(pts))

        assertNotNull(fit)
        assertEquals(CoverageResult.Engine.ALPHA_SHAPE, fit!!.engine)
        assertTrue("原因应包含点数或点距：${fit.engineReason}", fit.engineReason.isNotBlank())
        assertTrue(fit.hullLatLon.size >= 3)
    }

    @Test
    fun far_apart_points_use_track_buffer_even_if_many() {
        // 40 个点但点距 80 m（> 40 m 阈值），应走轨迹缓冲
        val pts = (0 until 40).map { it * 80.0 to 0.0 }
        val fit = CoverageFitter.fit(samples(pts))

        assertNotNull(fit)
        assertEquals(CoverageResult.Engine.TRACK_BUFFER, fit!!.engine)
        assertTrue("原因应说明点距过大：${fit.engineReason}", fit.engineReason.contains("平均点距"))
    }

    @Test
    fun stability_flag_is_off_by_default_and_can_be_enabled() {
        val pts = ArrayList<Pair<Double, Double>>()
        for (i in 0 until 10) for (j in 0 until 10) pts.add(i * 10.0 to j * 10.0)
        val s = samples(pts)

        val plain = CoverageFitter.fit(s)
        assertNotNull(plain)
        assertTrue("默认不做双引擎对比", !plain!!.unstable)

        val withStability = CoverageFitter.fit(s, CoverageFitter.Options(computeStability = true))
        assertNotNull(withStability)
    }

    @Test
    fun average_nearest_neighbor_distance_is_sane() {
        val line = (0 until 10).map { Pt(it * 10.0, 0.0) }
        val d = CoverageFitter.averageNearestNeighborM(line)
        assertEquals(10.0, d, 0.5)
    }
}
