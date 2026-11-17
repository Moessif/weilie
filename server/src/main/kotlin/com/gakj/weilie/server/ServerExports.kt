package com.gakj.weilie.server

import kotlin.math.cos
import kotlin.math.sin

/** 服务端导出（v1.3）：KML / GeoJSON / CSV，供评审与跨设备复盘统一取数。 */
object ServerExports {

    /** 圆 → 64 边形，便于 GIS 直接渲染 */
    private fun circleRing(lat: Double, lon: Double, radiusM: Double, steps: Int = 64): List<Pair<Double, Double>> {
        val dLat = radiusM / 111_320.0
        val dLon = radiusM / (111_320.0 * cos(Math.toRadians(lat)).coerceAtLeast(1e-6))
        return (0 until steps).map { i ->
            val a = 2 * Math.PI * i / steps
            (lat + dLat * sin(a)) to (lon + dLon * cos(a))
        }
    }

    private fun zoneRing(z: WsMsg.Zone): List<Pair<Double, Double>> = when (z.kind.uppercase()) {
        "CIRCLE" -> {
            val la = z.centerLat
            val lo = z.centerLon
            val r = z.radiusM
            if (la != null && lo != null && r != null) circleRing(la, lo, r) else emptyList()
        }
        else -> z.polygon.mapNotNull { p -> if (p.size >= 2) p[0] to p[1] else null }
    }

    // ---------------- GeoJSON ----------------

    fun geojson(room: RoomState, samples: List<WsMsg.SamplePing>): String {
        val features = ArrayList<String>()
        room.zones.forEach { z ->
            val ring = zoneRing(z)
            if (ring.size >= 3) {
                val coords = (ring + ring.first()).joinToString(",") { "[${it.second},${it.first}]" }
                features += """
                {"type":"Feature","geometry":{"type":"Polygon","coordinates":[[$coords]]},
                 "properties":{"kind":"zone","id":"${esc(z.id)}","by":"${esc(z.by)}",
                 "assignees":"${esc(z.assignees.joinToString(","))}","note":"${esc(z.note)}",
                 "deleted":${z.deleted},"ts":${z.ts}}}
                """.trimIndent()
            }
        }
        room.markers.forEach { m ->
            features += """
            {"type":"Feature","geometry":{"type":"Point","coordinates":[${m.lon},${m.lat}]},
             "properties":{"kind":"marker","id":"${esc(m.id)}","by":"${esc(m.by)}",
             "name":"${esc(m.name)}","type":"${esc(m.kind)}","note":"${esc(m.note)}","ts":${m.ts}}}
            """.trimIndent()
        }
        if (samples.size >= 2) {
            val line = samples.sortedBy { it.ts }.joinToString(",") { "[${it.lon},${it.lat}]" }
            features += """
            {"type":"Feature","geometry":{"type":"LineString","coordinates":[$line]},
             "properties":{"kind":"track","points":${samples.size}}}
            """.trimIndent()
        }
        return """{"type":"FeatureCollection","name":"weilie-${esc(room.roomId)}","features":[${features.joinToString(",")}]}"""
    }

    // ---------------- KML ----------------

    fun kml(room: RoomState, samples: List<WsMsg.SamplePing>): String = buildString {
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine("""<kml xmlns="http://www.opengis.net/kml/2.2"><Document>""")
        appendLine("<name>围猎 ${xmlEsc(room.roomId)}</name>")
        room.zones.forEach { z ->
            val ring = zoneRing(z)
            if (ring.size < 3) return@forEach
            appendLine("<Placemark><name>${xmlEsc(z.note.ifBlank { "任务区" })}</name>")
            appendLine("<description>by=${xmlEsc(z.by)} assignees=${xmlEsc(z.assignees.joinToString(","))} deleted=${z.deleted}</description>")
            appendLine("<Polygon><outerBoundaryIs><LinearRing><coordinates>")
            (ring + ring.first()).forEach { appendLine("${it.second},${it.first},0") }
            appendLine("</coordinates></LinearRing></outerBoundaryIs></Polygon></Placemark>")
        }
        room.markers.forEach { m ->
            appendLine("<Placemark><name>${xmlEsc(m.name.ifBlank { m.kind })}</name>")
            appendLine("<description>by=${xmlEsc(m.by)} note=${xmlEsc(m.note)}</description>")
            appendLine("<Point><coordinates>${m.lon},${m.lat},0</coordinates></Point></Placemark>")
        }
        if (samples.size >= 2) {
            appendLine("<Placemark><name>轨迹</name><LineString><coordinates>")
            samples.sortedBy { it.ts }.forEach { appendLine("${it.lon},${it.lat},0") }
            appendLine("</coordinates></LineString></Placemark>")
        }
        appendLine("</Document></kml>")
    }

    // ---------------- CSV ----------------

    fun csv(samples: List<WsMsg.SamplePing>): String = buildString {
        appendLine("userId,ts,lat,lon,rsrp,onTarget")
        samples.sortedBy { it.ts }.forEach {
            appendLine("${csvSafe(it.userId)},${it.ts},${it.lat},${it.lon},${it.rsrp ?: ""},${it.onTarget}")
        }
    }

    private fun esc(s: String): String =
        s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", " ")
            .replace("\r", " ")
            .replace("\t", " ")

    /**
     * CSV 单元格（安全加固）：除引号包裹外，还要中和以 = + - @ 开头的公式，
     * 否则攻击者把 userId/note 设成 `=cmd|'/C calc'!A0` 就能让分析员在 Excel 里执行命令。
     */
    private fun csvSafe(raw: String): String {
        val s = if (raw.length > 500) raw.take(500) else raw
        val guarded = if (s.isNotEmpty() && s[0] in "=+-@\t\r") "'$s" else s
        return if (guarded.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + guarded.replace("\"", "\"\"") + "\""
        } else {
            guarded
        }
    }

    /** XML 文本转义（KML 安全加固）：esc 是 JSON 风格转义，直接放进 KML 可注入 XML 片段 */
    private fun xmlEsc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
        .replace("\r", " ")
        .replace("\n", " ")
}
