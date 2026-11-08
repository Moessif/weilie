package com.gakj.weilie.core.telephony

/**
 * 信号等级（以 RSRP 为准的展示用经验门限）。
 *
 * 5G 的 SS-RSRP 与 4G 的 RSRP 量纲接近，共用一套门限，仅用于 UI 展示，不参与判定：
 * - ≥ -85 dBm：极佳
 * - ≥ -95 dBm：良好
 * - ≥ -105 dBm：一般
 * - ≥ -115 dBm：较弱
 * - 其余：很弱
 * - 无测量值：—
 */
object SignalLevel {

    fun grade(rsrp: Int?): String = when {
        rsrp == null -> "—"
        rsrp >= -85 -> "极佳"
        rsrp >= -95 -> "良好"
        rsrp >= -105 -> "一般"
        rsrp >= -115 -> "较弱"
        else -> "很弱"
    }
}
