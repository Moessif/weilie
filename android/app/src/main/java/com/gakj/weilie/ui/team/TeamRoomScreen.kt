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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.CheckCircle
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gakj.weilie.core.cellmap.CellLibraryClient
import com.gakj.weilie.core.cellmap.CellSite as KnownCell
import com.gakj.weilie.core.cellmap.SectorZones
import com.gakj.weilie.core.fitting.AreaBuilder
import com.gakj.weilie.core.fitting.TrackBufferArea
import com.gakj.weilie.core.fitting.Geo
import com.gakj.weilie.core.fitting.ConvexHull
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
import com.gakj.weilie.ui.common.navigateTo
import com.gakj.weilie.ui.common.toast
import com.gakj.weilie.ui.map.MapCanvas
import com.gakj.weilie.ui.map.MapCamera
import com.gakj.weilie.ui.map.CellSearchDialog
import com.gakj.weilie.ui.map.ClosedArea
import com.gakj.weilie.ui.map.HeatLayer
import com.gakj.weilie.ui.map.HeatOverlayBuilder
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    // 跨设备查看：本机没有照片原件时，用验真码向服务器查登记信息
    var detailPhotoCode by remember { mutableStateOf("") }

    // ---- 需求 4：自由绘制任务范围（多边形）并分配给队员 ----
    var drawMode by remember { mutableStateOf(false) }
    val drawPts = remember { mutableStateListOf<Pair<Double, Double>>() }

    // ---- 以基站为中心划区：队长先搜基站，再按"扇形/圈层"把区域分给队员 ----
    var showCellSearch by remember { mutableStateOf(false) }
    var zoneCenter by remember { mutableStateOf<KnownCell?>(null) }
    var zoneModeIdx by remember { mutableIntStateOf(0) }
    var zoneRadiusM by remember { mutableStateOf(800f) }
    var zoneRings by remember { mutableIntStateOf(3) }
    var drawAssignFor by remember { mutableStateOf<List<Pair<Double, Double>>?>(null) }

    // ---- 叠加图层：热力图 / 实测封闭区域 / 基站库 ----
    // 用户反馈「队长身份下这三个开关点了没反应」：面板早就画出了开关，
    // 但多人页从来没接状态与计算（这里补齐），队员端同样可用（本地计算，不占带宽）。
    var heatOn by remember { mutableStateOf(false) }
    var heatParams by remember { mutableStateOf(HeatOverlayBuilder.Params()) }
    var heatLayer by remember { mutableStateOf<HeatLayer?>(null) }
    var areaOn by remember { mutableStateOf(AppGraph.prefs.getBoolean(Route.PREF_AREA_ON, true)) }
    var areaResult by remember { mutableStateOf<AreaBuilder.Result?>(null) }
    // 成面方式与单人模式共用同一套偏好（高精度凹包 / 模拟实测区域）
    var areaMode by remember {
        mutableStateOf(
            AreaBuilder.Mode.entries.firstOrNull {
                it.name == AppGraph.prefs.getString(Route.PREF_AREA_MODE, "")
            } ?: AreaBuilder.Mode.AUTO,
        )
    }
    var areaBufferRadiusM by remember {
        mutableStateOf(AppGraph.prefs.getFloat(Route.PREF_AREA_BUFFER_RADIUS, 30f).toDouble())
    }
    var areaBufferShape by remember {
        mutableStateOf(
            TrackBufferArea.Shape.entries.firstOrNull {
                it.name == AppGraph.prefs.getString(Route.PREF_AREA_BUFFER_SHAPE, "")
            } ?: TrackBufferArea.Shape.CIRCLE,
        )
    }
    var libraryOn by remember { mutableStateOf(false) }
    var librarySites by remember { mutableStateOf<List<KnownCell>>(emptyList()) }
    var selectedCell by remember { mutableStateOf<KnownCell?>(null) }
    val libraryClient = remember { CellLibraryClient(AppGraph.serverHttpUrl()) }
    DisposableEffect(libraryClient) {
        onDispose { runCatching { libraryClient.close() } }
    }
    LaunchedEffect(heatOn, heatParams, samples.size) {
        if (!heatOn) {
            heatLayer = null
            return@LaunchedEffect
        }
        val snapshotSamples = samples
        heatLayer = withContext(Dispatchers.Default) {
            HeatOverlayBuilder.build(snapshotSamples, heatParams, version = System.currentTimeMillis())
        }
    }
    LaunchedEffect(areaOn, areaMode, areaBufferRadiusM, areaBufferShape, samples.size, samples.lastOrNull()?.tsEpochMs) {
        if (!areaOn) {
            areaResult = null
            return@LaunchedEffect
        }
        val snapshotSamples = samples
        val opt = AreaBuilder.Options(
            mode = areaMode,
            bufferRadiusM = areaBufferRadiusM,
            bufferShape = areaBufferShape,
        )
        areaResult = withContext(Dispatchers.Default) { AreaBuilder.build(snapshotSamples, opt) }
    }
    LaunchedEffect(libraryOn, camera.revision, samples.size) {
        if (!libraryOn) {
            librarySites = emptyList()
            return@LaunchedEffect
        }
        delay(800)
        val lat = samples.lastOrNull()?.lat ?: controller.lastLocation?.latitude ?: camera.centerLat
        val lon = samples.lastOrNull()?.lon ?: controller.lastLocation?.longitude ?: camera.centerLon
        val resp = runCatching {
            libraryClient.nearCached(lat, lon, CellLibraryClient.DEFAULT_RADIUS_M, 100)
        }.getOrNull()
        if (resp?.ok == true) {
            librarySites = resp.sites
        } else if (resp != null) {
            toast(ctx, resp.message ?: "基站库查询失败")
        }
    }

    // ---- 需求 5：任务圈/矩形划分以「自己的定位点」为中心 ----
    /** 自己的当前位置：优先最后一次采样，其次采集器最近一次 GPS 定位 */
    fun selfLatLon(): Pair<Double, Double>? {
        samples.lastOrNull()?.let { return it.lat to it.lon }
        controller.lastLocation?.let { return it.latitude to it.longitude }
        return null
    }

    // ---- 需求 6：队长/队员都能「定位到某个队员」或「定位到自己」 ----
    var locateMenu by remember { mutableStateOf(false) }
    fun focusMember(m: MemberView?) {
        locateMenu = false
        if (m == null) {
            val me = selfLatLon()
            if (me == null) {
                toast(ctx, "还没有自己的定位")
            } else {
                camera.focusOn(me.first, me.second)
                AppGraph.prefs.edit().remove(Route.PREF_LOCATE_MEMBER).apply()
            }
        } else {
            val la = m.lat
            val lo = m.lon
            if (la == null || lo == null) {
                toast(ctx, "${m.name} 还没上报位置")
            } else {
                camera.focusOn(la, lo)
                AppGraph.prefs.edit().putString(Route.PREF_LOCATE_MEMBER, m.userId).apply()
                toast(ctx, "已定位到 ${m.name}")
            }
        }
    }

    // ---- 需求 4/5：标点/拍照「点击地图选具体位置」，不再默认落在地图中心 ----
    var placeMode by remember { mutableStateOf<String?>(null) } // FLAG / SUSPICIOUS / PHOTO
    var pickedLatLon by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var navMarker by remember { mutableStateOf<WsMsg.Marker?>(null) }
    // 需求二：新标点先命名 + 选可见范围，再发出去
    var newMarkerAt by remember { mutableStateOf<Triple<Double, Double, String>?>(null) }
    var markerName by remember { mutableStateOf("") }
    var markerVis by remember { mutableIntStateOf(0) } // 0 全队 / 1 仅自己 / 2 指定成员
    val markerVisibleTo = remember { mutableStateListOf<String>() }

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
            markers = state.markers + listOfNotNull(
                zoneCenter?.let { c ->
                    WsMsg.Marker(
                        by = userId,
                        byName = "基站中心",
                        lat = c.lat,
                        lon = c.lon,
                        kind = "FLAG",
                        note = c.title,
                        ts = 0L,
                    )
                },
            ),
            zones = (if (zoneVisible) displayZones else emptyList()) + listOfNotNull(
                if (drawMode && drawPts.size >= 3) {
                    WsMsg.Zone(
                        by = userId,
                        kind = "POLYGON",
                        polygon = drawPts.map { listOf(it.first, it.second) },
                        note = "绘制中",
                        // 必须随内容变化：地图渲染器按 ts 做内容指纹，ts 恒为 1 时
                        // 加点后不会重绘，预览多边形就「不实时」（真机反馈）。
                        ts = drawPts.hashCode().toLong(),
                    )
                } else null,
            ),
            // 绘制中的手指轨迹：单独画成折线，手指滑过即有反馈
            draftPath = if (drawMode) drawPts.toList() else emptyList(),
            camera = camera,
            // 叠加图层（与单人模式同源：热力/封闭区域/基站库）
            heat = heatLayer.takeIf { heatOn },
            area = if (areaOn) {
                areaResult?.takeIf { it.kind != AreaBuilder.Kind.NONE }
                    ?.let { ClosedArea(ring = it.ringLatLon, label = it.kind.label) }
            } else {
                null
            },
            cellLibrary = if (libraryOn) librarySites else emptyList(),
            selectedCellKey = selectedCell?.key,
            onCellSiteClick = { selectedCell = it },
            drawMode = drawMode,
            onDrawPoint = { la, lo ->
                // 单指拖动连续取点（引擎已按 14px 节流），限制 400 点防止过度绘制
                if (drawMode && drawPts.size < 400) drawPts.add(la to lo)
            },
            onTapLatLon = { la, lo ->
                val mode = placeMode
                if (drawMode) {
                    // 轻点也补一个点，方便在触屏上点画
                    if (drawPts.size < 400) drawPts.add(la to lo)
                } else if (mode != null) {
                    placeMode = null
                    pickedLatLon = la to lo
                    if (mode == "PHOTO") {
                        showCamera = true
                    } else {
                        // 需求二：先命名 + 选可见范围（仅自己 / 全队 / 指定成员），确认后再发
                        markerName = ""
                        markerVis = 0
                        markerVisibleTo.clear()
                        newMarkerAt = Triple(la, lo, mode)
                    }
                }
            },
            onMarkerClick = { mk ->
                if (mk.photoId.isNotBlank()) {
                    detailPhotoId = mk.photoId
                    detailPhotoCode = mk.photoCode
                } else navMarker = mk
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
                    heatVisible = heatOn,
                    heatParams = heatParams,
                    onHeatToggle = { heatOn = it },
                    onHeatParams = { heatParams = it },
                    areaVisible = areaOn,
                    onAreaToggle = {
                        areaOn = it
                        AppGraph.prefs.edit().putBoolean(Route.PREF_AREA_ON, it).apply()
                    },
                    areaMode = areaMode,
                    onAreaModeChange = { m ->
                        areaMode = m
                        AppGraph.prefs.edit().putString(Route.PREF_AREA_MODE, m.name).apply()
                    },
                    areaBufferRadiusM = areaBufferRadiusM,
                    onAreaBufferRadius = { r ->
                        areaBufferRadiusM = r
                        AppGraph.prefs.edit().putFloat(Route.PREF_AREA_BUFFER_RADIUS, r.toFloat()).apply()
                    },
                    areaBufferShape = areaBufferShape,
                    onAreaBufferShape = { s ->
                        areaBufferShape = s
                        AppGraph.prefs.edit().putString(Route.PREF_AREA_BUFFER_SHAPE, s.name).apply()
                    },
                    libraryVisible = libraryOn,
                    libraryCount = librarySites.size,
                    onLibraryToggle = { libraryOn = it },
                    onLibrarySearch = { showCellSearch = true },
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

        // ---------- 右侧上部悬浮钮：任务 / 团队聊天 / 定位 ----------
        Column(
            Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(end = 10.dp, top = 150.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GlassIconButton(icon = Icons.Rounded.Assignment, label = "任务", size = 44.dp, onMap = true) { showTaskPanel = !showTaskPanel }
            GlassIconButton(icon = Icons.Rounded.ChatBubble, label = "聊天", size = 44.dp, onMap = true) { showChatPanel = !showChatPanel }
            Box {
                GlassIconButton(icon = Icons.Rounded.MyLocation, label = "定位", size = 44.dp, onMap = true) { locateMenu = true }
                DropdownMenu(
                    expanded = locateMenu,
                    onDismissRequest = { locateMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("定位到我自己") },
                        leadingIcon = { Icon(Icons.Rounded.MyLocation, contentDescription = null, tint = WColors.Primary) },
                        onClick = { focusMember(null) },
                    )
                    val others = state.members.filter { it.userId != userId }
                    if (others.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("还没有其他队员", color = WColors.OnBgDim) },
                            enabled = false,
                            onClick = {},
                        )
                    }
                    others.forEach { m ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    buildString {
                                        append(m.name)
                                        if (m.role == "CAPTAIN") append(" · 队长")
                                        if (m.lat == null || m.lon == null) append(" · 无位置")
                                    },
                                    color = if (m.lat != null && m.lon != null) WColors.OnBg else WColors.OnBgDim,
                                )
                            },
                            leadingIcon = { Icon(Icons.Rounded.Place, contentDescription = null, tint = WColors.Secondary) },
                            onClick = { focusMember(m) },
                        )
                    }
                }
            }
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
                                    placeMode = "FLAG"
                                    toast(ctx, "点击地图选择插旗位置")
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("可疑设备", color = WColors.Danger) },
                                onClick = {
                                    markerMenu = false
                                    placeMode = "SUSPICIOUS"
                                    toast(ctx, "点击地图选择可疑设备位置")
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("进相机取证（带水印）", color = WColors.OnBg) },
                                onClick = {
                                    markerMenu = false
                                    placeMode = "PHOTO"
                                    toast(ctx, "点击地图选择取证位置")
                                },
                            )
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    GlassIconButton(icon = Icons.Rounded.PhotoCamera, label = "拍照", size = 44.dp, onMap = true) {
                        placeMode = "PHOTO"
                        toast(ctx, "点击地图选择取证位置")
                    }
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

        // ---------- 点选位置提示条（需求 4：标点/拍照点地图选位置）----------
        placeMode?.let { mode ->
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 92.dp)
                    .clip(RoundedCornerShape(14.dp))
                    // 跟随主题：浅色主题下原来是深底 + 近黑字（不可读），改为白玻璃 + OnBg 文字
                    .background(WColors.Bg1.copy(alpha = 0.94f), RoundedCornerShape(14.dp))
                    .border(1.dp, WColors.GlassBorder, RoundedCornerShape(14.dp))
                    .clickable { placeMode = null }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Place, contentDescription = null, tint = WColors.Primary, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    "点击地图选择${if (mode == "PHOTO") "取证" else if (mode == "FLAG") "插旗" else "可疑设备"}位置 · 点此取消",
                    style = MaterialTheme.typography.labelMedium,
                    color = WColors.OnBg,
                )
            }
        }

        // ---------- 自由绘制任务范围的操作条（需求 4）----------
        if (drawMode) {
            // 实时面积：≥3 点即按鞋带公式算（局部平面近似，任务区尺度足够准）
            val drawArea = if (drawPts.size >= 3) {
                val lat0 = drawPts.first().first
                val lon0 = drawPts.first().second
                runCatching { ConvexHull.area(drawPts.map { Geo.toLocal(it.first, it.second, lat0, lon0) }) }.getOrDefault(0.0)
            } else 0.0
            val areaLabel = if (drawArea > 0.0) " · ${fmtArea(drawArea)}" else ""
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 78.dp)
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(16.dp))
                    // 同上：绘制操作条也要跟随主题，浅色主题不能再压深底
                    .background(WColors.Bg1.copy(alpha = 0.94f), RoundedCornerShape(16.dp))
                    .border(1.dp, WColors.GlassBorder, RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "单指按住拖动画范围（双指拖动地图）· 已 ${drawPts.size} 个点$areaLabel",
                    style = MaterialTheme.typography.labelMedium,
                    color = WColors.OnBg,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassTextButton("撤销一点") { if (drawPts.isNotEmpty()) drawPts.removeAt(drawPts.lastIndex) }
                    GlassTextButton("重画") { drawPts.clear() }
                    GlassTextButton("取消") { drawMode = false; drawPts.clear() }
                    GlassTextButton(
                        "完成",
                        onClick = {
                            if (drawPts.size < 3) {
                                toast(ctx, "至少 3 个点才能成面（当前 ${drawPts.size} 个）")
                            } else {
                                drawAssignFor = drawPts.toList()
                            }
                        },
                    )
                }
            }
        }

        // ---------- 任务浮窗 ----------
        if (showTaskPanel) {
            TaskPanel(
                role = myRole,
                members = state.members,
                zones = displayZones,
                onDismiss = { showTaskPanel = false },
                onOpenDraw = {
                    showTaskPanel = false
                    drawPts.clear()
                    drawMode = true
                    toast(ctx, "在地图上依次点选边界顶点，至少 3 个点后点“完成”")
                },
                onOpenCellZone = { showTaskPanel = false; showCellSearch = true },
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

    // ---------- 自由绘制完成：选择把这块范围分给谁（需求 4）----------
    drawAssignFor?.let { pts ->
        AlertDialog(
            onDismissRequest = { drawAssignFor = null },
            title = { Text("分配给谁") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "自由绘制范围（${pts.size} 个顶点）将以多边形任务区下发：",
                        style = MaterialTheme.typography.labelMedium,
                        color = WColors.OnBgDim,
                    )
                    Spacer(Modifier.height(8.dp))
                    val candidates = listOf<String?>(null) +
                        state.members.filter { it.role != "OBSERVER" }.map { it.userId }
                    candidates.forEach { uid ->
                        val label = if (uid == null) "全队（不指定）" else
                            (state.members.firstOrNull { it.userId == uid }?.name ?: uid)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    drawAssignFor = null
                                    drawMode = false
                                    drawPts.clear()
                                    scope.launch {
                                        val z = WsMsg.Zone(
                                            by = userId,
                                            kind = "POLYGON",
                                            polygon = pts.map { listOf(it.first, it.second) },
                                            assignees = listOfNotNull(uid),
                                            note = if (uid == null) "自由绘制任务区" else "自由绘制任务区（$label）",
                                            replace = true,
                                            ts = System.currentTimeMillis(),
                                        )
                                        runCatching { room.send(z) }
                                        localZones.add(z)
                                        toast(ctx, "已下发自由绘制任务区 · $label")
                                    }
                                }
                                .padding(vertical = 9.dp),
                        ) {
                            Text(label, style = MaterialTheme.typography.bodyMedium, color = WColors.OnBg)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton({ drawAssignFor = null }) { Text("取消") } },
        )
    }

    // ---------- 以基站为中心划区：先搜基站 ----------
    if (showCellSearch) {
        CellSearchDialog(
            onDismiss = { showCellSearch = false },
            onPickSite = { site ->
                showCellSearch = false
                zoneCenter = site
            },
            initLat = selfLatLon()?.first ?: camera.centerLat,
            initLon = selfLatLon()?.second ?: camera.centerLon,
        )
    }

    // ---------- 以基站为中心划区：选择切分方式并下发 ----------
    zoneCenter?.let { site ->
        val participants = state.members.filter { it.role != "OBSERVER" }
        AlertDialog(
            onDismissRequest = { zoneCenter = null },
            title = { Text("以基站为中心划分任务区") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(site.title, style = MaterialTheme.typography.bodyMedium, color = WColors.OnBg)
                    Text(site.cellText, style = MonoText.style, color = WColors.OnBgDim)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "众包坐标约百米量级，仅作参考中心；要确认实际覆盖请以实测为准。",
                        style = MaterialTheme.typography.labelSmall,
                        color = WColors.OnBgDim,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("切分方式", style = MaterialTheme.typography.labelSmall, color = WColors.OnBgDim)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(SectorZones.Mode.SECTOR, SectorZones.Mode.RING).forEachIndexed { i, m ->
                            val sel = zoneModeIdx == i
                            Text(
                                m.title,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (sel) WColors.OnPrimary else WColors.OnBgDim,
                                modifier = Modifier
                                    .background(
                                        if (sel) WColors.Primary else WColors.ChipBg,
                                        RoundedCornerShape(10.dp),
                                    )
                                    .clickable { zoneModeIdx = i }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (zoneModeIdx == 0) {
                            "按参与人数切成 ${participants.size} 个扇形（一人守一个方向）"
                        } else {
                            "同心圈层：先控中心，再逐层外扩"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = WColors.OnBgDim,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "半径 ${zoneRadiusM.toInt()} 米",
                        style = MaterialTheme.typography.labelSmall,
                        color = WColors.OnBgDim,
                    )
                    Slider(
                        value = zoneRadiusM,
                        onValueChange = { zoneRadiusM = it },
                        valueRange = 300f..2000f,
                        colors = SliderDefaults.colors(
                            thumbColor = WColors.Primary,
                            activeTrackColor = WColors.Primary,
                        ),
                    )
                    if (zoneModeIdx == 1) {
                        Text(
                            "圈层数 ${zoneRings}",
                            style = MaterialTheme.typography.labelSmall,
                            color = WColors.OnBgDim,
                        )
                        Slider(
                            value = zoneRings.toFloat(),
                            onValueChange = { zoneRings = it.toInt().coerceIn(2, 4) },
                            valueRange = 2f..4f,
                            steps = 1,
                            colors = SliderDefaults.colors(
                                thumbColor = WColors.Primary,
                                activeTrackColor = WColors.Primary,
                            ),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (participants.isEmpty()) {
                            "房间里还没有可分配的队员"
                        } else {
                            "将分给：" + participants.joinToString("、") { it.name }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (participants.isEmpty()) WColors.Warning else WColors.OnBgDim,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        KnownCell.SOURCE_ATTRIBUTION,
                        style = MaterialTheme.typography.labelSmall,
                        color = WColors.OnBgDim.copy(alpha = 0.75f),
                    )
                }
            },
            confirmButton = {
                TextButton({
                    val mode = SectorZones.Mode.entries[zoneModeIdx]
                    if (participants.isEmpty()) {
                        toast(ctx, "还没有可分配的队员")
                    } else {
                        val radius = zoneRadiusM.toDouble()
                        val polys = if (mode == SectorZones.Mode.SECTOR) {
                            SectorZones.sectors(site.lat, site.lon, radius, participants.size)
                        } else {
                            SectorZones.rings(site.lat, site.lon, radius, zoneRings)
                        }
                        val now = System.currentTimeMillis()
                        scope.launch {
                            polys.forEachIndexed { i, poly ->
                                val uid = participants[i % participants.size].userId
                                val label = SectorZones.labelFor(mode, i, polys.size)
                                val z = WsMsg.Zone(
                                    by = userId,
                                    kind = "POLYGON",
                                    polygon = poly.map { listOf(it.first, it.second) },
                                    assignees = listOf(uid),
                                    note = "任务：${site.title} · $label",
                                    // 只在第一块清空旧任务区：服务端 replace=true 会 clear()，逐块都 true 就只剩最后一块
                                    replace = i == 0,
                                    ts = now + i,
                                )
                                runCatching { room.send(z) }
                                localZones.add(z)
                            }
                            toast(ctx, "已按${mode.title} 下发 ${polys.size} 块任务区")
                        }
                        zoneCenter = null
                    }
                }) { Text("下发任务区") }
            },
            dismissButton = { TextButton({ zoneCenter = null }) { Text("取消") } },
        )
    }

    // ---------- 圆形任务圈下发 ----------
    if (showZoneDialog) {
        ZoneDialog(
            onDismiss = { showZoneDialog = false },
            onConfirm = { r, note ->
                showZoneDialog = false
                scope.launch {
                    // 任务圈以「自己的定位点」为中心（用户反馈：原来以地图中心，画完还得拖地图找人）
                    val me = selfLatLon()
                    if (me == null) toast(ctx, "还没拿到自己的定位，先用地图中心")
                    val z = WsMsg.Zone(
                        by = userId,
                        kind = "CIRCLE",
                        centerLat = me?.first ?: camera.centerLat,
                        centerLon = me?.second ?: camera.centerLon,
                        radiusM = r,
                        note = note,
                        replace = true, // 单块任务圈：清空旧任务区
                        ts = System.currentTimeMillis(),
                    )
                    room.send(z)
                    localZones.add(z)
                    toast(ctx, "任务圈已下发（以我的位置为中心，半径 %.0f 米）".format(r))
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
                    // 矩形网格同样以「自己的定位点」为中心
                    val me = selfLatLon()
                    if (me == null) toast(ctx, "还没拿到自己的定位，先用地图中心")
                    val centerLat = me?.first ?: camera.centerLat
                    val centerLon = me?.second ?: camera.centerLon
                    val cols = ceil(kotlin.math.sqrt(blocks.toDouble())).toInt().coerceAtLeast(1)
                    val rows = ceil(blocks.toDouble() / cols).toInt().coerceAtLeast(1)
                    val latSpan = heightM / 110_540.0
                    val lonSpan = widthM / (111_320.0 * cos(Math.toRadians(centerLat)))
                    val radius = minOf(widthM / cols, heightM / rows) / 2.0
                    val now = System.currentTimeMillis()
                    for (i in 0 until blocks) {
                        val row = i / cols
                        val col = i % cols
                        val lat = centerLat + ((rows - 1) / 2.0 - row) * latSpan
                        val lon = centerLon + (col - (cols - 1) / 2.0) * lonSpan
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

    // ---------- 新建标点：命名 + 可见范围（需求二）----------
    newMarkerAt?.let { (la, lo, mode) ->
        val isFlag = mode == "FLAG"
        val others = state.members.filter { it.userId != userId && it.role != "OBSERVER" }
        AlertDialog(
            onDismissRequest = { newMarkerAt = null },
            title = { Text(if (isFlag) "新建标点" else "标记可疑设备") },
            text = {
                Column(Modifier.heightIn(max = 420.dp)) {
                    OutlinedTextField(
                        value = markerName,
                        onValueChange = { markerName = it.take(24) },
                        label = { Text("标点名称") },
                        placeholder = { Text(if (isFlag) "如：3 号楼配电房" else "如：隐蔽摄像头") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("谁能看到", style = MaterialTheme.typography.labelMedium, color = WColors.OnBgDim)
                    Spacer(Modifier.height(5.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("全队可见", "仅自己", "指定成员").forEachIndexed { i, label ->
                            val on = markerVis == i
                            Box(
                                Modifier
                                    .background(
                                        if (on) WColors.Primary.copy(alpha = 0.18f) else WColors.ChipBg,
                                        RoundedCornerShape(9.dp),
                                    )
                                    .clickable { markerVis = i }
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                            ) {
                                Text(
                                    label,
                                    style = MonoText.style.copy(fontSize = 11.sp),
                                    color = if (on) WColors.Primary else WColors.OnBgDim,
                                )
                            }
                        }
                    }
                    if (markerVis == 2) {
                        Spacer(Modifier.height(8.dp))
                        if (others.isEmpty()) {
                            Text(
                                "房间里还没有其他队员，可先选「仅自己」",
                                style = MaterialTheme.typography.labelSmall,
                                color = WColors.OnBgDim,
                            )
                        } else {
                            Column(
                                Modifier
                                    .heightIn(max = 150.dp)
                                    .verticalScroll(rememberScrollState()),
                            ) {
                                others.forEach { m ->
                                    val picked = m.userId in markerVisibleTo
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                if (picked) markerVisibleTo.remove(m.userId)
                                                else markerVisibleTo.add(m.userId)
                                            }
                                            .padding(vertical = 7.dp, horizontal = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            if (picked) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                                            contentDescription = null,
                                            tint = if (picked) WColors.Primary else WColors.OnBgDim,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(m.name, style = MaterialTheme.typography.bodyMedium, color = WColors.OnBg)
                                        Spacer(Modifier.weight(1f))
                                        Text(
                                            if (m.role == "CAPTAIN") "队长" else "队员",
                                            style = MonoText.style.copy(fontSize = 10.sp),
                                            color = WColors.OnBgDim,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "名称会显示在地图与标点详情里；选「仅自己」时其他队员看不到这个标点。",
                        style = MaterialTheme.typography.labelSmall,
                        color = WColors.OnBgDim,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val visibleTo = when (markerVis) {
                            1 -> listOf(userId)
                            2 -> markerVisibleTo.toList()
                            else -> emptyList()
                        }
                        val finalName = markerName.trim()
                        scope.launch {
                            // 需求 4：标点与任务范围关联——把当前分配给我的任务名写进标点备注
                            val zoneNote = state.zones
                                .firstOrNull { it.assignees.isEmpty() || userId in it.assignees }
                                ?.note?.takeIf { it.isNotBlank() }
                            room.marker(
                                la, lo, mode,
                                zoneNote?.let { "任务：$it" } ?: "",
                                userId, name,
                                markerName = finalName,
                                visibleTo = visibleTo,
                            )
                        }
                        toast(
                            ctx,
                            when (markerVis) {
                                1 -> "已标点（仅自己可见）"
                                2 -> "已标点（${markerVisibleTo.size} 人可见）"
                                else -> "已标点（全队可见）"
                            },
                        )
                        newMarkerAt = null
                    },
                    enabled = markerVis != 2 || markerVisibleTo.isNotEmpty(),
                ) { Text("确定") }
            },
            dismissButton = { TextButton({ newMarkerAt = null }) { Text("取消") } },
        )
    }

    // ---------- 标点操作：导航 / 删除（需求 5 + 需求二）----------
    navMarker?.let { mk ->
        val isCaptain = state.members.firstOrNull { it.userId == userId }?.role == "CAPTAIN"
        val canDelete = mk.by == userId || isCaptain
        val visText = when {
            mk.visibleTo.isEmpty() -> "全队可见"
            mk.visibleTo == listOf(mk.by) -> "仅自己可见"
            else -> "指定 ${mk.visibleTo.size} 人可见"
        }
        AlertDialog(
            onDismissRequest = { navMarker = null },
            title = { Text(mk.name.ifBlank { if (mk.kind == "SUSPICIOUS") "可疑设备标点" else "标点" }) },
            text = {
                Column {
                    Text("记录人 ${mk.byName} · $visText", style = MaterialTheme.typography.labelMedium, color = WColors.OnBgDim)
                    Spacer(Modifier.height(6.dp))
                    Text(if (mk.note.isNotBlank()) mk.note else "无备注", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "坐标 " + String.format(java.util.Locale.US, "%.6f, %.6f", mk.lat, mk.lon),
                        style = MonoText.style.copy(fontSize = 11.sp),
                        color = WColors.OnBgDim,
                    )
                }
            },
            confirmButton = {
                TextButton({
                    navigateTo(ctx, mk.lat, mk.lon, mk.name.ifBlank { "队友标点" })
                    navMarker = null
                }) { Text("导航到此") }
            },
            dismissButton = {
                Row {
                    if (canDelete) {
                        TextButton({
                            scope.launch { room.deleteMarker(mk.id, userId) }
                            toast(ctx, "已删除标点")
                            navMarker = null
                        }) { Text("删除", color = WColors.Danger) }
                    }
                    TextButton({ navMarker = null }) { Text("关闭") }
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
        val pin = pickedLatLon
        PhotoCameraScreen(
            initialKind = "PHOTO",
            currentLat = pin?.first ?: fix?.lat ?: camera.centerLat,
            currentLon = pin?.second ?: fix?.lon ?: camera.centerLon,
            hasFix = pin != null || fix != null,
            taskId = "room-$roomId",
            onClose = { showCamera = false; pickedLatLon = null },
            onCaptured = { rec ->
                showCamera = false
                val chosen = pickedLatLon
                pickedLatLon = null
                scope.launch {
                    // 先登记验真码再广播：队友没有本机照片文件，只能靠 code 向服务器查登记信息
                    val file = runCatching { AppGraph.photoStore.fileOf(rec) }.getOrNull()
                    val code = if (file != null && file.isFile) {
                        runCatching { AppGraph.photoVerifier.register(file, rec) }.getOrNull()?.first
                            ?: AppGraph.verifyStore.byPhotoId(rec.id)?.code.orEmpty()
                    } else {
                        AppGraph.verifyStore.byPhotoId(rec.id)?.code.orEmpty()
                    }
                    room.photo(
                        WsMsg.Marker(
                            by = userId,
                            byName = name,
                            // 点了地图就以点选位置为准（需求 4），没点选才退回照片自带定位
                            lat = chosen?.first ?: rec.lat,
                            lon = chosen?.second ?: rec.lon,
                            kind = rec.kind,
                            note = rec.title,
                            ts = rec.ts,
                            photoId = rec.id,
                            deviceType = rec.deviceType,
                            photoCode = code,
                        ),
                    )
                }
                toast(ctx, "已上传取证照片，队友地图上会同步出现标点")
            },
            onOpenGallery = {
                AppGraph.photoStore.latest()?.let {
                    detailPhotoId = it.id
                    detailPhotoCode = AppGraph.verifyStore.byPhotoId(it.id)?.code.orEmpty()
                } ?: toast(ctx, "还没有取证照片")
            },
        )
    }

    detailPhotoId?.let { pid ->
        // 删除权限与服务端一致：创建者本人或队长（队长可清队员的标点）
        val deletable = state.markers
            .firstOrNull { it.photoId == pid }
            ?.takeIf { it.by == userId || myRole == "CAPTAIN" }
        PhotoDetailSheet(
            photoId = pid,
            remoteCode = detailPhotoCode,
            onDelete = deletable?.let { mk ->
                {
                    scope.launch { runCatching { room.deleteMarker(mk.id, userId) } }
                    toast(
                        ctx,
                        if (myRole == "CAPTAIN" && mk.by != userId) "已删除该队员的标点" else "已删除标点",
                    )
                    detailPhotoId = null
                    detailPhotoCode = ""
                }
            },
        ) {
            detailPhotoId = null
            detailPhotoCode = ""
        }
    }
}

/** 任务浮窗：任务区列表（协议 v1.1 zones）+ 队长操作 + 成员（点按转让） */
@Composable
private fun TaskPanel(
    role: String,
    members: List<MemberView>,
    zones: List<WsMsg.Zone>,
    onDismiss: () -> Unit,
    onOpenDraw: () -> Unit,
    onOpenCellZone: () -> Unit,
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
                                if (z.kind == "POLYGON") {
                                    append("（多边形 ${z.polygon.size} 顶点")
                                } else {
                                    append("（半径 %.0f 米".format(z.radiusM ?: 0.0))
                                    if (lat != null && lon != null) append("，%.5f, %.5f".format(lat, lon))
                                }
                                if (z.assignees.isNotEmpty()) append(" · 分配给 ${z.assignees.joinToString(",")}")
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
                    GlassTextButton("自由绘制任务范围（点地图落点）", Modifier.fillMaxWidth()) { onOpenDraw() }
                    Spacer(Modifier.height(6.dp))
                    GlassTextButton("以基站为中心划区（搜索基站位置）", Modifier.fillMaxWidth()) { onOpenCellZone() }
                    Spacer(Modifier.height(6.dp))
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
                Text("以我的位置为圆心向全队下发任务范围圈（还没定位时退回地图中心）。", style = MaterialTheme.typography.bodyMedium, color = WColors.OnBgDim)
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
                    "以我的位置为中心画一个矩形，划分成 N 块，每块取内切圆作为任务圈依次下发给全队（还没定位时退回地图中心）。",
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
/** 面积口径：小于 1 公顷显示平方米，否则公顷（两位小数），给绘制条实时看大小 */
private fun fmtArea(m2: Double): String =
    if (m2 < 10_000.0) "${m2.toInt()} m²" else String.format(java.util.Locale.US, "%.2f 公顷", m2 / 10_000.0)
