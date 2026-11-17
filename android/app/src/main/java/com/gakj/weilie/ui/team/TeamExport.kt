package com.gakj.weilie.ui.team

import android.content.Context
import com.gakj.weilie.core.export.CsvExporter
import com.gakj.weilie.core.export.GeoJsonExporter
import com.gakj.weilie.core.export.KmlExporter
import com.gakj.weilie.core.fitting.CoverageResult
import com.gakj.weilie.core.model.SampleRecord
import com.gakj.weilie.core.model.TargetCell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 团队模式导出（v0.17）：
 *  - 本人导出：用本地样本 + 本人覆盖拟合，复用单人的 KML/GeoJSON/CSV 生成器；
 *  - 全队导出：调用服务端 `GET /api/teams/{roomId}/export?code=&format=`（v1.3 已提供），
 *    拿回文件后交给系统分享。
 */
object TeamExport {

    private fun stamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    private fun dir(ctx: Context): File = File(ctx.cacheDir, "team-export").apply { mkdirs() }

    fun personalKml(
        ctx: Context,
        title: String,
        samples: List<SampleRecord>,
        fit: CoverageResult?,
    ): File {
        val text = KmlExporter.export(
            title = title,
            samples = samples,
            hullLatLon = fit?.hullLatLon,
            isolines = fit?.isolineLoopsLatLon ?: emptyList(),
            holesLatLon = fit?.holesLatLon ?: emptyList(),
            coverageAreaM2 = fit?.coverageAreaM2,
            hullAreaM2 = fit?.hullAreaM2,
            engine = fit?.engine?.name,
        )
        return File(dir(ctx), "team-self-$title-${stamp()}.kml").apply { writeText(text) }
    }

    fun personalGeoJson(
        ctx: Context,
        title: String,
        samples: List<SampleRecord>,
        target: TargetCell?,
        fit: CoverageResult?,
    ): File {
        val text = GeoJsonExporter.export(
            samples = samples,
            target = target,
            hullLatLon = fit?.hullLatLon,
            bufferedLatLon = fit?.bufferedLatLon,
            isolines = fit?.isolineLoopsLatLon ?: emptyList(),
            holesLatLon = fit?.holesLatLon ?: emptyList(),
            coverageAreaM2 = fit?.coverageAreaM2,
            hullAreaM2 = fit?.hullAreaM2,
            engine = fit?.engine?.name,
        )
        return File(dir(ctx), "team-self-$title-${stamp()}.geojson").apply { writeText(text) }
    }

    fun personalCsv(ctx: Context, title: String, samples: List<SampleRecord>): File {
        val text = CsvExporter.export(samples)
        return File(dir(ctx), "team-self-$title-${stamp()}.csv").apply { writeText(text) }
    }

    /** 全队导出：服务端需要队伍码；失败时抛异常，由调用方 toast */
    suspend fun serverExport(
        ctx: Context,
        httpBase: String,
        roomId: String,
        joinCode: String,
        format: String,
    ): File = withContext(Dispatchers.IO) {
        val base = httpBase.trim().trimEnd('/')
        require(base.isNotBlank()) { "服务器地址为空" }
        require(joinCode.isNotBlank()) { "缺少队伍码，无法导出全队数据" }
        val url = URL("$base/api/teams/$roomId/export?code=$joinCode&format=$format")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 15000
            requestMethod = "GET"
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                val msg = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                error("导出失败：HTTP $code ${msg.take(80)}")
            }
            val bytes = conn.inputStream.use { it.readBytes() }
            val ext = when (format) {
                "kml" -> "kml"
                "csv" -> "csv"
                else -> "geojson"
            }
            val mime = when (ext) {
                "kml" -> "application/vnd.google-earth.kml+xml"
                "csv" -> "text/csv"
                else -> "application/geo+json"
            }
            File(dir(ctx), "team-all-$roomId-${stamp()}.$ext").apply { writeBytes(bytes) }
        } finally {
            conn.disconnect()
        }
        // mime 由调用方按扩展名决定
    }
}
