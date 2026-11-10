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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.alpha

/**
 * 液态玻璃材质组件库：玻璃卡、发光按钮、脉冲录制点、动画信号条、统计芯片。
 * 材质原则：多层半透明 + 细描边 + 顶部高光渐变；无性能陷阱（不实时 blur，用层叠近似）。
 */

@Composable
fun Modifier.glass(shape: Shape = RoundedCornerShape(20.dp), filled: Boolean = true): Modifier {
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
fun Modifier.mapGlass(shape: Shape = RoundedCornerShape(20.dp), filled: Boolean = true): Modifier {
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

/** 玻璃卡片容器（带按压缩放反馈）；给 [onLongClick] 时支持长按 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onMap: Boolean = false,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
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
            .padding(16.dp),
    ) { content() }
}

/** 主操作按钮：渐变发光 + 按压弹簧 */
@Composable
fun GlowButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, Motion.bouncy(), label = "btnPress")
    val alpha by animateFloatAsState(if (enabled) 1f else 0.45f, label = "btnAlpha")
    Box(
        modifier = modifier
            .scale(scale)
            .alpha(alpha)
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.linearGradient(listOf(WColors.Primary, WColors.Secondary)),
            )
            .clickable(interactionSource = interaction, indication = null, enabled = enabled) { onClick() }
            .padding(horizontal = 22.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = WColors.OnPrimary)
    }
}

/** 录制脉冲点：采集运行时呼吸动画 */
@Composable
fun PulsingDot(color: Color = WColors.Danger, size: androidx.compose.ui.unit.Dp = 10.dp) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val s by transition.animateFloat(
        initialValue = 0.85f, targetValue = 1.35f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulseScale",
    )
    val glow by transition.animateFloat(
        initialValue = 0.25f, targetValue = 0.7f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulseGlow",
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

/** 数据芯片：标签+值，等宽渲染 */
@Composable
fun StatChip(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = WColors.OnBg) {
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(WColors.ChipBg)
            .border(1.dp, WColors.ChipBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        androidx.compose.foundation.layout.Column {
            Text(label, style = MaterialTheme.typography.labelSmall, color = WColors.OnBgDim)
            Text(value, style = MonoText.style, color = valueColor)
        }
    }
}

/** 分区标题 */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(4.dp, 16.dp).clip(RoundedCornerShape(2.dp)).background(WColors.Primary))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.titleMedium, color = WColors.OnBg)
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}
