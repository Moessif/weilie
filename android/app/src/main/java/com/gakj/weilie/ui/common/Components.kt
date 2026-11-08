package com.gakj.weilie.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gakj.weilie.ui.theme.Motion
import com.gakj.weilie.ui.theme.MonoText
import com.gakj.weilie.ui.theme.WColors
import com.gakj.weilie.ui.theme.glass
import com.gakj.weilie.ui.theme.mapGlass

/**
 * 通用 UI 组件：列表项错峰入场、玻璃圆形按钮、页头、玻璃输入框、状态徽章、键值行。
 */

/** 列表项错峰入场：透明度 + 上移，逐项延迟 */
@Composable
fun StaggerIn(index: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(
            1f,
            tween(Motion.normal, delayMillis = (index * 45).coerceAtMost(450), easing = Motion.EaseEmphasized),
        )
    }
    Box(
        modifier.graphicsLayer {
            alpha = progress.value
            translationY = (1f - progress.value) * 28f
        },
    ) { content() }
}

/** 玻璃圆形图标按钮：按压缩放弹簧，可选下方小字标签；content 缺省用 icon */
@Composable
fun GlassIconButton(
    icon: ImageVector? = null,
    label: String? = null,
    size: Dp = 52.dp,
    tint: Color = WColors.OnBg,
    onMap: Boolean = false,
    content: (@Composable androidx.compose.foundation.layout.BoxScope.() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, Motion.bouncy(), label = "glassBtnScale")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(size)
                .scale(scale)
                .then(if (onMap) Modifier.mapGlass(CircleShape) else Modifier.glass(shape = CircleShape))
                .clickable(interactionSource = interaction, indication = null) { onClick() },
            contentAlignment = Alignment.Center,
        ) {
            if (content != null) content()
            else icon?.let { Icon(it, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp)) }
        }
        if (label != null) {
            Spacer(Modifier.height(4.dp))
            if (onMap) {
                // 地图上底图可能是浅色瓦片：标签用高对比药丸（深底浅字），颜色随主题派生
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = WColors.MapLabelFg,
                    modifier = Modifier
                        .background(WColors.MapLabelBg, RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                )
            } else {
                Text(label, style = MaterialTheme.typography.labelSmall, color = WColors.OnBgDim)
            }
        }
    }
}

/** 子页统一页头：返回键 + 标题 + 副标题 + 右侧插槽 */
@Composable
fun ScreenTopBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    onMap: Boolean = false,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .then(
                if (onMap) Modifier
                    .padding(top = 6.dp)
                    .mapGlass(RoundedCornerShape(18.dp))
                else Modifier,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            val interaction = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .size(40.dp)
                    .glass(shape = CircleShape)
                    .clickable(interactionSource = interaction, indication = null) { onBack() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回", tint = WColors.OnBg, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = WColors.OnBg)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = WColors.OnBgDim)
            }
        }
        trailing()
    }
}

/** 玻璃质感输入框：统一配色与圆角，支持密码 / 等宽 */
@Composable
fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "",
    singleLine: Boolean = true,
    minLines: Int = 1,
    isPassword: Boolean = false,
    mono: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    Column(modifier) {
        if (label != null) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = WColors.OnBgDim)
            Spacer(Modifier.height(4.dp))
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(
                    placeholder,
                    color = WColors.OnBgDim,
                    style = if (mono) MonoText.style else MaterialTheme.typography.bodyMedium,
                )
            },
            singleLine = singleLine,
            minLines = minLines,
            visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            textStyle = if (mono) MonoText.style else MaterialTheme.typography.bodyLarge,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = WColors.Primary,
                unfocusedBorderColor = WColors.GlassBorder,
                focusedContainerColor = WColors.FieldBg,
                unfocusedContainerColor = WColors.FieldBgIdle,
                cursorColor = WColors.Primary,
                focusedTextColor = WColors.OnBg,
                unfocusedTextColor = WColors.OnBg,
            ),
            shape = RoundedCornerShape(14.dp),
        )
    }
}

/** 状态徽章：半透明底色胶囊 */
@Composable
fun StatusBadge(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.16f))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

/** 键值行：左标签右等宽值 */
@Composable
fun KeyValueRow(key: String, value: String, valueColor: Color = WColors.OnBg) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text(key, style = MaterialTheme.typography.bodyMedium, color = WColors.OnBgDim)
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            style = MonoText.style,
            color = valueColor,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 次级玻璃文字按钮（并排小按钮用） */
@Composable
fun GlassTextButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, Motion.bouncy(), label = "gtbScale")
    Box(
        modifier
            .scale(scale)
            .clip(RoundedCornerShape(14.dp))
            .background(if (enabled) WColors.ChipBg else WColors.ChipBg.copy(alpha = 0.35f))
            .border(1.dp, WColors.GlassBorder, RoundedCornerShape(14.dp))
            .clickable(interactionSource = interaction, indication = null, enabled = enabled) { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) WColors.Primary else WColors.OnBgDim,
        )
    }
}

/** 宽度动画小圆点（向导页进度指示用） */
@Composable
fun AnimatedDot(active: Boolean, activeColor: Color = WColors.Primary) {
    val w by animateDpAsState(if (active) 22.dp else 8.dp, Motion.bouncy(), label = "dotW")
    Box(
        Modifier
            .padding(horizontal = 4.dp)
            .size(width = w, height = 8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (active) activeColor else WColors.BarOff),
    )
}

/** 空状态占位：图标 + 主文 + 副文 */
@Composable
fun EmptyState(title: String, desc: String, icon: ImageVector? = null) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        icon?.let {
            Icon(it, contentDescription = null, tint = WColors.OnBgDim.copy(alpha = 0.6f), modifier = Modifier.size(44.dp))
            Spacer(Modifier.height(12.dp))
        }
        Text(title, style = MaterialTheme.typography.titleMedium, color = WColors.OnBg)
        Spacer(Modifier.height(4.dp))
        Text(desc, style = MaterialTheme.typography.bodyMedium, color = WColors.OnBgDim, textAlign = TextAlign.Center)
    }
}

/**
 * 圆形头像按钮（STRICT_SPEC §1）：已登录显示昵称首字，未登录显示默认人形。
 * 点击行为由调用方决定（登录页 / 个人中心）。
 */
@Composable
fun AvatarCircle(
    size: Dp,
    name: String?,
    logged: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Box(
        modifier
            .size(size)
            .glass(shape = CircleShape)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        val initial = name?.trim()?.take(1)
        if (logged && !initial.isNullOrBlank()) {
            Text(
                initial.uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = WColors.Primary,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.42f).sp,
            )
        } else {
            Icon(
                Icons.Rounded.Person,
                contentDescription = "头像",
                tint = WColors.OnBgDim,
                modifier = Modifier.size(size * 0.55f),
            )
        }
    }
}
