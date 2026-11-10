package com.gakj.weilie.core.fitting

/**
 * 原始定位点（WGS84 经纬度 + 精度 + 时间戳）。
 */
data class Fix(
    val lat: Double,
    val lon: Double,
    val accuracyM: Double,
    val tsEpochMs: Long,
)

/**
 * 双重安检结果：
 * - [Accept]：通过，坐标是**滤波后**的估计值；
 * - [Skip]：直接丢弃（精度差 / 飞点），[message] 可直接给用户看；
 * - [Merge]：与上一个成功点过近而合并，坐标是**当前滤波估计**（不推进滤波状态）。
 *
 * [Merge.distanceM] 为与上一个成功点的距离（米），[Merge.message] 为展示文案。
 */
sealed class GateResult {
    data class Accept(val lat: Double, val lon: Double) : GateResult()

    data class Skip(val reason: String, val message: String) : GateResult()

    data class Merge(
        val lat: Double,
        val lon: Double,
        val mergedWithLast: Boolean,
        val distanceM: Double = 0.0,
        val message: String = "",
    ) : GateResult()
}

/**
 * GPS 双重安检 + 平滑门（纯 Kotlin，无 Android 依赖）。
 *
 * 处理顺序：
 * 1. 精度门：accuracy > [maxAccuracyM] → Skip；
 * 2. 飞点门：与上一个成功点的等效速度 > [maxSpeedMs] → Skip；
 * 3. 合并门：与上一个成功点距离 < [minDistanceM] → Merge（不推进滤波状态）；
 * 4. 通过：以**首个点**为原点的局部米制坐标做**匀速（CV）卡尔曼滤波**，返回滤波后的经纬度。
 *
 * 为什么用 CV 模型：早期版本只有位置状态（随机游走），滤波器"天生认为人不该动"，
 * 走路时每次只把估计值往测量值挪一小步，实测表现为**蓝点/打点明显落后于真实位置、
 * 慢慢追上来**。把人走路的速度建进状态后，匀速直线运动的稳态滞后接近 0，
 * 静止时仍然会平滑掉 GPS 抖动。
 *
 * 说明：距离用 [Geo.haversineMeters]，坐标转换用 [Geo.toLocal]/[Geo.fromLocal]。
 */
class TrackGate(
    private val maxAccuracyM: Double = 50.0,
    private val minDistanceM: Double = 1.0,
    private val maxSpeedMs: Double = 40.0,
    /** 加速度噪声谱密度（m²/s³）：越大越"跟手"，越小越平滑；步行取 1.0~2.0 较合适 */
    private val kalmanQ: Double = 1.6,
    /** 观测噪声系数：R = kalmanR × accuracy² */
    private val kalmanR: Double = 1.0,
) {
    private var lat0 = 0.0
    private var lon0 = 0.0
    private var hasOrigin = false

    // x / y 各一套匀速卡尔曼（状态：位置 + 速度）
    private val axisX = Axis()
    private val axisY = Axis()

    private var lastLat = 0.0
    private var lastLon = 0.0
    private var lastTs = 0L
    private var hasLast = false

    /**
     * 单轴匀速卡尔曼：状态 pos/vel，协方差 2×2（对称，存 p00/p01/p11）。
     */
    private class Axis {
        var pos = 0.0
        var vel = 0.0
        var p00 = 0.0
        var p01 = 0.0
        var p11 = 0.0

        fun init(variance: Double) {
            pos = 0.0
            vel = 0.0
            p00 = variance
            p01 = 0.0
            p11 = 4.0
        }

        fun predict(dt: Double, q: Double) {
            pos += vel * dt
            val n00 = p00 + dt * (2.0 * p01 + dt * p11) + q * dt * dt * dt / 3.0
            val n01 = p01 + dt * p11 + q * dt * dt / 2.0
            val n11 = p11 + q * dt
            p00 = n00
            p01 = n01
            p11 = n11
        }

        fun update(meas: Double, r: Double) {
            val s = p00 + r
            val innov = meas - pos
            // 新息超过 3σ：判定为真实的大幅移动（不是抖动），直接重捕获，避免长时间拖尾
            if (innov * innov > 9.0 * s) {
                pos = meas
                vel = 0.0
                p00 = r
                p01 = 0.0
                p11 = 4.0
                return
            }
            val k0 = p00 / s
            val k1 = p01 / s
            pos += k0 * innov
            vel += k1 * innov
            val n00 = p00 - k0 * p00
            val n01 = p01 - k0 * p01
            val n11 = p11 - k1 * p01
            p00 = n00
            p01 = n01
            p11 = n11
        }
    }

    fun reset() {
        lat0 = 0.0
        lon0 = 0.0
        hasOrigin = false
        axisX.init(0.0)
        axisY.init(0.0)
        lastLat = 0.0
        lastLon = 0.0
        lastTs = 0L
        hasLast = false
    }

    /** 当前滤波估计（经纬度）；尚未收到任何有效点时为 null */
    fun estimate(): Pair<Double, Double>? =
        if (hasOrigin) Geo.fromLocal(axisX.pos, axisY.pos, lat0, lon0) else null

    /** 处理一个原始定位；Accept 返回滤波后坐标，Skip/Merge 不推进滤波状态 */
    fun push(fix: Fix): GateResult {
        val acc = fix.accuracyM
        if (!acc.isFinite() || acc > maxAccuracyM) {
            return GateResult.Skip(
                "ACCURACY",
                "当前精度 ${"%.0f".format(acc)}m，已跳过",
            )
        }

        // 首个有效点：定原点、初始化滤波器
        if (!hasOrigin) {
            lat0 = fix.lat
            lon0 = fix.lon
            hasOrigin = true
            axisX.init(acc * acc)
            axisY.init(acc * acc)
            lastLat = fix.lat
            lastLon = fix.lon
            lastTs = fix.tsEpochMs
            hasLast = true
            return GateResult.Accept(fix.lat, fix.lon)
        }

        val p = Geo.toLocal(fix.lat, fix.lon, lat0, lon0)
        if (hasLast) {
            val dist = Geo.haversineMeters(lastLat, lastLon, fix.lat, fix.lon)
            val dtSec = ((fix.tsEpochMs - lastTs).coerceAtLeast(0L) / 1000.0)
            if (dtSec >= 0.0 && dist / dtSec.coerceAtLeast(1.0) > maxSpeedMs) {
                return GateResult.Skip("JUMP", "疑似飞点，已丢弃")
            }
            if (dist < minDistanceM) {
                val est = Geo.fromLocal(axisX.pos, axisY.pos, lat0, lon0)
                return GateResult.Merge(
                    lat = est.first,
                    lon = est.second,
                    mergedWithLast = true,
                    distanceM = dist,
                    message = "距上个点 ${"%.1f".format(dist)}m 小于 ${"%.0f".format(minDistanceM)}m，已合并",
                )
            }
        }

        val dt = if (hasLast) {
            ((fix.tsEpochMs - lastTs).coerceAtLeast(0L) / 1000.0).coerceIn(0.2, 5.0)
        } else {
            1.0
        }
        val r = (kalmanR * acc * acc).coerceAtLeast(1.0)
        axisX.predict(dt, kalmanQ)
        axisY.predict(dt, kalmanQ)
        axisX.update(p.x, r)
        axisY.update(p.y, r)

        lastLat = fix.lat
        lastLon = fix.lon
        lastTs = fix.tsEpochMs
        val est = Geo.fromLocal(axisX.pos, axisY.pos, lat0, lon0)
        return GateResult.Accept(est.first, est.second)
    }
}
