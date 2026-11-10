package com.gakj.weilie.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import java.util.Locale

/**
 * 「导航到标点」：不自己实现路径规划，直接把坐标交给手机上的地图应用。
 *
 * 顺序：
 * 1. 标准 geo: URI（高德/百度/腾讯/Google 都会注册，系统会弹选择器）——最通用；
 * 2. 高德网页版（没装任何地图 App 也能在浏览器里看位置并点“导航”）；
 * 3. 都没有就 toast 提示。
 *
 * 说明：真实步行/驾车路径由地图 App 负责，本 App 只负责“把标点坐标交出去”，
 * 这样离线、无 SDK 配额时也能用。
 */
fun navigateTo(ctx: Context, lat: Double, lon: Double, label: String = "") {
    val tag = if (label.isNotBlank()) label else String.format(Locale.US, "%.6f, %.6f", lat, lon)
    // 1) 系统地图 intent
    val geo = Uri.parse(String.format(Locale.US, "geo:%.6f,%.6f?q=%.6f,%.6f(%s)", lat, lon, lat, lon, Uri.encode(tag)))
    val geoIntent = Intent(Intent.ACTION_VIEW, geo).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { ctx.startActivity(geoIntent) }.isSuccess) return

    // 2) 高德网页（浏览器打开，无需装 App）
    val web = Uri.parse(
        "https://uri.amap.com/marker?position=${lon},${lat}&name=${Uri.encode(tag)}&src=gakj-weilie&coordinate=wgs84&callnative=0",
    )
    val webIntent = Intent(Intent.ACTION_VIEW, web).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        ctx.startActivity(webIntent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(ctx, "没有可用的地图应用，坐标：$tag", Toast.LENGTH_LONG).show()
    }
}
