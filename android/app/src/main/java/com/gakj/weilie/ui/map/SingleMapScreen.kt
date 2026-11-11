package com.gakj.weilie.ui.map

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.Manifest
import android.view.PixelCopy
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.gakj.weilie.R
import com.gakj.weilie.core.ai.AiClient
import com.gakj.weilie.core.cellmap.CellLibraryClient
import com.gakj.weilie.core.cellmap.CellSite as KnownCell
import com.gakj.weilie.core.fitting.AreaBuilder
import com.gakj.weilie.core.fitting.TrackBufferArea
import com.gakj.weilie.core.fitting.CoverageFitter
import com.gakj.weilie.core.fitting.CoverageResult
import com.gakj.weilie.core.fitting.Geo
import com.gakj.weilie.core.fitting.TheorySpec
import com.gakj.weilie.core.parsing.TaskInputParser
import com.gakj.weilie.core.rating.SignalRating
import com.gakj.weilie.core.sampling.SamplingController
import com.gakj.weilie.core.sync.WsMsg
import com.gakj.weilie.core.telephony.NetMode
import com.gakj.weilie.core.tracking.TrackState
import com.gakj.weilie.service.SamplingForegroundService
import com.gakj.weilie.service.SilentTrackService
import com.gakj.weilie.ui.AppGraph
import com.gakj.weilie.ui.Route
import com.gakj.weilie.ui.ai.AiSheet
import com.gakj.weilie.ui.camera.PhotoCameraScreen
import com.gakj.weilie.ui.camera.PhotoDetailSheet
import com.gakj.weilie.ui.common.GlassIconButton
import com.gakj.weilie.ui.common.KeyValueRow
import com.gakj.weilie.ui.common.StatusBadge
import com.gakj.weilie.ui.common.exportCsvAndShare
import com.gakj.weilie.ui.common.exportGeoJsonAndShare
import com.gakj.weilie.ui.common.exportKmlAndShare
import com.gakj.weilie.ui.common.fmtDur
import com.gakj.weilie.ui.common.fmtTs
import com.gakj.weilie.ui.common.hasLocationPermission
import com.gakj.weilie.ui.common.hasPerm
import com.gakj.weilie.ui.common.navigateTo
import com.gakj.weilie.ui.common.shareFile
import com.gakj.weilie.ui.common.toast
import com.gakj.weilie.ui.common.vibrate
import com.gakj.weilie.ui.expert.ExpertPanel
import com.gakj.weilie.ui.theme.GlassCard
import com.gakj.weilie.ui.theme.GlowButton
import com.gakj.weilie.ui.theme.MonoText
import com.gakj.weilie.ui.theme.PulsingDot
import com.gakj.weilie.ui.theme.Space
import com.gakj.weilie.ui.theme.WColors
import com.gakj.weilie.ui.theme.mapGlass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 单人走测地图屏（STRICT_SPEC §2 全量编排）：
 * - 2.1 顶部悬浮数据栏：上下两半槽位可切换 + 整体显示合并 + 专家/普通切换 + 切基站白话通知
 * - 2.2 左上图层按钮：普通/卫星/轨迹/基站 四选一（普通与卫星经 MapEngine 抽象切换）
 * - 2.3 左下工具箱 FAB：A1 切基站 / A2 自定义数据显示 / A3 模式+组队 / A4 十项工具网格；
 *      简单功能原地展示、复杂功能弹玻璃浮窗；右下默认 AI 悬浮钮 + 最多 3 个固定小组件
 */
@Composable
fun SingleMapScreen(
    taskId: String,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTeam: () -> Unit,
) {
    val ctx = LocalContext.current

    // ---------- 任务控制器（A1 切基站时整体替换） ----------
    var overrideCtrl by remember { mutableStateOf<SamplingController?>(null) }
    var freshStart by remember { mutableStateOf(false) }
    val controller = overrideCtrl ?: remember(taskId) {
        val cur = AppGraph.currentController
        if (cur != null && cur.task.id == taskId) {
            freshStart = false
            cur
        } else {
            freshStart = true
            AppGraph.ensure(ctx, taskId, AppGraph.pendingTask).also { AppGraph.pendingTask = null }
        }
    }
    val task = controller.task
    val samples by controller.samples.collectAsState()
    val snapshot by controller.snapshot.collectAsState()
    val running by controller.running.collectAsState()
    val events by controller.events.collectAsState()
    val camera = remember { MapCamera() }

    var fit by remember { mutableStateOf<CoverageResult?>(null) }
    var showTools by remember { mutableStateOf(false) }
    var showAi by remember { mutableStateOf(false) }
    var showLayers by remember { mutableStateOf(false) }
    // 顶部 chrome（数据栏 + 图层/目标按钮）实测高度：图层浮层据此落在它下面，而不是盖住数据栏
    var topChromePx by remember { mutableIntStateOf(0) }
    var showTargetInfo by remember { mutableStateOf(false) }
    var switchCell by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }
    var inlineTool by remember { mutableStateOf<ToolId?>(null) }
    var popup by remember { mutableStateOf<ToolPopup?>(null) }
    var layerMode by remember { mutableStateOf(MapLayerMode.NORMAL) }

    // ---- 需求 6：信号热力图（独立开关 + 间隔/半径/透明度可调） ----
    var heatOn by remember { mutableStateOf(false) }
    var heatParams by remember { mutableStateOf(HeatOverlayBuilder.Params()) }
    var heatLayer by remember { mutableStateOf<HeatLayer?>(null) }

    // ---- 需求 7：基站理论覆盖区（蓝=理论区 / 橙=TA 距离带） ----
    var theory by remember { mutableStateOf<TheorySpec?>(null) }
    var showTheory by remember { mutableStateOf(false) }

    // ---- 需求 8：实心点封闭区域 ----
    // 实测封闭区域：默认开启，随采样实时刷新（用户反馈「打的点没有被实时围成面积」）
    var areaOn by remember { mutableStateOf(AppGraph.prefs.getBoolean(Route.PREF_AREA_ON, true)) }
    var areaResult by remember { mutableStateOf<AreaBuilder.Result?>(null) }
    // 成面方式：高精度凹包 / 模拟实测区域（轨迹点画圆或方块连成片），选择记住
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
    val areaBuildOptions = remember(areaMode, areaBufferRadiusM, areaBufferShape) {
        AreaBuilder.Options(
            mode = areaMode,
            bufferRadiusM = areaBufferRadiusM,
            bufferShape = areaBufferShape,
        )
    }

    // ---- 基站位置库图层（OpenCelliD 众包坐标，参考图层） ----
    var libraryOn by remember { mutableStateOf(false) }
    var librarySites by remember { mutableStateOf<List<KnownCell>>(emptyList()) }
    var selectedCell by remember { mutableStateOf<KnownCell?>(null) }
    var showCellSearch by remember { mutableStateOf(false) }
    val libraryClient = remember { CellLibraryClient(AppGraph.serverHttpUrl()) }
    DisposableEffect(libraryClient) {
        onDispose { runCatching { libraryClient.close() } }
    }

    // ---- 需求 10：静默跟踪 ----
    val silentRunning by SilentTrackService.running.collectAsState()
    val silentState by SilentTrackService.state.collectAsState()
    // 需求四：震动强度（1 轻 / 3 中 / 5 强），与前台服务里的静态值同步
    var silentStrength by remember { mutableIntStateOf(SilentTrackService.strength) }
    val silentStateText = when (silentState) {
        TrackState.MAIN -> "主区命中"
        TrackState.NEIGHBOR -> "邻区命中"
        TrackState.NONE -> "脱离目标"
    }

    // ---- 需求 2：GPS 双重安检提示 ----
    val gateNotice by controller.gateNotice.collectAsState()
    var expertMode by remember { mutableStateOf(false) }
    var startedAt by remember { mutableStateOf(System.currentTimeMillis()) }
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    val vibEnabled = remember { mutableStateOf(AppGraph.prefs.getBoolean(Route.PREF_VIBRATE, false)) }
    val cellAlertOn = remember { mutableStateOf(AppGraph.prefs.getBoolean(Route.PREF_CELL_ALERT, true)) }
    var lastNotice by remember { mutableStateOf<String?>(null) }
    val seenEvents = remember { mutableIntStateOf(0) }
    var framedOnce by remember { mutableStateOf(false) }
    // 用不可变 List + 重新赋值：mutableStateListOf 的内容变化对「只把列表当参数往下传」的
    // MapCanvas 不会触发重组，导致删除标点后地图上还留着（真机反馈）。
    var markers by remember { mutableStateOf<List<WsMsg.Marker>>(emptyList()) }
    val photoPoints = remember { mutableStateListOf<String>() }

    // 用户从地图上移除过的取证标点 id（照片文件保留——它是证据；只是不再显示/回填）
    var hiddenPhotoMarkers by remember { mutableStateOf(readHiddenPhotoMarkers()) }
    fun hidePhotoMarker(id: String) {
        if (id.isBlank()) return
        hiddenPhotoMarkers = hiddenPhotoMarkers + id
        AppGraph.prefs.edit()
            .putString(Route.PREF_HIDDEN_PHOTO_MARKERS, hiddenPhotoMarkers.joinToString("\u0001"))
            .apply()
    }

    // 进入任务时回填取证标点：只回填「本任务」拍的照片。
    // 用户反馈：换新任务后，上一个任务的拍照标点还留在原来的位置——所以按 taskId 归属过滤；
    // 老版本记录没有 taskId，用拍摄时间 >= 任务开始时间兜底（恢复任务时 createdAt 取 manifest 的开始时间，
    // 因此继续旧任务仍能看到它自己的标点）。
    LaunchedEffect(task.id) {
        // 换任务时先把「手动标点」清掉：上一个任务插的小旗不应跟着新任务跑
        markers = markers.filterNot { it.photoId.isBlank() }
        runCatching {
            val restored = AppGraph.photoStore.list()
                .filter { rec -> rec.id !in hiddenPhotoMarkers && markers.none { it.photoId == rec.id } }
                .filter { rec -> rec.taskId == task.id || (rec.taskId.isBlank() && rec.ts >= task.createdAtEpochMs) }
                .sortedBy { it.ts }
                .map { rec ->
                    WsMsg.Marker(
                        by = AppGraph.userId(),
                        byName = if (rec.kind == "SUSPICIOUS") "可疑设备" else "拍照标点",
                        lat = rec.lat,
                        lon = rec.lon,
                        kind = rec.kind,
                        note = rec.title,
                        ts = rec.ts,
                        photoId = rec.id,
                        deviceType = rec.deviceType,
                    )
                }
            if (restored.isNotEmpty()) markers = (markers + restored).sortedBy { it.ts }
        }
    }
    // 点击地图标点 → 打开该标点的取证记录（照片/可疑设备）
    var detailPhotoId by remember { mutableStateOf<String?>(null) }

    // 需求 5：单人模式下也能在地图上自己标点并导航（点「标点」按钮 → 点地图落点）
    var placeMarker by remember { mutableStateOf(false) }
    // 需求二：单人模式标点也要能命名、能删除
    var pendingFlagAt by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var flagName by remember { mutableStateOf("") }
    var detailNotice by remember { mutableStateOf<WsMsg.Marker?>(null) }

    // 自由摆放的工具悬浮钮（长按拖出，归一化坐标持久化，最多 6 个）
    val density = LocalDensity.current
    val densityF = density.density
    val btnPx = 44.dp.value * densityF
    val placedTools = remember { mutableStateListOf<PlacedTool>() }
    remember {
        val raw = AppGraph.prefs.getString(Route.PREF_PLACED_TOOLS, null)
        if (!raw.isNullOrEmpty()) {
            raw.split(";").filter { it.isNotBlank() }.forEach { seg ->
                val parts = seg.split(":")
                val tool = runCatching { ToolId.valueOf(parts[0]) }.getOrNull()
                val xy = parts.getOrNull(1)?.split(",")?.mapNotNull { it.toFloatOrNull() }
                if (tool != null && xy != null && xy.size == 2) {
                    placedTools.add(PlacedTool(tool, xy[0], xy[1]))
                }
            }
        } else {
            // 旧版 pinned（右下竖排固定）迁移到自由摆放：右侧竖排默认位，迁完清掉旧 key
            val legacy = (AppGraph.prefs.getString(Route.PREF_PINNED_TOOLS, "") ?: "")
                .split(",").filter { it.isNotBlank() }
                .mapNotNull { runCatching { ToolId.valueOf(it) }.getOrNull() }
                .filter { it != ToolId.AI }
            val ys = listOf(0.45f, 0.52f, 0.59f)
            legacy.take(3).forEachIndexed { i, t -> placedTools.add(PlacedTool(t, 0.92f, ys[i])) }
            if (legacy.isNotEmpty()) {
                AppGraph.prefs.edit()
                    .putString(Route.PREF_PLACED_TOOLS, placedTools.joinToString(";") { "${it.tool.name}:${it.x},${it.y}" })
                    .remove(Route.PREF_PINNED_TOOLS)
                    .apply()
            }
        }
        true
    }
    // 拖拽进行中的工具（fromIndex = placedTools 下标，-1 = 从工具箱拖出）
    var drag by remember { mutableStateOf<DragInfo?>(null) }

    fun savePlaced() {
        AppGraph.prefs.edit()
            .putString(Route.PREF_PLACED_TOOLS, placedTools.joinToString(";") { "${it.tool.name}:${it.x},${it.y}" })
            .apply()
    }

    // 根布局像素尺寸（拖拽落点判定用，BoxWithConstraints 里同步）
    var rootW by remember { mutableStateOf(1f) }
    var rootH by remember { mutableStateOf(1f) }

    // 顶部删除区命中判定：与屏幕上那条红色删除条的实际范围对齐（略微放宽，避免“贴边不生效”）
    fun overTrash(p: Offset): Boolean {
        val hy = 132.dp.value * densityF
        val hx = 64.dp.value * densityF
        return p.y <= hy && p.x >= rootW / 2 - hx && p.x <= rootW / 2 + hx
    }

    fun dropAt(tool: ToolId, pos: Offset, fromIndex: Int) {
        // 拖到顶部垃圾桶：已放置的移除，从工具箱拖出的视为取消
        if (overTrash(pos)) {
            if (fromIndex >= 0) {
                placedTools.removeAt(fromIndex)
                savePlaced()
                toast(ctx, "已移除「${tool.label}」")
            }
            drag = null
            return
        }
        // 允许放到屏幕任意位置（安全区由下面的 clamp 负责）；
        // 唯一例外：拖动过程中手指还在工具箱面板上（面板已半透明，但要放回面板=取消），
        // 由调用方在 onDragEnd 里判断——这里不再按 y 坐标一刀切。
        val padPx = 8.dp.value * densityF
        val topSafePx = 110.dp.value * densityF
        val botSafePx = 110.dp.value * densityF
        var cx = pos.x.coerceIn(padPx + btnPx / 2, rootW - padPx - btnPx / 2)
        var cy = pos.y.coerceIn(topSafePx + btnPx / 2, rootH - botSafePx - btnPx / 2)
        // 重叠避让：与其他工具中心距 <56dp 时就近找空位（8 方向各偏 48dp）
        val minDist = 56.dp.value * densityF
        val off = 48.dp.value * densityF
        val dirs = listOf(
            0f to 0f, off to 0f, -off to 0f, 0f to off, 0f to -off,
            off to off, off to -off, -off to off, -off to -off,
        )
        for (d in dirs) {
            val tx = (cx + d.first).coerceIn(padPx + btnPx / 2, rootW - padPx - btnPx / 2)
            val ty = (cy + d.second).coerceIn(topSafePx + btnPx / 2, rootH - botSafePx - btnPx / 2)
            val clash = placedTools.withIndex().any { (idx, other) ->
                idx != fromIndex && hypot(other.x * rootW - tx, other.y * rootH - ty) < minDist
            }
            if (!clash) { cx = tx; cy = ty; break }
        }
        val nx = cx / rootW
        val ny = cy / rootH
        if (fromIndex >= 0) {
            placedTools[fromIndex] = PlacedTool(tool, nx, ny)
        } else {
            if (placedTools.size >= 6) {
                toast(ctx, "最多固定 6 个工具，先拖到顶部删除区移除一个")
            } else {
                placedTools.add(PlacedTool(tool, nx, ny))
                toast(ctx, "已固定「${tool.label}」，长按可再拖动")
            }
        }
        savePlaced()
        drag = null
        showTools = false
    }

    fun startCollect() {
        // 需求 1：打点间隔可调（默认 1s），上次选择在启动时恢复
        controller.sampleIntervalMs = AppGraph.prefs.getLong(Route.PREF_SAMPLE_INTERVAL, 1_000L)
        controller.start()
        SamplingForegroundService.start(ctx)
    }

    fun stopCollect() {
        SamplingForegroundService.stop(ctx)
        controller.stop()
    }

    fun captureScreenshot() {
        val act = ctx.findActivity()
        if (act == null) {
            toast(ctx, "截图失败：找不到 Activity")
            return
        }
        runCatching {
            val decor = act.window.decorView
            val bmp = Bitmap.createBitmap(decor.width.coerceAtLeast(1), decor.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            PixelCopy.request(
                act.window,
                bmp,
                { r ->
                    if (r == PixelCopy.SUCCESS) {
                        val dir = ctx.getExternalFilesDir(null)?.resolve("exports")
                            ?: ctx.filesDir.resolve("exports")
                        dir.mkdirs()
                        val f = File(dir, "screenshot_${System.currentTimeMillis()}.png")
                        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        toast(ctx, "截图已保存，可分享")
                        shareFile(ctx, f, "image/png")
                    } else {
                        toast(ctx, "截图失败：码 $r")
                    }
                },
                Handler(Looper.getMainLooper()),
            )
        }.onFailure { toast(ctx, "截图失败：${it.message ?: "未知错误"}") }
    }

    // ---------- 水印相机（CameraX 全屏页，替代系统相机） ----------
    // 旧实现用 ActivityResultContracts.TakePicture()：没有水印、不能选可疑类型，
    // 而且标点落在「地图中心」而不是「我站的位置」（用户真机反馈）。
    var showCamera by remember { mutableStateOf(false) }
    var camKind by remember { mutableStateOf("PHOTO") }
    var camDeviceType by remember { mutableStateOf("") }

    fun openCamera(kind: String = "PHOTO", deviceType: String = "") {
        camKind = kind
        camDeviceType = deviceType
        showCamera = true
    }
    fun takePhoto() = openCamera("PHOTO", "")

    // ---------- 权限与自动开始 ----------
    // 双卡明细只读订阅列表（不读号码/通话），拒绝也能采集，只是退化成单卡显示
    val prefsPerm = remember { ctx.getSharedPreferences(AppGraph.PREFS_NAME, android.content.Context.MODE_PRIVATE) }
    // 权限说明弹窗：request 前先讲清楚「为什么要这个权限」，避免系统框连续弹出、用户不知情
    var permExplain by remember { mutableStateOf<Pair<String, String>?>(null) }
    // 用可赋值的 lambda 打破「launcher 回调 ↔ 申请函数」的前向引用（Kotlin 局部函数不能先用后定义）
    var askPhonePerm: () -> Unit = {}
    var askLocationPerm: () -> Unit = {}
    val phonePermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            startCollect()
            askPhonePerm()
        } else toast(ctx, "未授予定位权限，无法采集")
    }

    // 申请电话权限（读双卡小区参数），首次先弹说明
    askPhonePerm = {
        if (!hasPerm(ctx, Manifest.permission.READ_PHONE_STATE)) {
            if (prefsPerm.getBoolean("explained_phone_perm", false)) {
                runCatching { phonePermLauncher.launch(Manifest.permission.READ_PHONE_STATE) }
            } else {
                permExplain = Manifest.permission.READ_PHONE_STATE to
                    "读取双卡的小区编号、频点与信号参数需要「电话」权限。围猎只读取当前驻留/邻区小区信息，不读取手机号、通话记录与短信，也不会上传通讯录。"
            }
        }
    }

    // 申请定位权限，首次先弹说明
    askLocationPerm = {
        if (hasLocationPermission(ctx)) {
            startCollect()
        } else if (prefsPerm.getBoolean("explained_location_perm", false)) {
            runCatching { permLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }
        } else {
            permExplain = Manifest.permission.ACCESS_FINE_LOCATION to
                "围猎需要用定位记录采样点的经纬度，才能把信号强度还原成基站的真实覆盖范围。请选择「仅在使用应用时允许」，并开启「精确位置」。"
        }
    }

    LaunchedEffect(Unit) {
        startedAt = (withContext(Dispatchers.IO) { AppGraph.store.loadManifest(taskId)?.startedAtEpochMs })
            ?: System.currentTimeMillis()
        if (freshStart) {
            if (hasLocationPermission(ctx)) startCollect() else askLocationPerm()
        }
        // 已授权定位的情况下补申请一次 READ_PHONE_STATE（双卡明细用）
        if (hasLocationPermission(ctx)) askPhonePerm()
    }

    // 3 秒定时拟合覆盖
    LaunchedEffect(controller) {
        while (true) {
            delay(3000)
            val s = controller.samples.value
            if (s.size >= 8) {
                fit = withContext(Dispatchers.Default) {
                    CoverageFitter.fit(
                        s,
                        CoverageFitter.Options(thresholdDbm = AppGraph.pendingThresholdDbm.toDouble()),
                    )
                }
            }
        }
    }

    // 需求 6：信号热力图（样本变化或参数变化时重算；不在主线程做插值）
    LaunchedEffect(heatOn, heatParams, samples.size, samples.lastOrNull()?.tsEpochMs) {
        if (!heatOn) {
            heatLayer = null
            return@LaunchedEffect
        }
        if (samples.size < 2) return@LaunchedEffect
        val snapshotSamples = samples
        heatLayer = withContext(Dispatchers.Default) {
            HeatOverlayBuilder.build(snapshotSamples, heatParams, version = System.currentTimeMillis())
        }
    }

    // 需求 8：实心点封闭区域（凹包优先，点不足退化条带）
    LaunchedEffect(areaOn, areaBuildOptions, samples.size, samples.lastOrNull()?.tsEpochMs) {
        if (!areaOn) {
            areaResult = null
            return@LaunchedEffect
        }
        val snapshotSamples = samples
        val opt = areaBuildOptions
        areaResult = withContext(Dispatchers.Default) { AreaBuilder.build(snapshotSamples, opt) }
    }

    // 基站库图层：视野中心变化才查（拖动结束 800ms 防抖），近邻结果 60s 内走客户端记忆化
    LaunchedEffect(libraryOn, camera.revision, samples.size) {
        if (!libraryOn) return@LaunchedEffect
        delay(800)
        val lat = task.target.centerLat ?: camera.centerLat
        val lon = task.target.centerLon ?: camera.centerLon
        val centerLat = if (samples.isNotEmpty()) samples.last().lat else lat
        val centerLon = if (samples.isNotEmpty()) samples.last().lon else lon
        val resp = libraryClient.nearCached(centerLat, centerLon, CellLibraryClient.DEFAULT_RADIUS_M, 100)
        if (resp.ok) {
            librarySites = resp.sites
        } else {
            librarySites = emptyList()
            lastNotice = resp.message.orEmpty().ifBlank { "基站库查询失败" }
        }
    }

    // 需求 2：GPS 双重安检提示（精度不足 / 距上点过近已合并）
    LaunchedEffect(gateNotice) {
        val g = gateNotice
        if (!g.isNullOrBlank()) {
            lastNotice = g
            delay(2600)
            if (lastNotice == g) lastNotice = null
        }
    }

    // 首次取景：优先样本全览，其次目标参考圈
    LaunchedEffect(samples.size) {
        if (!framedOnce) {
            if (samples.isNotEmpty()) {
                camera.fit(samples.map { it.lat to it.lon })
                framedOnce = true
            } else if (task.target.centerLat != null && task.target.centerLon != null) {
                camera.focusOn(task.target.centerLat, task.target.centerLon)
                framedOnce = true
            }
        }
    }

    // 切基站：震动反馈 + 白话通知（STRICT_SPEC 2.1/2.3）
    LaunchedEffect(events.size) {
        if (events.size > seenEvents.intValue) {
            if (vibEnabled.value) vibrate(ctx, 100)
            if (cellAlertOn.value) {
                lastNotice = events.lastOrNull()?.plainText ?: "小区已切换"
                delay(3200)
                lastNotice = null
            }
        }
        seenEvents.intValue = events.size
    }

    // 图层切换副作用：普通/卫星走 MapEngine 抽象；轨迹/基站图层缺数据时给出明确提示
    LaunchedEffect(layerMode) {
        when (layerMode) {
            MapLayerMode.NORMAL -> activeMapEngine.setLayer(MapLayerType.NORMAL)
            MapLayerMode.SATELLITE -> activeMapEngine.setLayer(MapLayerType.SATELLITE)
            MapLayerMode.TRACK -> if (samples.size < 2) {
                toast(ctx, "轨迹图层：还没有轨迹点，开始采集后自动出现方向箭头")
            } else {
                toast(ctx, "轨迹图层：已在轨迹上叠加方向箭头")
            }
            MapLayerMode.CELLS -> {
                val sites = extractCellSites(samples)
                if (sites.isEmpty()) {
                    toast(ctx, "基站图层：暂无缓存的基站位置，走测后会自动记录")
                } else {
                    toast(ctx, "基站图层：已显示 ${sites.size} 个已采基站位置")
                }
            }
        }
    }

    // 采集中的秒级计时
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1000)
        }
    }

    BackHandler { confirmExit = true }

    // ---------- 实时派生状态 ----------
    val rsrp = snapshot?.signal?.rsrp
    val level = SignalRating.level(rsrp)
    val serving = snapshot?.serving
    val unified = task.target.unifiedCi()
    val onTargetLive = unified != null && serving?.ci == unified
    val targetSeenLive = onTargetLive ||
        (unified != null && snapshot?.neighbors?.any { it.ci == unified } == true)
    val loc = controller.lastLocation
    val inRefCircle = task.target.centerLat != null && task.target.centerLon != null && loc != null &&
        Geo.haversineMeters(loc.latitude, loc.longitude, task.target.centerLat, task.target.centerLon) <=
        (task.target.givenRadiusM ?: 1000.0)
    val distToTargetM = if (task.target.centerLat != null && task.target.centerLon != null && loc != null) {
        Geo.haversineMeters(loc.latitude, loc.longitude, task.target.centerLat, task.target.centerLon)
    } else null

    val targetText = run {
        val t = task.target
        when (t.format) {
            com.gakj.weilie.core.model.TargetFormat.ECI -> "ECI ${t.eci ?: "--"}"
            com.gakj.weilie.core.model.TargetFormat.NCI -> "NCI ${t.nci ?: "--"}"
            com.gakj.weilie.core.model.TargetFormat.LAC_CI -> "CI ${t.cid ?: "--"}"
            com.gakj.weilie.core.model.TargetFormat.LATLON_RADIUS -> "经纬度目标圈"
        }
    }

    // 目标命中态：数据栏「追踪目标」槽位据此显示命中/邻区命中/未命中，
    // 避免用户把「服务小区信号良好」误读成「目标信号良好」。
    val targetState = when {
        unified == null -> TargetState.NONE
        onTargetLive -> TargetState.HIT
        targetSeenLive -> TargetState.NEIGHBOR
        else -> TargetState.MISSING
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(WColors.Bg0),
    ) {
        // 拖拽几何：屏幕像素尺寸（渲染定位用；dropAt 用函数层 rootW/rootH）
        val maxW = constraints.maxWidth.toFloat()
        val maxH = constraints.maxHeight.toFloat()
        SideEffect { rootW = maxW; rootH = maxH }

        // ---------- 地图底图 ----------
        // 当前位置 / 方向箭头 / 基站站址一律交给地图引擎绘制：
        // 以前在 Compose 层用 camera 公式画，拖动地图时相机要等 onMapStatusChangeFinish 才回写，
        // 结果「蓝点跟着我的手指走，但走的路不一样」（用户真机反馈）。引擎层覆盖物天然跟手。
        MapCanvas(
            modifier = Modifier.fillMaxSize(),
            target = task.target,
            samples = samples,
            hullLatLon = fit?.hullLatLon,
            bufferedLatLon = fit?.bufferedLatLon,
            isolines = fit?.isolineLoopsLatLon ?: emptyList(),
            markers = markers,
            camera = camera,
            selfLatLon = loc?.let { it.latitude to it.longitude },
            trackArrows = layerMode == MapLayerMode.TRACK,
            cellSites = if (layerMode == MapLayerMode.CELLS) extractCellSites(samples) else emptyList(),
            sampleMarks = true,
            area = areaResult?.takeIf { areaOn && it.ringLatLon.size >= 3 }?.let {
                ClosedArea(ring = it.ringLatLon, label = it.kind.label)
            },
            theories = buildTheoryShapes(theory),
            heat = heatLayer.takeIf { heatOn },
            cellLibrary = if (libraryOn) librarySites else emptyList(),
            selectedCellKey = selectedCell?.key,
            onCellSiteClick = { site ->
                selectedCell = site
                camera.focusOn(site.lat, site.lon)
            },
            onMarkerClick = { mk ->
                // 有照片 → 打开取证记录；没有照片（纯标点）→ 显示标点信息
                if (mk.photoId.isNotBlank()) detailPhotoId = mk.photoId else detailNotice = mk
            },
            onTapLatLon = { la, lo ->
                if (placeMarker) {
                    placeMarker = false
                    flagName = ""
                    pendingFlagAt = la to lo
                }
            },
        )

        // 状态栏压暗：底图是浅色的，系统用浅色主题时白色状态栏图标会看不见。
        Box(
            Modifier
                .fillMaxWidth()
                .height(96.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xF2060910), Color(0x00060910)),
                    ),
                ),
        )

        // ---------- 顶部：数据栏（左右边线对齐）+ 其下的图层/目标按钮 ----------
        Column(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .onGloballyPositioned { topChromePx = it.size.height }
                .padding(horizontal = Space.screenH, vertical = Space.x2),
        ) {
            MapDataBar(
                running = running,
                level = level,
                rsrpText = rsrp?.toString() ?: "--",
                levelLabelText = if (level < 0) "无信号" else SignalRating.label(level),
                sinrText = snapshot?.signal?.sinr?.let { String.format(Locale.US, "%.1f dB", it.toDouble()) } ?: "--",
                neighborsCount = snapshot?.neighbors?.size ?: 0,
                wifiCount = snapshot?.wifi?.size ?: 0,
                coordsText = loc?.let { String.format(Locale.US, "%.5f, %.5f", it.latitude, it.longitude) } ?: "--",
                durationText = fmtDur(nowMs - startedAt),
                sampleCount = samples.size,
                targetText = targetText,
                targetState = targetState,
                carrierText = carrierName(serving?.mcc, serving?.mnc),
                ratText = when (snapshot?.netMode) {
                    NetMode.SA -> "5G SA"
                    NetMode.NSA -> "5G NSA"
                    NetMode.LTE -> "4G LTE"
                    NetMode.LEGACY -> serving?.rat?.displayName ?: "2G/3G"
                    else -> serving?.rat?.displayName ?: "--"
                },
                servingText = run {
                    val mode = snapshot?.netMode ?: NetMode.UNKNOWN
                    val nr = snapshot?.nrServing
                    val anchor = snapshot?.anchorLte ?: snapshot?.serving
                    val nrRow = snapshot?.sims?.firstOrNull { it.rat.contains("NR") }
                    buildString {
                        when (mode) {
                            NetMode.SA -> {
                                // SA：NR 就是服务小区，绝不能渲染「锚点 LTE」
                                append("5G SA · ")
                                append(nr?.display() ?: serving?.display() ?: "尚未读到 5G 服务小区")
                                append("（独立组网，无 LTE 锚点）")
                            }
                            NetMode.NSA -> {
                                if (nrRow != null) {
                                    append("5G NR · ${nrRow.carrier} ${nrRow.ratBand} · NCI ${nrRow.cellIdText}")
                                    if (nrRow.pci != Int.MIN_VALUE) append(" · PCI ${nrRow.pci}")
                                    append(" · ${nrRow.rsrpText}")
                                } else if (nr != null) {
                                    append("5G NR · ${nr.display()}")
                                } else {
                                    append("5G NSA（未解析到 NR 辅载波，LTE 锚点如下）")
                                }
                                append("\n锚点 LTE（5G NSA 的控制面承载）· ")
                                append(anchor?.display() ?: "尚未读到 LTE 锚点")
                            }
                            else -> append(anchor?.display() ?: "尚未读到服务小区")
                        }
                        val note = snapshot?.neighborNote.orEmpty()
                        if (note.isNotBlank()) append("\n").append(note)
                    }
                },
                // 邻区列表：CI 可能为空（2G/3G 或部分 5G 邻区），不能只取 ciHex，否则
                // 「附近还有 N 个基站」会显示 0，和读到的邻区数量对不上。
                neighborCis = snapshot?.neighbors?.map { c ->
                    c.ci?.toString()
                        ?: c.pci?.let { "PCI $it" }
                        ?: "${c.rat.displayName} 小区"
                } ?: emptyList(),
                neighborHint = snapshot?.neighborNote.orEmpty(),
                confidenceText = fit?.confidence?.let { "${it.level.name} ${it.score0to100}分" },
                snapshot = snapshot,
                lastSample = samples.lastOrNull(),
                events = events,
                lastEventNotice = lastNotice,
                rsrpHistory = samples.mapNotNull { it.rsrp }.takeLast(60),
                sims = snapshot?.sims ?: emptyList(),
                expertMode = expertMode,
                onExpertModeChange = { expertMode = it },
            )
            Spacer(Modifier.height(Space.x2))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.x2)) {
                GlassIconButton(Icons.Rounded.Layers, "图层", size = 40.dp, onMap = true) { showLayers = !showLayers }
                GlassIconButton(Icons.Rounded.Info, "目标", size = 40.dp, onMap = true) { showTargetInfo = true }
            }
        }

        // ---------- 图层浮层：全屏透明遮罩承接「点空白关闭」，浮层紧贴按钮列右侧 ----------
        if (showLayers) {
            // 返回键优先关闭浮层，而不是直接弹「退出采集」
            BackHandler { showLayers = false }
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { showLayers = false },
            ) {
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(
                            start = Space.screenH,
                            top = Space.x2 + with(LocalDensity.current) { topChromePx.toDp() },
                        ),
                ) {
                    MapLayersPanel(
                        current = layerMode,
                        onSelect = {
                            layerMode = it
                            showLayers = false
                        },
                        heatVisible = heatOn,
                        heatParams = heatParams,
                        onHeatToggle = {
                            heatOn = it
                            if (it && HeatOverlayBuilder.usableCount(samples) < 2) {
                                toast(ctx, "热力图：主区/邻区命中点还不足 2 个，先多走几个点")
                            }
                        },
                        onHeatParams = { heatParams = it },
                        areaVisible = areaOn,
                        onAreaToggle = {
                            areaOn = it
                            AppGraph.prefs.edit().putBoolean(Route.PREF_AREA_ON, it).apply()
                            if (it && samples.size < 2) toast(ctx, "封闭区域：先多走几个点")
                        },
                        areaMode = areaMode,
                        onAreaModeChange = { m ->
                            areaMode = m
                            AppGraph.prefs.edit().putString(Route.PREF_AREA_MODE, m.name).apply()
                            if (m == AreaBuilder.Mode.SIMULATED && samples.isEmpty()) {
                                toast(ctx, "模拟实测区域：边走边显示，轨迹点越多范围越完整")
                            }
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
                        onLibraryToggle = {
                            libraryOn = it
                            selectedCell = null
                            if (!it) librarySites = emptyList() else toast(ctx, "基站库：OpenCelliD 众包坐标，参考定位，非实测")
                        },
                        onLibrarySearch = {
                            showLayers = false
                            showCellSearch = true
                        },
                        onDismiss = { showLayers = false },
                    // 还要给底部「暂停/采集」主钮与比例尺留出空间，避免浮层一开就压住主操作
                    maxHeight = with(LocalDensity.current) { (maxH - topChromePx).toDp() } - 200.dp,
                    )
                }
            }
        }

        // ---------- 左下：工具箱 FAB（2.3） ----------
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                .padding(12.dp),
        ) {
            GlassIconButton(Icons.Rounded.Build, "工具箱", size = 44.dp, onMap = true) { showTools = true }
        }

        // ---------- 右下：AI + 缩放/定位（导航类固定，不参与拖拽） ----------
        Column(
            Modifier
                .align(Alignment.BottomEnd)
                // 吞掉按钮之间空隙的点击：否则点了「标点」再点到按钮附近（但没点在按钮上）
                // 会被地图当成落点，凭空冒出一个标点（用户真机反馈）
                .pointerInput(Unit) { detectTapGestures { } }
                .navigationBarsPadding()
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.End,
        ) {
            GlassIconButton(
                size = 44.dp,
                onMap = true,
                content = {
                    Icon(Icons.Rounded.SmartToy, contentDescription = "AI 助手", tint = WColors.Secondary, modifier = Modifier.size(22.dp))
                },
                onClick = { showAi = true },
            )
            GlassIconButton(
                size = 44.dp,
                onMap = true,
                content = {
                    Icon(
                        Icons.Rounded.Flag,
                        contentDescription = "标点",
                        tint = if (placeMarker) WColors.Primary else WColors.OnBg,
                        modifier = Modifier.size(22.dp),
                    )
                },
                onClick = {
                    placeMarker = !placeMarker
                    toast(ctx, if (placeMarker) "点击地图选择标点位置" else "已取消标点")
                },
            )
            GlassIconButton(Icons.Rounded.Add, size = 44.dp, onMap = true) { camera.zoomBy(1.18f) }
            GlassIconButton(Icons.Rounded.Remove, size = 44.dp, onMap = true) { camera.zoomBy(1f / 1.18f) }
            GlassIconButton(Icons.Rounded.MyLocation, size = 44.dp, onMap = true) {
                loc?.let { camera.focusOn(it.latitude, it.longitude) }
            }
        }

        // ---------- 底部居中：静默跟踪状态条 + 采集启停大钮 + 比例尺 ----------
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 需求 10：静默跟踪状态（明显停止入口：点这条即停）
            if (silentRunning) {
                val stColor = when (silentState) {
                    TrackState.MAIN -> Color(MarkStyles.MAIN_FILL)
                    TrackState.NEIGHBOR -> Color(0xFFF59E0B)
                    TrackState.NONE -> WColors.Danger
                }
                Row(
                    Modifier
                        .padding(bottom = 8.dp)
                        .mapGlass(RoundedCornerShape(14.dp))
                        .clickable {
                            SilentTrackService.stop(ctx)
                            toast(ctx, "已停止静默跟踪")
                        }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PulsingDot(stColor, 9.dp)
                    Spacer(Modifier.width(7.dp))
                    Text(
                        "静默跟踪 · $silentStateText",
                        style = MonoText.style.copy(fontSize = 11.sp),
                        color = stColor,
                    )
                    Spacer(Modifier.width(7.dp))
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "停止静默跟踪",
                        tint = WColors.OnBgDim,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
            GlassIconButton(
                label = if (running) "暂停" else "采集",
                size = 44.dp,
                onMap = true,
                content = {
                    if (running) {
                        // 暂停语义：红圈里放两条竖杠，不要只放一个红点（红点会被读成「正在录音」而不知道能按）
                        Box(
                            Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(WColors.Danger.copy(alpha = 0.18f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Rounded.Pause,
                                contentDescription = "暂停采集",
                                tint = WColors.Danger,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    } else {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = "采集", tint = WColors.OnBg, modifier = Modifier.size(22.dp))
                    }
                },
                onClick = {
                    if (running) {
                        stopCollect()
                    } else if (hasLocationPermission(ctx)) {
                        startCollect()
                    } else {
                        askLocationPerm()
                    }
                },
            )
            Spacer(Modifier.height(8.dp))
            ScaleBar(camera)
        }

        // ---------- 自由摆放的工具悬浮钮（长按拖动改位置，单击执行） ----------
        placedTools.forEachIndexed { i, pt ->
            val beingDragged = drag?.fromIndex == i
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .offset {
                        IntOffset(
                            (pt.x * maxW).roundToInt() - (btnPx / 2).roundToInt(),
                            (pt.y * maxH).roundToInt() - (btnPx / 2).roundToInt(),
                        )
                    }
                    .alpha(if (beingDragged) 0f else 1f)
                    .size(44.dp)
                    .mapGlass(CircleShape)
                    .pointerInput(i, pt.tool) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { _ ->
                                // 起点就是按钮中心
                                drag = DragInfo(pt.tool, Offset(pt.x * maxW, pt.y * maxH), i)
                            },
                            onDrag = { _, amt ->
                                val cur = drag
                                if (cur != null && cur.fromIndex == i) drag = cur.copy(pos = cur.pos + amt)
                            },
                            onDragEnd = {
                                val cur = drag
                                if (cur != null && cur.fromIndex == i) dropAt(cur.tool, cur.pos, i)
                            },
                            onDragCancel = {
                                if (drag?.fromIndex == i) drag = null
                            },
                        )
                    }
                    .clickable {
                        openTool(
                            pt.tool,
                            onTool = { id ->
                                if (id == ToolId.PHOTO) openCamera("PHOTO", "") else popup = id.toPopup()
                            },
                            onAi = { showAi = true },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(toolIcon(pt.tool), pt.tool.label, tint = WColors.OnBg, modifier = Modifier.size(22.dp))
            }
        }

        // ---------- 拖拽中的幽灵跟随 + 顶部删除区（只渲染，不拦截地图手势） ----------
        drag?.let { d ->
            // 删除区（顶部中央，压在数据栏之上，高对比）：拖到这里松手即移除
            val armed = overTrash(d.pos)
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 2.dp)
                    .width(96.dp)
                    .background(
                        if (armed) Color(0xF2EF4444) else Color(0xB3DC2626),
                        RoundedCornerShape(22.dp),
                    )
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Rounded.Delete,
                    contentDescription = "拖到此处移除",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    if (armed) "松手移除" else "拖到这里删除",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                )
            }
            // 幽灵：44dp 悬浮钮样式，alpha 0.9、放大 1.08，跟随手指
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .offset {
                        IntOffset(
                            (d.pos.x - btnPx / 2).roundToInt(),
                            (d.pos.y - btnPx / 2).roundToInt(),
                        )
                    }
                    .graphicsLayer {
                        scaleX = 1.08f
                        scaleY = 1.08f
                        alpha = 0.9f
                    }
                    .size(44.dp)
                    .mapGlass(CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(toolIcon(d.tool), d.tool.label, tint = WColors.Primary, modifier = Modifier.size(22.dp))
            }
        }
    }

    // ---------- 工具箱面板 ----------
    if (showTools) {
        ToolboxSheet(
            running = running,
            expertMode = expertMode,
            cellSummary = buildList {
                val simsNow = snapshot?.sims ?: emptyList()
                if (simsNow.isNotEmpty()) {
                    simsNow.forEach { s ->
                        add("${s.slotLabel} ${s.carrier} ${s.ratBand} · ${s.cellIdText}")
                        add("    ${s.rsrpText} · ${s.sinrText} · PCI ${if (s.pci == Int.MIN_VALUE) "--" else s.pci.toString()}")
                    }
                } else {
                    add(carrierName(serving?.mcc, serving?.mnc) + " " + (serving?.rat?.displayName ?: "--"))
                    add("CI " + (serving?.ci?.toString() ?: "--"))
                }
                add("RSRP " + (rsrp?.toString() ?: "--") + " dBm · 样本 ${samples.size}")
            },
            rsrpSeries = samples.takeLast(120).map { RsPoint(it.tsEpochMs, it.rsrp) },
            inlineTool = inlineTool,
            vib = vibEnabled.value,
            cellAlert = cellAlertOn.value,
            onVib = {
                vibEnabled.value = it
                AppGraph.prefs.edit().putBoolean(Route.PREF_VIBRATE, it).apply()
            },
            onCellAlert = {
                cellAlertOn.value = it
                AppGraph.prefs.edit().putBoolean(Route.PREF_CELL_ALERT, it).apply()
            },
            silent = silentRunning,
            silentStateText = "静默跟踪 · $silentStateText",
            silentAvailable = task.target.unifiedCi() != null,
            silentStrength = silentStrength,
            sampleIntervalMs = controller.sampleIntervalMs,
            onSilent = { on ->
                if (on) {
                    toast(ctx, "静默跟踪已开启：仅震动（前台服务会持续耗电），可在通知栏或地图底部状态条停止")
                    SilentTrackService.start(ctx)
                } else {
                    SilentTrackService.stop(ctx)
                    toast(ctx, "静默跟踪已关闭")
                }
            },
            onSilentStrength = { level ->
                silentStrength = level
                SilentTrackService.setStrength(ctx, level)
            },
            onSampleInterval = { ms ->
                controller.sampleIntervalMs = ms
                AppGraph.prefs.edit().putLong(Route.PREF_SAMPLE_INTERVAL, ms).apply()
                toast(ctx, "打点间隔已设为 ${ms / 1000}s")
            },
            onTool = { t ->
                showTools = false
                when (t) {
                    ToolId.AI -> showAi = true
                    // 拍照直接进全屏水印相机（用户要求：像 CellularZ 那样，不要先弹一层面板）
                    ToolId.PHOTO -> openCamera("PHOTO", "")
                    else -> popup = t.toPopup()
                }
            },
            onSimpleTool = { inlineTool = it },
            onInlineBack = { inlineTool = null },
            onSwitchCell = {
                showTools = false
                switchCell = true
            },
            onSetExpert = { expertMode = it },
            onTeam = {
                showTools = false
                onOpenTeam()
            },
            dimmed = drag != null,
            onDragStart = { t, p -> drag = DragInfo(t, p, -1) },
            onDrag = { p -> drag = drag?.copy(pos = p) },
            onDragEnd = { p ->
                val cur = drag
                if (cur != null) dropAt(cur.tool, p, cur.fromIndex) else drag = null
            },
            onDragCancel = { drag = null },
            onDismiss = { showTools = false },
        )
    }

    // ---------- 复杂工具浮窗 ----------
    popup?.let { p ->
        Box(Modifier.fillMaxSize()) {
            when (p) {
                ToolPopup.EXPORT -> GlassPanelDialog("数据导出", { popup = null }) {
                    ExportPanel(
                        onCsv = { exportCsvAndShare(ctx, AppGraph.store, taskId) },
                        onGeoJson = { exportGeoJsonAndShare(ctx, taskId, samples, task.target, fit) },
                        onKml = { exportKmlAndShare(ctx, taskId, task.title, samples, fit) },
                        onTrackPng = { shareTrackPng(ctx, samples, task.title) },
                        onMapPng = {
                            shareMapSnapshotPng(
                                ctx = ctx,
                                title = task.title,
                                subtitle = "${fmtTs(System.currentTimeMillis())} · ${samples.size} 个采样点 · ${targetText}",
                                legend = buildList {
                                    add(MarkStyles.MAIN_FILL to "主区命中")
                                    add(MarkStyles.baseColor(samples.lastOrNull()?.servingCi) to "邻区命中")
                                    add(MarkStyles.OUT_RING to "脱离")
                                    if (areaOn) add(0xFF34D399.toInt() to "实测覆盖区")
                                    if (theory != null) add(TheoryShape.BLUE to "理论覆盖区")
                                    if (theory?.radiusM != null) add(TheoryShape.ORANGE to "TA 距离带")
                                    if (theory != null && areaOn) add(TheoryShape.PURPLE to "理论∩实测")
                        if (libraryOn && librarySites.isNotEmpty()) add(0xFF22D3EE.toInt() to "基站库")
                                },
                            )
                        },
                        onTheory = { popup = null; showTheory = true },
                        onArea = {
                            popup = null
                            areaOn = !areaOn
                            AppGraph.prefs.edit().putBoolean(Route.PREF_AREA_ON, areaOn).apply()
                            val r = if (areaOn) {
                                areaResult ?: AreaBuilder.build(samples).also { areaResult = it }
                            } else null
                            toast(
                                ctx,
                                when {
                                    !areaOn -> "已关闭实测封闭区域"
                                    r == null || r.kind == AreaBuilder.Kind.NONE ->
                                        "有效实心点不足（需 ≥2 个），暂无法成面"
                                    else -> "已生成${r.kind.label} · ${r.pointCount} 个有效点"
                                },
                            )
                        },
                        theoryOn = theory != null,
                    )
                }
                ToolPopup.NAV -> GlassPanelDialog("定位与导航", { popup = null }) {
                    NavPanel(
                        myLat = loc?.latitude,
                        myLon = loc?.longitude,
                        myAcc = loc?.accuracy,
                        targetLat = task.target.centerLat,
                        targetLon = task.target.centerLon,
                        targetLabel = task.target.rawInput.ifBlank { targetText },
                        straightKm = distToTargetM?.div(1000.0),
                        onNavigate = {
                            val la = task.target.centerLat
                            val lo = task.target.centerLon
                            if (la != null && lo != null) {
                                runCatching {
                                    ctx.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse("geo:$la,$lo?q=$la,$lo(目标)")),
                                    )
                                }.onFailure { toast(ctx, "未找到可用的地图应用") }
                            }
                        },
                    )
                }
                ToolPopup.HELP -> GlassPanelDialog("帮助", { popup = null }) {
                    HelpPanel(legalText = stringResource(R.string.legal_notice))
                }
                ToolPopup.PHOTO -> GlassPanelDialog("拍照取证", { popup = null }) {
                    PhotoPanel(
                        photoPoints = photoPoints,
                        onTakePhoto = { popup = null; takePhoto() },
                        onMarkSuspicious = {
                            // 用户要求：标记可疑设备也要进相机（在水印照片里记录类型与现场），
                            // 不再凭空在地图中心扔一个点。
                            popup = null
                            openCamera("SUSPICIOUS", "SUSPICIOUS_DEVICE")
                        },
                    )
                }
                ToolPopup.SETTINGS -> GlassPanelDialog("设置", { popup = null }) {
                    var server by remember { mutableStateOf(AppGraph.prefs.getString(Route.PREF_SERVER, "") ?: "") }
                    var ak by remember { mutableStateOf(AppGraph.prefs.getString(Route.PREF_BAIDU_AK, "") ?: "") }
                    var key by remember { mutableStateOf(AppGraph.prefs.getString(Route.PREF_AI_KEY, "") ?: "") }
                    QuickSettingsPanel(
                        server = server,
                        baiduAk = ak,
                        aiKey = key,
                        onServer = { server = it },
                        onBaiduAk = { ak = it },
                        onAiKey = { key = it },
                        onSave = {
                            AppGraph.prefs.edit()
                                .putString(Route.PREF_SERVER, server)
                                .putString(Route.PREF_BAIDU_AK, ak)
                                .putString(Route.PREF_AI_KEY, key)
                                .apply()
                            AppGraph.refreshAiConfig()
                            toast(ctx, "配置已保存")
                        },
                        onOpenFull = {
                            popup = null
                            onOpenSettings()
                        },
                    )
                }
                ToolPopup.REALTIME -> Unit // 由下方 ExpertPanel 呈现
            }
        }
    }

    // ---------- 实时信息（专家全量面板，ModalBottomSheet） ----------
    if (popup == ToolPopup.REALTIME) {
        ExpertPanel(snapshot, samples.lastOrNull(), events, location = loc) { popup = null }
    }

    // ---------- AI 助手 ----------
    if (showAi) {
        val summary = AiClient.summarize(
            servingText = serving?.display(),
            rsrp = rsrp,
            onTarget = onTargetLive || (samples.lastOrNull()?.onTarget ?: false),
            sampleCount = samples.size,
            elapsedMin = (nowMs - startedAt) / 60000,
        )
        AiSheet(summary) { showAi = false }
    }

    // ---------- 目标参数 ----------
    if (showTargetInfo) {
        AlertDialog(
            onDismissRequest = { showTargetInfo = false },
            title = { Text("目标参数") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    val t = task.target
                    KeyValueRow("格式", t.format.name)
                    KeyValueRow("原始输入", t.rawInput)
                    KeyValueRow("统一编号", t.unifiedCi()?.toString() ?: "—")
                    t.eci?.let { KeyValueRow("ECI", it.toString()) }
                    t.nci?.let { KeyValueRow("NCI", it.toString()) }
                    t.cid?.let { KeyValueRow("CID", it.toString()) }
                    t.lac?.let { KeyValueRow("LAC/TAC", it.toString()) }
                    t.mcc?.let { KeyValueRow("MCC/MNC", "${t.mcc}-${t.mnc ?: "??"}") }
                    t.centerLat?.let { KeyValueRow("中心", "%.6f, %.6f".format(it, t.centerLon ?: 0.0)) }
                    t.givenRadiusM?.let { KeyValueRow("半径", "%.0f 米".format(it)) }
                    distToTargetM?.let { KeyValueRow("当前距离", "%.0f 米".format(it), WColors.Primary) }
                    t.warnings.forEach { w ->
                        Spacer(Modifier.height(4.dp))
                        Text("注意：$w", style = MaterialTheme.typography.labelMedium, color = WColors.Warning)
                    }
                }
            },
            confirmButton = { TextButton({ showTargetInfo = false }) { Text("关闭") } },
        )
    }

    // ---------- 基站库：选中的站址详情（含导航） ----------
    selectedCell?.let { site ->
        AlertDialog(
            onDismissRequest = { selectedCell = null },
            title = { Text(site.title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    KeyValueRow("编号", site.cellText)
                    KeyValueRow("十六进制", site.hexCi)
                    KeyValueRow("坐标", site.coordinateText)
                    KeyValueRow("精度", site.accuracyText)
                    KeyValueRow("覆盖半径", site.rangeText)
                    loc?.let { l ->
                        KeyValueRow("距我", CellLibraryClient.formatDistance(site.distanceM(l.latitude, l.longitude)), WColors.Primary)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "众包坐标仅作参考。要确认覆盖范围，请以实测轨迹为准。",
                        style = MaterialTheme.typography.labelSmall,
                        color = WColors.OnBgDim,
                    )
                    Text(
                        KnownCell.SOURCE_ATTRIBUTION,
                        style = MaterialTheme.typography.labelSmall,
                        color = WColors.OnBgDim.copy(alpha = 0.8f),
                    )
                }
            },
            confirmButton = {
                TextButton({
                    navigateTo(ctx, site.lat, site.lon, site.title)
                    selectedCell = null
                }) { Text("导航到此") }
            },
            dismissButton = { TextButton({ selectedCell = null }) { Text("关闭") } },
        )
    }

    // ---------- 基站库：查询弹窗 ----------
    if (showCellSearch) {
        CellSearchDialog(
            onDismiss = { showCellSearch = false },
            onPickSite = { site ->
                showCellSearch = false
                selectedCell = site
                camera.focusOn(site.lat, site.lon)
            },
            initLat = loc?.latitude ?: samples.lastOrNull()?.lat ?: task.target.centerLat,
            initLon = loc?.longitude ?: samples.lastOrNull()?.lon ?: task.target.centerLon,
        )
    }

    // ---------- 需求 7：基站理论覆盖区 ----------
    if (showTheory) {
        GlassPanelDialog("基站理论覆盖区", { showTheory = false }) {
            TheoryPanel(
                initLat = loc?.latitude ?: task.target.centerLat,
                initLon = loc?.longitude ?: task.target.centerLon,
                current = theory,
                onApply = { spec ->
                    theory = spec
                    showTheory = false
                    toast(
                        ctx,
                        when {
                            spec.azimuthDeg != null && spec.radiusM != null -> "已绘制定向扇区 + TA 距离带"
                            spec.azimuthDeg != null -> "已绘制定向扇区（方位角 ${spec.azimuthDeg.toInt()}°）"
                            spec.radiusM != null -> "已绘制 TA 距离环带（${spec.radiusM.toInt()} 米）"
                            else -> "已绘制全向理论覆盖圆"
                        },
                    )
                },
                onClear = {
                    theory = null
                    showTheory = false
                    toast(ctx, "已清除理论覆盖区")
                },
            )
        }
    }

    // ---------- A1 切换追踪基站 ----------
    if (switchCell) {
        SwitchCellDialog(
            currentTargetText = "$targetText · ${task.target.rawInput}",
            onSwitch = { newTarget ->
                val wasRunning = running
                val nc = AppGraph.retarget(ctx, task.copy(target = newTarget))
                overrideCtrl = nc
                fit = null
                framedOnce = false
                if (newTarget.centerLat != null && newTarget.centerLon != null) {
                    camera.focusOn(newTarget.centerLat, newTarget.centerLon)
                }
                if (wasRunning) {
                    nc.start()
                    SamplingForegroundService.start(ctx)
                }
                switchCell = false
                toast(ctx, "已切换追踪目标")
            },
            onDismiss = { switchCell = false },
        )
    }

    // ---------- 权限说明弹窗：先讲清楚为什么，再弹系统授权框 ----------
    permExplain?.let { (perm, text) ->
        AlertDialog(
            onDismissRequest = { permExplain = null },
            title = { Text(if (perm == Manifest.permission.ACCESS_FINE_LOCATION) "需要定位权限" else "需要电话权限") },
            text = { Text(text) },
            confirmButton = {
                TextButton({
                    permExplain = null
                    prefsPerm.edit()
                        .putBoolean(
                            if (perm == Manifest.permission.ACCESS_FINE_LOCATION) "explained_location_perm" else "explained_phone_perm",
                            true,
                        )
                        .apply()
                    runCatching {
                        if (perm == Manifest.permission.ACCESS_FINE_LOCATION) {
                            permLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                        } else {
                            phonePermLauncher.launch(Manifest.permission.READ_PHONE_STATE)
                        }
                    }
                }) { Text("去授权") }
            },
            dismissButton = { TextButton({ permExplain = null }) { Text("稍后") } },
        )
    }

    // ---------- 标点取证记录（点击地图标点打开） ----------
    detailPhotoId?.let { pid ->
        PhotoDetailSheet(
            photoId = pid,
            // 单人模式：拍摄者就是自己，可以直接从地图移除这个标点（照片保留在取证相册）
            onDelete = {
                markers = markers.filterNot { it.photoId == pid }
                hidePhotoMarker(pid)
                detailPhotoId = null
                toast(ctx, "已从地图移除标点，照片仍保留在取证记录里")
            },
        ) { detailPhotoId = null }
    }

    // ---------- 标点详情（没有绑定照片的纯标点） ----------
    detailNotice?.let { mk ->
        AlertDialog(
            onDismissRequest = { detailNotice = null },
            title = {
                Text(
                    when {
                        mk.name.isNotBlank() -> mk.name
                        mk.kind == "SUSPICIOUS" -> "可疑设备标点"
                        mk.photoId.isBlank() && mk.by == "self" -> "我的标点"
                        else -> "拍照标点"
                    },
                )
            },
            text = {
                Column {
                    if (mk.name.isNotBlank()) KeyValueRow("名称", mk.name)
                    KeyValueRow("记录人", mk.byName.ifBlank { mk.by })
                    KeyValueRow("时间", fmtTs(mk.ts))
                    KeyValueRow("坐标", String.format(Locale.US, "%.6f, %.6f", mk.lat, mk.lon))
                    if (mk.note.isNotBlank()) KeyValueRow("备注", mk.note)
                    if (mk.deviceType.isNotBlank()) KeyValueRow("可疑类型", mk.deviceType)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (mk.by == "self" && mk.photoId.isBlank()) "手动放置的标点：点「导航到此」可唤起地图应用走过去。"
                        else "该标点没有关联的取证照片（旧版本创建的标点）。重新拍照会同时保存照片与标点。",
                        style = MaterialTheme.typography.labelMedium,
                        color = WColors.OnBgDim,
                    )
                    // 手动标点的删除入口放在正文里（原来藏在右下角「关闭」旁边，用户找不到）
                    if ((mk.by == "self" || mk.by == AppGraph.userId()) && mk.photoId.isBlank()) {
                        Spacer(Modifier.height(12.dp))
                        TextButton(
                            onClick = {
                                markers = markers.filterNot {
                                    (mk.id.isNotBlank() && it.id == mk.id) ||
                                        (it.ts == mk.ts && it.lat == mk.lat && it.lon == mk.lon)
                                }
                                detailNotice = null
                                toast(ctx, "已删除标点")
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("删除这个标点", color = WColors.Danger) }
                    }
                }
            },
            confirmButton = {
                TextButton({
                    navigateTo(ctx, mk.lat, mk.lon, mk.note.ifBlank { "标点" })
                    detailNotice = null
                }) { Text("导航到此") }
            },
            dismissButton = {
                TextButton({ detailNotice = null }) { Text("关闭") }
            },
        )
    }

    // ---------- 单人标点命名（需求二）----------
    pendingFlagAt?.let { (la, lo) ->
        AlertDialog(
            onDismissRequest = { pendingFlagAt = null },
            title = { Text("新建标点") },
            text = {
                Column {
                    OutlinedTextField(
                        value = flagName,
                        onValueChange = { flagName = it.take(24) },
                        label = { Text("标点名称") },
                        placeholder = { Text("如：3 号楼配电房") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "名称会显示在地图上；不填也可以，图标仍会显示。",
                        style = MaterialTheme.typography.labelSmall,
                        color = WColors.OnBgDim,
                    )
                }
            },
            confirmButton = {
                TextButton({
                    val finalName = flagName.trim()
                    val ts = System.currentTimeMillis()
                    markers = markers + WsMsg.Marker(
                        by = "self",
                        byName = "我",
                        lat = la,
                        lon = lo,
                        kind = "FLAG",
                        note = "手动标点",
                        ts = ts,
                        id = "self-$ts",
                        name = finalName,
                    )
                    detailNotice = markers.lastOrNull()
                    pendingFlagAt = null
                    toast(ctx, if (finalName.isBlank()) "已放置标点" else "已放置标点：$finalName")
                }) { Text("确定") }
            },
            dismissButton = { TextButton({ pendingFlagAt = null }) { Text("取消") } },
        )
    }

    // ---------- 全屏水印相机（必须是根布局的最后一个子节点，否则会被地图浮层压住） ----------
    if (showCamera) {
            PhotoCameraScreen(
                initialKind = camKind,
                currentLat = loc?.latitude ?: camera.centerLat,
                currentLon = loc?.longitude ?: camera.centerLon,
                hasFix = loc != null,
                taskId = task.id,
                onClose = { showCamera = false },
                onCaptured = { rec ->
                    showCamera = false
                    if (rec.lat != 0.0 || rec.lon != 0.0) {
                        // 标点用照片自带坐标（=拍摄时的 GPS），不再用地图中心
                        markers = markers + WsMsg.Marker(
                            by = AppGraph.userId(),
                            byName = if (rec.kind == "SUSPICIOUS") "可疑设备" else "拍照标点",
                            lat = rec.lat,
                            lon = rec.lon,
                            kind = rec.kind,
                            note = rec.title,
                            ts = rec.ts,
                            photoId = rec.id,
                            deviceType = rec.deviceType,
                        )
                        photoPoints.add(
                            "${photoPoints.size + 1} · ${fmtTs(rec.ts)} · " +
                                String.format(Locale.US, "%.5f, %.5f", rec.lat, rec.lon),
                        )
                    }
                    toast(ctx, "已保存取证记录：${rec.title}")
                    // 拍照后自动登记到验真服务器（后台，不阻塞相机收起）：
                    // 成功写入 photos/verify.jsonl；失败留在待登记，打开详情页时会自动补登记重试。
                    AppGraph.appScope.launch {
                        runCatching {
                            AppGraph.photoVerifier.register(AppGraph.photoStore.fileOf(rec), rec)
                        }
                    }
                },
                onOpenGallery = {
                    // 相册 = 最近一条记录详情（无记录时提示）
                    val latest = AppGraph.photoStore.latest()
                    if (latest != null) detailPhotoId = latest.id else toast(ctx, "还没有取证照片")
                },
            )
        }

    // ---------- 退出确认 ----------
    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("暂停采集？") },
            text = { Text("返回大厅将暂停采集并保存已采样本（当前 ${samples.size} 个）。") },
            confirmButton = {
                TextButton({
                    confirmExit = false
                    stopCollect()
                    onBack()
                }) { Text("暂停并返回") }
            },
            dismissButton = {
                TextButton({ confirmExit = false }) { Text("继续采集") }
            },
        )
    }
}

/** 复杂工具浮窗种类 */
internal enum class ToolPopup { EXPORT, NAV, HELP, PHOTO, REALTIME, SETTINGS }

/** 自由摆放的工具悬浮钮：x/y 为相对屏幕可用宽高的归一化中心坐标（0..1） */
data class PlacedTool(val tool: ToolId, val x: Float, val y: Float)

/** 拖拽中的工具：pos = 窗口像素坐标；fromIndex = placedTools 下标（-1 = 从工具箱拖出） */
data class DragInfo(val tool: ToolId, val pos: Offset, val fromIndex: Int)

internal fun ToolId.toPopup(): ToolPopup = when (this) {
    ToolId.EXPORT -> ToolPopup.EXPORT
    ToolId.NAV -> ToolPopup.NAV
    ToolId.HELP -> ToolPopup.HELP
    ToolId.PHOTO -> ToolPopup.PHOTO
    ToolId.REALTIME -> ToolPopup.REALTIME
    else -> ToolPopup.SETTINGS
}

/** 右下固定小组件点按：与 A4 面板同语义 */
private fun openTool(t: ToolId, onTool: (ToolId) -> Unit, onAi: () -> Unit) {
    if (t == ToolId.AI) onAi() else onTool(t)
}

/** A1 切换追踪基站对话框：输入新编号 + 实时解析 + 确认切换 */
@Composable
private fun SwitchCellDialog(
    currentTargetText: String,
    onSwitch: (com.gakj.weilie.core.model.TargetCell) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    val parsed = remember(input) { TaskInputParser.parse(input) }
    GlassPanelDialog("切换追踪基站", onDismiss) {
        KeyValueRow("当前目标", currentTargetText)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("新目标：ECI / NCI / LAC-CI / 经纬度,半径") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = WColors.Primary,
                unfocusedBorderColor = WColors.GlassBorder,
                focusedTextColor = WColors.OnBg,
                unfocusedTextColor = WColors.OnBg,
            ),
        )
        Spacer(Modifier.height(8.dp))
        if (input.isNotBlank()) {
            if (parsed.target != null) {
                StatusBadge("解析成功：${parsed.target!!.format.name}", WColors.Success)
                Spacer(Modifier.height(4.dp))
                Text(
                    parsed.normalized,
                    style = MonoText.style,
                    color = WColors.OnBgDim,
                )
            } else {
                StatusBadge(parsed.error ?: "无法解析", WColors.Danger)
            }
        }
        Spacer(Modifier.height(14.dp))
        GlowButton(
            "切换并继续走测",
            enabled = parsed.target != null,
            modifier = Modifier.fillMaxWidth(),
            onClick = { parsed.target?.let(onSwitch) },
        )
    }
}

/**
 * 比例尺：按当前缩放取 60~150px 的整刻度。
 *
 * 注意：这里必须把宽度硬钳住。Compose 的 Constraints 每个轴最多 262143px（2^18-1），
 * 而 metersPerPixel 在最大缩放（zoom 21）时只有 ~0.057 m/px，
 * 旧的「候选值全部落空就取 5000 m」会算出 8 万多 dp，在密度 3.5 的机器上超过 30 万 px，
 * 直接抛 IllegalArgumentException: Can't represent a width of ... in Constraints（真机闪退根因）。
 */
@Composable
private fun ScaleBar(camera: MapCamera, modifier: Modifier = Modifier) {
    // 刻度候选从 1m 起：最大缩放下也能落到几十 px，不必回退到大刻度
    val candidates = listOf(1, 2, 5, 10, 20, 25, 50, 100, 200, 500, 1000, 2000, 5000)
    val rawMpp = camera.metersPerPixel
    val mpp = if (rawMpp.isFinite() && rawMpp > 1e-6) rawMpp else 1.0
    // 取离 100px 最近的一档（任何缩放下都有解，不再依赖 60~150 的窗口）
    val best = candidates.minByOrNull { kotlin.math.abs(it / mpp - 100.0) } ?: 100
    val widthDp = (best / mpp).dp.coerceIn(16.dp, 150.dp)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .width(widthDp)
                .height(3.dp)
                .background(WColors.OnBg.copy(alpha = 0.75f), RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            if (best >= 1000) "${best / 1000} km" else "$best m",
            style = MaterialTheme.typography.labelSmall,
            color = WColors.OnBgDim,
        )
    }
}

private fun carrierName(mcc: String?, mnc: String?): String {
    if (mcc.isNullOrBlank()) return "未知运营商"
    return when (mnc) {
        "00", "02", "04", "07", "08" -> "中国移动"
        "01", "06", "09" -> "中国联通"
        "03", "05", "11", "12" -> "中国电信"
        else -> "$mcc-$mnc"
    }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

/** 读取「已从地图移除的取证标点」集合（见 Route.PREF_HIDDEN_PHOTO_MARKERS，\u0001 分隔） */
private fun readHiddenPhotoMarkers(): Set<String> =
    (AppGraph.prefs.getString(Route.PREF_HIDDEN_PHOTO_MARKERS, "") ?: "")
        .split('\u0001')
        .filter { it.isNotBlank() }
        .toSet()
