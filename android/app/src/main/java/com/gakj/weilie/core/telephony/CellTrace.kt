package com.gakj.weilie.core.telephony

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 小区变化轨迹的一行展示文案：`HH:mm:ss  旧编号 → 新编号`。
 *
 * 「旧编号/新编号」用 [CellCodec.cellNumberFromDisplay] 从采样事件的白话文本里提取
 * （事件文本形如 `NR(5G)·460-01·…·NCI 5677244420(gNB 1386046-4) …`），提取不到显示「—」。
 * 纯函数，时间格式与 ZoneId 可注入，便于单测。
 */
object CellTrace {

    private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun line(
        tsEpochMs: Long,
        fromText: String?,
        toText: String?,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val time = TIME_FMT.format(Instant.ofEpochMilli(tsEpochMs).atZone(zone))
        val from = CellCodec.cellNumberFromDisplay(fromText) ?: "—"
        val to = CellCodec.cellNumberFromDisplay(toText) ?: "—"
        return "$time  $from → $to"
    }
}
