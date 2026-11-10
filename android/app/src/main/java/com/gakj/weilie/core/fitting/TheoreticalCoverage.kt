package com.gakj.weilie.core.fitting

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 基站理论覆盖区参数。
 *
 * @param azimuthDeg    天线方位角（0=正北、顺时针）；null 表示全向圆
 * @param sectorWidthDeg 有方位角时的扇形张角（度），默认 120
 * @param radiusM       外半径；null 用 [defaultRadiusM]
 * @param innerRadiusM  内半径；>0 时生成圆环/扇环（室内站/近场空洞）
 */
data class TheorySpec(
    val lat: Double,
    val lon: Double,
    val azimuthDeg: Double? = null,
    val sectorWidthDeg: Double = 120.0,
    val radiusM: Double? = null,
    val innerRadiusM: Double = 0.0,
    val defaultRadiusM: Double = 1000.0,
)

/**
 * 基站理论覆盖区（纯几何，无 Android 依赖）。
 *
 * 坐标约定：以 [TheorySpec.lat]/[TheorySpec.lon] 为原点，经 [Geo.toLocal] 转局部米制
 * （x=东西向、y=南北向）；方位角 0=正北、顺时针增加（x = r·sin(az)、y = r·cos(az)），
 * 最后经 [Geo.fromLocal] 转回经纬度。
 *
 * - 全向：正多边形近似圆，5° 步长（72 段）；
 * - 扇区：外弧 + 中心（或内弧），5° 步长；
 * - 圆环/扇环：外环仅外边界，洞由 [contains] 的内半径判断。
 */
object TheoreticalCoverage {

    private const val ARC_STEP_DEG = 5.0

    /** 生成理论覆盖区外环（经纬度多边形，首尾不重复） */
    fun outline(spec: TheorySpec): List<Pair<Double, Double>> {
        val r = (spec.radiusM ?: spec.defaultRadiusM).coerceAtLeast(0.1)
        val inner = spec.innerRadiusM.coerceIn(0.0, r)
        val az = spec.azimuthDeg

        val result = ArrayList<Pair<Double, Double>>()
        if (az == null) {
            // 全向圆：逆时针取点（外边界；圆环的洞由 contains 处理）
            val steps = max(8, (360.0 / ARC_STEP_DEG).toInt())
            for (i in 0 until steps) {
                val ang = 2.0 * PI * i / steps
                result.add(toLatLon(spec, r * cos(ang), r * sin(ang)))
            }
        } else {
            val half = (spec.sectorWidthDeg / 2.0).coerceIn(0.1, 180.0)
            val steps = max(1, (2.0 * half / ARC_STEP_DEG).toInt())
            // 外弧：从 az-half 扫到 az+half（顺时针、方位角递增）
            for (i in 0..steps) {
                val b = az - half + 2.0 * half * i / steps
                result.add(toLatLon(spec, r * sin(Math.toRadians(b)), r * cos(Math.toRadians(b))))
            }
            if (inner > 0.0) {
                // 内弧反向，形成扇环
                for (i in steps downTo 0) {
                    val b = az - half + 2.0 * half * i / steps
                    result.add(toLatLon(spec, inner * sin(Math.toRadians(b)), inner * cos(Math.toRadians(b))))
                }
            } else {
                // 无内半径：外弧端点 + 中心点
                result.add(toLatLon(spec, 0.0, 0.0))
            }
        }
        return dedupeAdjacent(result)
    }

    /** 点是否落在理论区内（扇区角度判断 + 半径/内半径约束） */
    fun contains(spec: TheorySpec, lat: Double, lon: Double): Boolean {
        val r = (spec.radiusM ?: spec.defaultRadiusM).coerceAtLeast(0.0)
        val inner = spec.innerRadiusM.coerceIn(0.0, r)
        val p = Geo.toLocal(lat, lon, spec.lat, spec.lon)
        val d = hypot(p.x, p.y)
        if (d > r || d < inner) return false
        val az = spec.azimuthDeg ?: return true
        if (d < 1e-9) {
            // 中心点方向不确定：无内半径即视为覆盖
            return inner <= 0.0
        }
        return angularDiff(bearingDeg(p.x, p.y), az) <= spec.sectorWidthDeg / 2.0
    }

    /** 从覆盖区中心看向 (lat, lon) 的方位角：0..360，0=正北、顺时针 */
    fun bearingOf(spec: TheorySpec, lat: Double, lon: Double): Double {
        val p = Geo.toLocal(lat, lon, spec.lat, spec.lon)
        return bearingDeg(p.x, p.y)
    }

    // ---------- 内部实现 ----------

    private fun toLatLon(spec: TheorySpec, x: Double, y: Double): Pair<Double, Double> =
        Geo.fromLocal(x, y, spec.lat, spec.lon)

    /** x=东西向、y=南北向；方位角 0=正北、顺时针 */
    private fun bearingDeg(x: Double, y: Double): Double {
        var b = Math.toDegrees(atan2(x, y))
        if (b < 0.0) b += 360.0
        return b
    }

    /** 两个方位角的最小夹角（0..180） */
    private fun angularDiff(a: Double, b: Double): Double {
        var d = abs(a - b) % 360.0
        if (d > 180.0) d = 360.0 - d
        return d
    }

    private fun dedupeAdjacent(points: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        if (points.isEmpty()) return points
        val out = ArrayList<Pair<Double, Double>>(points.size)
        for (p in points) {
            val last = out.lastOrNull()
            if (last != null && abs(last.first - p.first) < 1e-12 && abs(last.second - p.second) < 1e-12) continue
            out.add(p)
        }
        if (out.size > 1) {
            val first = out.first()
            val last = out.last()
            if (abs(first.first - last.first) < 1e-12 && abs(first.second - last.second) < 1e-12) {
                out.removeAt(out.size - 1)
            }
        }
        return out
    }
}
