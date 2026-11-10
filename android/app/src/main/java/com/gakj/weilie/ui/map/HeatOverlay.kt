package com.gakj.weilie.ui.map

import android.graphics.Bitmap
import com.gakj.weilie.core.fitting.Geo
import com.gakj.weilie.core.fitting.HeatPalette
import com.gakj.weilie.core.model.SampleRecord
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 信号热力图栅格构建（需求 6）：
 *
 * 把「主区命中 / 邻区命中」的采样点按 IDW（反距离加权）扩散成一张连续栅格图，
 * 交给地图层用 GroundOverlay 贴到经纬度包围盒上。
 *
 * - [Params.cellMeters]：网格间隔（越小越细腻，也越慢）；
 * - [Params.radiusM]：扩散半径（每个格子只受该半径内的采样点影响，避免全图糊成一片）；
 * - [Params.alpha]：整体透明度 0..255；
 * - 颜色由 [HeatPalette] 决定：强=蓝绿、中=黄、弱=橙红。
 *
 * 纯计算 + Bitmap 生成，调用方应放在 Dispatchers.Default 里跑。
 */
object HeatOverlayBuilder {

    data class Params(
        val cellMeters: Double = 15.0,
        val radiusM: Double = 45.0,
        val alpha: Int = 115,
        val strongDbm: Double = -85.0,
        val weakDbm: Double = -105.0,
        /** 单边最多格数：防止超大范围时生成千万像素 */
        val maxCells: Int = 160,
    )

    fun build(samples: List<SampleRecord>, params: Params = Params(), version: Long = 0L): HeatLayer? {
        val valid = samples.filter {
            it.rsrp != null &&
                SampleMark.of(it) != SampleMark.OUT &&
                // 坐标合法性：偶尔定位回调会给出 (0,0) 或越界值，会让包围盒跨半个地球，
                // 贴到地图上就是"整屏糊成一片"——直接剔除。
                it.lat.isFinite() && it.lon.isFinite() &&
                it.lat in -85.0..85.0 && it.lon in -180.0..180.0 &&
                !(it.lat == 0.0 && it.lon == 0.0)
        }
        if (valid.size < 2) return null

        // 栅格原点取包围盒中心，用局部米制坐标做插值
        val lat0 = (valid.minOf { it.lat } + valid.maxOf { it.lat }) / 2.0
        val lon0 = (valid.minOf { it.lon } + valid.maxOf { it.lon }) / 2.0
        val pts = valid.map {
            val p = Geo.toLocal(it.lat, it.lon, lat0, lon0)
            Triple(p.x, p.y, it.rsrp!!.toDouble())
        }

        val pad = params.radiusM
        val minX = pts.minOf { it.first } - pad
        val maxX = pts.maxOf { it.first } + pad
        val minY = pts.minOf { it.second } - pad
        val maxY = pts.maxOf { it.second } + pad
        // 防御：包围盒跨度异常（> 30km）说明混进了脏坐标，宁可不出图也不要糊满屏
        if (maxX - minX > 30_000.0 || maxY - minY > 30_000.0) {
            android.util.Log.w(
                "Weilie",
                "热力图跳过：包围盒过大 ${(maxX - minX).toInt()}x${(maxY - minY).toInt()} m（${valid.size} 点）",
            )
            return null
        }
        val widthM = (maxX - minX).coerceAtLeast(params.cellMeters)
        val heightM = (maxY - minY).coerceAtLeast(params.cellMeters)

        val cell = params.cellMeters.coerceIn(2.0, 200.0)
        var cols = (widthM / cell).roundToInt().coerceIn(4, params.maxCells)
        var rows = (heightM / cell).roundToInt().coerceIn(4, params.maxCells)
        if (cols <= 0 || rows <= 0) return null
        val cellW = widthM / cols
        val cellH = heightM / rows

        val r2 = params.radiusM * params.radiusM
        val values = DoubleArray(cols * rows) { Double.NaN }
        // 行优先：第 0 行是**最北**（地图图片上方=北）
        for (row in 0 until rows) {
            val y = maxY - (row + 0.5) * cellH
            for (col in 0 until cols) {
                val x = minX + (col + 0.5) * cellW
                var wSum = 0.0
                var vSum = 0.0
                for ((px, py, rsrp) in pts) {
                    val dx = px - x
                    val dy = py - y
                    val d2 = dx * dx + dy * dy
                    if (d2 > r2) continue
                    // 1/(d²+ε) 权重，ε 取 1m² 避免除零并让近点不至于一枝独秀
                    val w = 1.0 / (d2 + 1.0)
                    wSum += w
                    vSum += w * rsrp
                }
                if (wSum > 0.0) values[row * cols + col] = vSum / wSum
            }
        }
        if (values.none { !it.isNaN() }) return null

        val colors = HeatPalette.render(
            values, cols, rows,
            HeatPalette.Options(
                strongDbm = params.strongDbm,
                weakDbm = params.weakDbm,
                alpha = params.alpha,
            ),
        )
        val bmp = runCatching {
            Bitmap.createBitmap(colors, cols, rows, Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: return null

        val (minLat, minLon) = Geo.fromLocal(minX, minY, lat0, lon0)
        val (maxLat, maxLon) = Geo.fromLocal(maxX, maxY, lat0, lon0)
        android.util.Log.d(
            "Weilie",
            "热力图 ${cols}x${rows} 格，包围盒 lat[$minLat,$maxLat] lon[$minLon,$maxLon]，${valid.size} 点",
        )
        return HeatLayer(
            bitmap = bmp,
            minLat = minLat, minLon = minLon,
            maxLat = maxLat, maxLon = maxLon,
            version = version,
        )
    }

    /** 统计可用于热力图的点数（UI 上做「暂无数据」提示用） */
    fun usableCount(samples: List<SampleRecord>): Int =
        samples.count { it.rsrp != null && SampleMark.of(it) != SampleMark.OUT }

    @Suppress("unused")
    private fun distance(x1: Double, y1: Double, x2: Double, y2: Double): Double =
        hypot(x1 - x2, y1 - y2)
}
