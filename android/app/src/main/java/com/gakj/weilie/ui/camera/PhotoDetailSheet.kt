package com.gakj.weilie.ui.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gakj.weilie.core.photo.PhotoRecord
import com.gakj.weilie.core.verify.VerifyEntry
import com.gakj.weilie.core.verify.VerifyResult
import com.gakj.weilie.ui.AppGraph
import com.gakj.weilie.ui.common.KeyValueRow
import com.gakj.weilie.ui.common.StatusBadge
import com.gakj.weilie.ui.common.fmtTs
import com.gakj.weilie.ui.team.QrCodeImage
import com.gakj.weilie.ui.theme.GlowButton
import com.gakj.weilie.ui.theme.WColors
import com.gakj.weilie.ui.theme.glass
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 取证照片详情（地图标点 / 相册点开时弹出）。
 *
 * 数据来自 [AppGraph.photoStore]：record 里带坐标、水印行、可疑设备类型，
 * 大图按 [com.gakj.weilie.core.photo.PhotoStore.fileOf] 约定回查；图片文件被删也不崩，显示占位。
 *
 * @param photoId PhotoRecord.id
 * @param onClose 关闭 / 下滑 dismiss
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoDetailSheet(photoId: String, onClose: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var loading by remember(photoId) { mutableStateOf(true) }
    var record by remember(photoId) { mutableStateOf<PhotoRecord?>(null) }
    var photo by remember(photoId) { mutableStateOf<Bitmap?>(null) }
    var missing by remember(photoId) { mutableStateOf(false) }

    LaunchedEffect(photoId) {
        val loaded = withContext(Dispatchers.IO) {
            runCatching { AppGraph.photoStore.byId(photoId) }.getOrNull()
        }
        record = loaded
        if (loaded != null) {
            val file = runCatching { AppGraph.photoStore.fileOf(loaded) }.getOrNull()
            photo = when {
                file == null || loaded.file.isBlank() -> null
                !file.isFile || file.length() <= 0L -> null
                else -> withContext(Dispatchers.IO) {
                    runCatching { decodePhotoFile(file, 1440) }.getOrNull()
                }
            }
            missing = photo == null
        }
        loading = false
    }

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        containerColor = WColors.Bg1,
        contentColor = WColors.OnBg,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 18.dp)
                .padding(bottom = 8.dp),
        ) {
            when {
                loading -> Box(
                    Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = WColors.Primary)
                }

                record == null -> NotFoundContent(onClose = onClose)

                else -> {
                    val rec = record
                    if (rec != null) {
                        DetailContent(record = rec, photo = photo, missing = missing, onClose = onClose)
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

/** 详情正文：标题 / 时间 / 坐标 / 精度 / 可疑类型 / 大图 / 水印行 / 关闭 */
@Composable
private fun DetailContent(
    record: PhotoRecord,
    photo: Bitmap?,
    missing: Boolean,
    onClose: () -> Unit,
) {
    Text(
        text = record.title,
        style = MaterialTheme.typography.titleLarge,
        color = WColors.OnBg,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(record.ts)),
        style = MaterialTheme.typography.bodySmall,
        color = WColors.OnBgDim,
    )
    if (record.deviceType.isNotBlank()) {
        Spacer(Modifier.height(8.dp))
        StatusBadge(text = deviceTypeLabel(record.deviceType), color = WColors.Warning)
    }

    Spacer(Modifier.height(14.dp))
    KeyValueRow(key = "坐标", value = String.format(Locale.US, "%.6f, %.6f", record.lat, record.lon))
    KeyValueRow(
        key = "定位精度",
        value = String.format(Locale.US, "±%.1f m", record.accuracyM.toDouble()),
    )

    Spacer(Modifier.height(14.dp))
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .glass(shape)
            .padding(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (photo != null) {
            Image(
                bitmap = photo.asImageBitmap(),
                contentDescription = "取证照片原图",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(photo.width.toFloat() / photo.height.toFloat())
                    .clip(RoundedCornerShape(12.dp)),
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.BrokenImage,
                    contentDescription = null,
                    tint = WColors.OnBgDim,
                    modifier = Modifier.size(34.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (missing) "图片已丢失" else "正在加载…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = WColors.OnBgDim,
                )
            }
        }
    }

    if (record.lines.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        Text(
            text = "现场水印",
            style = MaterialTheme.typography.labelSmall,
            color = WColors.OnBgDim,
        )
        Spacer(Modifier.height(6.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .glass(RoundedCornerShape(12.dp))
                .padding(12.dp),
        ) {
            record.lines.forEach { line ->
                Text(
                    text = line,
                    color = WColors.OnBgDim,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }

    // 在线验真：验证码/二维码 + 三项校验结果
    VerifySection(record = record)

    Spacer(Modifier.height(18.dp))
    GlowButton(
        text = "关闭",
        modifier = Modifier.fillMaxWidth(),
        onClick = onClose,
    )
}

/**
 * 「验证真伪」区块：验证码/二维码 + 三行校验结果（登记记录 / 内容一致性 / 签名校验）。
 *
 * - 已登记：显示 8 位验证码与 WEILIE-VERIFY 二维码，点「在线验证」把本地哈希送服务器比对；
 * - 未登记（离线拍摄 / 上次登记失败）：按钮变「补登记」，先登记再校验；
 * - 打开详情页时若本机还没有回执，会在后台静默补登记一次（失败不打断用户，下次再试）。
 */
@Composable
private fun VerifySection(record: PhotoRecord) {
    val scope = rememberCoroutineScope()
    var entry by remember(record.id) { mutableStateOf<VerifyEntry?>(null) }
    var busy by remember(record.id) { mutableStateOf(false) }
    var result by remember(record.id) { mutableStateOf<VerifyResult?>(null) }

    /** 读本地回执（IO 线程，坏文件兜底为 null） */
    suspend fun readEntry(): VerifyEntry? = withContext(Dispatchers.IO) {
        runCatching { AppGraph.verifyStore.byPhotoId(record.id) }.getOrNull()
    }

    LaunchedEffect(record.id) {
        val loaded = readEntry()
        entry = loaded
        if (loaded == null) {
            // 离线拍摄 / 上次登记失败的待补登记：进详情页顺手重试一次
            busy = true
            runCatching { AppGraph.photoVerifier.register(AppGraph.photoStore.fileOf(record), record) }
            entry = readEntry()
            busy = false
        }
    }

    Spacer(Modifier.height(16.dp))
    Text(
        text = "验证真伪",
        style = MaterialTheme.typography.labelSmall,
        color = WColors.OnBgDim,
    )
    Spacer(Modifier.height(6.dp))

    Column(
        Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        val current = entry
        val res = result
        if (current != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "验证码",
                        style = MaterialTheme.typography.labelSmall,
                        color = WColors.OnBgDim,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = current.code,
                        color = WColors.Primary,
                        fontSize = 22.sp,
                        letterSpacing = 2.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "用任意手机扫码即可打开验真网页，第三方无需装 App 也能复核",
                        style = MaterialTheme.typography.labelSmall,
                        color = WColors.OnBgDim,
                    )
                }
                Spacer(Modifier.width(10.dp))
                // 二维码直接编码验真网页地址：别家手机的原生相机扫开就能验，不依赖本 App
                QrCodeImage(text = AppGraph.serverHttpUrl() + "/v/" + current.code, size = 120)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.RemoveCircleOutline,
                    contentDescription = null,
                    tint = WColors.OnBgDim,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "未在服务器登记（可能离线拍摄）",
                    style = MaterialTheme.typography.bodySmall,
                    color = WColors.OnBgDim,
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        VerifyRow(
            label = "登记记录",
            state = when {
                res?.found == true -> true
                res?.found == false -> false
                // 网络失败时不能把本地回执当成「已确认登记」，显示灰色
                res?.error != null -> null
                current != null -> true
                else -> null
            },
            value = when {
                res?.serverTs != null -> "服务器时间 ${fmtTs(res.serverTs)}"
                current != null -> "服务器时间 ${fmtTs(current.serverTs)}"
                res?.error != null -> "未能确认登记状态"
                else -> "未在服务器登记（可能离线拍摄）"
            },
        )
        VerifyRow(
            label = "内容一致性",
            state = when {
                res == null || res.error != null || !res.found -> null
                else -> res.hashMatch
            },
            value = when {
                res == null -> "未验证"
                res.error != null -> "未验证"
                !res.found -> "无法校验（未登记）"
                res.hashMatch -> "哈希一致 · 照片未被改动"
                else -> "哈希不一致 · 照片可能被改动"
            },
        )
        VerifyRow(
            label = "签名校验",
            state = when {
                res == null || res.error != null || !res.found -> null
                else -> res.sigValid
            },
            value = when {
                res == null -> "未验证"
                res.error != null -> "未验证"
                !res.found -> "无法校验（未登记）"
                res.sigValid -> "签名有效 · 登记记录可信"
                else -> "签名无效 · 记录可能被篡改"
            },
        )

        if (res?.error != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = res.error,
                style = MaterialTheme.typography.labelSmall,
                color = WColors.Warning,
            )
        }

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlowButton(
                text = when {
                    busy -> "处理中…"
                    current == null -> "补登记"
                    else -> "在线验证"
                },
                modifier = Modifier.weight(1f),
                enabled = !busy,
            ) {
                if (!busy) scope.launch {
                    busy = true
                    try {
                        var e = entry
                        if (e == null) {
                            // 未登记：补登记后再校验
                            AppGraph.photoVerifier.register(AppGraph.photoStore.fileOf(record), record)
                            e = readEntry()
                            entry = e
                        }
                        result = if (e == null) {
                            VerifyResult(
                                found = false,
                                hashMatch = false,
                                sigValid = false,
                                serverTs = null,
                                code = null,
                                error = "补登记失败：请检查网络或服务器地址",
                            )
                        } else {
                            AppGraph.photoVerifier.verifyFile(AppGraph.photoStore.fileOf(record), e.code)
                        }
                    } finally {
                        busy = false
                    }
                }
            }
            if (busy) {
                Spacer(Modifier.width(12.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = WColors.Primary,
                )
            }
        }
    }
}

/** 校验结果行：绿勾 / 红叉 / 灰问号 + 说明文字 */
@Composable
private fun VerifyRow(label: String, state: Boolean?, value: String) {
    val color = when (state) {
        true -> WColors.Success
        false -> WColors.Danger
        null -> WColors.OnBgDim
    }
    val icon = when (state) {
        true -> Icons.Rounded.CheckCircle
        false -> Icons.Rounded.Cancel
        null -> Icons.Rounded.RemoveCircleOutline
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = WColors.OnBgDim)
        Spacer(Modifier.width(10.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = color,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

/** byId 查不到：明确告知 + 关闭，不崩 */
@Composable
private fun NotFoundContent(onClose: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Rounded.PhotoLibrary,
            contentDescription = null,
            tint = WColors.OnBgDim,
            modifier = Modifier.size(38.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "未找到该标点的取证记录",
            style = MaterialTheme.typography.titleMedium,
            color = WColors.OnBg,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "记录可能已被删除或来自其它设备",
            style = MaterialTheme.typography.bodySmall,
            color = WColors.OnBgDim,
        )
        Spacer(Modifier.height(18.dp))
        GlowButton(
            text = "关闭",
            modifier = Modifier.fillMaxWidth(),
            onClick = onClose,
        )
    }
}

/** 设备类型编码 → 中文标签（与相机 chip 的口径一致） */
private fun deviceTypeLabel(raw: String): String = when (raw) {
    "HIDDEN_CAM" -> "隐蔽摄像头"
    "SUSPICIOUS_CAM" -> "可疑摄像头"
    "SUSPICIOUS_DEVICE" -> "可疑设备"
    "OTHER" -> "其他"
    else -> raw
}

/** 详情大图按屏幕尺寸下采样加载，避免整张 1600px 原图占内存 */
private fun decodePhotoFile(file: File, maxSide: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeFile(file.absolutePath, opts)
}
