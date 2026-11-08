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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.gakj.weilie.ui.theme.SectionHeader
import com.gakj.weilie.ui.theme.ThemeController
import com.gakj.weilie.ui.theme.ThemeMode
import com.gakj.weilie.ui.theme.ThemeSpec
import com.gakj.weilie.ui.theme.WColors
import com.gakj.weilie.ui.theme.colorHsl

/** 预设主题色（名称仅用于无障碍/提示，色值与设置页色块一一对应） */
private data class AccentPreset(val name: String, val argb: Int)

private val ACCENT_PRESETS = listOf(
    AccentPreset("冷青", 0xFF22D3EE.toInt()),
    AccentPreset("海蓝", 0xFF3B82F6.toInt()),
    AccentPreset("靛紫", 0xFF8B5CF6.toInt()),
    AccentPreset("品红", 0xFFD946EF.toInt()),
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

    // 权限状态刷新计数：申请结果回来后 +1，重新读取系统权限
    var permTick by remember { mutableIntStateOf(0) }
    // 定位 / 电话 / 相机共用一个权限申请器，回调里刷新状态
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permTick++
        toast(ctx, if (granted) "已授权" else "未授权，可到系统设置中手动开启")
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
}

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
            // 第一行：主题模式
            Text("主题模式", style = MaterialTheme.typography.labelSmall, color = WColors.OnBgDim)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModePill("跟随系统", spec.mode == ThemeMode.SYSTEM, Modifier.weight(1f)) {
                    apply(spec.copy(mode = ThemeMode.SYSTEM))
                }
                ModePill("浅色", spec.mode == ThemeMode.LIGHT, Modifier.weight(1f)) {
                    apply(spec.copy(mode = ThemeMode.LIGHT))
                }
                ModePill("深色", spec.mode == ThemeMode.DARK, Modifier.weight(1f)) {
                    apply(spec.copy(mode = ThemeMode.DARK))
                }
            }

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

/** 主题模式胶囊：选中态 = 主色描边 + 20% 填充 */
@Composable
private fun ModePill(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val bg = if (selected) WColors.Primary.copy(alpha = 0.20f) else WColors.ChipBg
    val borderColor = if (selected) WColors.Primary else WColors.ChipBorder
    val fg = if (selected) WColors.Primary else WColors.OnBgDim
    Box(
        modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(999.dp))
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = fg)
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
