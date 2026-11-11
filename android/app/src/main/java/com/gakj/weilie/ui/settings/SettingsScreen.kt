package com.gakj.weilie.ui.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import com.gakj.weilie.core.telephony.ShizukuCells
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.Date
import com.gakj.weilie.ui.AppGraph
import com.gakj.weilie.ui.Route
import com.gakj.weilie.ui.common.GlassTextButton
import com.gakj.weilie.ui.common.GlassTextField
import com.gakj.weilie.ui.common.ScreenTopBar
import com.gakj.weilie.ui.common.StatusBadge
import com.gakj.weilie.ui.common.hasLocationPermission
import com.gakj.weilie.ui.common.hasNotificationPermission
import com.gakj.weilie.ui.common.hasPerm
import com.gakj.weilie.ui.common.toast
import com.gakj.weilie.ui.theme.DEFAULT_ACCENT
import com.gakj.weilie.ui.theme.GlassCard
import com.gakj.weilie.ui.theme.GlowButton
import com.gakj.weilie.ui.theme.MonoText
import com.gakj.weilie.ui.theme.SectionHeader
import com.gakj.weilie.ui.theme.ThemeController
import com.gakj.weilie.ui.theme.ThemeSpec
import com.gakj.weilie.ui.theme.WColors
import com.gakj.weilie.ui.theme.colorHsl
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** 预设主题色（名称仅用于无障碍/提示，色值与设置页色块一一对应） */
private data class AccentPreset(val name: String, val argb: Int)

private val ACCENT_PRESETS = listOf(
    AccentPreset("冷青", 0xFF22D3EE.toInt()),
    AccentPreset("海蓝", 0xFF3B82F6.toInt()),
    AccentPreset("湖蓝", 0xFF0EA5E9.toInt()),
    AccentPreset("松绿", 0xFF059669.toInt()),
    AccentPreset("玫红", 0xFFF43F5E.toInt()),
    AccentPreset("橙金", 0xFFF59E0B.toInt()),
    AccentPreset("玉绿", 0xFF10B981.toInt()),
    AccentPreset("青柠", 0xFF84CC16.toInt()),
    AccentPreset("石墨", 0xFF64748B.toInt()),
    AccentPreset("警蓝", 0xFF1D4ED8.toInt()),
)

/**
 * 设置：外观（主题模式 + 主题色）+ AI 助手 + 协同服务器 + 权限状态 + 关于。
 */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var aiKey by remember { mutableStateOf(AppGraph.prefs.getString(Route.PREF_AI_KEY, "") ?: "") }
    var aiUrl by remember {
        mutableStateOf(
            (AppGraph.prefs.getString(Route.PREF_AI_URL, "") ?: "").ifBlank { "https://api.deepseek.com" },
        )
    }
    var aiModel by remember {
        mutableStateOf(
            (AppGraph.prefs.getString(Route.PREF_AI_MODEL, "") ?: "").ifBlank { "deepseek-chat" },
        )
    }
    var server by remember { mutableStateOf(AppGraph.prefs.getString(Route.PREF_SERVER, "") ?: "") }
    var baiduAk by remember { mutableStateOf(AppGraph.prefs.getString(Route.PREF_BAIDU_AK, "") ?: "") }

    // 需求 2：GPS 双重安检门槛（默认 50m / 3m，可调）
    var accGate by remember {
        mutableFloatStateOf(AppGraph.prefs.getFloat(Route.PREF_GATE_ACCURACY, 50f))
    }
    var distGate by remember {
        mutableFloatStateOf(AppGraph.prefs.getFloat(Route.PREF_GATE_MIN_DIST, 1f))
    }

    // 打点间隔：预设 1/2/5 秒之外还能手动输入（0.2 ~ 600 秒）
    var intervalMs by remember {
        mutableLongStateOf(AppGraph.prefs.getLong(Route.PREF_SAMPLE_INTERVAL, 1_000L))
    }
    var intervalEdit by remember { mutableStateOf(false) }
    var intervalText by remember { mutableStateOf("") }

    // 权限状态刷新计数：申请结果回来后 +1，重新读取系统权限
    var permTick by remember { mutableIntStateOf(0) }
    // 定位 / 电话 / 相机共用一个权限申请器，回调里刷新状态
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permTick++
        toast(ctx, if (granted) "已授权" else "未授权，可到系统设置中手动开启")
    }
    // Shizuku 邻区增强：授权后以 shell 身份读系统全量小区（补系统不给的邻区）
    var shizukuTick by remember { mutableIntStateOf(0) }
    val shizukuInstalled = remember { ShizukuCells.isInstalled(ctx) }
    val shizukuAlive = remember(shizukuTick) { ShizukuCells.isBinderAlive() }
    val shizukuGranted = remember(shizukuTick) { ShizukuCells.isAuthorized() }
    // 诊断：进页面（或点「刷新状态」）时真读一次，证明 binder 真的能拿到小区
    var shizukuDiag by remember { mutableStateOf<ShizukuCells.LastRead?>(null) }
    LaunchedEffect(shizukuGranted, shizukuTick) {
        if (shizukuGranted) {
            shizukuDiag = withContext(Dispatchers.IO) {
                ShizukuCells.readRegistry()
                ShizukuCells.lastRead
            }
        }
    }
    val shizukuListener = remember {
        Shizuku.OnRequestPermissionResultListener { code, result ->
            if (code == ShizukuCells.REQUEST_CODE) {
                shizukuTick++
                val ok = result == android.content.pm.PackageManager.PERMISSION_GRANTED
                toast(ctx, if (ok) "Shizuku 已授权：邻区将读取系统全量列表" else "Shizuku 授权被拒绝，可稍后重试")
            }
        }
    }
    DisposableEffect(Unit) {
        ShizukuCells.addResultListener(shizukuListener)
        onDispose { ShizukuCells.removeResultListener(shizukuListener) }
    }
    val locOk = remember(permTick) { hasLocationPermission(ctx) }
    val notifOk = remember(permTick) { hasNotificationPermission(ctx) }
    val phoneOk = remember(permTick) { hasPerm(ctx, Manifest.permission.READ_PHONE_STATE) }
    val camOk = remember(permTick) { hasPerm(ctx, Manifest.permission.CAMERA) }

    Column(
        Modifier
            .fillMaxSize()
            .background(WColors.Bg0)
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenTopBar("设置", "外观 · AI 助手 · 权限", onBack)

        // ---------- 外观 ----------
        SectionHeader("外观", modifier = Modifier.padding(horizontal = 20.dp))
        AppearanceSection()
        Spacer(Modifier.height(16.dp))

        // ---------- 采集与安检 ----------
        SectionHeader("采集与安检", modifier = Modifier.padding(horizontal = 20.dp))
        GlassCard(Modifier.padding(horizontal = 20.dp)) {
            Column {
                GateSliderRow(
                    title = "定位精度门槛",
                    valueText = "${accGate.roundToInt()} m",
                    hint = "GPS 误差超过该值直接丢弃并提示（默认 50 m）",
                    value = accGate,
                    range = 10f..100f,
                ) { v ->
                    accGate = v
                    AppGraph.prefs.edit().putFloat(Route.PREF_GATE_ACCURACY, v).apply()
                    AppGraph.currentController?.maxAccuracyM = v.toDouble()
                }
                Spacer(Modifier.height(6.dp))
                GateSliderRow(
                    title = "防抖距离门槛",
                    valueText = "${distGate.roundToInt()} m",
                    hint = "距上个成功点小于该值时合并为一点（默认 1 m）",
                    value = distGate,
                    range = 1f..10f,
                ) { v ->
                    distGate = v
                    AppGraph.prefs.edit().putFloat(Route.PREF_GATE_MIN_DIST, v).apply()
                    AppGraph.currentController?.minDistanceM = v.toDouble()
                }
                Spacer(Modifier.height(6.dp))
                // 打点间隔：预设之外还能手动输入（需求：打点频率支持手动填写）
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            intervalText = fmtInterval(intervalMs)
                            intervalEdit = true
                        }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "打点间隔",
                            style = MaterialTheme.typography.bodyMedium,
                            color = WColors.OnBg,
                        )
                        Text(
                            "默认 1 秒，可手动输入 0.2~600 秒（间隔越短越费电）",
                            style = MaterialTheme.typography.labelSmall,
                            color = WColors.OnBgDim,
                        )
                    }
                    Text(
                        fmtInterval(intervalMs),
                        style = MonoText.style,
                        color = WColors.Primary,
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        // ---------- 邻区增强（Shizuku） ----------
        SectionHeader("邻区增强（Shizuku）", modifier = Modifier.padding(horizontal = 20.dp))
        GlassCard(Modifier.padding(horizontal = 20.dp)) {
            Column {
                Text(
                    "系统对普通应用只返回驻留小区，邻区常被隐藏。授权 Shizuku 后，围猎会以 shell 身份读取系统全量小区列表，补全邻区并启用「邻区命中」判定。",
                    style = MaterialTheme.typography.bodySmall,
                    color = WColors.OnBgDim,
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusBadge(
                        text = when {
                            !shizukuInstalled -> "未安装"
                            !shizukuAlive -> "服务未运行"
                            shizukuGranted -> "已授权"
                            else -> "未授权"
                        },
                        color = when {
                            shizukuGranted -> WColors.Success
                            shizukuInstalled -> WColors.Warning
                            else -> WColors.OnBgDim
                        },
                    )
                    Spacer(Modifier.weight(1f))
                    GlassTextButton(
                        text = when {
                            !shizukuInstalled -> "去安装"
                            !shizukuAlive -> "打开 Shizuku"
                            shizukuGranted -> "刷新状态"
                            else -> "申请授权"
                        },
                    ) {
                        when {
                            !shizukuInstalled -> toast(ctx, "未检测到 Shizuku，请先安装 Shizuku 并启动服务")
                            !shizukuAlive -> {
                                if (!ShizukuCells.openShizukuApp(ctx)) toast(ctx, "请先打开 Shizuku 并启动服务")
                            }
                            shizukuGranted -> {
                                shizukuTick++
                                toast(ctx, "Shizuku 已授权")
                            }
                            else -> if (!ShizukuCells.requestPermission()) {
                                toast(ctx, "授权请求发送失败，请确认 Shizuku 正在运行")
                            }
                        }
                    }
                }
                if (shizukuInstalled && shizukuAlive) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Shizuku 服务 v${ShizukuCells.versionCode()} · uid ${ShizukuCells.serviceUid()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = WColors.OnBgDim,
                        )
                        Spacer(Modifier.weight(1f))
                        GlassTextButton("重新申请授权") {
                            if (!ShizukuCells.requestPermission()) {
                                toast(ctx, "授权请求发送失败，请确认 Shizuku 正在运行")
                            } else {
                                toast(ctx, "已向 Shizuku 重新申请，请在弹窗里点允许")
                            }
                        }
                    }
                }
                if (shizukuGranted) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "每 2 秒刷新一次；未授权时自动回退系统 API，不影响主区采集。部分 ROM 限制应用列表权限，Shizuku 管理器里不显示围猎属正常，以本页状态和下方读取结果为准。",
                        style = MaterialTheme.typography.labelSmall,
                        color = WColors.OnBgDim,
                    )
                    // 诊断行：证明 binder 真的读到了东西（Shizuku 的「应用管理」列表为空也可能能读）
                    val diag = shizukuDiag
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (diag == null) {
                            "最近读取：等待中…（每 2 秒一次）"
                        } else {
                            val time = SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                                .format(Date(diag.atEpochMs))
                            buildString {
                                append("最近读取 $time：")
                                append(diag.cellCount)
                                append(" 条小区 · 邻区 ")
                                append(diag.neighborCount)
                                append(" 条")
                                if (diag.error != null) {
                                    append(" · ")
                                    append(diag.error)
                                }
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (diag?.error != null) WColors.Warning else WColors.OnBgDim,
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        // ---------- AI 助手 ----------
        SectionHeader("AI 通信助手", modifier = Modifier.padding(horizontal = 20.dp))
        GlassCard(Modifier.padding(horizontal = 20.dp)) {
            Column {
                GlassTextField(
                    value = aiKey,
                    onValueChange = { aiKey = it.trim() },
                    label = "API Key（DeepSeek 或兼容服务）",
                    placeholder = "sk-…",
                    mono = true,
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                GlassTextField(
                    value = aiUrl,
                    onValueChange = { aiUrl = it.trim() },
                    label = "Base URL",
                    mono = true,
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                GlassTextField(
                    value = aiModel,
                    onValueChange = { aiModel = it.trim() },
                    label = "模型",
                    placeholder = "deepseek-chat",
                    mono = true,
                    singleLine = true,
                )
                Spacer(Modifier.height(12.dp))
                GlowButton("保存 AI 配置", Modifier.fillMaxWidth()) {
                    AppGraph.prefs.edit()
                        .putString(Route.PREF_AI_KEY, aiKey)
                        .putString(Route.PREF_AI_URL, aiUrl)
                        .putString(Route.PREF_AI_MODEL, aiModel)
                        .apply()
                    AppGraph.refreshAiConfig()
                    toast(ctx, "AI 配置已保存")
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        // ---------- 协同服务器 ----------
        SectionHeader("协同服务器", modifier = Modifier.padding(horizontal = 20.dp))
        GlassCard(Modifier.padding(horizontal = 20.dp)) {
            Column {
                GlassTextField(
                    value = server,
                    onValueChange = { server = it.trim() },
                    label = "服务器地址",
                    placeholder = "http://服务器IP:8080",
                    mono = true,
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                GlassTextField(
                    value = baiduAk,
                    onValueChange = { baiduAk = it.trim() },
                    label = "百度地图 AK（可选，地图标注用）",
                    mono = true,
                    singleLine = true,
                )
                Spacer(Modifier.height(12.dp))
                GlowButton("保存服务器配置", Modifier.fillMaxWidth()) {
                    AppGraph.prefs.edit()
                        .putString(Route.PREF_SERVER, server)
                        .putString(Route.PREF_BAIDU_AK, baiduAk)
                        .apply()
                    toast(ctx, "服务器配置已保存")
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        // ---------- 权限 ----------
        SectionHeader("权限状态", modifier = Modifier.padding(horizontal = 20.dp))
        GlassCard(Modifier.padding(horizontal = 20.dp)) {
            Column {
                // 三行核心权限可点击：已授权只提示，未授权当场申请，申请完自动刷新
                PermRow("定位权限", locOk) {
                    if (locOk) toast(ctx, "已授权")
                    else permLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                }
                Spacer(Modifier.height(8.dp))
                PermRow("电话权限", phoneOk) {
                    if (phoneOk) toast(ctx, "已授权")
                    else permLauncher.launch(Manifest.permission.READ_PHONE_STATE)
                }
                Spacer(Modifier.height(8.dp))
                PermRow("相机权限", camOk) {
                    if (camOk) toast(ctx, "已授权")
                    else permLauncher.launch(Manifest.permission.CAMERA)
                }
                Spacer(Modifier.height(8.dp))
                PermRow("通知权限（前台服务保活）", notifOk)
                Spacer(Modifier.height(12.dp))
                Text(
                    "若权限被永久拒绝，请到系统设置中手动开启",
                    style = MaterialTheme.typography.labelSmall,
                    color = WColors.OnBgDim,
                )
                Spacer(Modifier.height(8.dp))
                GlowButton("打开系统设置", Modifier.fillMaxWidth()) {
                    runCatching {
                        ctx.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }.onFailure { toast(ctx, "无法打开系统设置") }
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        // ---------- 关于 ----------
        SectionHeader("关于", modifier = Modifier.padding(horizontal = 20.dp))
        GlassCard(Modifier.padding(horizontal = 20.dp)) {
            Column {
                Text("围猎 v${com.gakj.weilie.BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium, color = WColors.OnBg)
                Spacer(Modifier.height(4.dp))
                Text(
                    "蜂窝网络覆盖实测与协同定位工具。数据仅保存在本机，导出由使用者自行管理。\n\n" +
                        "所需权限：定位、通知、振动、网络。\n\n" +
                        "仅供合法执法与教学场景使用。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = WColors.OnBgDim,
                )
            }
        }
        Spacer(Modifier.navigationBarsPadding().height(24.dp))
    }

    // 打点间隔手动输入（0.2 ~ 600 秒）
    if (intervalEdit) {
        AlertDialog(
            onDismissRequest = { intervalEdit = false },
            containerColor = WColors.Bg1,
            title = { Text("打点间隔", color = WColors.OnBg) },
            text = {
                Column {
                    GlassTextField(
                        value = intervalText,
                        onValueChange = { raw ->
                            intervalText = raw.filter { it.isDigit() || it == '.' }.take(7)
                        },
                        label = "秒",
                        placeholder = "1.0",
                        mono = true,
                        singleLine = true,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "允许 0.2 ~ 600 秒：越短越贴合路线但更耗电；在设置里改后立即生效。",
                        style = MaterialTheme.typography.labelSmall,
                        color = WColors.OnBgDim,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val sec = intervalText.toDoubleOrNull()
                    if (sec == null || sec < 0.2 || sec > 600.0) {
                        toast(ctx, "请输入 0.2 ~ 600 之间的秒数")
                    } else {
                        val ms = (sec * 1000).roundToLong()
                        intervalMs = ms
                        AppGraph.prefs.edit().putLong(Route.PREF_SAMPLE_INTERVAL, ms).apply()
                        AppGraph.currentController?.sampleIntervalMs = ms
                        intervalEdit = false
                        toast(ctx, "打点间隔已设为 ${fmtInterval(ms)}")
                    }
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { intervalEdit = false }) { Text("取消") }
            },
        )
    }
}

/** 打点间隔的展示文案：整秒显示「N 秒」，否则保留一位小数。 */
private fun fmtInterval(ms: Long): String =
    if (ms % 1000L == 0L) "${ms / 1000L} 秒" else String.format(java.util.Locale.US, "%.1f 秒", ms / 1000.0)

/**
 * 外观区块：主题模式 + 10 个预设色 + 自定义色相/饱和度 + 实时预览。
 * 任何改动都立刻 apply 并写入 prefs，没有“保存”按钮。
 */
@Composable
private fun AppearanceSection() {
    val systemDark = isSystemInDarkTheme()
    val spec = ThemeController.spec
    val apply: (ThemeSpec) -> Unit = { ThemeController.update(it, systemDark) }

    // 自定义滑杆回显：预设态取预设色的 HSL，自定义态取保存的 hue/sat
    val custom = spec.hue >= 0f
    val accentHsl = colorHsl(Color(spec.accent.toLong() and 0xFFFFFFFFL))
    val hueValue = if (custom) spec.hue else accentHsl[0]
    val satValue = if (custom) spec.sat else accentHsl[1]
    val sliderColors = SliderDefaults.colors(
        thumbColor = WColors.Primary,
        activeTrackColor = WColors.Primary,
        inactiveTrackColor = WColors.ChipBorder,
    )

    GlassCard(Modifier.padding(horizontal = 20.dp)) {
        Column {
            // 第一行：主题模式（只保留深色：户外/夜间执法场景更省电、对比度更稳）
            Text("主题模式", style = MaterialTheme.typography.labelSmall, color = WColors.OnBgDim)
            Spacer(Modifier.height(8.dp))
            Text(
                "深色模式（固定）",
                style = MaterialTheme.typography.bodyMedium,
                color = WColors.OnBg,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "围猎只提供深色界面：夜间/车内使用不刺眼，地图覆盖物对比度也更高。",
                style = MaterialTheme.typography.labelSmall,
                color = WColors.OnBgDim,
            )

            Spacer(Modifier.height(18.dp))

            // 第二行：主题色（10 个预设，两行放得下且点得准）
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("主题色", style = MaterialTheme.typography.labelSmall, color = WColors.OnBgDim)
                Spacer(Modifier.weight(1f))
                Text(
                    if (custom) "自定义" else ACCENT_PRESETS.firstOrNull { it.argb == spec.accent }?.name ?: "自定义",
                    style = MaterialTheme.typography.labelSmall,
                    color = WColors.OnBgDim,
                )
            }
            Spacer(Modifier.height(10.dp))
            ACCENT_PRESETS.chunked(5).forEach { rowPresets ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    rowPresets.forEach { preset ->
                        AccentSwatch(
                            color = Color(preset.argb.toLong() and 0xFFFFFFFFL),
                            selected = !custom && spec.accent == preset.argb,
                        ) {
                            apply(spec.copy(accent = preset.argb, hue = -1f))
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            Spacer(Modifier.height(6.dp))

            // 第三行：自定义色相 / 饱和度（一动就退出预设选中）
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("自定义", style = MaterialTheme.typography.labelSmall, color = WColors.OnBgDim)
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(WColors.Primary)
                        .border(1.dp, WColors.ChipBorder, CircleShape),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "H ${hueValue.toInt()}° · S ${(satValue * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = WColors.OnBgDim,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text("色相", style = MaterialTheme.typography.labelSmall, color = WColors.OnBgDim)
            Slider(
                value = hueValue,
                onValueChange = { h -> apply(spec.copy(hue = h, sat = satValue)) },
                valueRange = 0f..360f,
                colors = sliderColors,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("饱和度", style = MaterialTheme.typography.labelSmall, color = WColors.OnBgDim)
            Slider(
                value = satValue,
                onValueChange = { s -> apply(spec.copy(hue = hueValue, sat = s)) },
                valueRange = 0.3f..1f,
                colors = sliderColors,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))

            // 底部实时预览卡：改色立即生效，所见即所得
            GlassCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("围猎 · 主题预览", style = MaterialTheme.typography.titleMedium, color = WColors.OnBg)
                        Spacer(Modifier.weight(1f))
                        StatusBadge("警戒", WColors.Warning)
                        Spacer(Modifier.width(6.dp))
                        StatusBadge("高危", WColors.Danger)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "轨迹、目标、队友保持固定语义色；主题色作用于按钮、选中态与数据高亮。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = WColors.OnBgDim,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlowButton("开始采集", Modifier.weight(1f)) {}
                        GlassTextButton("次要操作", Modifier.weight(1f)) {}
                    }
                }
            }
        }
    }
}

/** 预设色块：36dp 圆，选中带对勾（对勾颜色按色块亮度自动反色，深色块上也能看清） */
@Composable
private fun AccentSwatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(color)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) WColors.OnBg else WColors.ChipBorder,
                shape = CircleShape,
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = "已选主题色",
                tint = if (color.luminance() > 0.55f) Color(0xFF0B1020) else Color.White,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** 权限行：右侧状态徽章；传了 onClick 就整行可点击（点击去申请权限） */
@Composable
private fun PermRow(text: String, ok: Boolean, onClick: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = WColors.OnBg, modifier = Modifier.weight(1f))
        StatusBadge(if (ok) "已授权" else "未授权", if (ok) WColors.Success else WColors.Danger)
    }
}


/** 采集门槛滑杆（需求 2：精度/防抖门槛可调） */
@Composable
private fun GateSliderRow(
    title: String,
    valueText: String,
    hint: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = WColors.OnBg)
            Text(valueText, style = MaterialTheme.typography.labelMedium, color = WColors.Primary)
        }
        Text(hint, style = MaterialTheme.typography.labelSmall, color = WColors.OnBgDim)
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = WColors.Primary,
                activeTrackColor = WColors.Primary,
                inactiveTrackColor = WColors.GlassBorder,
            ),
            modifier = Modifier.fillMaxWidth().height(28.dp),
        )
    }
}
