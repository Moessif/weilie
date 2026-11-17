package com.gakj.weilie.server

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

/**
 * 任务区完成度（v1.2）：把区域切成 [cellM] 米网格，
 * 统计"网格中心落在区内、且该网格内有采样点"的比例。
 *
 * 纯函数，无 IO；由 RoomWs 在采样攒够一批后调用，结果随 state 下发。
 *  - 圆区按半径判断；
 *  - 多边形按射线法判断；
 *  - 网格数超过 [MAX_CELLS] 时自动放大格子，避免超大区域把 CPU 打满。
 */
object ZoneCoverage {

    private const val MAX_CELLS = 40_000
    private const val DEFAULT_CELL_M = 20.0

    fun stat(
        zone: WsMsg.Zone,
        samples: List<WsMsg.SamplePing>,
        cellM: Double = DEFAULT_CELL_M,
    ): WsMsg.ZoneStat {
        val id = zone.id
        // 只统计该区负责人（为空则全队）且落在区内的样本
        val relevant = if (zone.assignees.isEmpty()) {
            samples
        } else {
            val set = zone.assignees.toHashSet()
            samples.filter { it.userId in set }
        }

        val center = zoneCenter(zone) ?: return WsMsg.ZoneStat(id)
        val lat0 = center.first
        val lon0 = center.second
        val pts = relevant.map { toLocal(it.lat, it.lon, lat0, lon0) }

        // 区域包围盒（局部米）
        val bbox = zoneBounds(zone, lat0, lon0) ?: return WsMsg.ZoneStat(id, samples = relevant.size)

        var cell = cellM.coerceAtLeast(4.0)
        while (cellCount(bbox, cell) > MAX_CELLS) cell *= 1.5

        val coveredSet = HashSet<Long>()
        pts.forEach { p ->
            val gx = ((p.first - bbox[0]) / cell).toInt()
            val gy = ((p.second - bbox[1]) / cell).toInt()
            if (gx >= 0 && gy >= 0) coveredSet.add(gx.toLong() shl 32 or (gy.toLong() and 0xffffffffL))
        }

        var total = 0
        var covered = 0
        val minX = bbox[0]
        val minY = bbox[1]
        var y = minY + cell / 2
        while (y < bbox[3]) {
            var x = minX + cell / 2
            while (x < bbox[2]) {
                if (containsLocal(zone, x, y)) {
                    total++
                    val gx = ((x - minX) / cell).toInt()
                    val gy = ((y - minY) / cell).toInt()
                    if (coveredSet.contains(gx.toLong() shl 32 or (gy.toLong() and 0xffffffffL))) covered++
                }
                x += cell
            }
            y += cell
        }
        val ratio = if (total == 0) 0.0 else covered.toDouble() / total
        return WsMsg.ZoneStat(id = id, covered = covered, total = total, ratio = ratio, samples = relevant.size)
    }

    private fun cellCount(bbox: DoubleArray, cell: Double): Long {
        val w = ((bbox[2] - bbox[0]) / cell).toLong() + 1
        val h = ((bbox[3] - bbox[1]) / cell).toLong() + 1
        return w * h
    }

    private fun zoneCenter(zone: WsMsg.Zone): Pair<Double, Double>? = when (zone.kind.uppercase()) {
        "CIRCLE" -> {
            val la = zone.centerLat
            val lo = zone.centerLon
            if (la != null && lo != null) la to lo else null
        }
        else -> {
            val poly = zone.polygon
            if (poly.isEmpty()) null else {
                poly.map { it[0] }.average() to poly.map { it[1] }.average()
            }
        }
    }

    /** [minX, minY, maxX, maxY]（相对中心点的局部米） */
    private fun zoneBounds(zone: WsMsg.Zone, lat0: Double, lon0: Double): DoubleArray? {
        return when (zone.kind.uppercase()) {
            "CIRCLE" -> {
                val r = zone.radiusM ?: return null
                doubleArrayOf(-r, -r, r, r)
            }
            else -> {
                val poly = zone.polygon
                if (poly.size < 3) return null
                val local = poly.map { toLocal(it[0], it[1], lat0, lon0) }
                doubleArrayOf(
                    local.minOf { it.first }, local.minOf { it.second },
                    local.maxOf { it.first }, local.maxOf { it.second },
                )
            }
        }
    }

    /** 局部坐标点是否落在区内 */
    private fun containsLocal(zone: WsMsg.Zone, x: Double, y: Double): Boolean =
        when (zone.kind.uppercase()) {
            "CIRCLE" -> hypot(x, y) <= (zone.radiusM ?: 0.0)
            else -> pointInPolygon(zone.polygon, x, y, zone)
        }

    /** 射线法（多边形按经纬度存，需换算到同一局部坐标系） */
    private fun pointInPolygon(poly: List<List<Double>>, x: Double, y: Double, zone: WsMsg.Zone): Boolean {
        if (poly.size < 3) return false
        val center = zoneCenter(zone) ?: return false
        val local = poly.map { toLocal(it[0], it[1], center.first, center.second) }
        var inside = false
        var j = local.size - 1
        for (i in local.indices) {
            val xi = local[i].first
            val yi = local[i].second
            val xj = local[j].first
            val yj = local[j].second
            if (abs(yi - yj) > 1e-12 && ((yi > y) != (yj > y)) &&
                (x < (xj - xi) * (y - yi) / (yj - yi) + xi)
            ) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    private fun toLocal(lat: Double, lon: Double, lat0: Double, lon0: Double): Pair<Double, Double> {
        val dy = (lat - lat0) * 111_320.0
        val dx = (lon - lon0) * 111_320.0 * cos(Math.toRadians(lat0))
        return dx to dy
    }
}
