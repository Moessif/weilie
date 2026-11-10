package com.gakj.weilie.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.baidu.mapapi.map.BaiduMap
import com.gakj.weilie.ui.common.fmtTs
import com.gakj.weilie.ui.common.shareFile
import com.gakj.weilie.ui.common.toast
import java.io.File

/**
 * 当前地图实例的取图通道。
 *
 * 百度地图的 `snapshot()` 会把「真实底图 + 全部覆盖物」合成为位图——所以导出带底图的
 * 轨迹照片不需要自己重画一遍，只要把当前地图截下来，再压上标题栏与图例即可。
 * [BaiduMapCanvas] 在创建/销毁时向这里登记与注销实例。
 */
object MapSnapshot {
    @Volatile
    var baiduMap: BaiduMap? = null

    /** 截图；没有地图实例（未初始化/已销毁）时回调 false */
    fun capture(onBitmap: (Bitmap?) -> Unit): Boolean {
        val map = baiduMap ?: return false
        return runCatching {
            map.snapshot { bmp -> onBitmap(bmp) }
            true
        }.getOrDefault(false)
    }
}

/**
 * 导出「带完整地图底图」的轨迹照片（需求 7）：
 * 底图（真实地图）+ 实心点/空心点/彩色邻区点 + 理论覆盖区 + 封闭区域，全部由地图覆盖物绘制，
 * 这里只负责把截图加一条标题栏和一行图例，让它像一张可直接贴进报告的现场图。
 */
fun shareMapSnapshotPng(
    ctx: Context,
    title: String,
    subtitle: String,
    legend: List<Pair<Int, String>> = emptyList(),
) {
    val started = MapSnapshot.capture { bmp ->
        if (bmp == null) {
            toast(ctx, "地图尚未就绪，请等底图加载完成再导出")
            return@capture
        }
        runCatching {
            val out = composeMapSheet(bmp, title, subtitle, legend)
            val dir = ctx.getExternalFilesDir(null)?.resolve("exports") ?: ctx.filesDir.resolve("exports")
            dir.mkdirs()
            val f = File(dir, "map_${System.currentTimeMillis()}.png")
            f.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
            shareFile(ctx, f, "image/png")
        }.onFailure { toast(ctx, "地图图片导出失败：${it.message ?: "未知错误"}") }
    }
    if (!started) toast(ctx, "当前不是百度地图模式，无法导出带底图的图片")
}

/** 在截图上方加标题栏、下方加图例条，返回新位图 */
fun composeMapSheet(
    src: Bitmap,
    title: String,
    subtitle: String,
    legend: List<Pair<Int, String>>,
): Bitmap {
    val padH = (src.width * 0.028f).coerceAtLeast(18f)
    val headH = (src.height * 0.11f).coerceAtLeast(120f)
    val legendH = if (legend.isEmpty()) 0f else (src.height * 0.055f).coerceAtLeast(56f)
    val outW = src.width
    val outH = src.height + (headH + legendH).toInt()
    val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.drawColor(Color.rgb(11, 16, 32))
    c.drawBitmap(src, 0f, headH, null)

    val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(230, 237, 247)
        textSize = headH * 0.30f
        typeface = Typeface.DEFAULT_BOLD
    }
    val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(170, 148, 163, 184)
        textSize = headH * 0.18f
    }
    c.drawText("围猎 · $title", padH, headH * 0.42f, titlePaint)
    c.drawText(subtitle, padH, headH * 0.74f, subPaint)

    if (legend.isNotEmpty()) {
        val y = headH + src.height + legendH * 0.62f
        var x = padH
        val dotR = legendH * 0.16f
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(200, 226, 232, 240)
            textSize = legendH * 0.28f
        }
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        legend.forEach { (color, label) ->
            dotPaint.color = color
            c.drawCircle(x + dotR, y - dotR * 0.4f, dotR, dotPaint)
            x += dotR * 2 + legendH * 0.12f
            c.drawText(label, x, y, labelPaint)
            x += labelPaint.measureText(label) + legendH * 0.42f
        }
        // 右下角时间戳
        val stamp = fmtTs(System.currentTimeMillis())
        val stampW = labelPaint.measureText(stamp)
        c.drawText(stamp, outW - padH - stampW, y, labelPaint)
    }

    // 底部细边，避免纯黑与底图糊在一起
    val edge = Paint().apply { color = Color.argb(60, 34, 211, 238) }
    c.drawRect(RectF(0f, headH - 2f, outW.toFloat(), headH), edge)
    return bmp
}
