package com.gakj.weilie.core.fitting

import com.gakj.weilie.core.model.SampleRecord
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 模拟实测区域（轨迹缓冲成面）。
 *
 * 现实里「高精度凹包」对稀疏、来回走、绕楼的轨迹并不总是好看：点一少就退化成条带、
 * 点一密又会沿着砖缝长出很多小尖角。很多民警真正想要的是更朴素的表达——
 * **我走到哪儿，就以那儿为圆心画一个圆（或方块），把圆连起来就是「我实测到的区域」**，
 * 这个圆/方块多大由人自己定，和信号强弱无关，纯看「走到过」。
 *
 * 实现：把轨迹（点及相邻点连线）按 [Options.radiusM] 在网格上做距离场（圆=欧氏距离，方=切比雪夫距离），
 * 再用 Marching Squares 取零等值线得到**多个圆/方块的并集外轮廓**——
 * 这样重叠处不会因为半透明叠加出现深浅不一，也不会有很多条边。
 *
 * 纯 Kotlin，无 Android 依赖。
 */
object TrackBufferArea {

    enum class Shape(val label: String) {
        CIRCLE("圆形"),
        SQUARE("方形"),
    }

    data class Options(
        /** 每个轨迹点周围的半径（米）：圆＝半径，方＝半个边长 */
        val radiusM: Double = 30.0,
        val shape: Shape = Shape.CIRCLE,
        val maxAccuracyM: Double = 120.0,
        val maxSpreadM: Double = 3_000.0,
    )

    data class Result(
        /** 经纬度外环，首尾不重复 */
        val ringLatLon: List<Pair<Double, Double>>,
        /** 估算面积（m²） */
        val areaM2: Double,
        /** 参与缓冲的轨迹点数 */
        val pointCount: Int,
    )

    fun build(samples: List<SampleRecord>, opt: Options = Options()): Result? {
        val sane = TrackSanity.saneSamples(samples, opt.maxAccuracyM, opt.maxSpreadM)
        if (sane.isEmpty()) return null
        val radius = opt.radiusM.coerceIn(3.0, 200.0)
        val lat0 = sane.map { it.lat }.average()
        val lon0 = sane.map { it.lon }.average()
        val pts = sane.map { Geo.toLocal(it.lat, it.lon, lat0, lon0) }

        val minX = pts.minOf { it.x } - radius * 1.6
        val maxX = pts.maxOf { it.x } + radius * 1.6
        val minY = pts.minOf { it.y } - radius * 1.6
        val maxY = pts.maxOf { it.y } + radius * 1.6
        val spanX = max(maxX - minX, radius * 2)
        val spanY = max(maxY - minY, radius * 2)
        // 网格步长：半径的 1/3，但总格数压到 200 以内（长轨迹也不会卡主线程）
        var step = (radius / 8.0).coerceIn(0.75, 6.0)
        step = max(step, max(spanX, spanY) / 200.0)
        val cols = ((spanX / step).toInt() + 2).coerceAtLeast(3)
        val rows = ((spanY / step).toInt() + 2).coerceAtLeast(3)

        // 距离场：值 = radius - 到最近「轨迹线段」的距离，>0 即在缓冲区内。
        // 用线段而不是孤立点，相邻点之间的走廊也会被铺满，两个点即使间距大于直径也不会断成两块
        // （真机反馈：只画出了其中一块圆，看起来像漏测）。
        val values = DoubleArray(cols * rows) { -1e6 }
        val reach = ((radius * 1.2) / step).toInt() + 2
        val segs = if (pts.size == 1) {
            listOf(pts[0] to pts[0])
        } else {
            pts.zipWithNext { a, b -> a to b }.filter { (a, b) -> dist(a, b) >= 1.0 }
                .ifEmpty { listOf(pts.first() to pts.first()) }
        }
        for ((a, b) in segs) {
            val reachM = radius * 1.2
            val c0 = (((minOf(a.x, b.x) - reachM) - minX) / step).toInt().coerceIn(0, cols - 1)
            val c1 = (((maxOf(a.x, b.x) + reachM) - minX) / step).toInt().coerceIn(0, cols - 1)
            val r0 = (((minOf(a.y, b.y) - reachM) - minY) / step).toInt().coerceIn(0, rows - 1)
            val r1 = (((maxOf(a.y, b.y) + reachM) - minY) / step).toInt().coerceIn(0, rows - 1)
            for (r in r0..r1) {
                val y = minY + r * step
                for (c in c0..c1) {
                    val x = minX + c * step
                    val d = distanceToSegment(x, y, a, b, opt.shape)
                    val v = radius - d
                    if (v > 0.0) {
                        val i = r * cols + c
                        if (v > values[i]) values[i] = v
                    }
                }
            }
        }

        val grid = IdwGrid(minX, minY, step, step, cols, rows, values)
        val loops = MarchingSquares.isolines(grid, 0.0).filter { it.size >= 3 }
        if (loops.isEmpty()) return null
        var ring = loops.maxByOrNull { abs(shoelace(it)) } ?: return null
        // Marching Squares 的环首尾重合，去掉最后一个点
        if (ring.size >= 4 && dist(ring.first(), ring.last()) < step * 0.75) ring = ring.dropLast(1)
        if (ring.size < 3) return null
        ring = PolylineSimplify.simplifyRing(ring, (step * 0.3).coerceIn(0.6, 2.5))
        if (ring.size < 3) return null

        // 规模兜底：正常走测几平方公里以内；超出说明输入异常，宁可不画
        val extent = max(
            ring.maxOf { it.x } - ring.minOf { it.x },
            ring.maxOf { it.y } - ring.minOf { it.y },
        )
        if (extent > 6_000.0) return null

        return Result(
            ringLatLon = ring.map { Geo.fromLocal(it.x, it.y, lat0, lon0) },
            areaM2 = abs(shoelace(ring)),
            pointCount = sane.size,
        )
    }

    /** 网格点 (x,y) 到一个线段的距离；CIRCLE 用欧氏距离，SQUARE 用切比雪夫距离（方块语义） */
    private fun distanceToSegment(x: Double, y: Double, a: Pt, b: Pt, shape: Shape): Double {
        val vx = b.x - a.x
        val vy = b.y - a.y
        val len2 = vx * vx + vy * vy
        val t = if (len2 <= 1e-9) 0.0 else (((x - a.x) * vx + (y - a.y) * vy) / len2).coerceIn(0.0, 1.0)
        val nx = a.x + vx * t
        val ny = a.y + vy * t
        val dx = abs(x - nx)
        val dy = abs(y - ny)
        return when (shape) {
            Shape.CIRCLE -> sqrt(dx * dx + dy * dy)
            Shape.SQUARE -> max(dx, dy)
        }
    }

    private fun dist(a: Pt, b: Pt): Double = sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y))

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
