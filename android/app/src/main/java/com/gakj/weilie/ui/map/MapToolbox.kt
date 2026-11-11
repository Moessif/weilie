package com.gakj.weilie.ui.map

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.CellTower
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import com.gakj.weilie.ui.common.HelpHint
import com.gakj.weilie.ui.common.HelpTexts
import com.gakj.weilie.ui.theme.Hairline.hairline
import com.gakj.weilie.ui.theme.MonoText
import com.gakj.weilie.ui.theme.Radius
import com.gakj.weilie.ui.theme.Space
import com.gakj.weilie.ui.theme.SectionHeader
import com.gakj.weilie.ui.theme.WColors
import com.gakj.weilie.ui.theme.glass
import com.gakj.weilie.ui.theme.mapGlass

/**
 * A4 工具箱工具定义（STRICT_SPEC 2.3）：simple = 原地展示；否则弹浮窗/跳转。
 */
enum class ToolId(val label: String, val simple: Boolean) {
    VIBRATE("震动", true),
    CELL_ALERT("切基站提醒", true),
    COLLECT_RESERVE("信息采集", true),
    EXPORT("数据导出", false),
    NAV("定位与导航", false),
    HELP("帮助", false),
    AI("AI 助手", false),
    PHOTO("拍照", false),
    REALTIME("实时信息", false),
    SETTINGS("设置", false),
}

fun toolIcon(id: ToolId): ImageVector = when (id) {
    ToolId.VIBRATE -> Icons.Rounded.Vibration
    ToolId.CELL_ALERT -> Icons.Rounded.NotificationsActive
    ToolId.COLLECT_RESERVE -> Icons.Rounded.Storage
    ToolId.EXPORT -> Icons.Rounded.IosShare
    ToolId.NAV -> Icons.Rounded.Navigation
    ToolId.HELP -> Icons.AutoMirrored.Rounded.HelpOutline
    ToolId.AI -> Icons.Rounded.SmartToy
    ToolId.PHOTO -> Icons.Rounded.PhotoCamera
    ToolId.REALTIME -> Icons.Rounded.Insights
    ToolId.SETTINGS -> Icons.Rounded.Settings
}

/** A2 折线图数据点：时间戳 + RSRP（null = 该点无值） */
data class RsPoint(val ts: Long, val rsrp: Int?)

/**
 * 底部弹出工具箱面板：
 * 第一行 A1(圆) + A2(长条自定义数据显示区) + A3(圆 模式/组队)；
 * 第二行 A4 大格：4 列 10 项工具网格；简单功能原地展示（带返回），复杂功能由父级弹浮窗。
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ToolboxSheet(
    running: Boolean,
    expertMode: Boolean,
    cellSummary: List<String>,
    rsrpSeries: List<RsPoint>,
    inlineTool: ToolId?,
    vib: Boolean,
    cellAlert: Boolean,
    silent: Boolean,
    silentStateText: String,
    silentAvailable: Boolean = true,
    silentStrength: Int = 3,
    sampleIntervalMs: Long,
    onVib: (Boolean) -> Unit,
    onCellAlert: (Boolean) -> Unit,
    onSilent: (Boolean) -> Unit,
    onSilentStrength: (Int) -> Unit = {},
    onSampleInterval: (Long) -> Unit,
    onTool: (ToolId) -> Unit,
    onSimpleTool: (ToolId) -> Unit,
    onInlineBack: () -> Unit,
    onSwitchCell: () -> Unit,
    onSetExpert: (Boolean) -> Unit,
    onTeam: () -> Unit,
    onDismiss: () -> Unit,
    // ---- 长按拖拽（把工具拖到地图上自由摆放）----
    dimmed: Boolean = false,
    onDragStart: (ToolId, Offset) -> Unit = { _, _ -> },
    onDrag: (Offset) -> Unit = {},
    onDragEnd: (Offset) -> Unit = { _ -> },
    onDragCancel: () -> Unit = {},
) {
    // 拖拽进行中面板整体降透明（连面板底色也半透明），让用户看得见下面的地图
    val sheetAlpha by animateFloatAsState(if (dimmed) 0.35f else 1f, tween(150), label = "toolboxAlpha")
    val sheetBg by animateColorAsState(
        if (dimmed) WColors.Bg1.copy(alpha = 0.2f) else WColors.Bg1,
        tween(150),
        label = "toolboxBg",
    )
    // 直接展开到最高高度：A1/A2/A3 + A4 十工具一屏尽量都露出，避免首屏只见半截图标
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // 用户要求：工具箱最多占屏幕 2/5，不要铺满全屏（内容超出时在面板内滚动）
    val maxSheetH = (LocalConfiguration.current.screenHeightDp * 0.42f).dp
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = sheetBg,
        shape = RoundedCornerShape(topStart = Radius.xl, topEnd = Radius.xl),
        scrimColor = if (dimmed) Color.Transparent else Color(0x52000000),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .alpha(sheetAlpha)
                .heightIn(max = maxSheetH)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.screenH)
                .padding(bottom = Space.x3),
        ) {
            var a3Open by remember { mutableStateOf(false) }
            // 默认展开：用户反馈「工具箱的数据显示区默认要显示一些内容」
            var a2Open by remember { mutableStateOf(false) }
            var a2Scheme by remember { mutableIntStateOf(0) }

            // ---------- 第一行：A1 + A2 + A3（统一方块，与下方工具箱网格同款）----------
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SquareTile(
                    icon = Icons.Rounded.CellTower,
                    label = "切换基站",
                    tint = WColors.Primary,
                    modifier = Modifier.weight(1f),
                    onClick = onSwitchCell,
                )
                SquareTile(
                    icon = Icons.Rounded.Insights,
                    label = "数据显示区",
                    tint = WColors.OnBg,
                    selected = a2Open,
                    modifier = Modifier.weight(1f),
                    onClick = { a2Open = !a2Open; if (a2Open) a3Open = false },
                )
                SquareTile(
                    icon = Icons.Rounded.Tune,
                    label = "模式·组队",
                    tint = WColors.Secondary,
                    selected = a3Open,
                    modifier = Modifier.weight(1f),
                    onClick = { a3Open = !a3Open; if (a3Open) a2Open = false },
                )
            }

            // 收起态也露一行关键数据：数据显示区默认有内容，但不把工具箱网格挤出屏幕
            if (!a2Open && cellSummary.isNotEmpty()) {
                val line = (cellSummary.first().trim() + " · " + cellSummary.last().replace("RSRP ", "")).trim()
                Text(
                    line,
                    style = MonoText.style.copy(fontSize = 10.sp),
                    color = WColors.OnBgDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Space.x2)
                        .background(WColors.ChipBg, Radius.pillShape)
                        .padding(horizontal = Space.x3, vertical = 6.dp),
                )
            }

            // ---------- 静默跟踪（需求 10）----------
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = Space.x2)
                    .background(WColors.ChipBg, Radius.mdShape)
                    .padding(start = Space.x3, end = Space.x2, top = Space.x2, bottom = Space.x2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "静默跟踪",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (silent) WColors.Primary else WColors.OnBg,
                    )
                    Text(
                        if (silent) silentStateText
                        else if (!silentAvailable) "经纬度任务无法判定命中态"
                        else "只震动不发声 · 后台保活",
                        style = MonoText.style.copy(fontSize = 10.sp),
                        color = if (silent) WColors.Primary else WColors.OnBgDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                HelpHint("振动逻辑说明", HelpTexts.silentTrack)
                Spacer(Modifier.width(Space.x2))
                Switch(
                    checked = silent,
                    onCheckedChange = if (silentAvailable) onSilent else null,
                    enabled = silentAvailable,
                    colors = SwitchDefaults.colors(
                            checkedThumbColor = WColors.OnPrimary,
                            checkedTrackColor = WColors.Primary,
                            uncheckedThumbColor = WColors.OnBgDim,
                            uncheckedTrackColor = WColors.BarOff,
                            uncheckedBorderColor = WColors.GlassBorder,
                        ),
                    modifier = Modifier.height(26.dp),
                )
            }

            // ---------- 打点间隔（需求 1）----------
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = Space.x2, start = Space.x2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("打点间隔", style = MaterialTheme.typography.labelSmall, color = WColors.OnBgDim)
                Spacer(Modifier.width(Space.x3))
                listOf(1_000L to "1 s", 2_000L to "2 s", 5_000L to "5 s").forEach { (ms, label) ->
                    val on = sampleIntervalMs == ms
                    Box(
                        Modifier
                            .padding(end = Space.x2)
                            .background(
                                if (on) WColors.Primary.copy(alpha = 0.16f) else Color.Transparent,
                                Radius.pillShape,
                            )
                            .clickable { onSampleInterval(ms) }
                            .padding(horizontal = Space.x3, vertical = 4.dp),
                    ) {
                        Text(
                            label,
                            style = MonoText.style.copy(fontSize = 10.sp),
                            color = if (on) WColors.Primary else WColors.OnBgDim,
                        )
                    }
                }
            }

            // 需求四：振动强度可调（1 轻 / 3 中 / 5 强），立即生效并记住
            if (silent) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp, start = 2.dp, end = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "震动强度",
                        style = MaterialTheme.typography.labelSmall,
                        color = WColors.OnBgDim,
                    )
                    Slider(
                        value = silentStrength.toFloat(),
                        onValueChange = { onSilentStrength(it.roundToInt().coerceIn(1, 5)) },
                        valueRange = 1f..5f,
                        steps = 3,
                        colors = SliderDefaults.colors(
                            thumbColor = WColors.Primary,
                            activeTrackColor = WColors.Primary,
                            inactiveTrackColor = WColors.GlassBorder,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 6.dp)
                            .height(28.dp),
                    )
                    Text(
                        strengthLabel(silentStrength),
                        style = MonoText.style.copy(fontSize = 10.sp),
                        color = WColors.Primary,
                        modifier = Modifier.width(26.dp),
                    )
                }
            }

            // ---------- A2 展开：数据显示区方案（上下两半 / 仅折线 / 仅信息）----------
            AnimatedVisibility(a2Open) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = Space.x2)
                        .background(WColors.Bg1, Radius.lgShape)
                        .hairline(Radius.lgShape)
                        .padding(Space.x3),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        A3Chip("上下两半", a2Scheme == 0) { a2Scheme = 0 }
                        A3Chip("仅折线", a2Scheme == 1) { a2Scheme = 1 }
                        A3Chip("仅信息", a2Scheme == 2) { a2Scheme = 2 }
                    }
                    Spacer(Modifier.height(10.dp))
                    when (a2Scheme) {
                        0 -> Column {
                            CellSummaryLines(cellSummary)
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "信号趋势（近 60 秒）",
                                style = MonoText.style.copy(fontSize = 10.sp),
                                color = WColors.OnBgDim,
                            )
                            Spacer(Modifier.height(4.dp))
                            Box(Modifier.fillMaxWidth().height(110.dp)) { RsrpMiniChart(rsrpSeries) }
                        }
                        1 -> Column {
                            Text(
                                "信号趋势（近 60 秒）",
                                style = MonoText.style.copy(fontSize = 10.sp),
                                color = WColors.OnBgDim,
                            )
                            Spacer(Modifier.height(4.dp))
                            Box(Modifier.fillMaxWidth().height(118.dp)) { RsrpMiniChart(rsrpSeries) }
                        }
                        else -> CellSummaryLines(cellSummary)
                    }
                }
            }

            // ---------- A3 展开行 ----------
            AnimatedVisibility(a3Open) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    A3Chip("专家模式", expertMode) { onSetExpert(true); a3Open = false }
                    A3Chip("普通模式", !expertMode) { onSetExpert(false); a3Open = false }
                    A3Chip("拉人组队", false) { onTeam(); a3Open = false }
                }
            }

            Spacer(Modifier.height(8.dp))

            // ---------- 第二行：A4 大格 ----------
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(WColors.Bg1, Radius.lgShape)
                    .hairline(Radius.lgShape)
                    .padding(Space.x2),
            ) {
                if (inlineTool == null) {
                    SectionHeader(
                        "工具箱",
                        trailing = {
                            Text(
                                "长按拖到地图上松手即可固定",
                                style = MaterialTheme.typography.labelSmall,
                                color = WColors.OnBgDim,
                            )
                        },
                    )
                    Spacer(Modifier.height(4.dp))
                    ToolId.entries.chunked(5).forEach { rowTools ->
                        Row(Modifier.fillMaxWidth()) {
                            rowTools.forEach { t ->
                                Box(Modifier.weight(1f)) {
                                    ToolCell(
                                        tool = t,
                                        toggled = (t == ToolId.VIBRATE && vib) || (t == ToolId.CELL_ALERT && cellAlert),
                                        onClick = {
                                            when (t) {
                                                // 震动/切基站提醒：点击直接切换开关（用户要求，不展开二级面板）
                                                ToolId.VIBRATE -> onVib(!vib)
                                                ToolId.CELL_ALERT -> onCellAlert(!cellAlert)
                                                else -> if (t.simple) onSimpleTool(t) else onTool(t)
                                            }
                                        },
                                        onDragStart = { p -> onDragStart(t, p) },
                                        onDrag = onDrag,
                                        onDragEnd = onDragEnd,
                                        onDragCancel = onDragCancel,
                                    )
                                }
                            }
                            repeat(5 - rowTools.size) { Spacer(Modifier.weight(1f)) }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                } else {
                    // ---------- 简单功能原地展示（带返回） ----------
                    SimpleToolInline(
                        tool = inlineTool,
                        vib = vib,
                        cellAlert = cellAlert,
                        onVib = onVib,
                        onCellAlert = onCellAlert,
                        onBack = onInlineBack,
                    )
                }
            }
        }
    }
}

@Composable
private fun SquareTile(
    icon: ImageVector,
    label: String,
    tint: Color,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .height(62.dp)
            .background(
                if (selected) WColors.Primary.copy(alpha = 0.16f) else WColors.ChipBg,
                Radius.mdShape,
            )
            .hairline(Radius.mdShape, strong = selected)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, label, tint = if (selected) WColors.Primary else tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(Space.x1))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = if (selected) WColors.Primary else WColors.OnBgDim,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun RowScope.A3Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .weight(1f)
            .background(
                if (selected) WColors.Primary.copy(alpha = 0.16f) else WColors.ChipBg,
                Radius.pillShape,
            )
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) WColors.Primary else WColors.OnBg,
        )
    }
}

/**
 * A4 网格单元：点按触发（开关类直接切换）；长按 ~350ms 进入拖拽态，
 * 拖拽过程中把「窗口绝对坐标」持续回报给父级（SingleMapScreen 画幽灵+落点判定）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolCell(
    tool: ToolId,
    toggled: Boolean = false,
    onClick: () -> Unit,
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: (Offset) -> Unit,
    onDragCancel: () -> Unit,
) {
    val onColor = if (toggled) WColors.Primary else WColors.OnBg
    // 本格子在窗口中的原点（把相对手势坐标换算成全屏坐标用）
    var origin by remember { mutableStateOf(Offset.Zero) }
    var lastPos by remember { mutableStateOf(Offset.Zero) }
    Box(Modifier.padding(2.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .background(
                    if (toggled) WColors.Primary.copy(alpha = 0.16f) else WColors.ChipBg,
                    Radius.mdShape,
                )
                .hairline(Radius.mdShape, strong = toggled)
                .onGloballyPositioned { origin = it.positionInRoot() }
                .pointerInput(tool) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            lastPos = origin + it
                            onDragStart(lastPos)
                        },
                        onDrag = { change, _ ->
                            lastPos = origin + change.position
                            onDrag(lastPos)
                        },
                        onDragEnd = { onDragEnd(lastPos) },
                        onDragCancel = onDragCancel,
                    )
                }
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                )
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(toolIcon(tool), null, tint = onColor, modifier = Modifier.size(20.dp))
            Spacer(Modifier.height(Space.x1))
            Text(
                tool.label,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = if (toggled) WColors.Primary else WColors.OnBgDim,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
        }
        // 右上角状态角标：开关开=小绿点
        if (toggled) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(6.dp)
                    .background(WColors.Success, RoundedCornerShape(999.dp)),
            )
        }
    }
}

/** 简单工具原地展示区（A4 内部替换显示，带返回） */
@Composable
private fun SimpleToolInline(
    tool: ToolId,
    vib: Boolean,
    cellAlert: Boolean,
    onVib: (Boolean) -> Unit,
    onCellAlert: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("‹ 返回工具箱", style = MaterialTheme.typography.labelMedium, color = WColors.Primary, modifier = Modifier.clickable(onClick = onBack))
    }
    Spacer(Modifier.height(12.dp))
    when (tool) {
        ToolId.VIBRATE -> ToggleBlock("震动", "关键事件（切基站/进目标圈）时震动反馈", vib, onVib)
        ToolId.CELL_ALERT -> ToggleBlock("切基站提醒", "小区切换时在地图顶部显示白话通知", cellAlert, onCellAlert)
        ToolId.COLLECT_RESERVE -> Column {
            Text("信息采集", style = MaterialTheme.typography.titleSmall, color = WColors.OnBg)
            Spacer(Modifier.height(6.dp))
            Text("预留功能，后续版本填充。", style = MaterialTheme.typography.bodyMedium, color = WColors.OnBgDim)
        }
        else -> Unit
    }
}

@Composable
private fun ToggleBlock(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = WColors.OnBg)
            Text(desc, style = MaterialTheme.typography.labelSmall, color = WColors.OnBgDim)
        }
        Switch(
        checked = checked,
        onCheckedChange = onChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = WColors.OnPrimary,
            checkedTrackColor = WColors.Primary,
            uncheckedThumbColor = WColors.OnBgDim,
            uncheckedTrackColor = WColors.BarOff,
            uncheckedBorderColor = WColors.GlassBorder,
        ),
    )
    }
}

/** A2 上半：当前小区摘要（等宽小字，最多 6 行） */
@Composable
private fun CellSummaryLines(lines: List<String>) {
    Column {
        if (lines.isEmpty()) {
            Text(
                "等待采集数据…（授予定位后会显示当前小区）",
                style = MonoText.style.copy(fontSize = 12.sp),
                color = WColors.OnBgDim,
                maxLines = 1,
            )
            return@Column
        }
        lines.take(6).forEach {
            Text(
                it,
                style = MonoText.style.copy(fontSize = 12.sp),
                color = WColors.OnBg,
                maxLines = 1,
            )
        }
    }
}

/** A2 下半：最近 60s RSRP 迷你折线图（含 -40/-80/-120 参考线与刻度） */
@Composable
private fun RsrpMiniChart(series: List<RsPoint>) {
    val labels = listOf(-40, -80, -120)
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxHeight()) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val minX = -135f
                val maxX = -55f
                // 参考线：-40 顶部、-80 中部、-120 底部附近
                labels.forEach { lv ->
                    val y = h * (1f - ((lv - minX) / (maxX - minX))).coerceIn(0f, 1f)
                    drawLine(WColors.ChipBorder, Offset(0f, y), Offset(w, y), Stroke(1f).width)
                }
                drawLine(WColors.ChipBorder, Offset(0f, h - 1f), Offset(w, h - 1f), Stroke(1f).width)
                if (series.size < 2) return@Canvas
                val now = series.last().ts
                val windowMs = 60_000L
                val t0 = now - windowMs
                val line = Path()
                var started = false
                series.forEach { p ->
                    if (p.rsrp == null || p.ts < t0) { started = false; return@forEach }
                    val x = (w * ((p.ts - t0).toFloat() / windowMs)).coerceIn(0f, w)
                    val y = h * (1f - ((p.rsrp.toFloat() - minX) / (maxX - minX))).coerceIn(0f, 1f)
                    if (!started) { line.moveTo(x, y); started = true } else line.lineTo(x, y)
                }
                drawPath(line, WColors.Primary, style = Stroke(width = 2.dp.toPx()))
            }
        }
        Column(
            Modifier.width(30.dp).fillMaxHeight().padding(start = 4.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("-40", style = MonoText.style.copy(fontSize = 9.sp), color = WColors.OnBgDim)
            Text("-80", style = MonoText.style.copy(fontSize = 9.sp), color = WColors.OnBgDim)
            Text("-120", style = MonoText.style.copy(fontSize = 9.sp), color = WColors.OnBgDim)
        }
    }
}

/**
 * 通用玻璃浮窗：复杂工具（导出/导航/帮助/拍照/设置）共用容器。
 */
@Composable
fun GlassPanelDialog(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x88000000))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth(0.92f)
                .mapGlass(RoundedCornerShape(22.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { }, // 吃掉点击，避免穿透关闭
                )
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = WColors.OnBg, modifier = Modifier.weight(1f))
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = "关闭",
                    tint = WColors.OnBgDim,
                    modifier = Modifier
                        .size(22.dp)
                        .clickable(onClick = onDismiss),
                )
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

/** 浮窗内一行操作（导出/导航面板用） */
@Composable
fun PanelActionRow(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(if (enabled) WColors.ChipBg else WColors.ChipBg.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let {
            Icon(it, null, tint = WColors.OnBgDim, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) WColors.OnBg else WColors.OnBgDim,
            modifier = Modifier.weight(1f),
        )
        Text("›", style = MaterialTheme.typography.titleMedium, color = WColors.OnBgDim)
    }
}

private fun strengthLabel(level: Int): String = when (level.coerceIn(1, 5)) {
    1 -> "轻"
    2 -> "较轻"
    3 -> "中"
    4 -> "较强"
    else -> "强"
}
