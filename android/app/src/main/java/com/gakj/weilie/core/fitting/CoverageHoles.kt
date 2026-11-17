package com.gakj.weilie.core.fitting

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 内洞检测（v0.13.3）。
 *
 * 场景：民警绕着某个院子/小区走一圈时，轨迹围出一个闭合环，
 * 但中间那片**没走过**的区域并不是"实测覆盖区"，却会被凹包/缓冲整片涂绿。
 * 本对象负责把这片"没走过的内部区域"识别成洞（多边形内环）。
 *
 * 算法：
 *  1. 环路判定（首尾距离 < 30% 轨迹长度，且轨迹长 > 200 m），不满足直接返回空；
 *  2. 在凹包包围盒上铺 2 m 网格，把每个格子到最近轨迹线段的距离"盖章"进距离场；
 *  3. 距离 > 洞半径的连续区域就是候选洞；
 *  4. 用 Marching Squares 取等值线成环，过滤掉面积过小、或**贴着外边界**的（那是海湾不是洞）。
 *
 * 纯 Kotlin，无 Android 依赖，可单测。
 */
object CoverageHoles {

    data class Result(
        val holesLocal: List<List<Pt>> = emptyList(),
        val holesLatLon: List<List<Pair<Double, Double>>> = emptyList(),
        val areaM2: Double = 0.0,
    ) {
        val isEmpty: Boolean get() = holesLocal.isEmpty()
    }

    val NONE = Result()

    /**
     * @param hull   外环（局部米制坐标，不闭合）
     * @param track  走过的轨迹（局部米制坐标，按时间顺序）
     * @param holeRadiusM 距离轨迹超过该半径的内部区域才算"没走过"
     * @param minHoleAreaM2 小于该面积的洞忽略（避免刚绕一小圈就抠出小洞）
     */
    fun detect(
        hull: List<Pt>,
        track: List<Pt>,
        lat0: Double,
        lon0: Double,
        holeRadiusM: Double = 30.0,
        minHoleAreaM2: Double = 300.0,
    ): Result {
        if (hull.size < 3 || track.size < 4) return NONE
        if (!isLoop(track)) return NONE

        val radius = holeRadiusM.coerceIn(8.0, 120.0)
        val minX = hull.minOf { it.x }
        val maxX = hull.maxOf { it.x }
        val minY = hull.minOf { it.y }
        val maxY = hull.maxOf { it.y }
        val spanX = maxX - minX
        val spanY = maxY - minY
        if (spanX < radius * 2 || spanY < radius * 2) return NONE

        // 网格：目标每轴 ≤ 200 格，最小 1.5 m
        var step = max(spanX, spanY) / 200.0
        step = step.coerceAtLeast(1.5)
        val cols = (spanX / step).toInt() + 3
        val rows = (spanY / step).toInt() + 3

        val infinity = Double.MAX_VALUE
        val dist = DoubleArray(cols * rows) { infinity }
        val reach = radius * 1.6

        for (i in 1 until track.size) {
            val a = track[i - 1]
            val b = track[i]
            if (dist2(a, b) < 1.0) continue
            val c0 = (((min(a.x, b.x) - reach) - minX) / step).toInt().coerceIn(0, cols - 1)
            val c1 = (((max(a.x, b.x) + reach) - minX) / step).toInt().coerceIn(0, cols - 1)
            val r0 = (((min(a.y, b.y) - reach) - minY) / step).toInt().coerceIn(0, rows - 1)
            val r1 = (((max(a.y, b.y) + reach) - minY) / step).toInt().coerceIn(0, rows - 1)
            for (r in r0..r1) {
                val y = minY + r * step
                for (c in c0..c1) {
                    val x = minX + c * step
                    val d = pointSegmentDistance(x, y, a, b)
                    val idx = r * cols + c
                    if (d < dist[idx]) dist[idx] = d
                }
            }
        }

        // 构造标量场：洞半径以内为负（走过），以外为正（没走过）；外边界外强负，防止等值线溢出
        val values = DoubleArray(cols * rows) { i ->
            val r = i / cols
            val c = i % cols
            val x = minX + c * step
            val y = minY + r * step
            if (!ConvexHull.contains(hull, Pt(x, y))) {
                -1e9
            } else {
                val d = if (dist[i] == infinity) radius * 3 else dist[i]
                d - radius
            }
        }

        val grid = IdwGrid(minX, minY, step, step, cols, rows, values)
        val loops = MarchingSquares.isolines(grid, 0.0).filter { it.size >= 4 }

        val holes = ArrayList<List<Pt>>(1)
        var holesArea = 0.0
        val hullArea = abs(shoelace(hull))
        for (raw in loops) {
            var ring = raw
            if (dist2(ring.first(), ring.last()) < step * 0.75) ring = ring.dropLast(1)
            if (ring.size < 4) continue
            val a = abs(shoelace(ring))
            if (a < minHoleAreaM2) continue
            if (a > hullArea * 0.9) continue              // 几乎把整个面挖空，判定为异常
            // 必须完全在外环内部：贴着外边界的是"海湾"（凹口），不能当洞
            val margin = step * 1.5
            val insideEnough = ring.all { p ->
                ConvexHull.contains(hull, p) && boundaryDistance(p, hull) > margin
            }
            if (!insideEnough) continue
            holes.add(ring)
            holesArea += a
        }
        if (holes.isEmpty()) return NONE

        return Result(
            holesLocal = holes,
            holesLatLon = holes.map { ring -> ring.map { Geo.fromLocal(it.x, it.y, lat0, lon0) } },
            areaM2 = holesArea,
        )
    }

    /** 首尾接近且轨迹足够长，才认为走了一个环 */
    fun isLoop(track: List<Pt>): Boolean {
        if (track.size < 8) return false
        var len = 0.0
        for (i in 1 until track.size) len += sqrt(dist2(track[i - 1], track[i]))
        if (len < 200.0) return false
        val gap = sqrt(dist2(track.first(), track.last()))
        return gap < max(40.0, len * 0.30)
    }

    // ---------- 几何工具 ----------

    private fun dist2(a: Pt, b: Pt): Double {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return dx * dx + dy * dy
    }

    private fun pointSegmentDistance(x: Double, y: Double, a: Pt, b: Pt): Double {
        val vx = b.x - a.x
        val vy = b.y - a.y
        val len2 = vx * vx + vy * vy
        val t = if (len2 <= 1e-9) 0.0 else (((x - a.x) * vx + (y - a.y) * vy) / len2).coerceIn(0.0, 1.0)
        val nx = a.x + vx * t
        val ny = a.y + vy * t
        val dx = x - nx
        val dy = y - ny
        return sqrt(dx * dx + dy * dy)
    }

    private fun boundaryDistance(p: Pt, ring: List<Pt>): Double {
        var best = Double.MAX_VALUE
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[(i + 1) % ring.size]
            val d = pointSegmentDistance(p.x, p.y, a, b)
            if (d < best) best = d
        }
        return best
    }

    private fun shoelace(poly: List<Pt>): Double {
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
