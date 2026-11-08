package com.gakj.weilie.core.fitting

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** 单个拟合输入样本（米制局部坐标 + 信号） */
data class FitSample(val p: Pt, val rsrp: Double, val accuracyM: Double = 10.0)

/** IDW 插值网格（规则网格，行列存值；NaN=无数据） */
class IdwGrid(
    val minX: Double, val minY: Double,
    val cellW: Double, val cellH: Double,
    val cols: Int, val rows: Int,
    val values: DoubleArray,
) {
    fun valueAt(x: Double, y: Double): Double {
        val c = ((x - minX) / cellW).toInt()
        val r = ((y - minY) / cellH).toInt()
        if (c < 0 || r < 0 || c >= cols || r >= rows) return Double.NaN
        return values[r * cols + c]
    }
}

/**
 * 反距离加权（IDW）信号场插值。
 * p=2 经典幂次；eps 防止除零；离采样点极近时直接取样本值。
 */
object IdwInterpolator {
    private const val POWER = 2.0
    private const val EPS = 1e-6

    fun build(
        samples: List<FitSample>,
        cellMeters: Double = 12.0,
        maxCols: Int = 220,
        maxRows: Int = 220,
        padRatio: Double = 0.15,
    ): IdwGrid {
        require(samples.isNotEmpty()) { "样本为空" }
        var minX = samples.minOf { it.p.x }; var maxX = samples.maxOf { it.p.x }
        var minY = samples.minOf { it.p.y }; var maxY = samples.maxOf { it.p.y }
        val padX = max((maxX - minX) * padRatio, cellMeters * 3)
        val padY = max((maxY - minY) * padRatio, cellMeters * 3)
        minX -= padX; maxX += padX; minY -= padY; maxY += padY
        val spanX = max(maxX - minX, cellMeters)
        val spanY = max(maxY - minY, cellMeters)
        val cols = min(maxCols, max(4, (spanX / cellMeters).toInt() + 1))
        val rows = min(maxRows, max(4, (spanY / cellMeters).toInt() + 1))
        val cw = spanX / cols
        val ch = spanY / rows
        val values = DoubleArray(cols * rows) { Double.NaN }
        for (r in 0 until rows) {
            val y = minY + (r + 0.5) * ch
            for (c in 0 until cols) {
                val x = minX + (c + 0.5) * cw
                values[r * cols + c] = idw(x, y, samples)
            }
        }
        return IdwGrid(minX, minY, cw, ch, cols, rows, values)
    }

    /** 在任意点求值（供单点查询/等值线加密） */
    fun idw(x: Double, y: Double, samples: List<FitSample>): Double {
        var wSum = 0.0
        var vSum = 0.0
        var nearestD2 = Double.MAX_VALUE
        var nearestV = Double.NaN
        for (s in samples) {
            val d2 = (s.p.x - x) * (s.p.x - x) + (s.p.y - y) * (s.p.y - y)
            if (d2 < nearestD2) { nearestD2 = d2; nearestV = s.rsrp }
            if (d2 < EPS) return s.rsrp
            val w = 1.0 / d2.pow(POWER / 2)
            wSum += w; vSum += w * s.rsrp
        }
        return if (wSum > 0) vSum / wSum else nearestV
    }
}
