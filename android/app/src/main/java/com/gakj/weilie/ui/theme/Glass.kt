package com.gakj.weilie.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.gakj.weilie.ui.theme.Hairline.hairline

/**
 * 物料组件库（v0.12 质感版）。
 *
 * 材质原则从「液态玻璃」收敛成「哑光面板」：
 * - 面板 = 一层 5%~8% 白 + 1dp 发丝描边，不用模糊、不用外发光；
 * - 主按钮 = 实心主色胶囊（不再用主色→辅助色渐变，那种渐变在深色底上会发脏）；
 * - 所有可点元素统一 0.96/0.97 按压缩放 + 弹簧，让界面「有手感」但不飘。
 */

@Composable
fun Modifier.glass(shape: Shape = Radius.lgShape, filled: Boolean = true): Modifier {
    val bg = if (filled) Brush.linearGradient(
        colors = listOf(WColors.GlassTop, WColors.GlassBottom),
        start = Offset(0f, 0f), end = Offset(0f, Float.MAX_VALUE),
    ) else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
    return this
        .clip(shape)
        .background(bg, shape)
        .border(1.dp, Brush.verticalGradient(listOf(WColors.GlassBorder, WColors.GlassBorderSoft)), shape)
}

/**
 * 地图上的玻璃材质：底图是浅色瓦片，纯白半透明会「消失」导致文字看不清。
 * 深色主题 → 深色玻璃 + 白字；亮色主题 → 磨砂白玻璃 + 深字（色值由 applyTheme 统一给）。
 */
@Composable
fun Modifier.mapGlass(shape: Shape = Radius.lgShape, filled: Boolean = true): Modifier {
    val bg = if (filled) Brush.linearGradient(
        colors = listOf(WColors.MapGlassTop, WColors.MapGlassBottom),
        start = Offset(0f, 0f), end = Offset(0f, Float.MAX_VALUE),
    ) else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
    return this
        .clip(shape)
        .background(bg, shape)
        .border(
            1.dp,
            Brush.verticalGradient(listOf(WColors.MapGlassBorderTop, WColors.MapGlassBorderBottom)),
            shape,
        )
}

/** 面板卡片（带按压缩放反馈）；给 [onLongClick] 时支持长按 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = Radius.lgShape,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onMap: Boolean = false,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.985f else 1f,
        animationSpec = Motion.bouncy(),
        label = "cardPress",
    )
    Box(
        modifier = modifier
            .scale(scale)
            .then(if (onMap) Modifier.mapGlass(shape) else Modifier.glass(shape))
            .then(
                when {
                    onLongClick != null -> Modifier.combinedClickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = { onClick?.invoke() },
                        onLongClick = { onLongClick.invoke() },
                    )
                    onClick != null -> Modifier.clickable(interactionSource = interaction, indication = null) { onClick() }
                    else -> Modifier
                },
            )
            .padding(Space.card),
    ) { content() }
}

/**
 * 主操作按钮：实心主色胶囊。
 *
 * 旧版是「主色→紫罗兰」渐变，深色底上彩色渐变边缘会发灰发脏，且全屏主按钮
 * 过大过亮。现在改为单一主色 + 轻微顶部高光，高度固定 48dp，宽高比更稳。
 */
@Composable
fun GlowButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.965f else 1f, Motion.bouncy(), label = "btnPress")
    // 禁用态不再用「主色整体降透明度」——那样仍是一大块彩色，看起来像可点。
    // 改为中性面板 + 发丝描边 + 弱文字，语义上明确「还不能按」。
    val bg by animateColorAsState(if (enabled) WColors.Primary else WColors.ChipBg, label = "btnBg")
    val fg by animateColorAsState(if (enabled) WColors.OnPrimary else WColors.OnBgDim, label = "btnFg")
    Box(
        modifier = modifier
            .scale(scale)
            .clip(Radius.pillShape)
            .background(bg)
            .then(if (enabled) Modifier else Modifier.hairline(Radius.pillShape))
            .clickable(interactionSource = interaction, indication = null, enabled = enabled) { onClick() }
            .padding(horizontal = 24.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = fg,
        )
    }
}

/** 次级按钮：发丝描边胶囊，用于「取消 / 重画 / 关闭」这类不争夺注意力的动作 */
@Composable
fun OutlineButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.965f else 1f, Motion.bouncy(), label = "outlinePress")
    Box(
        modifier = modifier
            .scale(scale)
            .clip(Radius.pillShape)
            .then(Modifier.hairline(Radius.pillShape, strong = true))
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
            ) { onClick() }
            .padding(horizontal = 20.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = WColors.OnBg)
    }
}

/** 录制脉冲点：采集运行时呼吸动画 */
@Composable
fun PulsingDot(color: Color = WColors.Danger, size: androidx.compose.ui.unit.Dp = 10.dp) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val s by transition.animateFloat(
        initialValue = 0.85f, targetValue = 1.30f,
        animationSpec = infiniteRepeatable(tween(720), RepeatMode.Reverse), label = "pulseScale",
    )
    val glow by transition.animateFloat(
        initialValue = 0.22f, targetValue = 0.55f,
        animationSpec = infiniteRepeatable(tween(720), RepeatMode.Reverse), label = "pulseGlow",
    )
    Box(
        Modifier
            .size(size)
            .scale(s)
            .background(color.copy(alpha = glow), CircleShape)
            .background(color, CircleShape),
    )
}

/** 动画信号条：5 格，随等级点亮（弹簧逐格） */
@Composable
fun SignalBars(level: Int, barColor: Color? = null, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        for (i in 0 until 5) {
            val on = i < level.coerceAtLeast(0)
            val h = (6 + i * 4).dp
            val c by animateColorAsState(
                if (on) (barColor ?: Color(com.gakj.weilie.core.rating.SignalRating.colorArgb(level.coerceIn(0, 5)).toInt())) else WColors.BarOff,
                animationSpec = tween(Motion.normal), label = "bar$i",
            )
            Box(
                Modifier
                    .padding(horizontal = 1.5.dp)
                    .size(width = 5.dp, height = h)
                    .clip(RoundedCornerShape(2.dp))
                    .background(c),
            )
        }
        Spacer(Modifier.width(6.dp))
    }
}

/** 数据芯片：标签 + 等宽值 */
@Composable
fun StatChip(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = WColors.OnBg) {
    Column(
        modifier
            .clip(Radius.smShape)
            .background(WColors.ChipBg)
            .border(1.dp, WColors.ChipBorder, Radius.smShape)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = WColors.OnBgDim)
        Spacer(Modifier.height(2.dp))
        Text(value, style = MonoText.style, color = valueColor)
    }
}

/** 分区标题：去掉装饰性色条，用字阶 + 字距建立层级 */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            color = WColors.OnBg,
        )
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

/**
 * 小标签（eyebrow）：全大写拉丁/短中文，字距 1.2sp，弱色。
 * 用在区块上方做「分类」提示，而不是把每个标题都加粗放大。
 */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = WColors.OnBgDim) {
    Text(
        text.uppercase(),
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = color,
    )
}
