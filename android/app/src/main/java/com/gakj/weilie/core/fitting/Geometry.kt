package com.gakj.weilie.core.fitting

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** 平面点：x=东西向米，y=南北向米（本工程内部统一用米制平面坐标，边界处再转回经纬度） */
data class Pt(val x: Double, val y: Double)

/** 地理工具：经纬度 <-> 局部米制平面（以 lat0 为基准纬度的等距圆柱近似，街区尺度误差可忽略） */
object Geo {
    private const val M_PER_DEG_LAT = 110540.0
    private const val M_PER_DEG_LON_EQ = 111320.0

    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }

    fun toLocal(lat: Double, lon: Double, lat0: Double, lon0: Double): Pt =
        Pt((lon - lon0) * M_PER_DEG_LON_EQ * cos(Math.toRadians(lat0)), (lat - lat0) * M_PER_DEG_LAT)

    fun fromLocal(x: Double, y: Double, lat0: Double, lon0: Double): Pair<Double, Double> {
        val lat = lat0 + y / M_PER_DEG_LAT
        val lon = lon0 + x / (M_PER_DEG_LON_EQ * cos(Math.toRadians(lat0)))
        return lat to lon
    }
}

/** Andrew 单调链凸包；返回逆时针（CCW）且不重复的顶点环 */
object ConvexHull {
    fun hull(points: List<Pt>): List<Pt> {
        if (points.size < 3) return points.distinctBy { it.x to it.y }
        val pts = points.distinctBy { it.x to it.y }.sortedWith(compareBy({ it.x }, { it.y }))
        if (pts.size < 3) return pts
        val cross = fun(o: Pt, a: Pt, b: Pt): Double =
            (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
        val lower = ArrayList<Pt>(pts.size)
        for (p in pts) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower.last(), p) <= 0) lower.removeAt(lower.size - 1)
            lower.add(p)
        }
        val upper = ArrayList<Pt>(pts.size)
        for (i in pts.indices.reversed()) {
            val p = pts[i]
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper.last(), p) <= 0) upper.removeAt(upper.size - 1)
            upper.add(p)
        }
        lower.removeAt(lower.size - 1)
        upper.removeAt(upper.size - 1)
        return lower + upper
    }

    fun area(pts: List<Pt>): Double {
        if (pts.size < 3) return 0.0
        var s = 0.0
        for (i in pts.indices) {
            val a = pts[i]; val b = pts[(i + 1) % pts.size]
            s += a.x * b.y - b.x * a.y
        }
        return abs(s) / 2
    }

    fun perimeter(pts: List<Pt>): Double {
        if (pts.size < 2) return 0.0
        var s = 0.0
        for (i in pts.indices) {
            val a = pts[i]; val b = pts[(i + 1) % pts.size]
            s += sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y))
        }
        return s
    }

    fun contains(poly: List<Pt>, p: Pt): Boolean {
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val a = poly[i]; val b = poly[j]
            if ((a.y > p.y) != (b.y > p.y) &&
                p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y + 1e-12) + a.x
            ) inside = !inside
            j = i
        }
        return inside
    }
}

/**
 * 凸多边形外扩缓冲带（r 米）。
 * 原理：凸包外扩 = 对每条边沿外法线平移 r，对每个顶点补半径 r 圆弧采样点，再取并集凸包。
 * 输入必须是 CCW 凸多边形（ConvexHull.hull 的输出）。
 */
object PolygonBuffer {
    fun buffer(hull: List<Pt>, r: Double): List<Pt> {
        if (hull.size < 3 || r <= 0.0) return hull
        val candidates = ArrayList<Pt>(hull.size * 6)
        val n = hull.size
        for (i in 0 until n) {
            val a = hull[i]; val b = hull[(i + 1) % n]
            val dx = b.x - a.x; val dy = b.y - a.y
            val len = sqrt(dx * dx + dy * dy)
            if (len < 1e-9) continue
            // CCW 多边形内部在边左侧，外法线为 (dy, -dx)/len
            val nx = dy / len; val ny = -dx / len
            candidates.add(Pt(a.x + nx * r, a.y + ny * r))
            candidates.add(Pt(b.x + nx * r, b.y + ny * r))
        }
        for (p in hull) {
            for (k in 0 until 8) {
                val ang = 2 * PI * k / 8
                candidates.add(Pt(p.x + r * cos(ang), p.y + r * sin(ang)))
            }
        }
        return ConvexHull.hull(candidates)
    }
}

/** 方向覆盖度：以质心为圆心按 16 扇区统计样本分布，衡量走测路线是否绕全目标 */
object DirectionCoverage {
    const val SECTORS = 16

    fun coverage(centroid: Pt, samples: List<Pt>): Pair<Int, Double> {
        if (samples.isEmpty()) return 0 to 0.0
        val filled = BooleanArray(SECTORS)
        for (p in samples) {
            val ang = atan2(p.y - centroid.y, p.x - centroid.x)
            val idx = ((ang / (2 * PI) * SECTORS).toInt() + SECTORS) % SECTORS
            filled[idx] = true
        }
        val count = filled.count { it }
        return count to count.toDouble() / SECTORS
    }
}

/** 折线抽稀：Douglas-Peucker（供轨迹与等值线导出瘦身） */
object Simplify {
    fun dp(points: List<Pt>, toleranceM: Double): List<Pt> {
        if (points.size <= 2) return points
        val keep = BooleanArray(points.size)
        keep[0] = true; keep[points.size - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.add(0 to points.size - 1)
        while (stack.isNotEmpty()) {
            val (s, e) = stack.removeLast()
            var maxD = 0.0; var idx = -1
            for (i in s + 1 until e) {
                val d = segDist(points[i], points[s], points[e])
                if (d > maxD) { maxD = d; idx = i }
            }
            if (maxD > toleranceM && idx > 0) {
                keep[idx] = true
                stack.add(s to idx); stack.add(idx to e)
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    private fun segDist(p: Pt, a: Pt, b: Pt): Double {
        val dx = b.x - a.x; val dy = b.y - a.y
        val l2 = dx * dx + dy * dy
        if (l2 < 1e-12) return min(sqrt((p.x - a.x) * (p.x - a.x) + (p.y - a.y) * (p.y - a.y)), Double.MAX_VALUE)
        var t = ((p.x - a.x) * dx + (p.y - a.y) * dy) / l2
        t = t.coerceIn(0.0, 1.0)
        val px = a.x + t * dx; val py = a.y + t * dy
        return sqrt((p.x - px) * (p.x - px) + (p.y - py) * (p.y - py))
    }
}
