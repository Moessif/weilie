package com.gakj.weilie.core.fitting

import com.gakj.weilie.core.model.SampleRecord
import kotlin.math.hypot
import kotlin.math.max

/**
 * 封闭区域生成（工具箱「实心点成面」需求）：
 *
 * 1. 优先用**凹包（Alpha Shape）**把有效实心点围成面 —— 能贴合 L 形、环形的真实走测轨迹；
 * 2. 凹包失败（点太少 / 共线 / 剖分退化）时，退化为**以路线为基准的两侧扩展条带**：
 *    沿时间顺序取相邻点方向，向左右各外扩半个带宽，首尾闭合，得到一条封闭走廊；
 * 3. 点数不足 2 时返回 [Kind.NONE]（调用方提示「有效点不足，无法成面」）。
 *
 * 纯 Kotlin，无 Android 依赖；输出经纬度环（首尾不重复）。
 */
object AreaBuilder {

    enum class Kind(val label: String) {
        CONCAVE("凹包成面"),
        STRIP("沿路线条带"),
        SIMULATED("模拟实测区域"),
        NONE("点不足"),
    }

    /** 成面方式：高精度凹包，或「以轨迹点为圆心画圆/方、连成一片」的模拟实测区域 */
    enum class Mode(val label: String) {
        AUTO("高精度凹包"),
        SIMULATED("模拟实测区域"),
    }

    data class Options(
        val mode: Mode = Mode.AUTO,
        /** 模拟实测区域的圆半径 / 方块半边长（米） */
        val bufferRadiusM: Double = 30.0,
        val bufferShape: TrackBufferArea.Shape = TrackBufferArea.Shape.CIRCLE,
        /** 凹包 alpha（米）；0 表示按点间距自适应 */
        val alphaM: Double = 0.0,
        /** 条带半宽（米） */
        val stripHalfWidthM: Double = 18.0,
        /** 条带半宽下限是否按定位精度放大 */
        val useAccuracy: Boolean = true,
        /** 参与成面的信号门槛（dBm），用于过滤扫到的一闪而过弱信号 */
        val rsrpFloorDbm: Int = -110,
        /**
         * 成面时再抬高一档的余量（dB）：只用比门槛强 [weakMarginDb] 的点围边，
         * 相当于把"极限弱点"排除在外，避免弱信号尖角把面积撑大（需求 3.1 边缘点降权）。
         */
        val weakMarginDb: Int = 6,
        /** 多边形抽稀容差（米）；0 表示按定位精度自适应（Douglas–Peucker，需求 3.2） */
        val simplifyM: Double = 0.0,
        /** 多边形向内收缩（米）；0 表示按定位精度自适应（边缘内缩，需求 3.1） */
        val insetM: Double = 0.0,
    )

    data class Result(
        val kind: Kind,
        /** 经纬度外环，首尾不重复 */
        val ringLatLon: List<Pair<Double, Double>>,
        /** 参与成面的点数 */
        val pointCount: Int,
        /** 估算面积（平方米；仅凹包结果有意义） */
        val areaM2: Double = 0.0,
    )

    /** 判定一个采样点是否算「有效实心点」（主区命中，或邻区命中且目标信号够强） */
    fun isValidPoint(s: SampleRecord, rsrpFloorDbm: Int = -110): Boolean = when {
        s.onTarget -> true
        s.targetSeen -> {
            val r = s.targetRsrp ?: s.rsrp
            r != null && r >= rsrpFloorDbm
        }
        else -> false
    }

    fun build(samples: List<SampleRecord>, opt: Options = Options()): Result {
        // 先剔除飞点 / (0,0) / 粗定位点：一个飞点就能把面撑到城市尺度（真机反馈过「一大片异常覆盖」）
        val sane = TrackSanity.saneSamples(samples)
        if (opt.mode == Mode.SIMULATED) {
            val sim = TrackBufferArea.build(
                sane,
                TrackBufferArea.Options(radiusM = opt.bufferRadiusM, shape = opt.bufferShape),
            ) ?: return Result(Kind.NONE, emptyList(), sane.size)
            return Result(Kind.SIMULATED, sim.ringLatLon, sim.pointCount, areaM2 = sim.areaM2)
        }

        val valid = sane.filter { isValidPoint(it, opt.rsrpFloorDbm) }
        if (valid.size < 2) return Result(Kind.NONE, emptyList(), valid.size)

        val meanAcc = valid.mapNotNull { it.accuracyM?.toDouble() }
            .filter { it.isFinite() && it > 0.0 }
            .average()
            .takeIf { !it.isNaN() } ?: 0.0

        val lat0 = valid.map { it.lat }.average()
        val lon0 = valid.map { it.lon }.average()

        // 成面只用「比门槛更强一档」的点；不足 3 个再退回全部有效点
        val strong = valid.filter { s ->
            val r = (s.targetRsrp ?: s.rsrp) ?: Int.MIN_VALUE
            s.onTarget || r >= opt.rsrpFloorDbm + opt.weakMarginDb
        }
        val basisSamples = if (strong.size >= 3) strong else valid
        val pts = basisSamples.map { Geo.toLocal(it.lat, it.lon, lat0, lon0) }

        val simplifyM = if (opt.simplifyM > 0.0) opt.simplifyM else (meanAcc * 0.4).coerceIn(1.5, 8.0)
        val insetM = if (opt.insetM > 0.0) opt.insetM else (meanAcc * 0.5).coerceIn(0.0, 20.0)

        val alpha = if (opt.alphaM > 0.0) opt.alphaM else AlphaShape.adaptiveAlpha(pts)
        val concave = AlphaShape.hull(pts, AlphaShape.Options(alphaM = alpha))
        if (concave.size >= 3) {
            // 抽稀去毛刺 → 向内收缩（边缘点降权）
            val cleaned = PolylineSimplify.insetRing(
                PolylineSimplify.simplifyRing(concave, simplifyM),
                insetM,
            )
            if (cleaned.size >= 3) {
                val ring = cleaned.map { Geo.fromLocal(it.x, it.y, lat0, lon0) }
                return Result(Kind.CONCAVE, ring, valid.size, areaM2 = Math.abs(signedArea(cleaned)))
            }
        }

        // 兜底：沿路线生成两侧扩展的封闭条带
        val half = max(
            opt.stripHalfWidthM,
            if (opt.useAccuracy) meanAcc else 0.0,
        ).coerceIn(3.0, 200.0)
        val strip = strip(pts, half)
        if (strip.size < 3) return Result(Kind.NONE, emptyList(), valid.size)
        val ring = strip.map { Geo.fromLocal(it.x, it.y, lat0, lon0) }
        return Result(Kind.STRIP, ring, valid.size, areaM2 = Math.abs(signedArea(strip)))
    }

    /**
     * 沿折线生成走廊多边形：左侧点序列 + 右侧点序列倒序。
     * 每个点的法向取「相邻点连线」的垂直方向，端点用首/末段方向。
     */
    private fun strip(pts: List<Pt>, halfWidth: Double): List<Pt> {
        if (pts.size < 2) return emptyList()
        val n = pts.size
        val left = ArrayList<Pt>(n)
        val right = ArrayList<Pt>(n)
        for (i in 0 until n) {
            val prev = pts[(i - 1).coerceAtLeast(0)]
            val next = pts[(i + 1).coerceAtMost(n - 1)]
            var dx = next.x - prev.x
            var dy = next.y - prev.y
            var len = hypot(dx, dy)
            if (len < 1e-9) {
                // 原地重复点：借用前后任意非零段
                val a = pts[(i - 1).coerceAtLeast(0)]
                val b = pts[(i + 1).coerceAtMost(n - 1)]
                dx = b.x - a.x
                dy = b.y - a.y
                len = hypot(dx, dy)
            }
            if (len < 1e-9) {
                left.add(pts[i])
                right.add(pts[i])
                continue
            }
            // 法向 = 方向旋转 90°
            val nx = -dy / len
            val ny = dx / len
            left.add(Pt(pts[i].x + nx * halfWidth, pts[i].y + ny * halfWidth))
            right.add(Pt(pts[i].x - nx * halfWidth, pts[i].y - ny * halfWidth))
        }
        val ring = ArrayList<Pt>(n * 2)
        ring.addAll(left)
        for (i in n - 1 downTo 0) ring.add(right[i])
        return ring
    }

    private fun signedArea(poly: List<Pt>): Double {
        if (poly.size < 3) return 0.0
        var s = 0.0
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[(i + 1) % poly.size]
            s += a.x * b.y - b.x * a.y
        }
        return s / 2.0
    }
}
