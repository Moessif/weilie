package com.gakj.weilie.core.cellmap

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

/**
 * 基站库（OpenCelliD 众包）里的一条小区记录。
 *
 * 注意：这是「参考位置」，不是运营商台账。
 * - 众包坐标多在百米量级，`rangeM` 是上报者估的覆盖半径，不是精确站距；
 * - 中国区数据稀疏（城区每平方公里几十条，5G NR 极少），查不到属于正常情况；
 * - 数据许可 CC BY-SA 4.0，界面与导出图必须保留署名（见 [SOURCE_ATTRIBUTION]）。
 */
@Serializable
data class CellSite(
    val mcc: Int = 460,
    val mnc: Int = 0,
    val lac: Int = 0,
    val cellid: Long = 0,
    val rat: String = "",
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val rangeM: Int = 0,
    val samples: Int = 0,
    val updatedSec: Long = 0,
) {
    /** 唯一键：MCC-MNC-LAC-CID，用于选中态与去重 */
    val key: String get() = "$mcc-$mnc-$lac-$cellid"

    val operatorName: String
        get() = when {
            mcc == 460 -> when (mnc) {
                0, 2, 7, 8 -> "中国移动"
                1, 6, 9 -> "中国联通"
                3, 11 -> "中国电信"
                15 -> "中国广电"
                20 -> "中国铁路"
                else -> "中国 · MNC $mnc"
            }
            else -> "MCC $mcc / MNC $mnc"
        }

    val ratLabel: String
        get() = when (rat.uppercase()) {
            "LTE" -> "4G LTE"
            "NR", "5GNR" -> "5G NR"
            "GSM" -> "2G GSM"
            "UMTS", "WCDMA" -> "3G WCDMA"
            "CDMA" -> "3G CDMA"
            "TDSCDMA" -> "3G TD-SCDMA"
            "" -> "制式未知"
            else -> rat.uppercase()
        }

    /** 列表主标题，如「中国移动 · 4G LTE」 */
    val title: String get() = "$operatorName · $ratLabel"

    /** 编号文本，如「460-0 · LAC 4213 · CID 18712839」 */
    val cellText: String get() = "$mcc-$mnc · LAC/TAC $lac · CID $cellid"

    /** CID 的十六进制写法，便于与 App 内小区编号对照 */
    val hexCi: String get() = "0x" + cellid.toString(16).uppercase()

    /** 精度说明：众包数据只能给量级，避免用户误当测量值 */
    val accuracyText: String
        get() = when {
            samples >= 10 -> "众包坐标，$samples 次上报，约百米量级"
            samples > 0 -> "众包坐标，仅 $samples 次上报，精度有限"
            else -> "众包坐标，精度未知"
        }

    val rangeText: String get() = if (rangeM > 0) "上报覆盖半径 ${rangeM} m" else "覆盖半径未知"

    val coordinateText: String get() = String.format(java.util.Locale.US, "%.5f, %.5f", lat, lon)

    fun distanceM(lat0: Double, lon0: Double): Double =
        CellGeometry.distanceM(lat0, lon0, lat, lon)

    fun isValid(): Boolean =
        lat.isFinite() && lon.isFinite() && lat in -85.0..85.0 && lon in -180.0..180.0 &&
            !(abs(lat) < 1e-9 && abs(lon) < 1e-9)

    companion object {
        /** CC BY-SA 4.0 要求展示的署名串（界面、导出图、文档统一用它） */
        const val SOURCE_ATTRIBUTION = "OpenCelliD · CC BY-SA 4.0 · https://opencellid.org"
    }
}

@Serializable
data class CellNearResponse(
    val ok: Boolean = false,
    val count: Int = 0,
    val sites: List<CellSite> = emptyList(),
    val fetched: Int = 0,
    val message: String? = null,
)

@Serializable
data class CellQueryResponse(
    val ok: Boolean = false,
    val site: CellSite? = null,
    val message: String? = null,
)

/** 球面/平面小范围几何工具：与服务端、SectorZones 共用同一套换算 */
object CellGeometry {
    const val METERS_PER_DEG_LAT = 111_320.0

    fun metersPerDegLon(lat: Double): Double =
        (METERS_PER_DEG_LAT * cos(Math.toRadians(lat))).coerceAtLeast(1.0)

    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        if (!lat1.isFinite() || !lon1.isFinite() || !lat2.isFinite() || !lon2.isFinite()) return Double.NaN
        val dy = (lat2 - lat1) * METERS_PER_DEG_LAT
        val dx = (lon2 - lon1) * metersPerDegLon((lat1 + lat2) / 2.0)
        return hypot(dx, dy)
    }

    /** 从 (lat,lon) 沿方位角 bearingDeg 走 distM 米后的坐标（小范围平面近似，误差 <0.1 m/km） */
    fun destination(lat: Double, lon: Double, distM: Double, bearingDeg: Double): Pair<Double, Double> {
        val b = Math.toRadians(bearingDeg.toDouble())
        val dNorth = distM * cos(b)
        val dEast = distM * Math.sin(b)
        val outLat = lat + dNorth / METERS_PER_DEG_LAT
        val outLon = lon + dEast / metersPerDegLon(lat)
        return outLat to outLon
    }
}
