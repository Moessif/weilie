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
    /** 实际使用的成面引擎（v0.13.2） */
    val engine: Engine = Engine.ALPHA_SHAPE,
    /** 为什么选这个引擎（白话，可直接给用户看） */
    val engineReason: String = "",
    /** 两引擎面积差 > 阈值：说明这次拟合对算法敏感，建议补测 */
    val unstable: Boolean = false,
    /** 轨迹缓冲引擎的面积（m²），仅该引擎有值 */
    val trackBufferAreaM2: Double? = null,
    /** 内洞（环绕走位时中间没走过的区域），经纬度环；v0.13.3 */
    val holesLatLon: List<List<Pair<Double, Double>>> = emptyList(),
    /** 外环面积（m²，不含洞） */
    val hullAreaM2: Double = 0.0,
    /** 内洞面积合计（m²） */
    val holesAreaM2: Double = 0.0,
    /** 覆盖面积 = 外环 − 洞；导出的多边形面积必须与它一致 */
    val coverageAreaM2: Double = 0.0,
    /** 缓冲带面积（仅用于展示不确定带，不计入覆盖面积） */
    val bufferedAreaM2: Double = 0.0,
) {
    enum class Engine(val label: String) {
        ALPHA_SHAPE("凹包"),
        TRACK_BUFFER("轨迹缓冲"),
    }
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
        /** 时段覆盖：会话三等分中有样本的比例（v0.13.1） */
        val timeCoverage: Double = 1.0,
        /** 短板原因，按影响从大到小排序，供 UI 直接展示（v0.13.1） */
        val reasons: List<String> = emptyList(),
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

    /**
     * 平均最近邻距（米）：每个点到最近邻点的距离取平均。
     * 点太多时等距抽样到 600 个，避免 O(n²) 拖慢主线程。
     */
    internal fun averageNearestNeighborM(points: List<Pt>): Double {
        if (points.size < 2) return 0.0
        val sample = if (points.size <= 600) points
        else points.filterIndexed { i, _ -> i % (points.size / 600 + 1) == 0 }
        var sum = 0.0
        for (p in sample) {
            var best = Double.MAX_VALUE
            for (q in points) {
                if (q === p) continue
                val dx = q.x - p.x
                val dy = q.y - p.y
                val d2 = dx * dx + dy * dy
                if (d2 < best) best = d2
            }
            if (best < Double.MAX_VALUE) sum += kotlin.math.sqrt(best)
        }
        return sum / sample.size
    }

    data class Options(
        val thresholdDbm: Double = -110.0,
        val bufferMeters: Double = 25.0,
        val cellMeters: Double = 12.0,
        val maxAccuracyM: Double = 60.0,
        val minSamples: Int = 8,
        /** 凹包 alpha（米）；0 = 按点间距自适应 */
        val alphaM: Double = 0.0,
        /**
         * 生成边界时抬高的一档余量（dB）：只用比阈值更强的点围边，
         * 把"极限弱点"排除在外，避免弱信号把面积撑出尖角（需求 3.1）。
         */
        val weakMarginDb: Double = 3.0,

        // ---- v0.13.2 引擎自动选择 ----
        /** 有效点少于该值 → 用轨迹缓冲（凹包在稀疏点下会退化成条带） */
        val trackBufferMinPoints: Int = 30,
        /** 平均最近邻距大于该值 → 用轨迹缓冲（点太散，连不成面） */
        val trackBufferNearestM: Double = 40.0,
        /** 轨迹缓冲半径（米）；0 = 按平均定位精度自适应 */
        val trackBufferRadiusM: Double = 0.0,
        /** 是否额外跑一次两引擎对比（会明显增加耗时，只在导出/详情页开启） */
        val computeStability: Boolean = false,
        /** 两引擎面积差超过该比例视为“拟合不稳定” */
        val unstableAreaDeltaRatio: Double = 0.30,

        // ---- v0.13.3 内洞 ----
        /** 是否检测内洞（环绕走位时中间没走过的区域） */
        val detectHoles: Boolean = true,
        /** 洞半径（米）：离轨迹超过该距离的内部区域算“没走过”；0 = 按精度自适应 */
        val holeRadiusM: Double = 0.0,
        /** 小于该面积的洞忽略 */
        val minHoleAreaM2: Double = 300.0,
    )

    fun fit(samples: List<SampleRecord>, opt: Options = Options()): CoverageResult? {
        // 先剔飞点/粗定位点：单个坏点就能把凹包撑到城市尺度（真机反馈「一大片异常覆盖」）
        val clean = TrackSanity.saneSamples(samples)
        // 需求 1.1/3.1：空心点（脱离目标基站范围）不参与范围与面积计算，
        // 邻区点也要满足信号门槛才算有效实心点。
        val areaSamples = clean.filter {
            AreaBuilder.isValidPoint(it, opt.thresholdDbm.roundToInt()) && it.rsrp != null
        }
        // 兜底：老会话/导入数据可能没写 onTarget/targetSeen 标记，此时按「全部点」拟合，
        // 否则会把整场走测判成空；只有标记有效点够用时才真正剔除空心点。
        val basisPool = if (areaSamples.size >= opt.minSamples) areaSamples else clean.filter { it.rsrp != null }
        if (basisPool.size < opt.minSamples) return null
        val usable = basisPool.filter { it.accuracyM == null || it.accuracyM <= opt.maxAccuracyM }
        if (usable.size < opt.minSamples) return null

        val lat0 = usable.map { it.lat }.average()
        val lon0 = usable.map { it.lon }.average()
        val local = usable.map { s ->
            Triple(Geo.toLocal(s.lat, s.lon, lat0, lon0), s.rsrp!!.toDouble(), (s.accuracyM ?: 10f).toDouble())
        }
        val fitSamples = local.map { (p, rsrp, acc) -> FitSample(p, rsrp, acc) }
        val meanAcc = fitSamples.map { it.accuracyM }.average().takeIf { !it.isNaN() } ?: 10.0
        // inlierCount 保持"信号≥阈值的点数"这一统计口径（界面展示用）；
        // 真正围边只用再高一档（weakMarginDb）的强点，弱边缘点不参与撑面积（需求 3.1）。
        val inliers = fitSamples.filter { it.rsrp >= opt.thresholdDbm }
        val strong = inliers.filter { it.rsrp >= opt.thresholdDbm + opt.weakMarginDb }
        val basis = if (strong.size >= 3) strong else inliers

        val basisPts = basis.map { it.p }

        // ---- v0.13.2：引擎自动选择 ----
        // 稀疏点（< trackBufferMinPoints）或点距过大（> trackBufferNearestM）时，
        // 凹包会退化成细长条甚至成不了面，此时改用「轨迹缓冲」更接近民警的直觉。
        val avgNn = averageNearestNeighborM(basisPts)
        val preferBuffer = basisPts.size < opt.trackBufferMinPoints || avgNn > opt.trackBufferNearestM
        val alphaHull: () -> List<Pt>? = {
            val alpha = if (opt.alphaM > 0.0) opt.alphaM else AlphaShape.adaptiveAlpha(basisPts)
            val raw = AlphaShape.hull(basisPts, AlphaShape.Options(alphaM = alpha))
                .ifEmpty { ConvexHull.hull(basisPts) }
            if (raw.size < 3) null
            else PolylineSimplify.simplifyRing(raw, (meanAcc * 0.4).coerceIn(1.5, 8.0))
                .takeIf { it.size >= 3 } ?: raw
        }

        var engine = CoverageResult.Engine.ALPHA_SHAPE
        var engineReason = ""
        var trackAreaM2: Double? = null
        var hull: List<Pt>
        var buffered: List<Pt>

        val bufferRadius = if (opt.trackBufferRadiusM > 0.0) {
            opt.trackBufferRadiusM
        } else {
            (meanAcc * 1.5).coerceIn(10.0, 60.0)
        }
        val tb = if (preferBuffer) {
            TrackBufferArea.build(usable, TrackBufferArea.Options(radiusM = bufferRadius))
        } else {
            null
        }

        if (tb != null) {
            engine = CoverageResult.Engine.TRACK_BUFFER
            hull = tb.ringLatLon.map { Geo.toLocal(it.first, it.second, lat0, lon0) }
            // 缓冲带已在轨迹缓冲里体现，不再二次外扩
            buffered = hull
            trackAreaM2 = tb.areaM2
            val why = if (basisPts.size < opt.trackBufferMinPoints) {
                "有效点仅 ${basisPts.size} 个（< ${opt.trackBufferMinPoints}）"
            } else {
                "平均点距 %.0f 米（> %.0f 米）".format(avgNn, opt.trackBufferNearestM)
            }
            engineReason = "$why，改用轨迹缓冲（半径 %.0f 米）".format(bufferRadius)
        } else {
            val h = alphaHull() ?: return null
            hull = h
            buffered = PolygonBuffer.buffer(hull, opt.bufferMeters + meanAcc * 0.5)
            engineReason = "有效点 ${basisPts.size} 个、平均点距 %.0f 米，用凹包".format(avgNn)
        }
        if (hull.size < 3) return null

        // ---- v0.13.3：内洞 + 面积口径统一 ----
        // 面积一律用「实际画出来的环」计算：外环 − 洞 = 覆盖面积，
        // 导出与界面都用这同一个数，避免 GIS 一算对不上。
        val hullArea = kotlin.math.abs(ConvexHull.area(hull))
        val trackLocal = usable.map { Geo.toLocal(it.lat, it.lon, lat0, lon0) }
        val holeR = if (opt.holeRadiusM > 0.0) opt.holeRadiusM else (meanAcc * 2.0).coerceIn(20.0, 60.0)
        val holes = if (opt.detectHoles) {
            CoverageHoles.detect(
                hull = hull,
                track = trackLocal,
                lat0 = lat0,
                lon0 = lon0,
                holeRadiusM = holeR,
                minHoleAreaM2 = opt.minHoleAreaM2,
            )
        } else {
            CoverageHoles.NONE
        }
        val coverageArea = (hullArea - holes.areaM2).coerceAtLeast(0.0)
        val bufferedArea = kotlin.math.abs(ConvexHull.area(buffered))

        // 可选：两引擎面积差对比（默认关闭，避免打点时重复计算）
        var unstable = false
        if (opt.computeStability && engine == CoverageResult.Engine.ALPHA_SHAPE && usable.size <= 400) {
            val alt = TrackBufferArea.build(usable, TrackBufferArea.Options(radiusM = bufferRadius))
            if (alt != null && trackAreaM2 == null) {
                val alphaArea = kotlin.math.abs(ConvexHull.area(hull))
                val base = maxOf(alphaArea, alt.areaM2)
                if (base > 1e-6 && kotlin.math.abs(alphaArea - alt.areaM2) / base > opt.unstableAreaDeltaRatio) {
                    unstable = true
                }
            }
        }

        val grid = IdwInterpolator.build(fitSamples, cellMeters = opt.cellMeters)
        val loops = MarchingSquares.isolines(grid, opt.thresholdDbm)
            .filter { it.size >= 4 }
            .take(3) // 最长的前几条

        fun toLatLon(pts: List<Pt>) = pts.map { Geo.fromLocal(it.x, it.y, lat0, lon0) }
        val (sectorsFilled, dirCov) = DirectionCoverage.coverage(
            Pt(hull.map { it.x }.average(), hull.map { it.y }.average()),
            basis.map { it.p },
        )
        // 样本量因子与置信度模型保持同一口径（口径常量见 ConfidenceModel.SAMPLE_SATURATION）
        val countFactor = minOf(1.0, usable.size / ConfidenceModel.SAMPLE_SATURATION)

        // v0.13.1：时段覆盖。把会话时长三等分，统计其中「有样本」的段数 / 3。
        // 会话太短（< 60 s）时无法判断时段，按满分处理，避免把短测判死。
        val tMin = usable.minOf { it.tsEpochMs }
        val tMax = usable.maxOf { it.tsEpochMs }
        val spanMs = tMax - tMin
        val timeFactor: Double = if (spanMs < 60_000L) {
            1.0
        } else {
            val hit = BooleanArray(3)
            usable.forEach { s ->
                val idx = (((s.tsEpochMs - tMin).toDouble() / spanMs) * 3).toInt().coerceIn(0, 2)
                hit[idx] = true
            }
            hit.count { it } / 3.0
        }

        // v0.13.1：置信度改为短板模型（实现在 ConfidenceModel，便于单测）
        val conf = ConfidenceModel.compute(
            sampleCount = usable.size,
            sectorsFilled = sectorsFilled,
            timeCoverage = timeFactor,
        )
        val score = conf.score
        val level = conf.level
        val reasons = conf.reasons

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
            confidence = CoverageResult.Confidence(score, level, countFactor, dirCov, meanAcc, timeFactor, reasons),
            inlierCount = inliers.size,
            totalSamples = usable.size,
            thresholdDbm = opt.thresholdDbm,
            engine = engine,
            engineReason = engineReason,
            unstable = unstable,
            trackBufferAreaM2 = trackAreaM2,
            holesLatLon = holes.holesLatLon,
            hullAreaM2 = hullArea,
            holesAreaM2 = holes.areaM2,
            coverageAreaM2 = coverageArea,
            bufferedAreaM2 = bufferedArea,
        )
    }
}
