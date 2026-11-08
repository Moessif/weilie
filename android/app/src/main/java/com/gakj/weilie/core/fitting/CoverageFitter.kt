package com.gakj.weilie.core.fitting

import com.gakj.weilie.core.model.SampleRecord
import kotlin.math.roundToInt

/** 覆盖拟合结果（全部同时给出米制局部坐标与经纬度两套，UI 与导出各取所需） */
data class CoverageResult(
    val lat0: Double,
    val lon0: Double,
    val hullLocal: List<Pt>,
    val hullLatLon: List<Pair<Double, Double>>,
    val bufferedLocal: List<Pt>,
    val bufferedLatLon: List<Pair<Double, Double>>,
    val isolineLoopsLatLon: List<List<Pair<Double, Double>>>,
    val heat: HeatGrid,
    val confidence: Confidence,
    val inlierCount: Int,
    val totalSamples: Int,
    val thresholdDbm: Double,
) {
    data class HeatGrid(
        val minLat: Double, val minLon: Double,
        val latStep: Double, val lonStep: Double,
        val cols: Int, val rows: Int,
        val values: DoubleArray,
    )

    data class Confidence(
        val score0to100: Int,
        val level: Level,
        val sampleCountFactor: Double,
        val directionCoverage: Double,
        val meanAccuracyM: Double,
    ) {
        enum class Level { 低, 中, 高 }
    }
}

/**
 * 覆盖拟合器：打点样本 → 可行动的覆盖边界。
 *  1) 过滤无效样本（无 RSRP 或精度过差）
 *  2) 阈值筛内点（RSRP ≥ 阈值视为"在覆盖内"的观测）
 *  3) 内点凸包 + 缓冲带（缓冲 = 边缘不确定带，由平均定位精度决定）
 *  4) 全样本 IDW 插值 → Marching Squares 阈值等值线
 *  5) 置信度 = 样本量因子 × 方向覆盖度，输出 0-100 分与低/中/高
 */
object CoverageFitter {

    data class Options(
        val thresholdDbm: Double = -110.0,
        val bufferMeters: Double = 25.0,
        val cellMeters: Double = 12.0,
        val maxAccuracyM: Double = 60.0,
        val minSamples: Int = 8,
    )

    fun fit(samples: List<SampleRecord>, opt: Options = Options()): CoverageResult? {
        if (samples.size < opt.minSamples) return null
        val usable = samples.filter { it.rsrp != null && it.accuracyM == null || (it.rsrp != null && (it.accuracyM ?: 0f) <= opt.maxAccuracyM) }
        if (usable.size < opt.minSamples) return null

        val lat0 = usable.map { it.lat }.average()
        val lon0 = usable.map { it.lon }.average()
        val local = usable.map { s ->
            Triple(Geo.toLocal(s.lat, s.lon, lat0, lon0), s.rsrp!!.toDouble(), (s.accuracyM ?: 10f).toDouble())
        }
        val fitSamples = local.map { (p, rsrp, acc) -> FitSample(p, rsrp, acc) }
        val inliers = fitSamples.filter { it.rsrp >= opt.thresholdDbm }
        val basis = if (inliers.size >= 3) inliers else fitSamples

        val hull = ConvexHull.hull(basis.map { it.p })
        if (hull.size < 3) return null
        val meanAcc = fitSamples.map { it.accuracyM }.average().takeIf { !it.isNaN() } ?: 10.0
        val buffered = PolygonBuffer.buffer(hull, opt.bufferMeters + meanAcc * 0.5)

        val grid = IdwInterpolator.build(fitSamples, cellMeters = opt.cellMeters)
        val loops = MarchingSquares.isolines(grid, opt.thresholdDbm)
            .filter { it.size >= 4 }
            .take(3) // 最长的前几条

        fun toLatLon(pts: List<Pt>) = pts.map { Geo.fromLocal(it.x, it.y, lat0, lon0) }
        val (sectorsFilled, dirCov) = DirectionCoverage.coverage(
            Pt(hull.map { it.x }.average(), hull.map { it.y }.average()),
            basis.map { it.p },
        )
        val countFactor = minOf(1.0, usable.size / 120.0)
        val score = ((countFactor * 0.5 + dirCov * 0.5) * 100).roundToInt()
        val level = when {
            score >= 70 && sectorsFilled >= 12 -> CoverageResult.Confidence.Level.高
            score >= 40 -> CoverageResult.Confidence.Level.中
            else -> CoverageResult.Confidence.Level.低
        }

        // 热力图转经纬度步长
        val latStep = grid.cellH / 110540.0
        val lonStep = grid.cellW / (111320.0 * kotlin.math.cos(Math.toRadians(lat0)))
        val (minLat, minLon) = Geo.fromLocal(grid.minX, grid.minY, lat0, lon0)

        return CoverageResult(
            lat0 = lat0, lon0 = lon0,
            hullLocal = hull, hullLatLon = toLatLon(hull),
            bufferedLocal = buffered, bufferedLatLon = toLatLon(buffered),
            isolineLoopsLatLon = loops.map { toLatLon(it) },
            heat = CoverageResult.HeatGrid(minLat, minLon, latStep, lonStep, grid.cols, grid.rows, grid.values.copyOf()),
            confidence = CoverageResult.Confidence(score, level, countFactor, dirCov, meanAcc),
            inlierCount = inliers.size,
            totalSamples = usable.size,
            thresholdDbm = opt.thresholdDbm,
        )
    }
}
