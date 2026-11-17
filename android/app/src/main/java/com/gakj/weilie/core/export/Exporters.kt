package com.gakj.weilie.core.export

import com.gakj.weilie.core.model.SampleRecord
import com.gakj.weilie.core.model.TargetCell
import kotlin.math.cos
import kotlin.math.PI
import kotlin.math.sin

/** 圆的经纬度近似多边形（给定参考圆/任务范围圈用） */
fun circleLatLon(lat: Double, lon: Double, radiusM: Double, segments: Int = 72): List<Pair<Double, Double>> {
    if (radiusM <= 0.0) return emptyList()
    val dLat = radiusM / 110540.0
    val dLon = radiusM / (111320.0 * cos(Math.toRadians(lat)))
    return (0 until segments).map { i ->
        val a = 2 * PI * i / segments
        (lat + dLat * sin(a)) to (lon + dLon * cos(a))
    }
}

/** ISO 时间戳（无三方依赖的紧凑实现） */
fun isoTs(epochMs: Long): String {
    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
    sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
    return sdf.format(java.util.Date(epochMs))
}

/** CSV 导出：列序可自定义；默认列与研究报告/试验数据计划对齐 */
object CsvExporter {

    data class Column(val key: String, val header: String, val get: (SampleRecord) -> String)

    val DEFAULT_COLUMNS: List<Column> = listOf(
        Column("ts", "time_utc", { isoTs(it.tsEpochMs) }),
        Column("ts_ms", "ts_epoch_ms", { it.tsEpochMs.toString() }),
        Column("lat", "lat", { "%.7f".format(it.lat) }),
        Column("lon", "lon", { "%.7f".format(it.lon) }),
        Column("acc", "accuracy_m", { it.accuracyM?.let { a -> "%.1f".format(a) } ?: "" }),
        Column("provider", "provider", { it.provider ?: "" }),
        Column("rat", "rat", { it.servingRat?.name ?: "" }),
        Column("mcc", "mcc", { it.servingMcc ?: "" }),
        Column("mnc", "mnc", { it.servingMnc ?: "" }),
        Column("lactac", "lac_or_tac", { it.servingLacTac?.toString() ?: "" }),
        Column("ci", "ci_eci_nci", { it.servingCi?.toString() ?: "" }),
        Column("ci_hex", "ci_hex", { it.servingCi?.let { c -> "0x%08X".format(c) } ?: "" }),
        Column("pci", "pci", { it.servingPci?.toString() ?: "" }),
        Column("earfcn", "earfcn", { it.servingEarfcn?.toString() ?: "" }),
        Column("rsrp", "rsrp_dbm", { it.rsrp?.toString() ?: "" }),
        Column("rsrq", "rsrq_db", { it.rsrq?.toString() ?: "" }),
        Column("sinr", "sinr_db", { it.sinr?.toString() ?: "" }),
        Column("rssi", "rssi_dbm", { it.rssi?.toString() ?: "" }),
        Column("nb", "neighbor_count", { it.neighborCount.toString() }),
        Column("on_target", "on_target", { it.onTarget.toString() }),
        Column("target_seen", "target_seen", { it.targetSeen.toString() }),
        Column("target_rsrp", "target_rsrp_dbm", { it.targetRsrp?.toString() ?: "" }),
    )

    fun export(samples: List<SampleRecord>, columns: List<Column> = DEFAULT_COLUMNS): String =
        buildString {
            appendLine(columns.joinToString(",") { csvEscape(it.header) })
            for (s in samples) {
                appendLine(columns.joinToString(",") { csvEscape(it.get(s)) })
            }
        }

    private fun csvEscape(v: String): String =
        if (v.contains(',') || v.contains('"') || v.contains('\n')) "\"${v.replace("\"", "\"\"")}\"" else v
}

/** GeoJSON 导出：采样点 + 拟合边界 + 阈值等值线 + 参考圈，GIS（QGIS/ArcGIS）直接可用 */
object GeoJsonExporter {

    fun export(
        samples: List<SampleRecord>,
        target: TargetCell?,
        hullLatLon: List<Pair<Double, Double>>?,
        bufferedLatLon: List<Pair<Double, Double>>?,
        isolines: List<List<Pair<Double, Double>>>,
        // v0.13.3：内洞与面积，导出与界面同口径
        holesLatLon: List<List<Pair<Double, Double>>> = emptyList(),
        coverageAreaM2: Double? = null,
        hullAreaM2: Double? = null,
        engine: String? = null,
    ): String {
        val feats = StringBuilder()
        for (s in samples) {
            feats.appendFeature(
                """{"type":"Point","coordinates":[${"%.7f".format(s.lon)},${"%.7f".format(s.lat)}]}""",
                buildString {
                    append("{\"time\":\"").append(isoTs(s.tsEpochMs)).append("\",\"rsrp\":")
                    append(s.rsrp ?: "null")
                    append(",\"on_target\":").append(s.onTarget)
                    append("}")
                },
            )
        }
        hullLatLon?.let { ring ->
            val props = buildString {
                append("{\"kind\":\"fitted_hull\"")
                append(""","holes":""").append(holesLatLon.size)
                if (coverageAreaM2 != null) append(""","area_m2":""").append("%.0f".format(coverageAreaM2))
                if (hullAreaM2 != null) append(""","hull_area_m2":""").append("%.0f".format(hullAreaM2))
                if (!engine.isNullOrBlank()) append(""","engine":""").append('"').append(engine).append('"')
                append("}")
            }
            feats.appendFeature(polygonWithHoles(ring, holesLatLon), props)
        }
        bufferedLatLon?.let { feats.appendFeature(ringToPolygon(it), """{"kind":"fitted_buffered"}""") }
        isolines.forEachIndexed { i, line ->
            if (line.size >= 2) {
                val coords = line.joinToString(",") { (lat, lon) -> "[${"%.7f".format(lon)},${"%.7f".format(lat)}]" }
                feats.appendFeature("""{"type":"LineString","coordinates":[$coords]}""", """{"kind":"threshold_isoline","idx":$i}""")
            }
        }
        target?.let { t ->
            if (t.centerLat != null && t.centerLon != null) {
                val ring = circleLatLon(t.centerLat, t.centerLon, t.givenRadiusM ?: 1000.0)
                feats.appendFeature(ringToPolygon(ring), """{"kind":"given_radius_reference"}""")
            }
        }
        return """{"type":"FeatureCollection","features":[${feats}]}"""
    }

    /** 多环 Polygon：第一个环是外环，其余是洞（RFC 7946 要求逆时针外环、顺时针洞，这里只保证闭合） */
    private fun polygonWithHoles(
        outer: List<Pair<Double, Double>>,
        holes: List<List<Pair<Double, Double>>>,
    ): String {
        if (outer.size < 3) return """{"type":"Polygon","coordinates":[]}"""
        val rings = ArrayList<String>(1 + holes.size)
        rings.add(closedRingJson(outer))
        holes.forEach { h -> if (h.size >= 3) rings.add(closedRingJson(h)) }
        return """{"type":"Polygon","coordinates":[${rings.joinToString(",")}]}"""
    }

    private fun closedRingJson(ring: List<Pair<Double, Double>>): String {
        val closed = ring.first() != ring.last()
        val pts = ring + if (closed) listOf(ring.first()) else emptyList()
        return "[" + pts.joinToString(",") { (lat, lon) -> "[${"%.7f".format(lon)},${"%.7f".format(lat)}]" } + "]"
    }

    private fun ringToPolygon(ring: List<Pair<Double, Double>>): String {
        if (ring.size < 3) return """{"type":"Polygon","coordinates":[]}"""
        val closed = ring.first() != ring.last()
        val pts = (ring + if (closed) listOf(ring.first()) else emptyList())
        val coords = pts.joinToString(",") { (lat, lon) -> "[${"%.7f".format(lon)},${"%.7f".format(lat)}]" }
        return """{"type":"Polygon","coordinates":[[$coords]]}"""
    }

    private fun StringBuilder.appendFeature(geom: String, props: String) {
        // 多个要素之间必须有逗号，否则输出不是合法 JSON（v0.13.3 修复）
        if (isNotEmpty()) append(',')
        append("""{"type":"Feature","geometry":""")
        append(geom)
        append(""","properties":""")
        append(props)
        append("}")
    }
}

/** KML 导出：Google Earth 查看，向「传统」警务 GIS 兼容 */
object KmlExporter {

    fun export(
        title: String,
        samples: List<SampleRecord>,
        hullLatLon: List<Pair<Double, Double>>?,
        isolines: List<List<Pair<Double, Double>>>,
        // v0.13.3：内洞与面积
        holesLatLon: List<List<Pair<Double, Double>>> = emptyList(),
        coverageAreaM2: Double? = null,
        hullAreaM2: Double? = null,
        engine: String? = null,
    ): String = buildString {
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine("""<kml xmlns="http://www.opengis.net/kml/2.2"><Document><name>${title.xmlEscape()}</name>""")
        // 轨迹
        if (samples.size >= 2) {
            val coords = samples.joinToString(" ") { "${"%.7f".format(it.lon)},${"%.7f".format(it.lat)},0" }
            appendLine("""<Placemark><name>轨迹</name><Style><LineStyle><color>ff00e5ff</color><width>3</width></LineStyle></Style>""")
            appendLine("""<LineString><tessellate>1</tessellate><coordinates>$coords</coordinates></LineString></Placemark>""")
        }
        // 采样点（限流：最多 2000 个，避免 Google Earth 卡顿）
        for (s in samples.take(2000)) {
            appendLine(
                """<Placemark><name>${s.rsrp ?: "?"} dBm</name><description>${isoTs(s.tsEpochMs)}</description>""" +
                    """<Point><coordinates>${"%.7f".format(s.lon)},${"%.7f".format(s.lat)},0</coordinates></Point></Placemark>""",
            )
        }
        hullLatLon?.let { ring ->
            val coords = ring.joinToString(" ") { (lat, lon) -> "${"%.7f".format(lon)},${"%.7f".format(lat)},0" }
            val desc = buildString {
                coverageAreaM2?.let { append("覆盖面积 %.0f m²；".format(it)) }
                hullAreaM2?.let { append("外环面积 %.0f m²；".format(it)) }
                append("内洞 ${holesLatLon.size} 个；")
                if (!engine.isNullOrBlank()) append("引擎 $engine")
            }.trimEnd('；')
            appendLine("""<Placemark><name>拟合边界</name><description>$desc</description><Style><PolyStyle><color>7f00b050</color></PolyStyle></Style>""")
            append("""<Polygon><outerBoundaryIs><LinearRing><coordinates>$coords</coordinates></LinearRing></outerBoundaryIs>""")
            holesLatLon.forEach { hole ->
                if (hole.size >= 3) {
                    val hc = hole.joinToString(" ") { (lat, lon) -> "${"%.7f".format(lon)},${"%.7f".format(lat)},0" }
                    append("""<innerBoundaryIs><LinearRing><coordinates>$hc</coordinates></LinearRing></innerBoundaryIs>""")
                }
            }
            appendLine("</Polygon></Placemark>")
        }
        isolines.forEachIndexed { i, line ->
            if (line.size >= 2) {
                val coords = line.joinToString(" ") { (lat, lon) -> "${"%.7f".format(lon)},${"%.7f".format(lat)},0" }
                appendLine(
                    """<Placemark><name>阈值等值线 #$i</name><Style><LineStyle><color>ff0066ff</color><width>2</width></LineStyle></Style>""" +
                        """<LineString><coordinates>$coords</coordinates></LineString></Placemark>""",
                )
            }
        }
        appendLine("</Document></kml>")
    }

    private fun String.xmlEscape(): String =
        replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
