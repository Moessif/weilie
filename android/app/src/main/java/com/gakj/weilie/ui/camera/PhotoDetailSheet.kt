package com.gakj.weilie.ui.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gakj.weilie.core.photo.PhotoRecord
import com.gakj.weilie.core.verify.RemotePhoto
import com.gakj.weilie.core.verify.VerifyEntry
import com.gakj.weilie.core.verify.VerifyResult
import com.gakj.weilie.ui.AppGraph
import com.gakj.weilie.ui.common.EmptyState
import com.gakj.weilie.ui.common.KeyValueRow
import com.gakj.weilie.ui.common.StatusBadge
import com.gakj.weilie.ui.common.fmtTs
import com.gakj.weilie.ui.team.QrCodeImage
import com.gakj.weilie.ui.theme.GlowButton
import com.gakj.weilie.ui.theme.Hairline.hairline
import com.gakj.weilie.ui.theme.Motion
import com.gakj.weilie.ui.theme.Radius
import com.gakj.weilie.ui.theme.Space
import com.gakj.weilie.ui.theme.WColors
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 取证照片详情（本机记录 / 跨设备远程记录）。
 *
 * @param photoId PhotoRecord.id
 * @param remoteCode 跨设备查看时的 8 位验证码（本机没有原件时按它向服务器查登记信息）
 * @param onDelete 非空时显示「从地图移除该标点」——删除的是地图/房间里的标点，照片本身保留
 * @param onClose 关闭 / 下滑 dismiss
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoDetailSheet(
    photoId: String,
    remoteCode: String = "",
    onDelete: (() -> Unit)? = null,
    onClose: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var loading by remember(photoId) { mutableStateOf(true) }
    var record by remember(photoId) { mutableStateOf<PhotoRecord?>(null) }
    var photo by remember(photoId) { mutableStateOf<Bitmap?>(null) }
    var missing by remember(photoId) { mutableStateOf(false) }
    // 跨设备：本机没有照片原件时按验真码向服务器查登记信息
    var remote by remember(photoId, remoteCode) { mutableStateOf<RemotePhoto?>(null) }
    var remoteError by remember(photoId, remoteCode) { mutableStateOf("") }
    var remotePhoto by remember(photoId, remoteCode) { mutableStateOf<Bitmap?>(null) }
    var remotePhotoLoading by remember(photoId, remoteCode) { mutableStateOf(false) }

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

        if (loaded == null && remoteCode.isNotBlank()) {
            val info = runCatching { AppGraph.photoVerifier.lookup(remoteCode) }.getOrNull()
            remote = info
            if (info == null) remoteError = "查不到该验证码的登记信息（可能离线或已过期）"
            // 服务端有原图就拉下来显示（拍照设备上传过才有；没有就只展示登记信息）
            remotePhotoLoading = true
            val bytes = runCatching { AppGraph.photoVerifier.fetchRemoteImage(remoteCode) }.getOrNull()
            remotePhoto = bytes?.let { withContext(Dispatchers.IO) { runCatching { decodePhotoBytes(it, 1440) }.getOrNull() } }
            remotePhotoLoading = false
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
                .padding(horizontal = Space.screenH)
                .padding(bottom = Space.x2),
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
                record == null && remoteCode.isNotBlank() -> RemoteContent(
                    code = remoteCode,
                    remote = remote,
                    error = remoteError,
                    photo = remotePhoto,
                    photoLoading = remotePhotoLoading,
                    onDelete = onDelete,
                    onClose = onClose,
                )

                record == null -> NotFoundContent(onClose = onClose)

                else -> {
                    val rec = record
                    if (rec != null) {
                        DetailContent(
                            record = rec,
                            photo = photo,
                            missing = missing,
                            onDelete = onDelete,
                            onClose = onClose,
                        )
                    }
                }
            }
            Spacer(Modifier.height(Space.x5))
        }
    }
}

/** 详情正文：标题 / 时间 / 坐标 / 精度 / 可疑类型 / 大图 / 水印行 / 从地图移除 / 关闭 */
@Composable
private fun DetailContent(
    record: PhotoRecord,
    photo: Bitmap?,
    missing: Boolean,
    onDelete: (() -> Unit)?,
    onClose: () -> Unit,
) {
    Text(
        text = record.title,
        style = MaterialTheme.typography.titleMedium,
        color = WColors.OnBg,
    )
    if (record.deviceType.isNotBlank()) {
        Spacer(Modifier.height(Space.x2))
        StatusBadge(text = deviceTypeLabel(record.deviceType), color = WColors.Warning)
    }

    Spacer(Modifier.height(Space.x3))
    KeyValueRow(
        key = "拍摄时间",
        value = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(record.ts)),
    )
    KeyValueRow(key = "坐标", value = String.format(Locale.US, "%.6f, %.6f", record.lat, record.lon))
    KeyValueRow(
        key = "定位精度",
        value = String.format(Locale.US, "±%.1f m", record.accuracyM.toDouble()),
    )

    Spacer(Modifier.height(Space.x3))
    Box(
        Modifier
            .fillMaxWidth()
            .clip(Radius.lgShape)
            .background(WColors.ChipBg)
            .hairline(Radius.lgShape),
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
                    .clip(Radius.lgShape),
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
                Spacer(Modifier.height(Space.x2))
                Text(
                    text = if (missing) "图片已丢失" else "正在加载…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = WColors.OnBgDim,
                )
            }
        }
    }

    if (record.lines.isNotEmpty()) {
        Spacer(Modifier.height(Space.x3))
        Text(
            text = "现场水印",
            style = MaterialTheme.typography.labelSmall,
            color = WColors.OnBgDim,
        )
        Spacer(Modifier.height(Space.x1))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(Radius.mdShape)
                .background(WColors.ChipBg)
                .hairline(Radius.mdShape)
                .padding(Space.x3),
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

    if (onDelete != null) {
        Spacer(Modifier.height(Space.x4))
        DeleteMarkerButton(
            hint = "照片文件会保留在本机，仅从地图上移除这个标点",
            onConfirm = onDelete,
        )
    }

    Spacer(Modifier.height(Space.x5))
    GlowButton(
        text = "关闭",
        modifier = Modifier.fillMaxWidth(),
        onClick = onClose,
    )
}

/**
 * 「从地图移除该标点」——红色危险按钮 + 二次确认。
 *
 * 为什么是「移除标点」而不是「删除照片」：照片是现场证据，App 不提供销毁入口；
 * 用户要的是地图/房间里别再显示这个点（旧版本删了还会从 PhotoStore 回填，反馈「删不掉」）。
 */
@Composable
private fun DeleteMarkerButton(hint: String, onConfirm: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    DangerOutlineButton(text = "从地图移除该标点", onClick = { confirming = true })
    Spacer(Modifier.height(Space.x1))
    Text(hint, fontSize = 11.sp, color = WColors.OnBgDim)

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            containerColor = WColors.Bg1,
            titleContentColor = WColors.OnBg,
            textContentColor = WColors.OnBgDim,
            title = { Text("移除这个标点？") },
            text = { Text("$hint。这个操作可以随时重新拍照或重新标点补回来。") },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    onConfirm()
                }) { Text("移除", color = WColors.Danger, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("取消", color = WColors.OnBgDim) }
            },
        )
    }
}

/** 危险语义的次级按钮：红色 1dp 描边胶囊，与 OutlineButton 同构，但保留红色语义 */
@Composable
private fun DangerOutlineButton(text: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.965f else 1f, Motion.bouncy(), label = "dangerPress")
    Row(
        Modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(Radius.pillShape)
            .border(1.dp, WColors.Danger.copy(alpha = 0.7f), Radius.pillShape)
            .clickable(interactionSource = interaction, indication = null) { onClick() }
            .padding(horizontal = Space.x5, vertical = Space.x3),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.DeleteOutline,
            contentDescription = null,
            tint = WColors.Danger,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(Space.x2))
        Text(text, style = MaterialTheme.typography.labelLarge, color = WColors.Danger)
    }
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

    Spacer(Modifier.height(Space.x3))
    Text(
        text = "验证真伪",
        style = MaterialTheme.typography.labelSmall,
        color = WColors.OnBgDim,
    )
    Spacer(Modifier.height(Space.x1))

    Column(
        Modifier
            .fillMaxWidth()
            .clip(Radius.mdShape)
            .background(WColors.ChipBg)
            .hairline(Radius.mdShape)
            .padding(Space.x3),
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
                    Spacer(Modifier.height(Space.x1))
                    Text(
                        text = current.code,
                        color = WColors.Primary,
                        fontSize = 22.sp,
                        letterSpacing = 2.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.height(Space.x1))
                    Text(
                        text = "用任意手机扫码即可打开验真网页，第三方无需装 App 也能复核",
                        style = MaterialTheme.typography.labelSmall,
                        color = WColors.OnBgDim,
                    )
                }
                Spacer(Modifier.width(Space.x2))
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
                Spacer(Modifier.width(Space.x2))
                Text(
                    text = "未在服务器登记（可能离线拍摄）",
                    style = MaterialTheme.typography.bodySmall,
                    color = WColors.OnBgDim,
                )
            }
        }

        Spacer(Modifier.height(Space.x2))
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
            Spacer(Modifier.height(Space.x1))
            Text(
                text = res.error,
                style = MaterialTheme.typography.labelSmall,
                color = WColors.Warning,
            )
        }

        Spacer(Modifier.height(Space.x2))
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
                Spacer(Modifier.width(Space.x3))
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = WColors.Primary,
                )
            }
        }
    }
}

/** 校验结果行：标签 + 状态徽章（有效 Success / 无效 Danger / 未验证灰）+ 底部说明 */
@Composable
private fun VerifyRow(label: String, state: Boolean?, value: String) {
    val badgeText = when (state) {
        true -> "有效"
        false -> "无效"
        null -> "未验证"
    }
    val color = when (state) {
        true -> WColors.Success
        false -> WColors.Danger
        null -> WColors.OnBgDim
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = Space.x1),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = WColors.OnBgDim,
                modifier = Modifier.weight(1f),
            )
            StatusBadge(text = badgeText, color = color)
        }
        Spacer(Modifier.height(Space.x1))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = color,
        )
    }
}

/** byId 查不到：明确告知 + 关闭，不崩 */
@Composable
private fun NotFoundContent(onClose: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Space.x4),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        EmptyState(
            title = "未找到该标点的取证记录",
            desc = "记录可能已被删除或来自其它设备",
            icon = Icons.Rounded.PhotoLibrary,
        )
        GlowButton(
            text = "关闭",
            modifier = Modifier.fillMaxWidth(),
            onClick = onClose,
        )
    }
}

/** 远程取证记录：本机没有照片原件，只展示服务器上的登记信息与签名校验结果 */
@Composable
private fun RemoteContent(
    code: String,
    remote: RemotePhoto?,
    error: String,
    photo: Bitmap?,
    photoLoading: Boolean,
    onDelete: (() -> Unit)?,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Space.x3),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.PhotoLibrary,
                contentDescription = null,
                tint = WColors.Primary,
                modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.width(Space.x2))
            Column {
                Text(
                    text = "远程取证记录",
                    style = MaterialTheme.typography.titleLarge,
                    color = WColors.OnBg,
                )
                Text(
                    text = if (photo != null) "照片由拍摄设备上传，下方为服务器登记信息" else if (photoLoading) "正在从服务器加载照片…" else "拍摄设备未上传原图，本机只展示登记信息",
                    style = MaterialTheme.typography.bodySmall,
                    color = WColors.OnBgDim,
                )
            }
        }
        Spacer(Modifier.height(Space.x3))
        if (photo != null) {
            Image(
                bitmap = photo.asImageBitmap(),
                contentDescription = "远程取证照片",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(Radius.mdShape),
            )
            Spacer(Modifier.height(Space.x3))
        }
        KeyValueRow(key = "验证码", value = code)
        if (remote != null) {
            KeyValueRow(key = "登记时间", value = fmtTime(remote.serverTs))
            KeyValueRow(key = "拍摄时间", value = fmtTime(remote.ts))
            KeyValueRow(
                key = "坐标",
                value = String.format(Locale.US, "%.6f, %.6f", remote.lat, remote.lon),
            )
            if (remote.device.isNotBlank()) {
                KeyValueRow(key = "设备类型", value = deviceTypeLabel(remote.device))
            }
            if (remote.note.isNotBlank()) KeyValueRow(key = "备注", value = remote.note)
            KeyValueRow(key = "照片哈希", value = remote.hashShort)
            Spacer(Modifier.height(Space.x2))
            StatusBadge(
                text = if (remote.sigValid) "服务器签名有效" else "服务器签名无效",
                color = if (remote.sigValid) WColors.Success else WColors.Danger,
            )
        } else {
            Spacer(Modifier.height(Space.x2))
            Text(
                text = error.ifBlank { "正在查询登记信息…" },
                style = MaterialTheme.typography.bodyMedium,
                color = WColors.OnBgDim,
            )
        }
        Spacer(Modifier.height(Space.x3))
        Text(
            text = "队友在拍摄设备上可以打开原图；本机也可把这个验证码输入网页验真页查看记录。",
            style = MaterialTheme.typography.bodySmall,
            color = WColors.OnBgDim,
        )
        Spacer(Modifier.height(Space.x4))
        if (onDelete != null) {
            DeleteMarkerButton(
                hint = "队友手机上的照片会保留，仅从地图与房间里移除这个标点",
                onConfirm = onDelete,
            )
            Spacer(Modifier.height(Space.x3))
        }
        GlowButton(
            text = "关闭",
            modifier = Modifier.fillMaxWidth(),
            onClick = onClose,
        )
    }
}

/** 时间戳 → 本地时间文本（0 表示未登记） */
private fun fmtTime(ms: Long): String =
    if (ms <= 0L) "—" else SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(ms))
/** 设备类型编码 → 中文标签（与相机 chip 的口径一致） */
private fun deviceTypeLabel(raw: String): String = when (raw) {
    "HIDDEN_CAM" -> "隐蔽摄像头"
    "SUSPICIOUS_CAM" -> "可疑摄像头"
    "SUSPICIOUS_DEVICE" -> "可疑设备"
    "OTHER" -> "其他"
    else -> raw
}

/** 远程图片（字节）按边长下采样解码 */
private fun decodePhotoBytes(bytes: ByteArray, maxSide: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
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
