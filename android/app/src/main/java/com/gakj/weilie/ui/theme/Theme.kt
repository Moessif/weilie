package com.gakj.weilie.ui.theme

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.gakj.weilie.ui.AppGraph
import com.gakj.weilie.ui.Route

/**
 * 「围猎」色彩与排版：深夜蓝底 + 冷青主色 + 紫罗兰辅助（公安场景沉稳、荧光数据醒目）。
 *
 * 这里的每个颜色都是 Compose 快照状态：全工程几百处 `WColors.X` 读取点无需改动，
 * [applyTheme] 改写调色板后，@Composable 里读到的地方会自动重组。
 * 地图等 Canvas 绘制阶段读取的状态同样会被 Compose 追踪（draw 阶段失效）。
 *
 * 写入入口只有 [applyTheme]（本对象的 writeBase/writeAccent 是它的内部实现），
 * 避免业务代码各处改色导致主题态不一致。
 */
object WColors {
    // ---------- 背景层 ----------
    var Bg0 by mutableStateOf(Color(0xFF0B1020)); private set     // 最底层
    var Bg1 by mutableStateOf(Color(0xFF111834)); private set     // 面板层
    var Bg2 by mutableStateOf(Color(0xFF1A2447)); private set     // 卡片层

    // ---------- 玻璃材质 ----------
    var Glass by mutableStateOf(Color(0x14FFFFFF)); private set        // 玻璃填充（药丸/气泡底）
    var GlassBorder by mutableStateOf(Color(0x2EFFFFFF)); private set  // 玻璃描边
    var GlassTop by mutableStateOf(Color(0x1AFFFFFF)); private set     // 玻璃渐变起始
    var GlassBottom by mutableStateOf(Color(0x0AFFFFFF)); private set  // 玻璃渐变结束
    var GlassBorderSoft by mutableStateOf(Color(0x14FFFFFF)); private set

    // ---------- 输入/芯片 ----------
    var FieldBg by mutableStateOf(Color(0x0FFFFFFF)); private set       // 输入框聚焦底
    var FieldBgIdle by mutableStateOf(Color(0x0AFFFFFF)); private set   // 输入框常态底
    var ChipBg by mutableStateOf(Color(0x0FFFFFFF)); private set
    var ChipBorder by mutableStateOf(Color(0x1AFFFFFF)); private set
    var BarOff by mutableStateOf(Color(0x33FFFFFF)); private set        // 未点亮信号条/进度点

    // ---------- 主色（由主题色推导） ----------
    var Primary by mutableStateOf(Color(0xFF22D3EE)); private set   // 冷青
    var PrimaryDim by mutableStateOf(Color(0xFF0E7490)); private set
    var Secondary by mutableStateOf(Color(0xFFA78BFA)); private set // 紫罗兰
    var OnPrimary by mutableStateOf(Color(0xFF052E33)); private set // 主色上的文字/图标

    // ---------- 语义色（不跟主题乱变，保证现场辨识） ----------
    var Success by mutableStateOf(Color(0xFF34D399)); private set
    var Warning by mutableStateOf(Color(0xFFF59E0B)); private set
    var Danger by mutableStateOf(Color(0xFFF87171)); private set
    var Target by mutableStateOf(Color(0xFFFF6B6B)); private set     // 目标基站
    var Teammate by mutableStateOf(Color(0xFF4ADE80)); private set   // 队友
    var Track by mutableStateOf(Color(0xFF38BDF8)); private set      // 轨迹

    // ---------- 文本 ----------
    var OnBg by mutableStateOf(Color(0xFFE6EDF7)); private set
    var OnBgDim by mutableStateOf(Color(0xFF94A3B8)); private set

    // ---------- 地图专用（标签要压过浅色瓦片） ----------
    var MapGlassTop by mutableStateOf(Color(0xF20B1220)); private set
    var MapGlassBottom by mutableStateOf(Color(0xE6080D16)); private set
    var MapGlassBorderTop by mutableStateOf(Color(0x47FFFFFF)); private set
    var MapGlassBorderBottom by mutableStateOf(Color(0x12FFFFFF)); private set
    var MapLabelBg by mutableStateOf(Color(0xB30B1220)); private set  // 地图按钮下方文字底
    var MapLabelFg by mutableStateOf(Color(0xFFE6EDF7)); private set

    /** 画布网格线（自绘兜底底图用，浅色主题下换成深线才看得见） */
    var GridLine by mutableStateOf(Color(0x14FFFFFF)); private set

    /** 基础层：暗色沿用旧版值；亮色为冷灰底 + 白玻璃 + 深色文字 */
    internal fun writeBase(dark: Boolean) {
        if (dark) {
            Bg0 = Color(0xFF0B1020)
            Bg1 = Color(0xFF111834)
            Bg2 = Color(0xFF1A2447)
            Glass = Color(0x14FFFFFF)
            GlassBorder = Color(0x2EFFFFFF)
            GlassTop = Color(0x1AFFFFFF)
            GlassBottom = Color(0x0AFFFFFF)
            GlassBorderSoft = Color(0x14FFFFFF)
            FieldBg = Color(0x0FFFFFFF)
            FieldBgIdle = Color(0x0AFFFFFF)
            ChipBg = Color(0x0FFFFFFF)
            ChipBorder = Color(0x1AFFFFFF)
            BarOff = Color(0x33FFFFFF)
            OnBg = Color(0xFFE6EDF7)
            OnBgDim = Color(0xFF94A3B8)
            GridLine = Color(0x14FFFFFF)
            MapGlassTop = Color(0xF20B1220)
            MapGlassBottom = Color(0xE6080D16)
            MapGlassBorderTop = Color(0x47FFFFFF)
            MapGlassBorderBottom = Color(0x12FFFFFF)
            MapLabelBg = Color(0xB30B1220)
            MapLabelFg = Color(0xFFE6EDF7)
        } else {
            Bg0 = Color(0xFFF4F7FB)
            Bg1 = Color(0xFFFFFFFF)
            Bg2 = Color(0xFFE9EFF8)
            Glass = Color(0x8CFFFFFF)
            GlassBorder = Color(0x1F0B1020)
            GlassTop = Color(0xD9FFFFFF)
            GlassBottom = Color(0xA6FFFFFF)
            GlassBorderSoft = Color(0x140B1020)
            FieldBg = Color(0x0F0B1020)
            FieldBgIdle = Color(0x0A0B1020)
            ChipBg = Color(0x0A0B1020)
            ChipBorder = Color(0x1F0B1020)
            BarOff = Color(0x330B1020)
            OnBg = Color(0xFF0B1020)
            OnBgDim = Color(0xFF5A6B85)
            GridLine = Color(0x140B1020)
            // 百度底图是浅色瓦片：亮色主题下用「磨砂白玻璃」，靠深字读清
            MapGlassTop = Color(0xE6FFFFFF)
            MapGlassBottom = Color(0xD9F1F5FA)
            MapGlassBorderTop = Color(0x330B1020)
            MapGlassBorderBottom = Color(0x140B1020)
            // 标签药丸保持深底浅字：无论底图深浅都能压住
            MapLabelBg = Color(0xE60B1220)
            MapLabelFg = Color(0xFFF4F7FB)
        }
        // 语义色恒定：轨迹/目标/队友的辨识度优先于主题一致性
        Success = Color(0xFF34D399)
        Warning = Color(0xFFF59E0B)
        Danger = Color(0xFFF87171)
    }

    /** 主色系：由主题色推导；语义色向主题色混 20% 保持协调 */
    internal fun writeAccent(spec: ThemeSpec, dark: Boolean) {
        val custom = spec.hue >= 0f
        val accent = Color(spec.accent.toLong() and 0xFFFFFFFFL)
        val accentHsl = colorHsl(accent)

        // 亮色主题下主色亮度压到 0.33：白底上做主色文字仍有 ~4.5:1 对比度
        val lightL = 0.33f
        val primary: Color
        val primaryDim: Color
        val secondary: Color
        when {
            custom -> {
                val l = if (dark) 0.62f else lightL
                primary = hslColor(spec.hue, spec.sat, l)
                primaryDim = hslColor(spec.hue, spec.sat, l * 0.55f)
                secondary = hslColor(spec.hue + 55f, spec.sat * 0.9f, if (dark) 0.72f else 0.42f)
            }
            // 默认冷青 + 深色：沿用旧版配色，升级后观感逐像素不变
            spec.accent == DEFAULT_ACCENT && dark -> {
                primary = Color(0xFF22D3EE)
                primaryDim = Color(0xFF0E7490)
                secondary = Color(0xFFA78BFA)
            }
            else -> {
                val l = if (dark) maxOf(accentHsl[2], 0.58f) else minOf(accentHsl[2], lightL)
                primary = hslColor(accentHsl[0], accentHsl[1], l)
                primaryDim = hslColor(accentHsl[0], accentHsl[1], l * 0.55f)
                secondary = hslColor(accentHsl[0] + 55f, accentHsl[1] * 0.9f, if (dark) 0.72f else 0.42f)
            }
        }
        Primary = primary
        PrimaryDim = primaryDim
        Secondary = secondary
        OnPrimary = onColorFor(primary)

        // 语义色向主题色混 20%：跟主题协调，但绿/红/蓝的相对关系不变，现场仍一眼可分
        Track = mix(Color(0xFF38BDF8), primary, 0.20f)
        Target = mix(Color(0xFFFF6B6B), primary, 0.20f)
        Teammate = mix(Color(0xFF4ADE80), primary, 0.20f)
    }
}

/** 主题模式：跟随系统 / 强制浅色 / 强制深色 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** 默认主题色（冷青） */
val DEFAULT_ACCENT: Int = 0xFF22D3EE.toInt()

/**
 * 一套主题参数（持久化到 prefs）。
 * - [accent]：预设色的 ARGB；
 * - [hue]/[sat]：自定义色相/饱和度；[hue] < 0 表示用预设 [accent]，否则用 HSL 生成主色。
 */
data class ThemeSpec(
    val mode: ThemeMode = ThemeMode.DARK,
    val accent: Int = DEFAULT_ACCENT,
    val hue: Float = -1f,
    val sat: Float = 1f,
)

// ---------- 颜色工具 ----------

/** 由 HSL 生成颜色（h 0..360, s 0..1, l 0..1），自己算避免隐藏 API */
private fun hslColor(h: Float, s: Float, l: Float): Color {
    val hue = ((h % 360f) + 360f) % 360f
    val sat = s.coerceIn(0f, 1f)
    val lit = l.coerceIn(0f, 1f)
    val c = (1f - kotlin.math.abs(2f * lit - 1f)) * sat
    val x = c * (1f - kotlin.math.abs((hue / 60f) % 2f - 1f))
    val m = lit - c / 2f
    val r: Float
    val g: Float
    val b: Float
    when {
        hue < 60f -> { r = c; g = x; b = 0f }
        hue < 120f -> { r = x; g = c; b = 0f }
        hue < 180f -> { r = 0f; g = c; b = x }
        hue < 240f -> { r = 0f; g = x; b = c }
        hue < 300f -> { r = x; g = 0f; b = c }
        else -> { r = c; g = 0f; b = x }
    }
    return Color(r + m, g + m, b + m, 1f)
}

/** 取颜色的 HSL（h 0..360, s 0..1, l 0..1），设置页滑杆回显用 */
fun colorHsl(color: Color): FloatArray {
    val r = color.red
    val g = color.green
    val b = color.blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val d = max - min
    val l = (max + min) / 2f
    if (d == 0f) return floatArrayOf(0f, 0f, l)
    val s = d / (1f - kotlin.math.abs(2f * l - 1f))
    val h = when (max) {
        r -> 60f * (((g - b) / d) % 6f)
        g -> 60f * (((b - r) / d) + 2f)
        else -> 60f * (((r - g) / d) + 4f)
    }
    return floatArrayOf((h + 360f) % 360f, s.coerceIn(0f, 1f), l)
}

/** 两色线性混合，t=0 取 a，t=1 取 b */
private fun mix(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = 1f,
)

private fun onColorFor(bg: Color): Color =
    if (bg.luminance() > 0.45f) Color(0xFF052E33) else Color.White

/**
 * 应用一套主题：全应用所有颜色都从这里刷写，是调色板的唯一外部写入口。
 * @param systemDark 系统当前是否深色（[ThemeMode.SYSTEM] 时生效）
 */
/** 主题规格是否落在深色（SYSTEM 跟随系统）。系统栏图标、窗口底色都按这个值切。 */
fun resolveDark(
    @Suppress("UNUSED_PARAMETER") spec: ThemeSpec,
    @Suppress("UNUSED_PARAMETER") systemDark: Boolean,
): Boolean {
    // 产品决策（用户 2026-11-10）：取消浅色模式，只保留深色模式。
    // 旧 prefs 里可能还存着 LIGHT/SYSTEM，读出来一律按深色渲染，避免升级后界面变形。
    return true
}

fun applyTheme(spec: ThemeSpec, systemDark: Boolean) {
    val dark = resolveDark(spec, systemDark)
    WColors.writeBase(dark)
    WColors.writeAccent(spec, dark)
}

// ---------- Material3 配色 ----------

private fun buildScheme(dark: Boolean): ColorScheme {
    val onSecondary = onColorFor(WColors.Secondary)
    val primaryContainer = if (dark) WColors.PrimaryDim else WColors.Primary.copy(alpha = 0.15f)
    return if (dark) {
        darkColorScheme(
            primary = WColors.Primary,
            onPrimary = WColors.OnPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = WColors.OnBg,
            secondary = WColors.Secondary,
            onSecondary = onSecondary,
            background = WColors.Bg0,
            onBackground = WColors.OnBg,
            surface = WColors.Bg1,
            onSurface = WColors.OnBg,
            surfaceVariant = WColors.Bg2,
            onSurfaceVariant = WColors.OnBgDim,
            error = WColors.Danger,
            onError = Color(0xFF2A0B0B),
            outline = WColors.GlassBorder,
        )
    } else {
        lightColorScheme(
            primary = WColors.Primary,
            onPrimary = WColors.OnPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = WColors.OnBg,
            secondary = WColors.Secondary,
            onSecondary = onSecondary,
            background = WColors.Bg0,
            onBackground = WColors.OnBg,
            surface = WColors.Bg1,
            onSurface = WColors.OnBg,
            surfaceVariant = WColors.Bg2,
            onSurfaceVariant = WColors.OnBgDim,
            error = WColors.Danger,
            onError = Color.White,
            outline = WColors.GlassBorder,
        )
    }
}

// ---------- 主题状态 ----------

/**
 * 主题唯一数据源：设置页改 → 写 prefs + 立即 applyTheme；
 * 启动时 [install] 在 setContent 之前先着色一帧，避免白/黑闪一下。
 */
object ThemeController {
    var spec by mutableStateOf(ThemeSpec())
        private set

    fun install(context: Context) {
        spec = readPrefs()
        applyTheme(spec, systemDarkNow(context))
    }

    fun update(newSpec: ThemeSpec, systemDark: Boolean) {
        spec = newSpec
        writePrefs(newSpec)
        applyTheme(newSpec, systemDark)
    }

    fun systemDarkNow(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun readPrefs(): ThemeSpec {
        val p = AppGraph.prefs
        // 只保留深色模式：历史版本可能存过 LIGHT/SYSTEM，这里不再读取，统一按深色
        return ThemeSpec(
            mode = ThemeMode.DARK,
            accent = p.getInt(Route.PREF_THEME_ACCENT, DEFAULT_ACCENT),
            hue = p.getFloat(Route.PREF_THEME_HUE, -1f),
            sat = p.getFloat(Route.PREF_THEME_SAT, 1f),
        )
    }

    private fun writePrefs(spec: ThemeSpec) {
        AppGraph.prefs.edit()
            .putString(Route.PREF_THEME_MODE, spec.mode.name)
            .putInt(Route.PREF_THEME_ACCENT, spec.accent)
            .putFloat(Route.PREF_THEME_HUE, spec.hue)
            .putFloat(Route.PREF_THEME_SAT, spec.sat)
            .apply()
    }
}

// ---------- 排版 ----------

private val AppTypography = Typography(
    displaySmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 34.sp, letterSpacing = 0.2.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 26.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 13.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 10.5.sp, letterSpacing = 0.4.sp),
    // 等宽数据（工程参数面板专用）
)

/** 工程参数用等宽风格（无自定义字体时用默认等宽回退） */
object MonoText {
    val style = TextStyle(fontWeight = FontWeight.Medium, fontSize = 13.sp, letterSpacing = 0.2.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
}

@Composable
fun WeilieTheme(content: @Composable () -> Unit) {
    val spec = ThemeController.spec
    val systemDark = isSystemInDarkTheme()
    // 组合成功后再刷调色板：改设置/系统切明暗都会走到这里（同值写入不会引发额外重组）
    SideEffect { applyTheme(spec, systemDark) }
    val dark = resolveDark(spec, systemDark)
    MaterialTheme(
        colorScheme = buildScheme(dark),
        typography = AppTypography,
        content = content,
    )
}
