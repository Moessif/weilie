package com.gakj.weilie.core.model

import com.gakj.weilie.core.telephony.CellCodec
import kotlinx.serialization.Serializable

/** 无线接入制式 */
@Serializable
enum class Rat {
    GSM, WCDMA, LTE, NR, CDMA, TDSCDMA, UNKNOWN;

    val displayName: String
        get() = when (this) {
            GSM -> "GSM"; WCDMA -> "WCDMA"; LTE -> "LTE"; NR -> "NR(5G)"
            CDMA -> "CDMA"; TDSCDMA -> "TD-SCDMA"; UNKNOWN -> "未知"
        }
}

/**
 * 小区标识（服务小区或邻区）。
 * 统一口径：
 * - LTE：ci = ECI（28bit），lacOrTac = TAC
 * - NR：ci = NCI（36bit），lacOrTac = TAC
 * - GSM/WCDMA/TDSCDMA：ci = CID，lacOrTac = LAC
 * - CDMA：ci = BID，lacOrTac = NID/SID 组合不适用（另行记录）
 */
@Serializable
data class CellIdentity(
    val rat: Rat = Rat.UNKNOWN,
    val mcc: String? = null,
    val mnc: String? = null,
    val lacOrTac: Long? = null,
    val ci: Long? = null,
    val pci: Int? = null,
    val arfcn: Int? = null,
    val band: Int? = null,
    val isRegistered: Boolean = false,
) {
    /** LTE: eNodeB ID = ECI >> 8 */
    val enodebId: Long? get() = ci?.takeIf { rat == Rat.LTE }?.let { it ushr 8 }
    /** LTE: 小区 ID = ECI & 0xFF */
    val lteCellId: Long? get() = ci?.takeIf { rat == Rat.LTE }?.let { it and 0xFF }
    /** NR: 常用 8bit 小区段拆分约定：gNB ID = NCI >> 8 */
    val gnbId: Long? get() = ci?.takeIf { rat == Rat.NR }?.let { it ushr 8 }

    val ciHex: String? get() = ci?.let { "0x%08X".format(it) }

    /**
     * 一行式人类可读标识（编号部分用用户习惯写法：十进制原值 + 括号内 gNB/eNB-CID + 十六进制），如：
     * - NR：`NR(5G)·460-01·TAC 12345·NCI 5677244420(gNB 1386046-4) (0x15263E004)·PCI 231`
     * - LTE：`LTE·460-00·TAC 12345·ECI 123456789(eNB 482253-21) (0x75BCD15)·PCI 231`
     */
    fun display(): String = buildString {
        append(rat.displayName)
        if (mcc != null) append("·").append(mcc).append("-").append(mnc ?: "??")
        lacOrTac?.let { append("·").append(if (rat == Rat.GSM || rat == Rat.WCDMA) "LAC " else "TAC ").append(it) }
        ci?.let {
            append("·").append(rat.ciLabel()).append(" ").append(CellCodec.formatCellId(rat, it))
            append(" (").append(CellCodec.hexOf(it)).append(")")
        }
        pci?.let { append("·PCI ").append(it) }
        arfcn?.let { append("·").append(rat.arfcnLabel()).append(" ").append(it) }
        band?.let { append("·B").append(it) }
    }
}

fun Rat.ciLabel(): String = when (this) {
    Rat.LTE -> "ECI"; Rat.NR -> "NCI"; else -> "CI"
}

fun Rat.arfcnLabel(): String = when (this) {
    Rat.LTE -> "EARFCN"; Rat.NR -> "NR-ARFCN"; Rat.WCDMA -> "UARFCN"; Rat.GSM -> "ARFCN"; else -> "ARFCN"
}

/** 信号测量值（dBm/dB 单位的可选项集合） */
@Serializable
data class SignalData(
    val rsrp: Int? = null,
    val rsrq: Int? = null,
    val sinr: Int? = null,
    val rssi: Int? = null,
    val dbm: Int? = null,
)

@Serializable
data class WifiAp(
    val ssid: String? = null,
    val bssid: String? = null,
    val rssi: Int? = null,
    val channel: Int? = null,
    /** 厂商名（按 BSSID 的 OUI 离线查表；未收录/随机 MAC 为 null） */
    val vendor: String? = null,
)

/** 一次定位结果 */
@Serializable
data class LocationFix(
    val lat: Double,
    val lon: Double,
    val accuracyM: Float? = null,
    val altitudeM: Double? = null,
    val speedMs: Float? = null,
    val bearingDeg: Float? = null,
    val provider: String? = null,
    val tsEpochMs: Long,
)

/**
 * 一个采样点：一次打点 = 定位 + 当时的小区/信号上下文（扁平化，直接可导出 CSV）。
 * append-only：写入后永不修改。
 */
@Serializable
data class SampleRecord(
    val tsEpochMs: Long,
    val lat: Double,
    val lon: Double,
    val accuracyM: Float? = null,
    val provider: String? = null,
    val servingRat: Rat? = null,
    val servingMcc: String? = null,
    val servingMnc: String? = null,
    val servingLacTac: Long? = null,
    val servingCi: Long? = null,
    val servingPci: Int? = null,
    val servingEarfcn: Int? = null,
    val servingBand: Int? = null,
    val rsrp: Int? = null,
    val rsrq: Int? = null,
    val sinr: Int? = null,
    val rssi: Int? = null,
    val neighborCount: Int = 0,
    val neighborCis: List<String> = emptyList(),
    val wifiCount: Int = 0,
    /** 采样时手机是否驻留在目标小区（目标小区=服务小区） */
    val onTarget: Boolean = false,
    /** 该点邻区/服务小区中是否包含目标小区测量值 */
    val targetSeen: Boolean = false,
    val targetRsrp: Int? = null,
)

/** 上级给的目标输入格式 */
enum class TargetFormat { ECI, NCI, LAC_CI, LATLON_RADIUS }

/** 解析出的目标小区/区域 */
@Serializable
data class TargetCell(
    val format: TargetFormat,
    val rawInput: String,
    val ratHint: Rat = Rat.LTE,
    val eci: Long? = null,
    val nci: Long? = null,
    val lac: Long? = null,
    val cid: Long? = null,
    val mcc: String? = null,
    val mnc: String? = null,
    val centerLat: Double? = null,
    val centerLon: Double? = null,
    val givenRadiusM: Double? = null,
    val warnings: List<String> = emptyList(),
) {
    /** 目标小区的 ci 统一口（ECI/NCI/CID） */
    fun unifiedCi(): Long? = when (format) {
        TargetFormat.ECI -> eci
        TargetFormat.NCI -> nci
        TargetFormat.LAC_CI -> cid
        TargetFormat.LATLON_RADIUS -> null
    }
}

/** 覆盖实测任务 */
@Serializable
data class CoverageTask(
    val id: String,
    val title: String,
    val target: TargetCell,
    val createdAtEpochMs: Long,
    var sampleCount: Int = 0,
    var finishedAtEpochMs: Long? = null,
)

/** 协同角色（与后端协议一致） */
enum class TeamRole { CAPTAIN, MEMBER, SPECTATOR }

@Serializable
data class TeamMemberView(
    val userId: String,
    val username: String,
    val displayName: String,
    val role: String = "MEMBER",
)
