package com.gakj.weilie.server

import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * v1.2 任务区覆盖率：圆区 / 多边形 / 边界情况。
 * 用固定经纬度把"米"换算成度数，避免依赖真实地图数据。
 */
class ZoneCoverageTest {

    private val lat0 = 39.9
    private val lon0 = 116.3
    private fun latOf(dy: Double) = lat0 + dy / 111_320.0
    private fun lonOf(dx: Double) = lon0 + dx / (111_320.0 * cos(Math.toRadians(lat0)))

    private fun sample(userId: String, dx: Double, dy: Double) =
        WsMsg.SamplePing(userId = userId, lat = latOf(dy), lon = lonOf(dx), ts = System.currentTimeMillis())

    @Test
    fun `圆区无采样时覆盖率为零`() {
        val zone = WsMsg.Zone(
            id = "z1", by = "u1", kind = "CIRCLE",
            centerLat = lat0, centerLon = lon0, radiusM = 100.0, ts = 0,
        )
        val stat = ZoneCoverage.stat(zone, emptyList(), cellM = 20.0)
        assertEquals(0, stat.samples)
        assertEquals(0, stat.covered)
        assertTrue(stat.total > 0)
        assertEquals(0.0, stat.ratio)
    }

    @Test
    fun `圆区中心一条线穿过时覆盖率在合理区间`() {
        val zone = WsMsg.Zone(
            id = "z2", by = "u1", kind = "CIRCLE",
            centerLat = lat0, centerLon = lon0, radiusM = 100.0, ts = 0,
        )
        // 沿 x 轴每 5m 一个点，覆盖中间一条带
        val samples = (-20..20).map { i -> sample("u1", i * 5.0, 0.0) }
        val stat = ZoneCoverage.stat(zone, samples, cellM = 10.0)
        assertTrue(stat.total > 50, "网格数应足够大: ${stat.total}")
        assertTrue(stat.covered > 0)
        assertTrue(stat.ratio in 0.02..0.6, "带状覆盖比例应在此区间: ${stat.ratio}")
    }

    @Test
    fun `多边形只统计区内采样`() {
        // 20m 见方的小方块
        val half = 10.0
        val poly = listOf(
            listOf(latOf(-half), lonOf(-half)),
            listOf(latOf(-half), lonOf(half)),
            listOf(latOf(half), lonOf(half)),
            listOf(latOf(half), lonOf(-half)),
        )
        val zone = WsMsg.Zone(id = "z3", by = "u1", kind = "POLYGON", polygon = poly, ts = 0)
        val inside = sample("u1", 0.0, 0.0)
        val outside = sample("u1", 500.0, 500.0)
        val stat = ZoneCoverage.stat(zone, listOf(inside, outside), cellM = 5.0)
        assertTrue(stat.total > 0)
        assertTrue(stat.covered > 0)
        assertTrue(stat.covered < stat.total, "外侧点不应被计入覆盖")
    }

    @Test
    fun `只统计负责人样本`() {
        val zone = WsMsg.Zone(
            id = "z4", by = "u1", kind = "CIRCLE",
            centerLat = lat0, centerLon = lon0, radiusM = 60.0, ts = 0,
            assignees = listOf("u2"),
        )
        val mine = sample("u1", 0.0, 0.0)
        val assignee = sample("u2", 0.0, 0.0)
        val stat = ZoneCoverage.stat(zone, listOf(mine, assignee), cellM = 20.0)
        assertEquals(1, stat.samples, "只应统计 assignee 的点")
    }
}
