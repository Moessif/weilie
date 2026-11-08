package com.gakj.weilie.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import com.gakj.weilie.core.model.SampleRecord
import com.gakj.weilie.ui.common.fmtTs
import com.gakj.weilie.ui.common.shareFile
import com.gakj.weilie.ui.common.toast
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

/**
 * 轨迹图导出：把样本轨迹渲染成独立 PNG（深色底 + 网格 + 方向箭头 + 起终点 + 水印），
 * 不依赖屏幕截图，随时可导出/分享。
 */

/** 计算轨迹外接框，渲染 PNG 位图；样本不足（<2）返回 null */
fun renderTrackBitmap(samples: List<SampleRecord>, title: String): Bitmap? {
    if (samples.size < 2) return null
    val w = 1080
    val h = 1080
    val pad = 96f

    var minLat = Double.MAX_VALUE
    var maxLat = -Double.MAX_VALUE
    var minLon = Double.MAX_VALUE
    var maxLon = -Double.MAX_VALUE
    samples.forEach { s ->
        minLat = min(minLat, s.lat); maxLat = max(maxLat, s.lat)
        minLon = min(minLon, s.lon); maxLon = max(maxLon, s.lon)
    }
    // 防零跨度
    if (abs(maxLat - minLat) < 1e-6) { minLat -= 0.0005; maxLat += 0.0005 }
    if (abs(maxLon - minLon) < 1e-6) { minLon -= 0.0005; maxLon += 0.0005 }

    val drawW = w - pad * 2
    val drawH = h - pad * 2
    val spanLat = maxLat - minLat
    val spanLon = maxLon - minLon
    val midLat = (minLat + maxLat) / 2.0
    // 经度按纬度收缩，保持形状
    val lonScale = kotlin.math.cos(Math.toRadians(midLat))
    val spanX = spanLon * lonScale
    val scale = min(drawW / spanX, drawH / spanLat)

    fun px(lat: Double, lon: Double): Float {
        val x = (lon - minLon) * lonScale * scale
        val y = (maxLat - lat) * scale
        // 居中
        return (((drawW - spanX * scale) / 2 + pad + x)).toFloat()
    }

    fun py(lat: Double, lon: Double): Float {
        val y = (maxLat - lat) * scale
        return (((drawH - spanLat * scale) / 2 + pad + y)).toFloat()
    }

    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.drawColor(Color.rgb(11, 16, 32)) // Bg0

    // 网格
    val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(26, 255, 255, 255); strokeWidth = 1f
    }
    val step = w / 6f
    var gx = 0f
    while (gx <= w) { c.drawLine(gx, 0f, gx, h.toFloat(), grid); gx += step }
    var gy = 0f
    while (gy <= h) { c.drawLine(0f, gy, w.toFloat(), gy, grid); gy += step }

    // 轨迹
    val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(56, 189, 248) // Track
        strokeWidth = 6f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        style = Paint.Style.STROKE
    }
    val path = Path()
    samples.forEachIndexed { i, s ->
        val x = px(s.lat, s.lon); val y = py(s.lat, s.lon)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    c.drawPath(path, line)

    // 方向箭头（每 ~12 个样本点画一个）
    val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(167, 139, 250); style = Paint.Style.FILL
    }
    var i = 1
    while (i < samples.size) {
        val a = samples[i - 1]; val b = samples[i]
        val x1 = px(a.lat, a.lon); val y1 = py(a.lat, a.lon)
        val x2 = px(b.lat, b.lon); val y2 = py(b.lat, b.lon)
        val ang = atan2((y2 - y1).toDouble(), (x2 - x1).toDouble()).toFloat()
        val cx = (x1 + x2) / 2f; val cy = (y1 + y2) / 2f
        val s = 11f
        val p1 = Pair(cx + s * 1.6f * kotlin.math.cos(ang), cy + s * 1.6f * kotlin.math.sin(ang))
        val p2 = Pair(cx + s * kotlin.math.cos(ang + 2.5f), cy + s * kotlin.math.sin(ang + 2.5f))
        val p3 = Pair(cx + s * kotlin.math.cos(ang - 2.5f), cy + s * kotlin.math.sin(ang - 2.5f))
        val tri = Path()
        tri.moveTo(p1.first, p1.second); tri.lineTo(p2.first, p2.second); tri.lineTo(p3.first, p3.second)
        tri.close()
        c.drawPath(tri, arrow)
        i += 12
    }

    // 起点（绿）/ 终点（红）
    val first = samples.first(); val last = samples.last()
    fun dot(lat: Double, lon: Double, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        c.drawCircle(px(lat, lon), py(lat, lon), 13f, p)
    }
    dot(first.lat, first.lon, Color.rgb(52, 211, 153))
    dot(last.lat, last.lon, Color.rgb(255, 107, 107))

    // 水印
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 230, 237, 247)
        textSize = 34f; typeface = Typeface.DEFAULT_BOLD
    }
    val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 148, 163, 184)
        textSize = 28f
    }
    c.drawText("围猎 · $title", pad, h - pad + 8f, text)
    c.drawText(
        "${fmtTs(last.tsEpochMs)} · ${samples.size} 个样本点",
        pad, h - pad + 52f, dim,
    )
    return bmp
}

/** 渲染并分享轨迹 PNG；样本不足时 toast 提示 */
fun shareTrackPng(ctx: Context, samples: List<SampleRecord>, title: String) {
    val bmp = renderTrackBitmap(samples, title)
    if (bmp == null) {
        toast(ctx, "样本点不足（至少 2 个），无法生成轨迹图")
        return
    }
    runCatching {
        val dir = ctx.getExternalFilesDir(null)?.resolve("exports") ?: ctx.filesDir.resolve("exports")
        dir.mkdirs()
        val f = File(dir, "track_${System.currentTimeMillis()}.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        shareFile(ctx, f, "image/png")
    }.onFailure { toast(ctx, "轨迹图导出失败：${it.message ?: "未知错误"}") }
}
