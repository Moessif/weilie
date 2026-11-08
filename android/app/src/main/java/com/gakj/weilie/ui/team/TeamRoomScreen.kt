package com.gakj.weilie.ui.team

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Adjust
import androidx.compose.material.icons.rounded.Assignment
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gakj.weilie.core.sync.ConnState
import com.gakj.weilie.core.sync.MemberView
import com.gakj.weilie.core.sync.RoomClient
import com.gakj.weilie.core.sync.WsMsg
import com.gakj.weilie.service.SamplingForegroundService
import com.gakj.weilie.ui.AppGraph
import com.gakj.weilie.ui.Route
import com.gakj.weilie.ui.camera.PhotoCameraScreen
import com.gakj.weilie.ui.camera.PhotoDetailSheet
import com.gakj.weilie.ui.common.GlassIconButton
import com.gakj.weilie.ui.common.GlassTextButton
import com.gakj.weilie.ui.common.hasLocationPermission
import com.gakj.weilie.ui.common.toast
import com.gakj.weilie.ui.map.MapCanvas
import com.gakj.weilie.ui.map.MapCamera
import com.gakj.weilie.ui.map.MapLayersPanel
import com.gakj.weilie.ui.map.MapLayerMode
import com.gakj.weilie.ui.theme.GlassCard
import com.gakj.weilie.ui.theme.MonoText
import com.gakj.weilie.ui.theme.PulsingDot
import com.gakj.weilie.ui.theme.WColors
import com.gakj.weilie.ui.theme.glass
import com.gakj.weilie.ui.theme.mapGlass
import kotlin.math.ceil
import kotlin.math.cos
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 协同地图（STRICT_SPEC §3）：地图 + 成员/标记/任务圈，
 * 右侧上部「任务」「团队聊天」两个悬浮钮；左上图层（含队友位置/任务范围开关）；
 * 队长可矩形划分任务区分块下发与转让身份；观战只读。
 */
@Composable
fun TeamRoomScreen(roomId: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val userId = remember { AppGraph.userId() }
    val name = remember {
        (AppGraph.prefs.getString(Route.PREF_NICKNAME, "") ?: "").ifBlank { "队员${userId.take(4)}" }
    }
    val role = remember { AppGraph.lastRole ?: "MEMBER" }
    val wsBase = remember {
        val http = (AppGraph.prefs.getString(Route.PREF_SERVER, "") ?: "").trim().trimEnd('/')
            .ifBlank { AppGraph.DEFAULT_SERVER }
        when {
            http.isBlank() -> ""
            http.startsWith("https://") -> http.replaceFirst("https://", "wss://")
            http.startsWith("http://") -> http.replaceFirst("http://", "ws://")
            else -> "ws://$http"
        }
    }

    // QA-07：协同地图自己也要有采集控制器——否则没有定位来源，队友位置/采样/轨迹全是空的
    // （原实现只读 AppGraph.currentController，而它只在单人地图页被创建）
    val controller = remember(roomId) { AppGraph.ensure(ctx, "team-$roomId", null) }
    val samples by controller.samples.collectAsState()
    var locGranted by remember { mutableStateOf(hasLocationPermission(ctx)) }
    fun beginSampling() {
        if (!locGranted) return
        runCatching { controller.start() }
        runCatching { SamplingForegroundService.start(ctx) }
    }
    val locPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        locGranted = granted
        if (granted) beginSampling()
    }
    DisposableEffect(controller) {
        beginSampling()
        onDispose {
            runCatching { SamplingForegroundService.stop(ctx) }
            runCatching { controller.stop() }
        }
    }
    LaunchedEffect(Unit) {
        if (!locGranted) runCatching { locPermLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }
    }

    val room = remember(roomId) {
        AppGraph.currentRoom?.disconnect()
        RoomClient(AppGraph.appScope, wsBase.ifBlank { "ws://127.0.0.1:8080" }).also { AppGraph.currentRoom = it }
    }
    DisposableEffect(roomId) {
        room.connect(WsMsg.Hello(roomId = roomId, userId = userId, name = name, role = role))
        onDispose {
            room.disconnect()
            if (AppGraph.currentRoom === room) AppGraph.currentRoom = null
        }
    }

    val conn by room.state.collectAsState()
    val state by room.room.collectAsState()
    val chats by room.chats.collectAsState()
    val camera = remember { MapCamera() }
    val scope = rememberCoroutineScope()
    var markerMenu by remember { mutableStateOf(false) }
    var framed by remember { mutableStateOf(false) }
    var showLayers by remember { mutableStateOf(false) }
    var layerMode by remember { mutableStateOf(MapLayerMode.NORMAL) }
    var teammateVisible by remember { mutableStateOf(true) }
    var zoneVisible by remember { mutableStateOf(true) }
    var showTaskPanel by remember { mutableStateOf(false) }
    var showChatPanel by remember { mutableStateOf(false) }
    var showZoneDialog by remember { mutableStateOf(false) }
    var showSplitDialog by remember { mutableStateOf(false) }
    var transferTarget by remember { mutableStateOf<MemberView?>(null) }
    val localZones = remember { mutableStateListOf<WsMsg.Zone>() }
    var showCamera by remember { mutableStateOf(false) }
    var detailPhotoId by remember { mutableStateOf<String?>(null) }

    // QA-05：服务器 State 是权威快照——一到就丢弃本地乐观任务区，
    // 否则队长重划/换圈时旧批次会永久堆在列表与地图上（队友端只收到最新一批）
    LaunchedEffect(state.zones) {
        if (state.zones.isNotEmpty()) localZones.clear()
    }

    // 有采集控制器时同步上报自己的位置/状态
    LaunchedEffect(controller) {
        controller.samples.collect { list ->
            val s = list.lastOrNull() ?: return@collect
            room.maybeReportLoc(s.lat, s.lon, s.accuracyM?.toDouble(), s.onTarget, userId)
        }
    }

    // QA-07：位置上报不能只依赖「采样成功」——没采到小区（NSA 锚点缺失/ROM 不给 CellInfo）时
    // 也必须在队伍地图上显示自己的位置，否则队友永远看到「0 采、无位置」
    LaunchedEffect(room, locGranted) {
        while (isActive) {
            val loc = controller.lastLocation
            if (loc != null) {
                val snap = controller.snapshot.value
                val rsrp = snap?.signal?.rsrp
                val onTarget = samples.lastOrNull()?.onTarget ?: false
                val acc = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null
                runCatching { room.reportSample(userId, loc.latitude, loc.longitude, rsrp, onTarget, acc) }
            }
            delay(2_000L)
        }
    }

    // 首次有成员位置/任务区时自动取景。
    // 注意：成员是先注册（无坐标）后上报 Loc 的，若只按 size 做 key 就永远不会在
    // 拿到坐标后重新取景（地图会停在 MapCamera 的默认中心，队友点和标点全在屏幕外）。
    LaunchedEffect(state.members, state.zones.size) {
        if (!framed) {
            val pts = buildList {
                state.members.forEach { m ->
                    val la = m.lat
                    val lo = m.lon
                    if (la != null && lo != null) add(la to lo)
                }
                state.zones.forEach { z -> z.centerLat?.let { la -> z.centerLon?.let { lo -> add(la to lo) } } }
                localZones.forEach { z -> z.centerLat?.let { la -> z.centerLon?.let { lo -> add(la to lo) } } }
            }
            if (pts.isNotEmpty()) {
                camera.fit(pts)
                // 取景后若超过 17.5 级，会出现「空白瓦片」，退回可出图的级别
                if (camera.zoom > 17.5f) camera.zoomBy(17.5f / camera.zoom)
                framed = true
            }
        }
    }

    // 任务区显示：服务器 zones 为准，本地 localZones 仅作「已下发未回推」的乐观补差（协议 v1.1）
    val displayZones = remember(state.zones, localZones.size) {
        state.zones + localZones.filter { lz ->
            state.zones.none { sz -> sz.ts == lz.ts && sz.note == lz.note }
        }
    }

    // 队长/观战身份以服务器 members 为准，加入时的 role 仅作未收到 State 前的兜底（协议 v1.1）
    val myRole = state.members.firstOrNull { it.userId == userId }?.role ?: role

    val (connText, connColor) = when (conn) {
        ConnState.CONNECTED -> "已连接" to WColors.Success
        ConnState.CONNECTING -> "连接中" to WColors.Warning
        ConnState.RETRYING -> "重连中" to WColors.Warning
        ConnState.DISCONNECTED -> "离线" to WColors.OnBgDim
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(WColors.Bg0),
    ) {
        MapCanvas(
            modifier = Modifier.fillMaxSize(),
            samples = samples,
            members = if (teammateVisible) state.members else emptyList(),
            markers = state.markers,
            zones = if (zoneVisible) displayZones else emptyList(),
            camera = camera,
            onMarkerClick = { mk ->
                if (mk.photoId.isNotBlank()) detailPhotoId = mk.photoId
                else toast(ctx, "${mk.byName} 的标点：${if (mk.note.isNotBlank()) mk.note else "无备注"}")
            },
        )

        // ---------- 左上图层列 ----------
        Column(
            Modifier
                .statusBarsPadding()
                .padding(start = 10.dp, top = 10.dp),
        ) {
            GlassIconButton(icon = Icons.Rounded.Layers, size = 44.dp, onMap = true) { showLayers = !showLayers }
        }
        if (showLayers) {
            Box(
                Modifier
                    .statusBarsPadding()
                    .padding(start = 62.dp, top = 10.dp),
            ) {
                MapLayersPanel(
                    current = layerMode,
                    onSelect = { layerMode = it },
                    showTeamToggles = true,
                    teammateVisible = teammateVisible,
                    zoneVisible = zoneVisible,
                    onTeammateToggle = { teammateVisible = it },
                    onZoneToggle = { zoneVisible = it },
                    onDismiss = { showLayers = false },
                )
            }
        }

        // ---------- 顶栏 ----------
        Column(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 62.dp, end = 10.dp, top = 10.dp),
        ) {
            GlassCard(Modifier.fillMaxWidth(), onMap = true) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Groups, contentDescription = null, tint = WColors.Secondary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("房间 $roomId", style = MonoText.style.copy(fontSize = 15.sp), color = WColors.OnBg)
                    Spacer(Modifier.width(10.dp))
                    PulsingDot(if (conn == ConnState.CONNECTED) WColors.Success else WColors.Warning, 8.dp)
                    Spacer(Modifier.width(4.dp))
                    Text(connText, style = MaterialTheme.typography.labelSmall, color = connColor)
                    Spacer(Modifier.weight(1f))
                    Text("成员 ${state.members.size}", style = MaterialTheme.typography.labelSmall, color = WColors.OnBgDim)
                }
            }
            if (state.members.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.members) { m -> MemberChip(m) }
                }
            }
        }

        // ---------- 右侧上部悬浮钮：任务 / 团队聊天 ----------
        Column(
            Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(end = 10.dp, top = 150.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GlassIconButton(icon = Icons.Rounded.Assignment, label = "任务", size = 44.dp, onMap = true) { showTaskPanel = !showTaskPanel }
            GlassIconButton(icon = Icons.Rounded.ChatBubble, label = "聊天", size = 44.dp, onMap = true) { showChatPanel = !showChatPanel }
        }

        // ---------- 左下聊天浮层 ----------
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 12.dp, bottom = 86.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            chats.takeLast(3).forEach { c ->
                Box(
                    Modifier
                        // 跟随主题：暗色=深蓝玻璃，亮色=白玻璃；文字统一用 OnBg，两种主题都可读
                        .background(WColors.Bg1.copy(alpha = 0.92f), RoundedCornerShape(12.dp))
                        .border(1.dp, WColors.GlassBorder, RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(
                        "${c.fromName}：${c.text}",
                        style = MaterialTheme.typography.bodySmall,
                        color = WColors.OnBg,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // ---------- 底部：标记 / 观战提示 ----------
        if (myRole != "OBSERVER") {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        GlassIconButton(icon = Icons.Rounded.Flag, label = "标记", size = 44.dp, onMap = true) { markerMenu = true }
                        DropdownMenu(expanded = markerMenu, onDismissRequest = { markerMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("普通旗标", color = WColors.OnBg) },
                                onClick = {
                                    markerMenu = false
                                    scope.launch {
                                        room.marker(camera.centerLat, camera.centerLon, "FLAG", "", userId, name)
                                        toast(ctx, "已在地图中心插旗")
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("可疑设备", color = WColors.Danger) },
                                onClick = {
                                    markerMenu = false
                                    scope.launch {
                                        room.marker(camera.centerLat, camera.centerLon, "SUSPICIOUS", "", userId, name)
                                        toast(ctx, "已标记可疑设备")
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("进相机取证（带水印）", color = WColors.OnBg) },
                                onClick = {
                                    markerMenu = false
                                    showCamera = true
                                },
                            )
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    GlassIconButton(icon = Icons.Rounded.PhotoCamera, label = "拍照", size = 44.dp, onMap = true) { showCamera = true }
                }
            }
        } else {
            Text(
                "观战模式（只读）",
                style = MaterialTheme.typography.labelMedium,
                color = WColors.OnBgDim,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 20.dp),
            )
        }

        // ---------- 任务浮窗 ----------
        if (showTaskPanel) {
            TaskPanel(
                role = myRole,
                members = state.members,
                zones = displayZones,
                onDismiss = { showTaskPanel = false },
                onOpenSplit = { showTaskPanel = false; showSplitDialog = true },
                onOpenCircle = { showTaskPanel = false; showZoneDialog = true },
                onTransfer = { m -> transferTarget = m },
            )
        }

        // ---------- 团队聊天浮窗 ----------
        if (showChatPanel) {
            ChatPanel(
                chats = chats.takeLast(40),
                userId = userId,
                readOnly = myRole == "OBSERVER",
                onSend = { t -> scope.launch { room.chat(t, userId, name) } },
                onDismiss = { showChatPanel = false },
            )
        }
    }

    // ---------- 圆形任务圈下发 ----------
    if (showZoneDialog) {
        ZoneDialog(
            onDismiss = { showZoneDialog = false },
            onConfirm = { r, note ->
                showZoneDialog = false
                scope.launch {
                    val z = WsMsg.Zone(
                        by = userId,
                        kind = "CIRCLE",
                        centerLat = camera.centerLat,
                        centerLon = camera.centerLon,
                        radiusM = r,
                        note = note,
                        replace = true, // 单块任务圈：清空旧任务区
                        ts = System.currentTimeMillis(),
                    )
                    room.send(z)
                    localZones.add(z)
                    toast(ctx, "任务圈已下发（半径 %.0f 米）".format(r))
                }
            },
        )
    }

    // ---------- 矩形划分任务区 ----------
    if (showSplitDialog) {
        ZoneSplitDialog(
            onDismiss = { showSplitDialog = false },
            onConfirm = { widthM, heightM, blocks ->
                showSplitDialog = false
                scope.launch {
                    val cols = ceil(kotlin.math.sqrt(blocks.toDouble())).toInt().coerceAtLeast(1)
                    val rows = ceil(blocks.toDouble() / cols).toInt().coerceAtLeast(1)
                    val latSpan = heightM / 110_540.0
                    val lonSpan = widthM / (111_320.0 * cos(Math.toRadians(camera.centerLat)))
                    val radius = minOf(widthM / cols, heightM / rows) / 2.0
                    val now = System.currentTimeMillis()
                    for (i in 0 until blocks) {
                        val row = i / cols
                        val col = i % cols
                        val lat = camera.centerLat + ((rows - 1) / 2.0 - row) * latSpan
                        val lon = camera.centerLon + (col - (cols - 1) / 2.0) * lonSpan
                        val z = WsMsg.Zone(
                            by = userId,
                            kind = "CIRCLE",
                            centerLat = lat,
                            centerLon = lon,
                            radiusM = radius,
                            note = "任务区 ${i + 1}/$blocks",
                            replace = i == 0, // 第一块带 replace，服务器先清空旧任务区（协议 v1.1）
                            ts = now,
                        )
                        room.send(z)
                        localZones.add(z)
                    }
                    toast(ctx, "已划分 $blocks 块任务区并下发（每块内切圆 %.0f 米）".format(radius))
                }
            },
        )
    }

    // ---------- 转让身份确认 ----------
    transferTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { transferTarget = null },
            title = { Text("转让队长") },
            text = { Text("将队长身份转让给「${target.name}」？转让后你将变为队员身份。") },
            confirmButton = {
                TextButton({
                    transferTarget = null
                    scope.launch {
                        // 协议 v1.1：服务端校验现队长身份，改双方角色并广播系统消息与新 State
                        room.promote(userId, target.userId)
                        toast(ctx, "已发起队长转让给 ${target.name}")
                    }
                }) { Text("确认转让") }
            },
            dismissButton = { TextButton({ transferTarget = null }) { Text("取消") } },
        )
    }

    // ---------- 团队取证相机（QA-09：协同地图补回拍照打点，照片带水印并入房间标记） ----------
    if (showCamera) {
        val fix = samples.lastOrNull()
        PhotoCameraScreen(
            initialKind = "PHOTO",
            currentLat = fix?.lat ?: camera.centerLat,
            currentLon = fix?.lon ?: camera.centerLon,
            hasFix = fix != null,
            onClose = { showCamera = false },
            onCaptured = { rec ->
                showCamera = false
                scope.launch {
                    room.photo(
                        WsMsg.Marker(
                            by = userId,
                            byName = name,
                            lat = rec.lat,
                            lon = rec.lon,
                            kind = rec.kind,
                            note = rec.title,
                            ts = rec.ts,
                            photoId = rec.id,
                            deviceType = rec.deviceType,
                        ),
                    )
                }
                toast(ctx, "已上传取证照片，队友地图上会同步出现标点")
            },
            onOpenGallery = {
                AppGraph.photoStore.latest()?.let { detailPhotoId = it.id } ?: toast(ctx, "还没有取证照片")
            },
        )
    }

    detailPhotoId?.let { pid -> PhotoDetailSheet(pid) { detailPhotoId = null } }
}

/** 任务浮窗：任务区列表（协议 v1.1 zones）+ 队长操作 + 成员（点按转让） */
@Composable
private fun TaskPanel(
    role: String,
    members: List<MemberView>,
    zones: List<WsMsg.Zone>,
    onDismiss: () -> Unit,
    onOpenSplit: () -> Unit,
    onOpenCircle: () -> Unit,
    onTransfer: (MemberView) -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x66000000))
            .clickable(onClick = onDismiss),
    ) {
        GlassCard(
            Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(end = 10.dp, top = 216.dp)
                .widthIn(max = 340.dp)
                .fillMaxWidth()
                .clickable(enabled = false) {},
            onMap = true,
        ) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Assignment, contentDescription = null, tint = WColors.Primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("任务", style = MaterialTheme.typography.titleMedium, color = WColors.OnBg)
                }
                Spacer(Modifier.height(10.dp))
                if (zones.isEmpty()) {
                    Text("暂无任务圈", style = MaterialTheme.typography.bodyMedium, color = WColors.OnBgDim)
                } else {
                    zones.forEach { z ->
                        val lat = z.centerLat
                        val lon = z.centerLon
                        Text(
                            buildString {
                                append("· ")
                                append(z.note.ifBlank { "任务圈" })
                                append("（半径 %.0f 米".format(z.radiusM ?: 0.0))
                                if (lat != null && lon != null) append("，%.5f, %.5f".format(lat, lon))
                                append("）")
                            },
                            style = MonoText.style,
                            color = WColors.OnBg,
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                }
                if (role == "CAPTAIN") {
                    Spacer(Modifier.height(10.dp))
                    GlassTextButton("矩形划分任务区（分块下发）", Modifier.fillMaxWidth()) { onOpenSplit() }
                    Spacer(Modifier.height(6.dp))
                    GlassTextButton("下发圆形任务圈", Modifier.fillMaxWidth()) { onOpenCircle() }
                    Spacer(Modifier.height(12.dp))
                    Text("队伍成员（点按可转让队长）", style = MaterialTheme.typography.labelMedium, color = WColors.OnBgDim)
                    Spacer(Modifier.height(6.dp))
                    members.forEach { m ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { if (m.userId != AppGraph.userId() && m.role != "CAPTAIN") onTransfer(m) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                m.name + when (m.role) {
                                    "CAPTAIN" -> " · 队长"
                                    "OBSERVER" -> " · 观战"
                                    else -> ""
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = WColors.OnBg,
                            )
                            Spacer(Modifier.weight(1f))
                            Text("${m.sampleCount} 采", style = MonoText.style, color = WColors.OnBgDim)
                        }
                    }
                } else {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "任务圈由队长下发；队员按圈执行走测",
                        style = MaterialTheme.typography.labelSmall,
                        color = WColors.OnBgDim,
                    )
                }
            }
        }
    }
}

/** 团队聊天浮窗 */
@Composable
private fun ChatPanel(
    chats: List<WsMsg.Chat>,
    userId: String,
    readOnly: Boolean,
    onSend: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x66000000))
            .clickable(onClick = onDismiss),
    ) {
        GlassCard(
            Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(end = 10.dp, top = 216.dp)
                .widthIn(max = 340.dp)
                .fillMaxWidth()
                .clickable(enabled = false) {},
            onMap = true,
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.ChatBubble, contentDescription = null, tint = WColors.Secondary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("团队聊天", style = MaterialTheme.typography.titleMedium, color = WColors.OnBg)
                }
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().height(300.dp)) {
                    if (chats.isEmpty()) {
                        Text("还没有消息", style = MaterialTheme.typography.bodyMedium, color = WColors.OnBgDim, modifier = Modifier.align(Alignment.Center))
                    } else {
                        Column(
                            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            chats.forEach { c ->
                                val mine = c.from == userId
                                Column(
                                    Modifier.fillMaxWidth(),
                                    horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
                                ) {
                                    Text(c.fromName, style = MaterialTheme.typography.labelSmall, color = WColors.OnBgDim)
                                    Spacer(Modifier.height(2.dp))
                                    Box(
                                        Modifier
                                            .background(
                                                if (mine) WColors.Primary.copy(alpha = 0.16f) else WColors.Glass,
                                                RoundedCornerShape(12.dp),
                                            )
                                            .padding(horizontal = 10.dp, vertical = 6.dp),
                                    ) {
                                        Text(c.text, style = MaterialTheme.typography.bodyMedium, color = WColors.OnBg)
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                if (readOnly) {
                    Text("观战模式（只读）", style = MaterialTheme.typography.labelMedium, color = WColors.OnBgDim)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it.take(120) },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("发送给队友…", color = WColors.OnBgDim, style = MaterialTheme.typography.bodyMedium) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            textStyle = MaterialTheme.typography.bodyLarge,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = WColors.Primary,
                                unfocusedBorderColor = WColors.GlassBorder,
                                cursorColor = WColors.Primary,
                                focusedTextColor = WColors.OnBg,
                                unfocusedTextColor = WColors.OnBg,
                            ),
                        )
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier
                                .size(42.dp)
                                .mapGlass(shape = CircleShape)
                                .clickable {
                                    val t = input.trim()
                                    if (t.isNotEmpty()) {
                                        input = ""
                                        onSend(t)
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.AutoMirrored.Rounded.Send,
                                contentDescription = "发送",
                                tint = if (input.isNotBlank()) WColors.Primary else WColors.OnBgDim,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 队长圆形任务圈对话框 */
@Composable
private fun ZoneDialog(onDismiss: () -> Unit, onConfirm: (Double, String) -> Unit) {
    var radiusText by remember { mutableStateOf("500") }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("下发任务圈") },
        text = {
            Column {
                Text("以当前地图中心为圆心向全队下发任务范围圈。", style = MaterialTheme.typography.bodyMedium, color = WColors.OnBgDim)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = radiusText,
                    onValueChange = { radiusText = it.filter { ch -> ch.isDigit() }.take(6) },
                    label = { Text("半径（米）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = WColors.Primary,
                        unfocusedBorderColor = WColors.GlassBorder,
                        cursorColor = WColors.Primary,
                        focusedTextColor = WColors.OnBg,
                        unfocusedTextColor = WColors.OnBg,
                    ),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(60) },
                    label = { Text("备注（可选）") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = WColors.Primary,
                        unfocusedBorderColor = WColors.GlassBorder,
                        cursorColor = WColors.Primary,
                        focusedTextColor = WColors.OnBg,
                        unfocusedTextColor = WColors.OnBg,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton({
                val r = radiusText.toDoubleOrNull()
                if (r != null && r > 0) onConfirm(r, note)
            }) { Text("下发") }
        },
        dismissButton = { TextButton(onDismiss) { Text("取消") } },
    )
}

/** 矩形划分任务区对话框：宽/高 + 块数，每块取内切圆下发 */
@Composable
private fun ZoneSplitDialog(onDismiss: () -> Unit, onConfirm: (Double, Double, Int) -> Unit) {
    var widthText by remember { mutableStateOf("400") }
    var heightText by remember { mutableStateOf("300") }
    var blocks by remember { mutableIntStateOf(4) }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = WColors.Primary,
        unfocusedBorderColor = WColors.GlassBorder,
        cursorColor = WColors.Primary,
        focusedTextColor = WColors.OnBg,
        unfocusedTextColor = WColors.OnBg,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("矩形划分任务区") },
        text = {
            Column {
                Text(
                    "以当前地图中心画一个矩形，划分成 N 块，每块取内切圆作为任务圈依次下发给全队。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = WColors.OnBgDim,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = widthText,
                        onValueChange = { widthText = it.filter { ch -> ch.isDigit() }.take(6) },
                        label = { Text("宽（米）") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = RoundedCornerShape(12.dp),
                        colors = fieldColors,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = heightText,
                        onValueChange = { heightText = it.filter { ch -> ch.isDigit() }.take(6) },
                        label = { Text("高（米）") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = RoundedCornerShape(12.dp),
                        colors = fieldColors,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text("划分块数：$blocks", style = MaterialTheme.typography.bodyMedium, color = WColors.OnBg)
                Slider(
                    value = blocks.toFloat(),
                    onValueChange = { blocks = it.toInt() },
                    valueRange = 2f..6f,
                    steps = 3,
                    colors = SliderDefaults.colors(
                        thumbColor = WColors.Primary,
                        activeTrackColor = WColors.Primary,
                        inactiveTrackColor = WColors.Bg2,
                    ),
                )
                Text(
                    "默认 4 块；每块半径取内切圆",
                    style = MaterialTheme.typography.labelSmall,
                    color = WColors.OnBgDim,
                )
            }
        },
        confirmButton = {
            TextButton({
                val w = widthText.toDoubleOrNull()
                val h = heightText.toDoubleOrNull()
                if (w != null && h != null && w > 0 && h > 0) onConfirm(w, h, blocks)
            }) { Text("划分并下发") }
        },
        dismissButton = { TextButton(onDismiss) { Text("取消") } },
    )
}

/** 成员小胶囊：名字 + 角色 + 采样数 + 目标驻留高亮（地图上层，用深色地图玻璃保证可读性） */
@Composable
private fun MemberChip(m: MemberView) {
    val isCaptain = m.role == "CAPTAIN"
    val isObserver = m.role == "OBSERVER"
    val roleTag = when {
        isCaptain -> " ·队长"
        isObserver -> " ·观战"
        else -> ""
    }
    Box(
        Modifier
            .mapGlass(CircleShape)
            .border(
                1.dp,
                when {
                    m.onTarget -> WColors.Success
                    isCaptain -> WColors.Secondary
                    isObserver -> WColors.Warning
                    else -> WColors.GlassBorder
                },
                RoundedCornerShape(999.dp),
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(if (m.onTarget) WColors.Success else WColors.Teammate, CircleShape),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                m.name + roleTag,
                style = MaterialTheme.typography.labelMedium,
                color = WColors.MapLabelFg,
            )
            Spacer(Modifier.width(6.dp))
            Text("${m.sampleCount}采", style = MonoText.style, color = WColors.MapLabelFg)
        }
    }
}
