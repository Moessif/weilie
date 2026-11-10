package com.gakj.weilie.core.cellmap

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.math.sin

/**
 * 以某个点（通常是基站库里的站址）为中心，把一块圆形区域切成若干子区：
 * - [sectors]：按方位角切成 N 个扇形（人数少、想一人守一条射线方向时用）
 * - [rings]：按半径切成 N 个同心圈层（想先打中心再向外扩时用）
 *
 * 坐标系约定：多边形顶点一律 `(lat, lon)`，与 [com.gakj.weilie.core.sync.WsMsg.Zone.polygon]
 * 的 `List<List<Double>>`（每组 `[lat, lon]`）顺序一致，可直接发给服务端。
 */
/** 多边形 = 顶点列表（纬度, 经度），与 WsMsg.Zone.polygon 的 [lat, lon] 顺序一致 */
typealias PolygonPoints = List<Pair<Double, Double>>

object SectorZones {


    /** 正圆多边形（逆时针，从正北开始） */
    fun circle(lat: Double, lon: Double, radiusM: Double, segments: Int = 24): PolygonPoints {
        val n = segments.coerceIn(8, 180)
        return (0 until n).map { i ->
            CellGeometry.destination(lat, lon, radiusM, i * 360.0 / n)
        }
    }

    /**
     * 扇形切分：把以 center 为圆心、radiusM 为半径的圆按方位角均分。
     *
     * @param count 参与分区的队员数（>=1）
     * @param startBearingDeg 第一块扇区的起始方位角，默认 0（正北）
     * @return 每个多边形都是「圆心 + 外弧点」，共 count 个
     */
    fun sectors(
        centerLat: Double,
        centerLon: Double,
        radiusM: Double,
        count: Int,
        startBearingDeg: Double = 0.0,
        arcStepDeg: Double = 5.0,
    ): List<PolygonPoints> {
        val n = count.coerceIn(1, 36)
        if (n == 1) return listOf(circle(centerLat, centerLon, radiusM, 72))
        val sweep = 360.0 / n
        val step = arcStepDeg.coerceIn(1.0, 30.0)
        return (0 until n).map { i ->
            val from = startBearingDeg + i * sweep
            val pts = ArrayList<Pair<Double, Double>>()
            pts.add(centerLat to centerLon)
            var a = 0.0
            while (a <= sweep + 1e-6) {
                pts.add(CellGeometry.destination(centerLat, centerLon, radiusM, from + a))
                a += step
            }
            pts
        }
    }

    /**
     * 同心圈层：按**等面积**切分（第 i 层外半径 = R·√((i+1)/n)），第 0 层是中心圆，之后每层是环带。
     * 这样每个队员分到的搜索面积相同（等半径切分会让外圈面积是内圈的数倍，不公平）。
     * 环带多边形 = 外弧逆时针 + 内弧顺时针，这样即使渲染层不做奇偶填充也是闭合单环。
     */
    fun rings(
        centerLat: Double,
        centerLon: Double,
        radiusM: Double,
        ringCount: Int,
        segments: Int = 48,
    ): List<PolygonPoints> {
        val n = ringCount.coerceIn(1, 8)
        val seg = segments.coerceIn(12, 180)
        fun edge(at: Int): Double = radiusM * sqrt(at.toDouble() / n)
        return (0 until n).map { i ->
            val outer = edge(i + 1)
            val inner = edge(i)
            when {
                i == 0 -> circle(centerLat, centerLon, outer, seg)
                else -> {
                    val pts = ArrayList<Pair<Double, Double>>(seg * 2)
                    for (k in 0 until seg) {
                        pts.add(CellGeometry.destination(centerLat, centerLon, outer, k * 360.0 / seg))
                    }
                    for (k in seg - 1 downTo 0) {
                        pts.add(CellGeometry.destination(centerLat, centerLon, inner, k * 360.0 / seg))
                    }
                    pts
                }
            }
        }
    }

    /** 多边形面积（㎡），局部平面近似：先转成相对质心的米制坐标再鞋带公式 */
    fun areaM2(poly: PolygonPoints): Double {
        if (poly.size < 3) return 0.0
        val lat0 = poly.sumOf { it.first } / poly.size
        val lon0 = poly.sumOf { it.second } / poly.size
        val mPerLon = CellGeometry.metersPerDegLon(lat0)
        var sum = 0.0
        for (i in poly.indices) {
            val (latA, lonA) = poly[i]
            val (latB, lonB) = poly[(i + 1) % poly.size]
            val xa = (lonA - lon0) * mPerLon
            val ya = (latA - lat0) * CellGeometry.METERS_PER_DEG_LAT
            val xb = (lonB - lon0) * mPerLon
            val yb = (latB - lat0) * CellGeometry.METERS_PER_DEG_LAT
            sum += xa * yb - xb * ya
        }
        return abs(sum) / 2.0
    }

    fun centroid(poly: PolygonPoints): Pair<Double, Double> {
        if (poly.isEmpty()) return 0.0 to 0.0
        return poly.sumOf { it.first } / poly.size to poly.sumOf { it.second } / poly.size
    }

    /** 多边形是否可用（足够顶点且首尾不重合） */
    fun isUsable(poly: PolygonPoints): Boolean {
        if (poly.size < 3) return false
        return poly.all { it.first.isFinite() && it.second.isFinite() }
    }

    /** 扇形/圈层的默认标签，便于任务备注里说清「哪一块给了谁」 */
    fun labelFor(mode: Mode, index: Int, count: Int): String = when (mode) {
        Mode.SECTOR -> "扇区 ${index + 1}/$count"
        Mode.RING -> "圈层 ${index + 1}/$count"
    }

    enum class Mode(val title: String, val desc: String) {
        SECTOR("按方位切扇形", "适合一人守一条方向、沿路推进"),
        RING("按半径切圈层", "适合先控中心、再逐层外扩"),
    }
}
