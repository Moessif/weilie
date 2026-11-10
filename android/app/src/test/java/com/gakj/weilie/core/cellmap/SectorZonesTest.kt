package com.gakj.weilie.core.cellmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SectorZonesTest {

    private val lat = 39.9011
    private val lon = 116.3914

    @Test
    fun destinationNorthIncreasesLatitudeByExpectedMeters() {
        val (outLat, outLon) = CellGeometry.destination(lat, lon, 1000.0, 0.0)
        assertEquals(1000.0, (outLat - lat) * CellGeometry.METERS_PER_DEG_LAT, 1.0)
        assertEquals(lon, outLon, 1e-9)
    }

    @Test
    fun destinationEastIncreasesLongitude() {
        val (outLat, outLon) = CellGeometry.destination(lat, lon, 1000.0, 90.0)
        assertTrue(outLon > lon)
        assertEquals(lat, outLat, 1e-6)
        val d = CellGeometry.distanceM(lat, lon, outLat, outLon)
        assertEquals(1000.0, d, 5.0)
    }

    @Test
    fun sectorCountMatchesMemberCount() {
        val zones = SectorZones.sectors(lat, lon, 800.0, 4)
        assertEquals(4, zones.size)
        zones.forEach { poly ->
            assertTrue("每个扇区至少 4 个顶点", poly.size >= 4)
            // 第一点必须是圆心
            assertEquals(lat, poly.first().first, 1e-9)
            assertEquals(lon, poly.first().second, 1e-9)
        }
    }

    @Test
    fun singleMemberGetsFullCircle() {
        val zones = SectorZones.sectors(lat, lon, 600.0, 1)
        assertEquals(1, zones.size)
        assertTrue(zones[0].size > 10)
        // 单人时不应该是「圆心+一段弧」的半个多边形，面积应接近整圆
        val expected = Math.PI * 600.0 * 600.0
        assertEquals(expected, SectorZones.areaM2(zones[0]), expected * 0.05)
    }

    @Test
    fun sectorAreasSumToCircle() {
        val r = 800.0
        val total = SectorZones.sectors(lat, lon, r, 5, arcStepDeg = 2.0)
            .sumOf { SectorZones.areaM2(it) }
        val expected = Math.PI * r * r
        assertEquals(expected, total, expected * 0.02)
    }

    @Test
    fun ringAreasAreBandsNotOverlapping() {
        val r = 1200.0
        val rings = SectorZones.rings(lat, lon, r, 3, segments = 72)
        assertEquals(3, rings.size)
        val areas = rings.map { SectorZones.areaM2(it) }
        // 第 0 层是中心圆，后面两层是环带，三层面积应基本相等（外圈略大，因为球面近似）
        areas.forEachIndexed { i, a ->
            val expected = Math.PI * r * r / 3.0
            assertEquals("第 $i 层面积", expected, a, expected * 0.12)
        }
        // 总面积 = 大圆
        assertEquals(Math.PI * r * r, areas.sum(), Math.PI * r * r * 0.03)
    }

    @Test
    fun ringBandsAreClosedPolysWithDistinctInnerOuter() {
        val rings = SectorZones.rings(lat, lon, 900.0, 2, segments = 24)
        val band = rings[1]
        assertEquals(48, band.size) // 外弧 24 + 内弧 24
        val outer = band.take(24)
        val inner = band.drop(24)
        val outerDist = outer.map { CellGeometry.distanceM(lat, lon, it.first, it.second) }.average()
        val innerDist = inner.map { CellGeometry.distanceM(lat, lon, it.first, it.second) }.average()
        assertTrue("外弧应比内弧远", outerDist > innerDist + 100.0)
        assertEquals(900.0, outerDist, 20.0)
        // 等面积切分：n=2 时内半径 = 900/√2 ≈ 636.4 m（等半径切分才是 450）
        assertEquals(900.0 / Math.sqrt(2.0), innerDist, 25.0)
    }

    @Test
    fun areaOfKnownSquareIsAccurate() {
        // 以 (39.90,116.39) 为西南角、约 100m × 100m 的正方形
        val dLat = 100.0 / CellGeometry.METERS_PER_DEG_LAT
        val dLon = 100.0 / CellGeometry.metersPerDegLon(39.90)
        val poly = listOf(
            39.90 to 116.39,
            39.90 to 116.39 + dLon,
            39.90 + dLat to 116.39 + dLon,
            39.90 + dLat to 116.39,
        )
        assertEquals(10_000.0, SectorZones.areaM2(poly), 150.0)
    }

    @Test
    fun isUsableRejectsDegeneratePolys() {
        assertFalse(SectorZones.isUsable(emptyList()))
        assertFalse(SectorZones.isUsable(listOf(1.0 to 2.0, 3.0 to 4.0)))
        assertFalse(SectorZones.isUsable(listOf(1.0 to 2.0, 3.0 to Double.NaN, 5.0 to 6.0)))
        assertTrue(SectorZones.isUsable(listOf(1.0 to 2.0, 3.0 to 4.0, 5.0 to 6.0)))
    }

    @Test
    fun labelsMatchMode() {
        assertEquals("扇区 2/4", SectorZones.labelFor(SectorZones.Mode.SECTOR, 1, 4))
        assertEquals("圈层 1/3", SectorZones.labelFor(SectorZones.Mode.RING, 0, 3))
    }

    @Test
    fun centroidOfCircleIsCenter() {
        val poly = SectorZones.circle(lat, lon, 500.0, 72)
        val (cLat, cLon) = SectorZones.centroid(poly)
        assertTrue(abs(cLat - lat) < 1e-6)
        assertTrue(abs(cLon - lon) < 1e-6)
    }
}
