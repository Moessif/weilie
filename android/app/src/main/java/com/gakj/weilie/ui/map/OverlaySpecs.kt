package com.gakj.weilie.ui.map

import android.graphics.Bitmap

/** 信号热力图图层：IDW 插值生成的栅格位图 + 地理包围盒（WGS84） */
data class HeatLayer(
    val bitmap: Bitmap,
    val minLat: Double,
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double,
    /** 内容指纹（样本数/参数变化时递增，用于触发重绘） */
    val version: Long,
)

/** 基站理论覆盖区（可叠加多个）：全向圆 / 扇形 / TA 距离环带 */
data class TheoryShape(
    val kind: Kind,
    val centerLat: Double,
    val centerLon: Double,
    /** 全向圆或扇形半径；环带为外半径 */
    val radiusM: Double,
    /** 环带内半径（kind == RING 时有意义） */
    val innerRadiusM: Double = 0.0,
    /** 扇形起始方位角（0=正北，顺时针） */
    val startBearing: Float = 0f,
    /** 扇形张角，默认 120° */
    val sweepDeg: Float = 120f,
    val color: Int = BLUE,
) {
    enum class Kind { CIRCLE, SECTOR, RING }

    companion object {
        const val BLUE = 0xFF3B82F6.toInt()
        const val ORANGE = 0xFFFB923C.toInt()
        const val PURPLE = 0xFFA855F7.toInt()
    }
}

/** 封闭区域（实测有效点凹包 / 条带兜底） */
data class ClosedArea(
    val ring: List<Pair<Double, Double>>,
    val fillColor: Int = 0x5534D399,
    val strokeColor: Int = 0xCC34D399.toInt(),
    val label: String = "实测覆盖区",
)
