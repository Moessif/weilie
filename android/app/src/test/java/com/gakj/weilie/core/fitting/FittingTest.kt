package com.gakj.weilie.core.fitting

import com.gakj.weilie.core.model.SampleRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FittingTest {

    private val lat0 = 31.0
    private val lon0 = 121.0
    private var ts = 1_000_000L

    /** 在 (lat0,lon0) 附近按米制偏移构造 SampleRecord（RSRP = -60 - 距离*0.5） */
    private fun sample(dxM: Double, dyM: Double, rsrp: Int? = null, acc: Float = 10f): SampleRecord {
        val lat = lat0 + dyM / 110540.0
        val lon = lon0 + dxM / (111320.0 * kotlin.math.cos(Math.toRadians(lat0)))
        val d = kotlin.math.sqrt(dxM * dxM + dyM * dyM)
        return SampleRecord(
            tsEpochMs = ts++, lat = lat, lon = lon, accuracyM = acc, provider = "test",
            servingRat = com.gakj.weilie.core.model.Rat.LTE, rsrp = rsrp ?: (-60.0 - d * 0.5).toInt(),
        )
    }

    /** 5x5 网格，间距 40m */
    private fun gridSamples(): List<SampleRecord> =
        (-2..2).flatMap { ix -> (-2..2).map { iy -> sample(ix * 40.0, iy * 40.0) } }

    @Test
    fun `convex hull of square grid`() {
        val pts = listOf(Pt(0.0, 0.0), Pt(4.0, 0.0), Pt(4.0, 4.0), Pt(0.0, 4.0), Pt(2.0, 2.0), Pt(1.0, 3.0))
        val hull = ConvexHull.hull(pts)
        assertEquals(4, hull.size)
        assertEquals(16.0, ConvexHull.area(hull), 1e-9)
    }

    @Test
    fun `haversine known distance`() {
        // 天安门到王府井约 1.1-1.6km，粗验
        val d = Geo.haversineMeters(39.9087, 116.3975, 39.9097, 116.4109)
        assertTrue("d=$d", d in 800.0..1700.0)
    }

    @Test
    fun `local roundtrip`() {
        val p = Geo.toLocal(31.001, 121.001, 31.0, 121.0)
        val (lat, lon) = Geo.fromLocal(p.x, p.y, 31.0, 121.0)
        assertEquals(31.001, lat, 1e-9)
        assertEquals(121.001, lon, 1e-9)
    }

    @Test
    fun `polygon buffer grows area`() {
        val square = listOf(Pt(0.0, 0.0), Pt(100.0, 0.0), Pt(100.0, 100.0), Pt(0.0, 100.0))
        val hull = ConvexHull.hull(square)
        val buffered = PolygonBuffer.buffer(hull, 20.0)
        assertTrue(ConvexHull.area(buffered) > 10000.0)
    }

    @Test
    fun `coverage fitter produces isolines and confidence`() {
        val res = CoverageFitter.fit(gridSamples(), CoverageFitter.Options(thresholdDbm = -100.0))
            ?: throw AssertionError("fit should not return null")
        assertTrue(res.hullLocal.isNotEmpty())
        assertTrue(res.confidence.score0to100 > 0)
        assertTrue(res.isolineLoopsLatLon.isNotEmpty())
        // 内点 = rsrp>=-100 → 距离 ≤ 80m 的 13 个网格点
        assertEquals(13, res.inlierCount)
        assertEquals(25, res.totalSamples)
    }

    @Test
    fun `fitter returns null when below minSamples`() {
        val few = (0 until 5).map { sample(it * 30.0, 0.0) }
        assertTrue(CoverageFitter.fit(few, CoverageFitter.Options()) == null)
    }

    @Test
    fun `marching squares chains a loop`() {
        val grid = IdwGrid(
            minX = 0.0, minY = 0.0, cellW = 10.0, cellH = 10.0, cols = 5, rows = 5,
            values = DoubleArray(25) { i ->
                val x = (i % 5) * 10.0; val y = (i / 5) * 10.0
                if (x >= 10.0 && x <= 30.0 && y >= 10.0 && y <= 30.0) -50.0 else -120.0
            },
        )
        val loops = MarchingSquares.isolines(grid, -100.0)
        assertTrue(loops.isNotEmpty())
        val longest = loops.maxByOrNull { it.size }!!
        assertTrue("loop too short: ${longest.size}", longest.size >= 4)
    }

    @Test
    fun `idw interpolates toward nearest sample`() {
        val samples = listOf(FitSample(Pt(0.0, 0.0), -60.0), FitSample(Pt(1000.0, 0.0), -120.0))
        val near0 = IdwInterpolator.idw(10.0, 0.0, samples)
        val near1 = IdwInterpolator.idw(990.0, 0.0, samples)
        assertTrue(near0 > -80.0)
        assertTrue(near1 < -100.0)
    }

    @Test
    fun `direction coverage full circle`() {
        val pts = (0 until 16).map { i ->
            val ang = i / 16.0 * 2 * Math.PI
            Pt(100 * kotlin.math.cos(ang), 100 * kotlin.math.sin(ang))
        }
        val (filled, ratio) = DirectionCoverage.coverage(Pt(0.0, 0.0), pts)
        assertTrue("filled=$filled ratio=$ratio", filled >= 12 && ratio > 0.8)
    }

    @Test
    fun `simplify keeps far outlier`() {
        val line = listOf(Pt(0.0, 0.0), Pt(50.0, 60.0), Pt(100.0, 0.0))
        val out = Simplify.dp(line, 5.0)
        assertEquals(3, out.size)
        val flat = listOf(Pt(0.0, 0.0), Pt(50.0, 0.0), Pt(100.0, 0.0))
        assertTrue(Simplify.dp(flat, 5.0).size < 3)
    }
}
