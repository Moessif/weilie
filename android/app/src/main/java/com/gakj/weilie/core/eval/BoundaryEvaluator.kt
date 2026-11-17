package com.gakj.weilie.core.eval

import com.gakj.weilie.core.fitting.Pt

/**
 * 边界精度评估（v0.13.1 新增）
 *
 * 用途：给「围猎」的成面算法一个**可比的裁判**。把算法输出的实测多边形
 * 与一条已知真值多边形（运营商工参、专业路测结果、人工标绘）对比，
 * 输出四个数字，回答"到底测得准不准"。
 *
 * 全部为纯 JVM Kotlin，不依赖 Android，可直接单测。
 *
 * 指标定义：
 * - [Result.iou]：交并比，用 2 m 网格采样近似（对凹多边形同样适用）。
 * - [Result.areaBias]：(实测面积 − 真值面积) / 真值面积，正数表示偏大。
 * - [Result.medianOffsetM]：实测每个顶点到真值边界最短距离的中位数。
 * - [Result.hausdorffApproxM]：顶点采样近似 Hausdorff 距离（取双向最大最小距离）。
 *
 * 注意：Hausdorff 用顶点近似，不是严格的连续曲线距离；对报告口径足够，
 * 但不要把它当作数学意义上的 Hausdorff。
 */
object BoundaryEvaluator {

    private const val M_PER_DEG_LAT = 111_320.0

    data class Result(
        val iou: Double,
        val areaBias: Double,
        val medianOffsetM: Double,
        val hausdorffApproxM: Double,
        val referenceAreaM2: Double,
        val measuredAreaM2: Double,
    )

    /**
     * @param reference 真值多边形（WGS84 经纬度，顺序无关，首尾不必闭合）
     * @param measured  实测多边形（同上）
     * @param gridM     IoU 采样网格边长（米），越小越准、越慢；2 m 对百米级范围足够
     */
    fun evaluate(
        reference: List<Pair<Double, Double>>,
        measured: List<Pair<Double, Double>>,
        gridM: Double = 2.0,
    ): Result {
        if (reference.size < 3 || measured.size < 3 || gridM <= 0.0) {
            return Result(0.0, 0.0, Double.NaN, Double.NaN, 0.0, 0.0)
        }
        val lat0 = reference.map { it.first }.average()
        val lon0 = reference.map { it.second }.average()
        val mLon = M_PER_DEG_LAT * kotlin.math.cos(Math.toRadians(lat0))

        fun proj(p: Pair<Double, Double>) =
            Pt((p.second - lon0) * mLon, (p.first - lat0) * M_PER_DEG_LAT)

        val ref = reference.map(::proj)
        val mea = measured.map(::proj)

        val refArea = area(ref)
        val meaArea = area(mea)
        val areaBias = if (refArea > 1e-9) (meaArea - refArea) / refArea else Double.NaN

        val iou = sampledIoU(ref, mea, gridM)
        val medianOffset = median(mea.map { minDistanceToRing(it, ref) })
        val hApprox = approxHausdorff(mea, ref)

        return Result(
            iou = iou,
            areaBias = areaBias,
            medianOffsetM = medianOffset,
            hausdorffApproxM = hApprox,
            referenceAreaM2 = refArea,
            measuredAreaM2 = meaArea,
        )
    }

    // ---------- 基础几何 ----------

    private fun area(poly: List<Pt>): Double {
        var s = 0.0
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[(i + 1) % poly.size]
            s += a.x * b.y - b.x * a.y
        }
        return kotlin.math.abs(s) / 2.0
    }

    /** 射线法；点恰在边上时结果不稳定，但网格采样时影响可忽略 */
    private fun contains(poly: List<Pt>, p: Pt): Boolean {
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val pi = poly[i]
            val pj = poly[j]
            val intersect = (pi.y > p.y) != (pj.y > p.y) &&
                p.x < (pj.x - pi.x) * (p.y - pi.y) / (pj.y - pi.y) + pi.x
            if (intersect) inside = !inside
            j = i
        }
        return inside
    }

    private fun sampledIoU(a: List<Pt>, b: List<Pt>, gridM: Double): Double {
        val minX = minOf(a.minOf { it.x }, b.minOf { it.x })
        val maxX = maxOf(a.maxOf { it.x }, b.maxOf { it.x })
        val minY = minOf(a.minOf { it.y }, b.minOf { it.y })
        val maxY = maxOf(a.maxOf { it.y }, b.maxOf { it.y })
        if (maxX - minX < gridM || maxY - minY < gridM) return 0.0

        var both = 0
        var union = 0
        var y = minY + gridM / 2
        while (y <= maxY) {
            var x = minX + gridM / 2
            while (x <= maxX) {
                val p = Pt(x, y)
                val inA = contains(a, p)
                val inB = contains(b, p)
                if (inA && inB) both++
                if (inA || inB) union++
                x += gridM
            }
            y += gridM
        }
        return if (union == 0) 0.0 else both.toDouble() / union
    }

    private fun minDistanceToRing(p: Pt, ring: List<Pt>): Double {
        var best = Double.MAX_VALUE
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[(i + 1) % ring.size]
            val d = segmentDistance(p, a, b)
            if (d < best) best = d
        }
        return best
    }

    private fun segmentDistance(p: Pt, a: Pt, b: Pt): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        if (len2 < 1e-12) return kotlin.math.hypot(p.x - a.x, p.y - a.y)
        var t = ((p.x - a.x) * dx + (p.y - a.y) * dy) / len2
        t = t.coerceIn(0.0, 1.0)
        return kotlin.math.hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return Double.NaN
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    }

    /** 顶点采样的近似 Hausdorff（双向最大最小距离），最多各取 200 个点 */
    private fun approxHausdorff(a: List<Pt>, b: List<Pt>): Double {
        fun sample(list: List<Pt>): List<Pt> {
            if (list.size <= 200) return list
            val step = list.size / 200.0
            return (0 until 200).map { list[(it * step).toInt().coerceIn(0, list.size - 1)] }
        }
        val sa = sample(a)
        val sb = sample(b)
        val d1 = sa.maxOf { minDistanceToRing(it, b) }
        val d2 = sb.maxOf { minDistanceToRing(it, a) }
        return maxOf(d1, d2)
    }
}
