package com.gakj.weilie.core.fitting

/**
 * 多边形求交（Sutherland–Hodgman）。
 *
 * 用途：把「实测覆盖封闭区域」和「基站理论覆盖区」叠在一起时，画出两者的交集
 * （需求第 7 条的紫色 = 理论 ∩ 实测）。
 *
 * 约定：坐标是 (lat, lon)，按小范围（几公里内）近似成平面直角坐标处理——
 * 经度方向 1° 的实际长度随纬度收缩，但当两个多边形范围一致、尺寸相近时，
 * 该近似对「画一块半透明色块」的精度完全够用，且不会引入投影依赖。
 *
 * 限制：裁剪多边形（clip）必须是凸多边形。理论覆盖区的圆/扇形本身就是凸的；
 * 圆环（TA 距离带）不凸，调用方应退化成「外圆」作为裁剪多边形再画。
 */
object CoverageIntersect {

    /**
     * @param subject 被裁剪多边形（可以是凹的）
     * @param clip    裁剪多边形（必须凸，顶点顺序任意）
     * @return 交集多边形顶点；无交集或输入不合法时返回空列表
     */
    fun intersect(
        subject: List<Pair<Double, Double>>,
        clip: List<Pair<Double, Double>>,
    ): List<Pair<Double, Double>> {
        if (subject.size < 3 || clip.size < 3) return emptyList()
        val ccw = signedArea(clip) >= 0.0
        var output: List<Pair<Double, Double>> = subject
        for (i in clip.indices) {
            if (output.isEmpty()) return emptyList()
            val a = clip[i]
            val b = clip[(i + 1) % clip.size]
            val input = output
            val next = ArrayList<Pair<Double, Double>>(input.size + 4)
            var prev = input.last()
            var prevInside = inside(prev, a, b, ccw)
            for (cur in input) {
                val curInside = inside(cur, a, b, ccw)
                if (curInside) {
                    if (!prevInside) crossPoint(prev, cur, a, b)?.let { next.add(it) }
                    next.add(cur)
                } else if (prevInside) {
                    crossPoint(prev, cur, a, b)?.let { next.add(it) }
                }
                prev = cur
                prevInside = curInside
            }
            output = next
        }
        return output
    }

    /** 有向面积（lat 当 y、lon 当 x）；>0 = 逆时针 */
    fun signedArea(poly: List<Pair<Double, Double>>): Double {
        if (poly.size < 3) return 0.0
        var s = 0.0
        for (i in poly.indices) {
            val p = poly[i]
            val q = poly[(i + 1) % poly.size]
            s += p.second * q.first - q.second * p.first
        }
        return s / 2.0
    }

    /** 点是否在边 a→b 的内侧（含边界） */
    private fun inside(
        p: Pair<Double, Double>,
        a: Pair<Double, Double>,
        b: Pair<Double, Double>,
        ccw: Boolean,
    ): Boolean {
        val cross = (b.second - a.second) * (p.first - a.first) - (b.first - a.first) * (p.second - a.second)
        return if (ccw) cross >= -1e-12 else cross <= 1e-12
    }

    /** 线段 p→q 与直线 a→b 的交点（平行时返回 null） */
    private fun crossPoint(
        p: Pair<Double, Double>,
        q: Pair<Double, Double>,
        a: Pair<Double, Double>,
        b: Pair<Double, Double>,
    ): Pair<Double, Double>? {
        val rx = q.first - p.first
        val ry = q.second - p.second
        val sx = b.first - a.first
        val sy = b.second - a.second
        val denom = rx * sy - ry * sx
        if (kotlin.math.abs(denom) < 1e-15) return null
        val t = ((a.first - p.first) * sy - (a.second - p.second) * sx) / denom
        return (p.first + t * rx) to (p.second + t * ry)
    }

    /** 生成圆形（凸）多边形的顶点，供裁剪用 */
    fun circlePolygon(
        lat: Double,
        lon: Double,
        radiusM: Double,
        segments: Int = 48,
    ): List<Pair<Double, Double>> {
        if (radiusM <= 0.0) return emptyList()
        val n = segments.coerceIn(12, 120)
        val dLat = radiusM / 111_320.0
        val cosLat = kotlin.math.cos(Math.toRadians(lat)).coerceAtLeast(1e-6)
        val dLon = radiusM / (111_320.0 * cosLat)
        return (0 until n).map { i ->
            val th = 2.0 * Math.PI * i / n
            (lat + dLat * kotlin.math.sin(th)) to (lon + dLon * kotlin.math.cos(th))
        }
    }
}
