package com.gakj.weilie.core.telephony

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一张 SIM 卡当前驻留小区的「可展示」快照。
 *
 * 为什么单独抽出来：真机双卡（移动 + 联通）时，用户要看的是「每张卡各自驻留在哪个小区、
 * 什么制式、什么频段、信号多少」，而不是一个笼统的 registered 小区。CellularZ 的水印与
 * 数据栏都是按「一行一张卡」来排的，这里就按那个口径建模。
 *
 * 字段口径：
 * - [rat]：NR / LTE / WCDMA / GSM / CDMA 之一（大写）
 * - [band]：频段文本，如 "N41"（5G）、"B3"（4G）
 * - [arfcn]：5G = NR-ARFCN，4G = EARFCN；未知填 -1
 * - [cellId]：5G = NCI（36bit 原始值），4G = ECI（28bit 原始值）；未知填 -1
 * - [rsrp] / [sinr]：dBm / dB；未知填 [UNKNOWN]
 */
data class SimCell(
    val slot: Int,
    val carrier: String,
    val rat: String,
    val band: String,
    val arfcn: Int,
    val pci: Int,
    val cellId: Long,
    val rsrp: Int,
    val sinr: Int,
    val mccMnc: String = "",
) {
    companion object {
        const val UNKNOWN = Int.MIN_VALUE
    }

    /** 卡槽展示名：0 -> SIM 1 */
    val slotLabel: String get() = "SIM ${slot + 1}"

    /** 制式 + 频段短标签，如 "NR N41" */
    val ratBand: String get() = listOf(rat, band).filter { it.isNotBlank() && it != "-" }.joinToString(" ")

    /** 编号的习惯写法：5G 给 gNB-CID，4G 给 eNB-CID；未知给 "--" */
    val cellIdText: String
        get() = when {
            cellId <= 0L -> "--"
            rat.equals("NR", true) -> {
                val (g, c) = CellCodec.decodeNci(cellId)
                "$g-$c"
            }
            else -> {
                val (e, c) = CellCodec.decodeEci(cellId)
                "$e-$c"
            }
        }

    val rsrpText: String get() = if (rsrp == UNKNOWN) "--" else "$rsrp"

    val sinrText: String get() = if (sinr == UNKNOWN) "--" else (sinr / 1.0).let {
        if (sinr % 1 == 0) "${sinr}" else String.format(Locale.US, "%.1f", it)
    }
}

/**
 * 相机水印 / 数据栏共用的文案拼装。
 *
 * 参照 CellularZ 的水印排布：左边一列一张卡（制式/频段/ARFCN/PCI/编号/RSRP/SINR），
 * 右下角放坐标与时间。这里产出的是「逐行文本」，由调用方决定是烧进图片还是画在 Compose 上。
 */
object CellWatermark {

    /** 每张卡一列；两卡时并排，单卡时单列 */
    fun columns(sims: List<SimCell>): List<List<String>> = sims.map { s ->
        listOf(
            "${s.carrier} ${s.ratBand}".trim(),
            "${if (s.rat.equals("NR", true)) "NRARFCN" else "EARFCN"}:${if (s.arfcn > 0) s.arfcn else "--"}",
            "${if (s.rat.equals("NR", true)) "NR-PCI" else "PCI"}:${if (s.pci >= 0) s.pci else "--"}",
            "${if (s.rat.equals("NR", true)) "NCI" else "ECI"}:${s.cellIdText}",
            "${if (s.rat.equals("NR", true)) "SS-RSRP" else "RSRP"}:${s.rsrpText}",
            "${if (s.rat.equals("NR", true)) "SS-SINR" else "SINR"}:${s.sinrText}",
        )
    }

    /**
     * 坐标 + 时间那几行。
     *
     * [hasFix] 为 false 时不打印坐标——此时调用方传进来的经纬度只是「地图中心」之类的占位值，
     * 烧进取证照片会变成伪坐标，因此只保留一行「定位中…」提示。
     */
    fun footer(
        lat: Double,
        lon: Double,
        tsEpochMs: Long,
        place: String = "",
        hasFix: Boolean = true,
    ): List<String> {
        val f = SimpleDateFormat("yyyy.MM.dd HH:mm:ss", Locale.CHINA)
        val loc = if (hasFix) {
            listOf(
                String.format(Locale.US, "LAT:%.6f", lat),
                String.format(Locale.US, "LON:%.6f", lon),
            )
        } else {
            listOf("GPS 定位中…")
        }
        return listOf(place.ifBlank { "位置" }) + loc + f.format(Date(tsEpochMs))
    }

    /** 展平成「左列 右列」两栏文本行（供烧图时逐行绘制） */
    fun flatLines(
        sims: List<SimCell>,
        lat: Double,
        lon: Double,
        tsEpochMs: Long,
        place: String = "",
        hasFix: Boolean = true,
    ): List<String> {
        val cols = columns(sims)
        val rows = mutableListOf<String>()
        val maxRow = cols.maxOfOrNull { it.size } ?: 0
        if (cols.isEmpty()) {
            rows += "无蜂窝数据（未授权定位/电话状态，或飞行模式）"
        } else {
            for (i in 0 until maxRow) {
                val left = cols.getOrNull(0)?.getOrNull(i) ?: ""
                val right = cols.getOrNull(1)?.getOrNull(i) ?: ""
                rows += if (right.isBlank()) left else "$left    $right"
            }
        }
        rows += footer(lat, lon, tsEpochMs, place, hasFix)
        return rows
    }
}

/** ARFCN / EARFCN -> 频段文本（国内常见频段即可，未知返回 "-"） */
object BandTable {

    fun lteBand(earfcn: Int): String = when (earfcn) {
        in 0..599 -> "B1"
        in 600..1199 -> "B2"
        in 1200..1949 -> "B3"
        in 1950..2399 -> "B4"
        in 2400..2649 -> "B5"
        in 2750..3449 -> "B7"
        in 3450..3799 -> "B8"
        in 36200..36349 -> "B34"
        in 37750..38249 -> "B38"
        in 38250..38649 -> "B39"
        in 38650..39649 -> "B40"
        in 39650..41589 -> "B41"
        else -> "-"
    }

    fun nrBand(arfcn: Int): String = when (arfcn) {
        in 422000..434000 -> "N1"
        in 361000..376000 -> "N3"
        in 173800..178800 -> "N5"
        in 185000..192000 -> "N8"
        in 151600..160600 -> "N28"
        in 499200..537999 -> "N41"
        in 620000..653333 -> "N78"
        in 653334..680000 -> "N77"
        in 693334..733333 -> "N79"
        else -> "-"
    }
}
