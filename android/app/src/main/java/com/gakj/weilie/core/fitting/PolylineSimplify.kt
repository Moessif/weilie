package com.gakj.weilie.core.fitting

import kotlin.math.abs
import kotlin.math.hypot

/**
 * 轨迹抽稀与多边形内缩（需求 3.1 / 3.2）：
 *
 * - [douglasPeucker]：Douglas–Peucker 抽稀，去掉轨迹里冗余的直线点、保留拐点，
 *   让后续凹包边缘更平滑（也顺带减少 Alpha Shape 的三角剖分规模）；
 * - [insetRing]：把多边形向内部收一段距离（近似做法：按顶点到质心的方向缩放），
 *   用于「边缘点降权 / 内缩」，避免最外围的极限弱点把面积撑出尖角。
 *
 * 纯 Kotlin、无 Android 依赖；坐标单位米（[Geo.toLocal] 的局部平面坐标）。
 */
object PolylineSimplify {

    /**
     * Douglas–Peucker。输入按顺序排列的点，输出保留首尾的关键点。
     *
     * @param epsilon 容差（米）：点到首尾连线的垂距小于该值即视为可丢弃的直线点。
     */
    fun douglasPeucker(points: List<Pt>, epsilon: Double): List<Pt> {
        if (points.size < 3 || epsilon <= 0.0) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to points.size - 1)
        while (stack.isNotEmpty()) {
            val (first, last) = stack.removeLast()
            if (last <= first + 1) continue
            var maxDist = -1.0
            var index = -1
            for (i in first + 1 until last) {
                val d = perpendicularDistance(points[i], points[first], points[last])
                if (d > maxDist) {
                    maxDist = d
                    index = i
                }
            }
            if (index > 0 && maxDist > epsilon) {
                keep[index] = true
                stack.addLast(first to index)
                stack.addLast(index to last)
            }
        }
        val out = ArrayList<Pt>(points.size)
        for (i in points.indices) if (keep[i]) out.add(points[i])
        return out
    }

    /** 平滑环（闭合多边形）：先按开门折线抽稀，再补一次首尾邻接的闭合检查 */
    fun simplifyRing(ring: List<Pt>, epsilon: Double): List<Pt> {
        if (ring.size < 4 || epsilon <= 0.0) return ring
        val open = ring + ring.first()
        val simplified = douglasPeucker(open, epsilon)
        // 去掉重复的闭合点
        val out = if (simplified.size > 1 && samePoint(simplified.first(), simplified.last())) {
            simplified.dropLast(1)
        } else {
            simplified
        }
        return if (out.size >= 3) out else ring
    }

    /**
     * 向内部收缩 [insetMeters]：顶点沿「质心方向」按比例缩放。
     *
     * 这是对真正多边形 offset 的轻量近似：对走测产生的近似凸/微凹多边形足够稳定，
     * 且不会像逐边偏移那样在大凹角处自交。
     */
    fun insetRing(ring: List<Pt>, insetMeters: Double): List<Pt> {
        if (ring.size < 3 || insetMeters <= 0.0) return ring
        val cx = ring.sumOf { it.x } / ring.size
        val cy = ring.sumOf { it.y } / ring.size
        // 用平均半径把"收缩多少米"换算成缩放比例，最多收 35%（再多就没面积了）
        val meanRadius = ring.sumOf { hypot(it.x - cx, it.y - cy) } / ring.size
        if (meanRadius < 1e-6) return ring
        val factor = (1.0 - (insetMeters / meanRadius).coerceIn(0.0, 0.35))
        return ring.map { Pt(cx + (it.x - cx) * factor, cy + (it.y - cy) * factor) }
    }

    private fun perpendicularDistance(p: Pt, a: Pt, b: Pt): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len = hypot(dx, dy)
        if (len < 1e-9) return hypot(p.x - a.x, p.y - a.y)
        return abs(dy * p.x - dx * p.y + b.x * a.y - b.y * a.x) / len
    }

    private fun samePoint(a: Pt, b: Pt): Boolean =
        abs(a.x - b.x) < 1e-9 && abs(a.y - b.y) < 1e-9
}
