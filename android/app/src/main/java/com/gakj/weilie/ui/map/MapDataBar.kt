package com.gakj.weilie.ui.map

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.CallMerge
import androidx.compose.material.icons.rounded.CallSplit
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast

import com.gakj.weilie.core.model.SampleRecord
import com.gakj.weilie.core.sampling.CellChangeEvent
import com.gakj.weilie.core.telephony.CellSnapshot
import com.gakj.weilie.ui.expert.ExpertPanelContent
import com.gakj.weilie.ui.theme.Hairline.hairline
import com.gakj.weilie.ui.theme.MonoText
import com.gakj.weilie.ui.theme.PulsingDot
import com.gakj.weilie.ui.theme.Radius
import com.gakj.weilie.ui.theme.SignalBars
import com.gakj.weilie.ui.theme.Space
import com.gakj.weilie.ui.theme.WColors

/**
 * 数据栏槽位：可由用户点击切换的 8 种指标。
 */
enum class DataSlot(val label: String) {
    TARGET("追踪目标"),
    SIGNAL("服务小区信号"),
    SINR("信噪比"),
    NEIGHBORS("邻区数"),
    WIFI("WiFi 数"),
    COORDS("坐标"),
    DURATION("采集时长"),
    SAMPLES("样本计数"),
}

/**
 * 追踪目标的命中态（@1 需求：主区命中 / 邻区命中 / 脱离）。
 */
enum class TargetState(val label: String, private val argb: Long) {
    HIT("命中目标", 0xFF22C55E),
    NEIGHBOR("邻区命中", 0xFFF59E0B),
    MISSING("未命中", 0xFF94A3B8),
    NONE("未设目标", 0xFF64748B);

    val color: Color get() = Color(argb.toInt())
}

/**
 * 顶部数据栏。
 *
 * 视觉规则（与全 App 一套系统保持一致）：
 * - 一块面板，内部用发丝分隔线分段，不再嵌套第二层卡片；
 * - 标签一列（固定宽度）、数值一列（等宽字体），左右两条边线始终对齐；
 * - 面板底色比普通玻璃更实（地图文字会透过来，会显得脏），顶部保留一道细高光；
 * - 主区只放「追踪目标 + 当前服务小区」两行；灵敏度信息收进展开区。
 */
@Composable
fun MapDataBar(
    running: Boolean,
    // 槽位数据
    level: Int,
    rsrpText: String,
    levelLabelText: String,
    sinrText: String,
    neighborsCount: Int,
    wifiCount: Int,
    // RSRP 趋势曲线数据（最近采样，时间顺序；下槽 SIGNAL 时绘制）
    rsrpHistory: List<Int> = emptyList(),
    // 双卡明细（每张卡一行）
    sims: List<com.gakj.weilie.core.telephony.SimCell> = emptyList(),
    coordsText: String?,
    durationText: String,
    sampleCount: Int,
    targetText: String,
    targetState: TargetState = TargetState.NONE,
    // 普通模式内容
    carrierText: String,
    ratText: String,
    servingText: String?,
    neighborCis: List<String>,
    /** 邻区为 0 时的原因提示，空串不显示 */
    neighborHint: String = "",
    confidenceText: String?,
    // 专家模式数据源
    snapshot: CellSnapshot?,
    lastSample: SampleRecord?,
    events: List<CellChangeEvent>,
    // 切基站白话提示（null = 不显示）
    lastEventNotice: String?,
    modifier: Modifier = Modifier,
    expertMode: Boolean = false,
    onExpertModeChange: (Boolean) -> Unit = {},
) {
    var slotTop by remember { mutableStateOf(DataSlot.TARGET) }
    var slotBottom by remember { mutableStateOf(DataSlot.SIGNAL) }
    var merged by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(true) }
    var pickingSlot by remember { mutableStateOf<DataSlot?>(null) }
    var showServingFull by remember { mutableStateOf(false) }
    var showNeighborList by remember { mutableStateOf(false) }

    Column(modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(Radius.lgShape)
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF0C1220), Color(0xFF090E18)),
                    ),
                )
                .border(
                    1.dp,
                    Brush.verticalGradient(
                        listOf(WColors.GlassBorder, WColors.GlassBorderSoft),
                    ),
                    Radius.lgShape,
                )
                .padding(horizontal = Space.x4, vertical = Space.x3),
        ) {
            // ---------- 第一行：追踪目标（槽位可切换） + 展开按钮 ----------
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    SlotCell(
                        slot = slotTop, running = running, level = level, rsrpText = rsrpText,
                        levelLabelText = levelLabelText, sinrText = sinrText,
                        neighborsCount = neighborsCount, wifiCount = wifiCount,
                        coordsText = coordsText, durationText = durationText,
                        sampleCount = sampleCount, targetText = targetText, targetState = targetState,
                        rsrpHistory = rsrpHistory, showTrend = false, expertMode = expertMode,
                        showPickerHint = true,
                    ) { pickingSlot = slotTop }
                }
                Box(
                    Modifier
                        .size(28.dp)
                        .clickable { expanded = !expanded },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = if (expanded) "收起数据栏" else "展开数据栏",
                        tint = WColors.OnBgDim,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            AnimatedVisibility(expanded) {
                Column {
                    // ---------- 第二行：下槽位 ----------
                    HorizontalDivider(color = WColors.GlassBorder)
                    if (!merged) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) {
                                SlotCell(
                                    slot = slotBottom, running = running, level = level,
                                    rsrpText = rsrpText, levelLabelText = levelLabelText,
                                    sinrText = sinrText, neighborsCount = neighborsCount,
                                    wifiCount = wifiCount, coordsText = coordsText,
                                    durationText = durationText, sampleCount = sampleCount,
                                    targetText = targetText, targetState = targetState,
                                    rsrpHistory = rsrpHistory, showTrend = true,
                                    expertMode = expertMode, showPickerHint = true,
                                ) { pickingSlot = slotBottom }
                            }
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) {
                                SlotCell(
                                    slot = slotTop, running = running, level = level,
                                    rsrpText = rsrpText, levelLabelText = levelLabelText,
                                    sinrText = sinrText, neighborsCount = neighborsCount,
                                    wifiCount = wifiCount, coordsText = coordsText,
                                    durationText = durationText, sampleCount = sampleCount,
                                    targetText = targetText, targetState = targetState,
                                    rsrpHistory = rsrpHistory, showTrend = false,
                                    expertMode = expertMode, showPickerHint = true,
                                ) { pickingSlot = slotTop }
                            }
                            Box(Modifier.weight(1f)) {
                                SlotCell(
                                    slot = slotBottom, running = running, level = level,
                                    rsrpText = rsrpText, levelLabelText = levelLabelText,
                                    sinrText = sinrText, neighborsCount = neighborsCount,
                                    wifiCount = wifiCount, coordsText = coordsText,
                                    durationText = durationText, sampleCount = sampleCount,
                                    targetText = targetText, targetState = targetState,
                                    rsrpHistory = rsrpHistory, showTrend = true,
                                    expertMode = expertMode, showPickerHint = true,
                                ) { pickingSlot = slotBottom }
                            }
                        }
                    }

                    // ---------- 控制行：合并显示 / 普通·专家 ----------
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = Space.x2, bottom = Space.x1),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            Modifier
                                .clip(Radius.pillShape)
                                .background(WColors.ChipBg)
                                .clickable { merged = !merged }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (merged) Icons.Rounded.CallMerge else Icons.Rounded.CallSplit,
                                contentDescription = null,
                                tint = WColors.OnBgDim,
                                modifier = Modifier.size(13.dp),
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                if (merged) "分开显示" else "合并显示",
                                style = MaterialTheme.typography.labelSmall,
                                color = WColors.OnBgDim,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        Row(
                            Modifier
                                .clip(Radius.pillShape)
                                .background(WColors.ChipBg)
                                .padding(3.dp),
                        ) {
                            SegPill("普通", !expertMode, WColors.Primary) { onExpertModeChange(false) }
                            SegPill("专家", expertMode, WColors.Secondary) { onExpertModeChange(true) }
                        }
                    }

                    HorizontalDivider(color = WColors.GlassBorder)
                    Spacer(Modifier.height(Space.x2))
                    if (expertMode) {
                        Column(
                            Modifier
                                .heightIn(max = 280.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            ExpertPanelContent(snapshot, lastSample, events)
                        }
                    } else {
                        NormalContent(
                            carrierText = carrierText,
                            ratText = ratText,
                            sims = sims,
                            servingText = servingText,
                            showServingFull = showServingFull,
                            onToggleServing = { showServingFull = !showServingFull },
                            level = level,
                            levelLabelText = levelLabelText,
                            showSignalRow = slotTop != DataSlot.SIGNAL && slotBottom != DataSlot.SIGNAL,
                            neighborCis = neighborCis,
                            neighborHint = neighborHint,
                            showNeighborList = showNeighborList,
                            onToggleNeighbors = { showNeighborList = !showNeighborList },
                            confidenceText = confidenceText,
                            running = running,
                        )
                    }
                }
            }
        }

        // ---------- 切基站白话提示条 ----------
        AnimatedVisibility(lastEventNotice != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = Space.x2)
                    .clip(Radius.mdShape)
                    .background(
                        Brush.verticalGradient(listOf(Color(0xFF0C1220), Color(0xFF090E18))),
                        Radius.mdShape,
                    )
                    .hairline(Radius.mdShape)
                    .hairline(Radius.pillShape)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PulsingDot(WColors.Warning, 8.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    lastEventNotice ?: "",
                    style = MaterialTheme.typography.labelMedium,
                    color = WColors.OnBg,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    pickingSlot?.let { slot ->
        SlotPicker(
            current = slot,
            onPick = { picked ->
                if (slot == slotTop) slotTop = picked else slotBottom = picked
                pickingSlot = null
            },
            onDismiss = { pickingSlot = null },
        )
    }
}

/** 分段控件单格：选中时实心 tint 底 + OnPrimary 字，未选中透明 + 次色字 */
@Composable
private fun SegPill(text: String, selected: Boolean, tint: Color, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = if (selected) WColors.OnPrimary else WColors.OnBgDim,
        modifier = Modifier
            .clip(Radius.pillShape)
            .background(if (selected) tint else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    )
}

/**
 * 单槽：标签 + 数值。
 * 第一槽承载「追踪目标」，第二槽默认「服务小区信号」（trend 曲线）。
 */
@Composable
private fun SlotCell(
    slot: DataSlot,
    running: Boolean,
    level: Int,
    rsrpText: String,
    levelLabelText: String,
    sinrText: String,
    neighborsCount: Int,
    wifiCount: Int,
    coordsText: String?,
    durationText: String,
    sampleCount: Int,
    targetText: String,
    targetState: TargetState,
    rsrpHistory: List<Int>,
    showTrend: Boolean,
    expertMode: Boolean,
    showPickerHint: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PulsingDot(if (running) WColors.Primary else Color(0xFF64748B), 7.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            slot.label,
            style = MaterialTheme.typography.labelSmall,
            color = WColors.OnBgDim,
            modifier = Modifier.width(76.dp),
        )
        when (slot) {
            DataSlot.TARGET -> {
                Text(
                    targetText,
                    style = MonoText.style.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                    color = WColors.OnBg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    targetState.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = targetState.color,
                )
            }
            DataSlot.SIGNAL -> {
                if (showTrend) {
                    SignalTrend(rsrpHistory, Modifier.weight(1f), showScale = expertMode)
                } else {
                    SignalBars(level)
                    if (expertMode) {
                        Spacer(Modifier.width(8.dp))
                        Text(rsrpText, style = MonoText.style.copy(fontSize = 13.sp), color = WColors.OnBg)
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        levelLabelText,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (level < 0) WColors.OnBgDim
                        else Color(com.gakj.weilie.core.rating.SignalRating.colorArgb(level).toInt()),
                    )
                }
            }
            DataSlot.SINR -> Text(sinrText, style = MonoText.style, color = WColors.OnBg)
            DataSlot.NEIGHBORS -> Text("$neighborsCount 个", style = MonoText.style, color = WColors.OnBg)
            DataSlot.WIFI -> Text("$wifiCount 个", style = MonoText.style, color = WColors.OnBg)
            DataSlot.COORDS -> Text(coordsText ?: "—", style = MonoText.style.copy(fontSize = 12.sp), color = WColors.OnBg)
            DataSlot.DURATION -> Text(durationText, style = MonoText.style, color = WColors.OnBg)
            DataSlot.SAMPLES -> Text("$sampleCount 点", style = MonoText.style, color = WColors.OnBg)
        }
        if (showPickerHint) {
            // 趋势折线自己吃 weight(1f)；再叠一个 weight 的间隔会把可用宽度对半分，
            // 于是「追踪目标」这种长值只显示前半截（NCI 后的编号被裁掉）。
            val hasWeightedChild = slot == DataSlot.SIGNAL && showTrend
            if (hasWeightedChild) {
                Spacer(Modifier.width(8.dp))
            } else {
                Spacer(Modifier.weight(1f))
            }
            Icon(
                Icons.Rounded.SwapHoriz,
                contentDescription = "切换该行显示的指标",
                tint = WColors.OnBgDim.copy(alpha = 0.55f),
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/** 指标选择菜单 */
@Composable
private fun SlotPicker(
    current: DataSlot,
    onPick: (DataSlot) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        DropdownMenu(
            expanded = true,
            onDismissRequest = onDismiss,
            containerColor = WColors.Bg1,
        ) {
            DataSlot.entries.forEach { s ->
                DropdownMenuItem(
                    text = {
                        Text(
                            s.label + if (s == current) "（当前）" else "",
                            color = if (s == current) WColors.Primary else WColors.OnBg,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    onClick = { onPick(s) },
                )
            }
        }
    }
}

/**
 * 普通模式展开内容（白话优先）：
 * 服务小区一行 → 双卡明细 → 已连接基站（可展开）→ 附近基站（可展开）→ 拟合置信度。
 */
@Composable
private fun NormalContent(
    carrierText: String,
    ratText: String,
    sims: List<com.gakj.weilie.core.telephony.SimCell> = emptyList(),
    servingText: String?,
    showServingFull: Boolean,
    onToggleServing: () -> Unit,
    level: Int,
    levelLabelText: String,
    showSignalRow: Boolean,
    neighborCis: List<String>,
    neighborHint: String = "",
    showNeighborList: Boolean,
    onToggleNeighbors: () -> Unit,
    confidenceText: String?,
    running: Boolean,
) {
    Column {
        // 网络归属：有双卡明细时由每张卡自己交代（避免同一信息出现两遍）
        if (sims.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "网络",
                    style = MaterialTheme.typography.labelSmall,
                    color = WColors.OnBgDim,
                    modifier = Modifier.width(76.dp),
                )
                Text(
                    "$carrierText · $ratText",
                    style = MaterialTheme.typography.bodySmall,
                    color = WColors.OnBg,
                )
            }
        } else {
            Spacer(Modifier.height(Space.x1))
            sims.forEach { s ->
                SimRow(s)
            }
        }

        if (showSignalRow) {
            Spacer(Modifier.height(Space.x2))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "信号",
                    style = MaterialTheme.typography.labelSmall,
                    color = WColors.OnBgDim,
                    modifier = Modifier.width(76.dp),
                )
                SignalBars(level)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (level < 0) "无信号" else "信号$levelLabelText",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (level < 0) WColors.OnBgDim
                    else Color(com.gakj.weilie.core.rating.SignalRating.colorArgb(level).toInt()),
                )
            }
        }

        Spacer(Modifier.height(Space.x2))
        // 已连接基站（点按展开全文）
        Column(
            Modifier
                .fillMaxWidth()
                .clip(Radius.smShape)
                .clickable(onClick = onToggleServing)
                .padding(vertical = 2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PulsingDot(if (running) WColors.Success else Color(0xFF64748B), 6.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    if (servingText != null) "已连接基站" else "未连接基站",
                    style = MaterialTheme.typography.labelSmall,
                    color = WColors.OnBgDim,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (servingText != null) (if (showServingFull) "收起" else "详情") else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = WColors.Primary,
                )
            }
            if (servingText != null && showServingFull) {
                Spacer(Modifier.height(4.dp))
                Text(
                    servingText,
                    style = MonoText.style.copy(fontSize = 12.sp),
                    color = WColors.OnBg,
                )
            }
        }

        Spacer(Modifier.height(Space.x1))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(Radius.smShape)
                .clickable(onClick = onToggleNeighbors)
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "附近还有 ${neighborCis.size} 个基站",
                style = MaterialTheme.typography.labelMedium,
                color = WColors.Primary,
            )
            if (neighborCis.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                Text(
                    if (showNeighborList) "收起" else "查看",
                    style = MaterialTheme.typography.labelSmall,
                    color = WColors.OnBgDim,
                )
            }
        }
        AnimatedVisibility(showNeighborList && neighborCis.isNotEmpty()) {
            Column(Modifier.padding(top = 4.dp)) {
                neighborCis.take(8).forEach { ci ->
                    Text("· $ci", style = MonoText.style.copy(fontSize = 12.sp), color = WColors.OnBgDim)
                }
            }
        }
        if (neighborCis.isEmpty() && neighborHint.isNotBlank()) {
            Text(
                neighborHint,
                style = MaterialTheme.typography.labelSmall,
                color = WColors.OnBgDim,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        if (confidenceText != null) {
            Spacer(Modifier.height(Space.x2))
            HorizontalDivider(color = WColors.GlassBorder)
            Spacer(Modifier.height(Space.x2))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "拟合置信度",
                    style = MaterialTheme.typography.labelSmall,
                    color = WColors.OnBgDim,
                    modifier = Modifier.width(76.dp),
                )
                Text(
                    confidenceText,
                    style = MaterialTheme.typography.bodySmall,
                    color = WColors.OnBg,
                )
            }
        }
    }
}

/** 一张 SIM 卡：上行给「卡号 · 运营商 频段」+ 信号评级，下行给小区编号（等宽） */
@Composable
private fun SimRow(s: com.gakj.weilie.core.telephony.SimCell) {
    val simLevel = com.gakj.weilie.core.rating.SignalRating.level(s.rsrp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            s.slotLabel,
            style = MaterialTheme.typography.labelSmall,
            color = WColors.OnBgDim,
            modifier = Modifier.width(76.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                "${s.carrier} ${s.ratBand}",
                style = MaterialTheme.typography.bodySmall,
                color = WColors.OnBg,
                maxLines = 1,
            )
            Spacer(Modifier.height(1.dp))
            Text(
                s.cellIdText,
                style = MonoText.style.copy(fontSize = 11.sp),
                color = WColors.Primary,
                maxLines = 1,
            )
        }
        val ctx = LocalContext.current
        val clipboard = LocalClipboardManager.current
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .clickable {
                    clipboard.setText(AnnotatedString(cellCopyText(s)))
                    Toast.makeText(ctx, "已复制基站信息", Toast.LENGTH_SHORT).show()
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.ContentCopy,
                contentDescription = "复制基站信息",
                tint = WColors.OnBgDim,
                modifier = Modifier.size(15.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            SignalBars(simLevel)
            Spacer(Modifier.height(2.dp))
            Text(
                if (simLevel < 0) "无信号" else com.gakj.weilie.core.rating.SignalRating.label(simLevel),
                style = MaterialTheme.typography.labelSmall,
                color = if (simLevel < 0) WColors.OnBgDim
                else Color(com.gakj.weilie.core.rating.SignalRating.colorArgb(simLevel).toInt()),
                maxLines = 1,
            )
        }
    }
}

/** 复制给外勤/内勤转发用的基站信息：一行内给出运营商、制式、小区编号与关键参数 */
private fun cellCopyText(s: com.gakj.weilie.core.telephony.SimCell): String {
    val parts = mutableListOf("${s.carrier} ${s.ratBand}".trim())
    if (s.mccMnc.isNotBlank()) parts += s.mccMnc
    parts += s.cellIdText
    if (s.pci > 0) parts += "PCI ${s.pci}"
    if (s.arfcn > 0) parts += "ARFCN ${s.arfcn}"
    if (s.rsrp != com.gakj.weilie.core.telephony.SimCell.UNKNOWN) parts += "RSRP ${s.rsrp} dBm"
    return parts.joinToString(" · ")
}