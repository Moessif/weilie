package com.gakj.weilie.ui.map

import androidx.compose.ui.graphics.Color
import com.gakj.weilie.core.cellmap.CellSite as KnownCell

/**
 * 基站库图层的配色与标签：百度渲染层、Canvas 兜底层、查询弹窗三处共用一套，
 * 避免「地图上是青色、列表里是紫色」这种对不上的情况。
 */
fun cellSiteColor(rat: String): Color = when (rat.uppercase()) {
    "LTE" -> Color(0xFF22D3EE)          // 4G：青
    "NR", "5GNR" -> Color(0xFFA855F7)   // 5G：紫
    "GSM" -> Color(0xFFFB923C)          // 2G：橙
    "UMTS", "WCDMA" -> Color(0xFF34D399) // 3G：绿
    else -> Color(0xFF94A3B8)           // 未知：灰
}

/** 地图上的极短标签：用制式而不是运营商，远看也能分辨 */
fun cellSiteShort(site: KnownCell): String = when (site.rat.uppercase()) {
    "LTE" -> "4G"
    "NR", "5GNR" -> "5G"
    "GSM" -> "2G"
    "UMTS", "WCDMA" -> "3G"
    else -> "?"
}
