package com.gakj.weilie.core.fitting

import com.gakj.weilie.core.model.Rat
import com.gakj.weilie.core.model.SampleRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模拟实测区域（轨迹缓冲成面）与飞点筛选的单元测试。
 *
 * 现场问题：一个定位失败的采样点（0,0 / 粗定位 / 差几公里）就能把面积撑到城市尺度，
 * 或者让凹包退化成大片异常覆盖；这里保证「先剔飞点、再按半径铺圆/方块」的链路成立。
 */
class TrackBufferAreaTest {

    private val lat0 = 39.9
    private val lon0 = 116.4
    private var ts = 1_700_000_000_000L

    private fun sample(dxM: Double, dyM: Double, acc: Float = 10f, rsrp: Int = -80): SampleRecord {
        val lat = lat0 + dyM / 110540.0
        val lon = lon0 + dxM / (111320.0 * kotlin.math.cos(Math.toRadians(lat0)))
        return SampleRecord(
            tsEpochMs = ts++,
            lat = lat,
            lon = lon,
            accuracyM = acc,
            provider = "test",
            servingRat = Rat.NR,
            rsrp = rsrp,
            onTarget = true,
            targetSeen = true,
            targetRsrp = rsrp,
        )
    }

    /** 一条自西向东 200 m 的直线轨迹，每 20 m 一个点 */
    private fun lineSamples(): List<SampleRecord> = (0..10).map { sample(it * 20.0, 0.0) }

    private fun bboxM(ring: List<Pair<Double, Double>>): Pair<Double, Double> {
        val pts = ring.map { Geo.toLocal(it.first, it.second, lat0, lon0) }
        return (pts.maxOf { it.x } - pts.minOf { it.x }) to (pts.maxOf { it.y } - pts.minOf { it.y })
    }

    @Test
    fun `circle buffer follows the track`() {
        val res = TrackBufferArea.build(lineSamples(), TrackBufferArea.Options(radiusM = 30.0))
        assertNotNull(res)
        val (w, h) = bboxM(res!!.ringLatLon)
        // 200 m 轨迹 + 两侧各 30 m ⇒ 宽度约 60 m，长度约 260 m
        assertTrue("w=$w", w in 200.0..320.0)
        assertTrue("h=$h", h in 45.0..90.0)
        // 走廊面积 ≈ 200×60 + 两端半圆 ≈ 14800 m²，抽样误差允许 ±40%
        assertTrue("area=${res.areaM2}", res.areaM2 in 8_000.0..22_000.0)
        assertEquals(11, res.pointCount)
    }

    @Test
    fun `square buffer is wider than circle at the same radius`() {
        val line = lineSamples()
        val circle = TrackBufferArea.build(line, TrackBufferArea.Options(radiusM = 30.0, shape = TrackBufferArea.Shape.CIRCLE))
        val square = TrackBufferArea.build(line, TrackBufferArea.Options(radiusM = 30.0, shape = TrackBufferArea.Shape.SQUARE))
        assertNotNull(circle)
        assertNotNull(square)
        // 方形用切比雪夫距离，同样的“半径”铺得更满，面积只会更大
        assertTrue("square=${square!!.areaM2} circle=${circle!!.areaM2}", square.areaM2 >= circle.areaM2 * 0.95)
    }

    @Test
    fun `flying point is dropped before buffering`() {
        val withFly = lineSamples() + listOf(sample(5_000.0, 5_000.0, acc = 500f))
        val res = TrackBufferArea.build(withFly, TrackBufferArea.Options(radiusM = 30.0))
        assertNotNull(res)
        val (w, h) = bboxM(res!!.ringLatLon)
        assertTrue("w=$w h=$h", w < 500.0 && h < 500.0)
        // 5 km 外的粗定位点被剔除，只剩原来的 11 个有效点
        assertEquals(11, res.pointCount)
    }

    @Test
    fun `sanity filter removes zero coords and coarse fixes`() {
        val good = lineSamples()
        val zero = sample(0.0, 0.0).copy(lat = 0.0, lon = 0.0)
        val coarse = sample(10.0, 10.0, acc = 300f)
        val sane = TrackSanity.saneSamples(good + zero + coarse)
        assertEquals(good.size, sane.size)
        assertTrue(sane.none { it.lat == 0.0 && it.lon == 0.0 })
        assertTrue(sane.none { (it.accuracyM ?: 0f) > 120f })
    }

    @Test
    fun `sanity filter keeps short tracks untouched`() {
        val two = listOf(sample(0.0, 0.0), sample(10.0, 0.0))
        assertEquals(2, TrackSanity.saneSamples(two).size)
    }

    @Test
    fun `area builder simulated mode returns simulated kind`() {
        val res = AreaBuilder.build(
            lineSamples(),
            AreaBuilder.Options(mode = AreaBuilder.Mode.SIMULATED, bufferRadiusM = 25.0),
        )
        assertEquals(AreaBuilder.Kind.SIMULATED, res.kind)
        assertTrue(res.ringLatLon.size >= 3)
        assertTrue(res.areaM2 > 0.0)
    }

    @Test
    fun `area builder auto mode is not blown up by a flying point`() {
        val withFly = lineSamples() + listOf(sample(5_000.0, 5_000.0, acc = 500f))
        val res = AreaBuilder.build(withFly, AreaBuilder.Options())
        assertTrue(res.kind != AreaBuilder.Kind.NONE)
        val (w, h) = bboxM(res.ringLatLon)
        // 没剔飞点时这里是 5 km 级别的巨幅图形
        assertTrue("w=$w h=$h", w < 1_000.0 && h < 1_000.0)
    }

    @Test
    fun `single point circle is smooth enough`() {
        val one = listOf(sample(0.0, 0.0))
        val res = TrackBufferArea.build(one, TrackBufferArea.Options(radiusM = 30.0))!!
        // 网格步长半径/5、简化容差收紧后，30m 圆应有足够多的顶点（不再是七八条边的粗多边形）
        assertTrue("vertices=${res.ringLatLon.size}", res.ringLatLon.size >= 14)
        val (w, h) = bboxM(res.ringLatLon)
        assertTrue("w=$w h=$h", w in 50.0..75.0 && h in 50.0..75.0)
    }

    @Test
    fun `empty input yields null`() {
        assertNull(TrackBufferArea.build(emptyList()))
    }
}
