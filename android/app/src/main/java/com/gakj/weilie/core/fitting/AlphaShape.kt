package com.gakj.weilie.core.fitting

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 凹包（Alpha Shape）：用 Delaunay 三角剖分替代凸包，贴合 L 形 / 非凸走测点集。
 *
 * 算法：
 * 1. 点去重 + 极小确定性抖动（避免网格点共线/共圆的退化）；
 * 2. Bowyer–Watson 增量 Delaunay 三角剖分（自实现，无第三方依赖）；
 * 3. 只保留三条边长都 ≤ alpha 的三角形；
 * 4. 取只被 1 个三角形共享的边连成闭合环，取面积最大的外环；
 * 5. 输出逆时针（CCW）、首尾不重复、已清理共线点。
 *
 * 失败（点太少 / 剖分退化 / 环 < 3 点）返回空 list，调用方回退 [ConvexHull]。
 * 纯 Kotlin，无 Android 依赖。
 */
object AlphaShape {

    data class Options(val alphaM: Double = 45.0, val minPoints: Int = 3)

    /** 自适应 alpha：2.2 × 平均最近邻距离（供调用方展示/调参；点 < 2 时返回 0） */
    fun adaptiveAlpha(points: List<Pt>): Double {
        val pts = dedupe(points)
        if (pts.size < 2) return 0.0
        var sum = 0.0
        for (i in pts.indices) {
            var best = Double.MAX_VALUE
            for (j in pts.indices) {
                if (i == j) continue
                val d = dist(pts[i], pts[j])
                if (d < best) best = d
            }
            sum += best
        }
        return 2.2 * sum / pts.size
    }

    /** 凹包外环（CCW，首尾不重复）；失败返回 emptyList() */
    fun hull(points: List<Pt>, opt: Options = Options()): List<Pt> {
        val unique = dedupe(points)
        if (unique.size < max(3, opt.minPoints)) return emptyList()

        val alpha = max(opt.alphaM, adaptiveAlpha(unique))
        val work = jitter(unique)
        val tris = delaunay(work) ?: return emptyList()
        if (tris.isEmpty()) return emptyList()

        // 1) 边长过滤：三条边都 ≤ alpha 才保留（alpha 越大越接近凸包）
        val kept = ArrayList<Tri>(tris.size)
        for (t in tris) {
            if (edgeLen(work, t.a, t.b) <= alpha &&
                edgeLen(work, t.b, t.c) <= alpha &&
                edgeLen(work, t.c, t.a) <= alpha
            ) {
                kept.add(t)
            }
        }
        if (kept.isEmpty()) return emptyList()

        // 2) 统计边被引用次数，只保留边界边（count == 1）
        val count = HashMap<Long, Int>()
        for (t in kept) {
            bump(count, t.a, t.b)
            bump(count, t.b, t.c)
            bump(count, t.c, t.a)
        }
        val adj = HashMap<Int, MutableList<Int>>()
        for ((key, c) in count) {
            if (c != 1) continue
            val a = (key ushr 32).toInt()
            val b = (key and 0xFFFFFFFFL).toInt()
            adj.getOrPut(a) { ArrayList() }.add(b)
            adj.getOrPut(b) { ArrayList() }.add(a)
        }
        if (adj.isEmpty()) return emptyList()

        // 3) 边界边连成闭合环
        val used = HashSet<Long>()
        val loops = ArrayList<List<Int>>()
        for ((start, nbrs) in adj) {
            for (v0 in nbrs) {
                if (edgeKey(start, v0) in used) continue
                val loop = ArrayList<Int>()
                var prev = start
                var cur = v0
                used.add(edgeKey(start, v0))
                loop.add(start)
                var guard = 0
                while (cur != start && guard++ <= adj.size + 8) {
                    loop.add(cur)
                    val nexts = adj[cur].orEmpty()
                    var chosen = -1
                    for (n in nexts) {
                        if (n == prev) continue
                        if (edgeKey(cur, n) in used) continue
                        chosen = n
                        break
                    }
                    if (chosen < 0) break
                    used.add(edgeKey(cur, chosen))
                    prev = cur
                    cur = chosen
                }
                if (cur == start && loop.size >= 3) loops.add(loop)
            }
        }
        if (loops.isEmpty()) return emptyList()

        // 4) 取面积最大的环作为外环
        var best = loops[0]
        var bestArea = abs(signedAreaByIndex(best, work))
        for (k in 1 until loops.size) {
            val a = abs(signedAreaByIndex(loops[k], work))
            if (a > bestArea) {
                bestArea = a
                best = loops[k]
            }
        }

        // 5) 转回原始坐标（非抖动）、统一 CCW、清理共线点
        var poly = ArrayList<Pt>(best.size)
        for (idx in best) poly.add(unique[idx])
        if (signedArea(poly) < 0) poly.reverse()
        poly = ArrayList(removeCollinear(poly))
        return if (poly.size >= 3) poly else emptyList()
    }

    // ---------- 内部实现 ----------

    private class Tri(val a: Int, val b: Int, val c: Int)

    private fun dedupe(points: List<Pt>): List<Pt> = points.distinctBy { it.x to it.y }

    private fun dist(a: Pt, b: Pt): Double {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }

    private fun edgeLen(pts: List<Pt>, a: Int, b: Int): Double = dist(pts[a], pts[b])

    /** 极小确定性抖动，破除网格点的精确共线/共圆退化（量级 1e-7 m，不影响结果） */
    private fun jitter(points: List<Pt>): List<Pt> {
        if (points.size < 3) return points
        return points.mapIndexed { i, p ->
            val jx = ((i * 37 + 11) % 11 - 5) * 1e-7
            val jy = ((i * 53 + 7) % 13 - 6) * 1e-7
            Pt(p.x + jx, p.y + jy)
        }
    }

    /** Bowyer–Watson 增量 Delaunay；返回真实点索引三角形；无法剖分返回 null */
    private fun delaunay(pts: List<Pt>): List<Tri>? {
        val n = pts.size
        if (n < 3) return null
        var minX = pts[0].x
        var maxX = minX
        var minY = pts[0].y
        var maxY = minY
        for (p in pts) {
            minX = min(minX, p.x)
            maxX = max(maxX, p.x)
            minY = min(minY, p.y)
            maxY = max(maxY, p.y)
        }
        val d = max(1e-6, max(maxX - minX, maxY - minY))
        val cx = (minX + maxX) / 2.0
        val cy = (minY + maxY) / 2.0
        val ext = ArrayList<Pt>(n + 3)
        ext.addAll(pts)
        ext.add(Pt(cx - 20 * d, cy - 20 * d))
        ext.add(Pt(cx + 20 * d, cy - 20 * d))
        ext.add(Pt(cx, cy + 20 * d))
        val s0 = n
        val s1 = n + 1
        val s2 = n + 2

        val tris = ArrayList<Tri>()
        tris.add(Tri(s0, s1, s2))
        for (i in 0 until n) {
            val p = ext[i]
            val bad = ArrayList<Tri>()
            for (t in tris) {
                if (inCircumcircle(p, ext[t.a], ext[t.b], ext[t.c])) bad.add(t)
            }
            val edgeCount = HashMap<Long, Int>()
            for (t in bad) {
                bump(edgeCount, t.a, t.b)
                bump(edgeCount, t.b, t.c)
                bump(edgeCount, t.c, t.a)
            }
            if (bad.isNotEmpty()) tris.removeAll(bad)
            for ((key, c) in edgeCount) {
                if (c != 1) continue
                val a = (key ushr 32).toInt()
                val b = (key and 0xFFFFFFFFL).toInt()
                tris.add(Tri(a, b, i))
            }
        }
        return tris.filter { it.a < n && it.b < n && it.c < n }
    }

    /** 点 p 是否落在三角形 abc 的外接圆内（相对误差判定，抗共圆抖动） */
    private fun inCircumcircle(p: Pt, a: Pt, b: Pt, c: Pt): Boolean {
        val cross = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
        if (abs(cross) < 1e-18) return false
        val ax = a.x - p.x
        val ay = a.y - p.y
        val bx = b.x - p.x
        val by = b.y - p.y
        val cx = c.x - p.x
        val cy = c.y - p.y
        val a2 = ax * ax + ay * ay
        val b2 = bx * bx + by * by
        val c2 = cx * cx + cy * cy
        val det = if (cross > 0) {
            ax * (by * c2 - b2 * cy) - ay * (bx * c2 - b2 * cx) + a2 * (bx * cy - by * cx)
        } else {
            ax * (cy * b2 - c2 * by) - ay * (cx * b2 - c2 * bx) + a2 * (cx * by - cy * bx)
        }
        val scale = abs(ax * by * c2) + abs(b2 * cy * ax) + abs(ay * bx * c2) +
            abs(b2 * cx * ay) + abs(a2 * bx * cy) + abs(a2 * by * cx)
        return det > 1e-12 * (scale + 1e-30)
    }

    private fun edgeKey(a: Int, b: Int): Long {
        val lo = min(a, b).toLong()
        val hi = max(a, b).toLong()
        return (lo shl 32) or (hi and 0xFFFFFFFFL)
    }

    private fun bump(map: HashMap<Long, Int>, a: Int, b: Int) {
        val k = edgeKey(a, b)
        map[k] = (map[k] ?: 0) + 1
    }

    private fun signedAreaByIndex(loop: List<Int>, pts: List<Pt>): Double {
        var s = 0.0
        for (i in loop.indices) {
            val a = pts[loop[i]]
            val b = pts[loop[(i + 1) % loop.size]]
            s += a.x * b.y - b.x * a.y
        }
        return s / 2.0
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

    /** 去掉（近似）共线点：|叉积| 相对两条边长的乘积可忽略时删除 */
    private fun removeCollinear(poly: List<Pt>): List<Pt> {
        if (poly.size <= 3) return poly
        val out = ArrayList<Pt>(poly.size)
        val n = poly.size
        for (i in 0 until n) {
            val a = poly[(i - 1 + n) % n]
            val b = poly[i]
            val c = poly[(i + 1) % n]
            val abx = b.x - a.x
            val aby = b.y - a.y
            val bcx = c.x - b.x
            val bcy = c.y - b.y
            val cr = abx * bcy - aby * bcx
            val scale = sqrt(abx * abx + aby * aby) * sqrt(bcx * bcx + bcy * bcy)
            if (scale > 1e-12 && abs(cr) <= 1e-9 * scale) continue
            out.add(b)
        }
        return if (out.size >= 3) out else poly
    }
}
