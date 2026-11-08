package com.gakj.weilie.ui.common

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.gakj.weilie.core.export.GeoJsonExporter
import com.gakj.weilie.core.export.KmlExporter
import com.gakj.weilie.core.fitting.CoverageResult
import com.gakj.weilie.core.model.SampleRecord
import com.gakj.weilie.core.model.TargetCell
import com.gakj.weilie.core.store.SessionStore
import java.io.File
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** UI 层通用小工具：时间/时长格式化、震动、权限判断、导出分享。 */

private val tsFmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
private val dayFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

fun fmtTs(epochMs: Long): String = tsFmt.format(Date(epochMs))

fun fmtDay(epochMs: Long): String = dayFmt.format(Date(epochMs))

/** 毫秒时长 → 「X分YY秒」/「X时Y分」 */
fun fmtDur(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return when {
        h > 0 -> "${h}时${m}分"
        m > 0 -> "${m}分${s}秒"
        else -> "${s}秒"
    }
}

fun toast(context: Context, msg: String) {
    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
}

fun vibrate(context: Context, ms: Long = 60) {
    runCatching {
        val vib: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vib.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vib.vibrate(ms)
        }
    }
}

fun hasPerm(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

fun hasLocationPermission(context: Context): Boolean =
    hasPerm(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
        hasPerm(context, Manifest.permission.ACCESS_COARSE_LOCATION)

fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        hasPerm(context, Manifest.permission.POST_NOTIFICATIONS)

// ---------- 导出与分享 ----------

/** 导出文件统一落盘：外部应用目录 exports/（file_paths.xml 已登记） */
fun writeExportFile(context: Context, name: String, content: String): File? = runCatching {
    val dir = context.getExternalFilesDir(null)?.resolve("exports")
        ?: context.filesDir.resolve("exports")
    dir.mkdirs()
    val f = File(dir, name)
    f.writeText(content)
    f
}.getOrNull()

fun shareFile(context: Context, file: File, mime: String) {
    runCatching {
        val uri = FileProvider.getUriForFile(
            context, context.packageName + ".fileprovider", file,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享导出文件"))
    }.onFailure { toast(context, "分享失败：${it.message ?: "未知错误"}") }
}

/** CSV：复用 SessionStore.exportCsvFile（外部 sessions/ 目录，已登记 provider） */
fun exportCsvAndShare(context: Context, store: SessionStore, taskId: String) {
    val f = store.exportCsvFile(taskId)
    if (f == null) {
        toast(context, "导出失败：会话不存在")
        return
    }
    toast(context, "CSV 已导出：${f.absolutePath}")
    shareFile(context, f, "text/csv")
}

fun exportGeoJsonAndShare(
    context: Context,
    taskId: String,
    samples: List<SampleRecord>,
    target: TargetCell?,
    result: CoverageResult?,
) {
    val json = GeoJsonExporter.export(
        samples = samples,
        target = target,
        hullLatLon = result?.hullLatLon,
        bufferedLatLon = result?.bufferedLatLon,
        isolines = result?.isolineLoopsLatLon ?: emptyList(),
    )
    val f = writeExportFile(context, "${taskId}_coverage.geojson", json)
    if (f == null) {
        toast(context, "GeoJSON 导出失败")
        return
    }
    toast(context, "GeoJSON 已导出：${f.absolutePath}")
    shareFile(context, f, "application/geo+json")
}

fun exportKmlAndShare(
    context: Context,
    taskId: String,
    title: String,
    samples: List<SampleRecord>,
    result: CoverageResult?,
) {
    val kml = KmlExporter.export(
        title = title,
        samples = samples,
        hullLatLon = result?.hullLatLon,
        isolines = result?.isolineLoopsLatLon ?: emptyList(),
    )
    val f = writeExportFile(context, "${taskId}_coverage.kml", kml)
    if (f == null) {
        toast(context, "KML 导出失败")
        return
    }
    toast(context, "KML 已导出：${f.absolutePath}")
    shareFile(context, f, "application/vnd.google-earth.kml+xml")
}

/** URL 编码（房间号等拼 URL 用） */
fun urlEncode(s: String): String =
    URLEncoder.encode(s, "UTF-8")

/** 房间号：6 位大写字母数字（去易混 0O1I） */
fun randomRoomCode(): String {
    val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    return (1..6).map { alphabet.random() }.joinToString("")
}
