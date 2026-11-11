package com.gakj.weilie.core.fitting

import com.gakj.weilie.core.model.Rat
import com.gakj.weilie.core.model.SampleRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「模拟实测区域」只铺在命中目标的采样点周围。
 *
 * 真机反馈：目标未命中、人已经走出目标基站覆盖范围，地图上仍然沿整条轨迹铺出模拟区域，
 * 看起来像「没信号的地方也测到了覆盖」。这里锁住三件事：
 * 1. 只有主区命中 / 邻区命中的点参与成面；
 * 2. 脱离目标的轨迹段不扩大面积，也不把边界拉过去；
 * 3. 一个命中点都没有时返回 NONE，而不是画一条空条带。
 */
class AreaBuilderSimulatedTest {

    private val lat0 = 39.9
    private val lon0 = 116.4
    private var ts = 1_700_000_000_000L

    private fun sample(
        dxM: Double,
        dyM: Double,
        onTarget: Boolean,
        targetSeen: Boolean,
        targetRsrp: Int? = if (onTarget || targetSeen) -85 else null,
    ): SampleRecord {
        val lat = lat0 + dyM / 110540.0
        val lon = lon0 + dxM / (111320.0 * kotlin.math.cos(Math.toRadians(lat0)))
        return SampleRecord(
            tsEpochMs = ts++,
            lat = lat,
            lon = lon,
            accuracyM = 8f,
            provider = "test",
            servingRat = Rat.NR,
            rsrp = -80,
            onTarget = onTarget,
            targetSeen = targetSeen,
            targetRsrp = targetRsrp,
        )
    }

    private fun bboxM(ring: List<Pair<Double, Double>>): Pair<Double, Double> {
        val pts = ring.map { Geo.toLocal(it.first, it.second, lat0, lon0) }
        return (pts.maxOf { it.x } - pts.minOf { it.x }) to (pts.maxOf { it.y } - pts.minOf { it.y })
    }

    private val opt = AreaBuilder.Options(mode = AreaBuilder.Mode.SIMULATED, bufferRadiusM = 20.0)

    @Test
    fun `out-of-target track is not blown into measured area`() {
        // 前 100 m 命中目标，后 600 m 已脱离目标（OUT）
        val hits = listOf(sample(0.0, 0.0, true, true), sample(100.0, 0.0, true, true))
        val outs = listOf(300.0, 450.0, 600.0, 700.0).map { sample(it, 0.0, false, false) }
        val res = AreaBuilder.build(hits + outs, opt)
        assertEquals(AreaBuilder.Kind.SIMULATED, res.kind)
        assertEquals(2, res.pointCount)
        val (w, _) = bboxM(res.ringLatLon)
        // 100 m 轨迹 + 两端各 20 m 缓冲 ⇒ 140 m 左右，绝不会被 700 m 处的脱离点拉到整段
        assertTrue("w=$w", w in 120.0..190.0)
    }

    @Test
    fun `neighbor hit still counts as measured`() {
        val hits = listOf(
            sample(0.0, 0.0, true, true),
            sample(60.0, 0.0, false, true, targetRsrp = -95),
        )
        val res = AreaBuilder.build(hits, opt)
        assertEquals(AreaBuilder.Kind.SIMULATED, res.kind)
        assertEquals(2, res.pointCount)
    }

    @Test
    fun `weak neighbor hit below floor is excluded`() {
        val weak = sample(0.0, 0.0, false, true, targetRsrp = -125)
        val res = AreaBuilder.build(listOf(weak), opt)
        assertEquals(AreaBuilder.Kind.NONE, res.kind)
        assertEquals(0, res.pointCount)
    }

    @Test
    fun `all out samples produce no area`() {
        val outs = (0..6).map { sample(it * 50.0, 0.0, false, false) }
        val res = AreaBuilder.build(outs, opt)
        assertEquals(AreaBuilder.Kind.NONE, res.kind)
        assertTrue(res.ringLatLon.isEmpty())
    }

    @Test
    fun `single hit point cannot form an area`() {
        val res = AreaBuilder.build(listOf(sample(0.0, 0.0, true, true)), opt)
        assertEquals(AreaBuilder.Kind.NONE, res.kind)
    }

    @Test
    fun `auto mode also ignores out samples`() {
        val hits = (0..8).map { sample(it * 15.0, 0.0, true, true) }
        val outs = (0..5).map { sample(400.0 + it * 50.0, 0.0, false, false) }
        val res = AreaBuilder.build(
            hits + outs,
            AreaBuilder.Options(mode = AreaBuilder.Mode.AUTO),
        )
        assertNotNull(res.kind)
        if (res.kind != AreaBuilder.Kind.NONE) {
            val (w, _) = bboxM(res.ringLatLon)
            assertTrue("w=$w", w < 300.0)
        }
    }
}
