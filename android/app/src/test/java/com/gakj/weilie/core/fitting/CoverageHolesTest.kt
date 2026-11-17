package com.gakj.weilie.core.fitting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 内洞检测 + 面积口径（v0.13.3）。
 */
class CoverageHolesTest {

    /** 圆心 (0,0)、半径 r 的圆上均匀取 n 个点（米制局部坐标） */
    private fun circle(r: Double, n: Int): List<Pt> =
        (0 until n).map { i ->
            val a = 2 * PI * i / n
            Pt(r * cos(a), r * sin(a))
        }

    private fun ringLikeHull(r: Double, n: Int = 72): List<Pt> = circle(r, n)

    @Test
    fun loop_walk_creates_one_hole() {
        val track = circle(100.0, 120)              // 半径 100 m 绕一圈
        val hull = ringLikeHull(115.0)              // 外环略大
        val res = CoverageHoles.detect(
            hull = hull, track = track, lat0 = 39.9, lon0 = 116.3,
            holeRadiusM = 30.0, minHoleAreaM2 = 300.0,
        )

        assertEquals("应识别出 1 个洞", 1, res.holesLocal.size)
        // 洞半径 ≈ 100 − 30 = 70 m → 面积 ≈ π·70² ≈ 15394 m²
        val expected = PI * 70.0 * 70.0
        assertTrue("洞面积 ${res.areaM2} 应接近 $expected", kotlin.math.abs(res.areaM2 - expected) / expected < 0.30)
    }

    @Test
    fun straight_walk_has_no_hole() {
        val track = (0 until 120).map { Pt(it * 3.0, 0.0) }
        val hull = listOf(Pt(0.0, -20.0), Pt(357.0, -20.0), Pt(357.0, 20.0), Pt(0.0, 20.0))
        val res = CoverageHoles.detect(hull, track, 39.9, 116.3)
        assertTrue("直线走测不应有洞", res.isEmpty)
    }

    @Test
    fun tiny_loop_below_min_area_is_ignored() {
        val track = circle(20.0, 40)                // 半径 20 m，洞半径 30 m 时无洞
        val hull = ringLikeHull(30.0)
        val res = CoverageHoles.detect(hull, track, 39.9, 116.3, holeRadiusM = 30.0, minHoleAreaM2 = 300.0)
        assertTrue("小环不应抠洞", res.isEmpty)
    }

    @Test
    fun is_loop_detects_closed_and_open_paths() {
        assertTrue(CoverageHoles.isLoop(circle(100.0, 120)))
        val open = (0 until 120).map { Pt(it * 3.0, 0.0) }
        assertTrue("直线不应判为环", !CoverageHoles.isLoop(open))
    }

    @Test
    fun fit_subtracts_hole_area_from_coverage() {
        // 用一条半径 100 m 的环形走测构造会话（时间递增，RSRP 合格）
        val t0 = 1_700_000_000_000L
        val samples = circle(100.0, 120).mapIndexed { i, p ->
            com.gakj.weilie.core.model.SampleRecord(
                tsEpochMs = t0 + i * 3000L,
                lat = 39.9 + p.y / 111_320.0,
                lon = 116.3 + p.x / (111_320.0 * cos(Math.toRadians(39.9))),
                accuracyM = 8f,
                rsrp = -95,
                onTarget = true,
                targetSeen = true,
            )
        }
        // 强制走「轨迹缓冲」引擎：它只取最外环，最容易把中间没走过的区域填实
        val opt = CoverageFitter.Options(
            trackBufferMinPoints = 200,   // 让引擎选到轨迹缓冲
            trackBufferRadiusM = 30.0,
            holeRadiusM = 25.0,
            minHoleAreaM2 = 300.0,
        )
        val fit = CoverageFitter.fit(samples, opt)
        assertTrue("环形走测应能拟合", fit != null)
        fit!!
        assertEquals(CoverageResult.Engine.TRACK_BUFFER, fit.engine)
        assertTrue("应有内洞", fit.holesLatLon.isNotEmpty())
        assertTrue("洞面积 > 0", fit.holesAreaM2 > 0.0)
        assertTrue(
            "覆盖面积必须小于外环面积：${fit.coverageAreaM2} vs ${fit.hullAreaM2}",
            fit.coverageAreaM2 < fit.hullAreaM2,
        )
        assertEquals(
            "覆盖面积 = 外环 − 洞",
            fit.hullAreaM2 - fit.holesAreaM2,
            fit.coverageAreaM2,
            1.0,
        )
    }
}
