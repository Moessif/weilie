package com.gakj.weilie.ui.map

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gakj.weilie.core.model.SampleRecord
import com.gakj.weilie.core.sampling.CellChangeEvent
import com.gakj.weilie.core.telephony.CellSnapshot
import com.gakj.weilie.ui.expert.ExpertPanelContent
import com.gakj.weilie.ui.theme.GlassCard
import com.gakj.weilie.ui.theme.MonoText
import com.gakj.weilie.ui.theme.PulsingDot
import com.gakj.weilie.ui.theme.SignalBars
import com.gakj.weilie.ui.theme.StatChip
import com.gakj.weilie.ui.theme.WColors

/**
 * 数据栏槽位：可由用户点击切换的 8 种指标。
 */
enum class DataSlot(val label: String) {
    TARGET("追踪目标"),
    SIGNAL("信号强度"),
    SINR("信噪比"),
    NEIGHBORS("邻区数"),
    WIFI("WiFi 数"),
    COORDS("坐标"),
    DURATION("采集时长"),
    SAMPLES("样本计数"),
}

/**
 * 顶部悬浮数据栏（STRICT_SPEC 2.1）：
 * 上下两半槽位（点按切换指标）+ 整体显示合并开关 + 专家/普通切换 + 展开/收起；
 * 普通模式：运营商/制式、已连接基站（可折叠全文）、五格信号+评级、附近基站列表、切基站白话提示；
 * 专家模式：复用 ExpertPanelContent 全量字段 + 切换时间线。
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
    // 双卡明细（每张卡一行，等价于 CellularZ 底部水印的信息密度；空列表则退回单条)
    sims: List<com.gakj.weilie.core.telephony.SimCell> = emptyList(),
    coordsText: String?,
    durationText: String,
    sampleCount: Int,
    targetText: String,
    // 普通模式内容
    carrierText: String,
    ratText: String,
    servingText: String?,
    neighborCis: List<String>,
    confidenceText: String?,
    // 专家模式数据源
    snapshot: CellSnapshot?,
    lastSample: SampleRecord?,
    events: List<CellChangeEvent>,
    // 切基站白话提示（null = 不显示）
    lastEventNotice: String?,
    modifier: Modifier = Modifier,
    // 由页面级状态托管（A3「专业/简单切换」与数据栏自身 toggle 必须是同一份状态）
    expertMode: Boolean = false,
    onExpertModeChange: (Boolean) -> Unit = {},
) {
    var slotTop by remember { mutableStateOf(DataSlot.TARGET) }
    var slotBottom by remember { mutableStateOf(DataSlot.SIGNAL) }
    var merged by remember { mutableStateOf(false) }
    // 默认展开：用户反馈「数据显示区默认要显示一些内容」，收起态只留一行摘要
    var expanded by remember { mutableStateOf(true) }
    var pickingSlot by remember { mutableStateOf<DataSlot?>(null) }
    var showServingFull by remember { mutableStateOf(false) }
    var showNeighborList by remember { mutableStateOf(false) }

    Column(modifier) {
        GlassCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), onMap = true) {
            Column {
                // ---------- 收起态：一行摘要（≤56dp），点按展开 ----------
                if (!expanded) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { expanded = true }
                            .padding(horizontal = 4.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PulsingDot(if (running) WColors.Danger else Color(0xFF64748B), 8.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "$carrierText · $ratText",
                            style = MaterialTheme.typography.labelMedium,
                            color = WColors.OnBgDim,
                        )
                        Spacer(Modifier.width(12.dp))
                        SignalBars(level)
                        // 普通模式只看五格信号条 + 白话等级；专家模式才露出 dBm 数值
                        if (expertMode) {
                            Spacer(Modifier.width(8.dp))
                            Text(rsrpText, style = MonoText.style.copy(fontSize = 13.sp), color = WColors.OnBg)
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (level < 0) "无信号" else levelLabelText,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (level < 0) WColors.OnBgDim else Color(com.gakj.weilie.core.rating.SignalRating.colorArgb(level).toInt()),
                        )
                        Spacer(Modifier.weight(1f))
                        Icon(
                            Icons.Rounded.ExpandMore,
                            contentDescription = "展开",
                            tint = WColors.OnBgDim,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                AnimatedVisibility(expanded) {
                    Column {
                // ---------- 槽位区 ----------
                if (!merged) {
                    SlotCell(slotTop, running, level, rsrpText, levelLabelText, sinrText,
                        neighborsCount, wifiCount, coordsText, durationText, sampleCount, targetText,
                        rsrpHistory = rsrpHistory, showTrend = false, expertMode = expertMode) {
                        pickingSlot = slotTop
                    }
                    HorizontalDivider(color = WColors.GlassBorder)
                    SlotCell(slotBottom, running, level, rsrpText, levelLabelText, sinrText,
                        neighborsCount, wifiCount, coordsText, durationText, sampleCount, targetText,
                        rsrpHistory = rsrpHistory, showTrend = true, expertMode = expertMode) {
                        pickingSlot = slotBottom
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            SlotCell(slotTop, running, level, rsrpText, levelLabelText, sinrText,
                                neighborsCount, wifiCount, coordsText, durationText, sampleCount, targetText,
                                rsrpHistory = rsrpHistory, showTrend = false, expertMode = expertMode) {
                                pickingSlot = slotTop
                            }
                        }
                        Box(Modifier.weight(1f)) {
                            SlotCell(slotBottom, running, level, rsrpText, levelLabelText, sinrText,
                                neighborsCount, wifiCount, coordsText, durationText, sampleCount, targetText,
                                rsrpHistory = rsrpHistory, showTrend = true, expertMode = expertMode) {
                                pickingSlot = slotBottom
                            }
                        }
                    }
                }

                // ---------- 控制行 ----------
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "整体显示",
                        style = MaterialTheme.typography.labelSmall,
                        color = WColors.OnBgDim,
                        modifier = Modifier
                            .clickable { merged = !merged }
                            .background(
                                if (merged) WColors.Primary.copy(alpha = 0.08f) else Color.Transparent,
                                RoundedCornerShape(8.dp),
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clickable { onExpertModeChange(!expertMode) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Icon(
                            Icons.Rounded.Science,
                            contentDescription = null,
                            tint = if (expertMode) WColors.Secondary else WColors.OnBgDim,
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (expertMode) "专家" else "普通",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (expertMode) WColors.Secondary else WColors.OnBgDim,
                        )
                    }
                    IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(30.dp)) {
                        Icon(
                            if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = "展开/收起",
                            tint = WColors.OnBgDim,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                // ---------- 展开内容：普通 / 专家（外层 AnimatedVisibility 已按 expanded 条件渲染） ----------
                        HorizontalDivider(color = WColors.GlassBorder)
                        Spacer(Modifier.height(8.dp))
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
                                showNeighborList = showNeighborList,
                                onToggleNeighbors = { showNeighborList = !showNeighborList },
                                confidenceText = confidenceText,
                                running = running,
                            )
                        }
                    }
                }
            }
        }

        // ---------- 切基站白话提示条 ----------
        AnimatedVisibility(lastEventNotice != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .background(WColors.Bg1.copy(alpha = 0.92f), RoundedCornerShape(999.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PulsingDot(WColors.Warning, 8.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    lastEventNotice ?: "",
                    style = MaterialTheme.typography.labelMedium,
                    color = WColors.OnBg,
                    maxLines = 1,
                )
            }
        }
    }

    // ---------- 槽位指标选择弹层 ----------
    pickingSlot?.let { target ->
        SlotPicker(
            current = target,
            onPick = { picked ->
                if (target == slotTop) slotTop = picked else slotBottom = picked
                pickingSlot = null
            },
            onDismiss = { pickingSlot = null },
        )
    }
}

/** 单个槽位：label + 值，点按弹出指标选择；showTrend=true 时 SIGNAL 绘制趋势曲线 */
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
    rsrpHistory: List<Int>,
    showTrend: Boolean,
    expertMode: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 5.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PulsingDot(if (running) WColors.Danger else Color(0xFF64748B), 8.dp)
        Spacer(Modifier.width(8.dp))
        Text(slot.label, style = MaterialTheme.typography.labelSmall, color = WColors.OnBgDim)
        Spacer(Modifier.width(10.dp))
        when (slot) {
            DataSlot.TARGET -> SlotValue(targetText)
            DataSlot.SIGNAL -> {
                if (showTrend) {
                    // 下槽 SIGNAL：趋势曲线（Basement.md：下半=信号趋势），替代静态信号条避免重复
                    SignalTrend(rsrpHistory, Modifier.weight(1f), showScale = expertMode)
                } else {
                    SignalBars(level)
                    // 普通模式不露 dBm，只给白话等级；专家模式才显示原始数值
                    if (expertMode) SlotValue(rsrpText)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        levelLabelText,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (level < 0) WColors.OnBgDim else Color(com.gakj.weilie.core.rating.SignalRating.colorArgb(level).toInt()),
                    )
                }
            }
            DataSlot.SINR -> SlotValue(sinrText)
            DataSlot.NEIGHBORS -> SlotValue("$neighborsCount 个")
            DataSlot.WIFI -> SlotValue("$wifiCount 个")
            DataSlot.COORDS -> SlotValue(coordsText ?: "—")
            DataSlot.DURATION -> SlotValue(durationText)
            DataSlot.SAMPLES -> SlotValue("$sampleCount 点")
        }
        if (!(showTrend && slot == DataSlot.SIGNAL)) {
            Spacer(Modifier.weight(1f))
            Text("切换", style = MaterialTheme.typography.labelSmall, color = WColors.OnBgDim.copy(alpha = 0.6f))
        }
    }
}

@Composable
private fun SlotValue(text: String) {
    Text(
        text,
        style = MonoText.style.copy(fontSize = 14.sp),
        color = WColors.OnBg,
        maxLines = 1,
    )
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

/** 普通模式展开内容：白话优先；showSignalRow=false 时信号条与槽位重复，不再重复渲染 */
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
    showNeighborList: Boolean,
    onToggleNeighbors: () -> Unit,
    confidenceText: String?,
    running: Boolean,
) {
    Column {
        // 双卡逐行明细：卡号 · 运营商 · 制式频段 · 小区编号(习惯写法) · RSRP · SINR
        // 参考 CellularZ 底部水印的信息密度 —— 打开数据栏就能看到每张卡当前挂在哪个小区。
        if (sims.isNotEmpty()) {
            sims.forEach { s ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        s.slotLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = WColors.OnBgDim,
                        modifier = Modifier.width(30.dp),
                    )
                    Text(
                        "${s.carrier} ${s.ratBand}",
                        style = MaterialTheme.typography.labelSmall,
                        color = WColors.OnBg,
                        maxLines = 1,
                        modifier = Modifier.width(96.dp),
                    )
                    Text(
                        s.cellIdText,
                        style = MonoText.style.copy(fontSize = 11.sp),
                        color = WColors.Primary,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    // 普通模式：只给五格条 + 白话等级（不露 dBm / SINR 数字；要数字切专家模式）
                    val simLevel = com.gakj.weilie.core.rating.SignalRating.level(s.rsrp)
                    SignalBars(simLevel)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (simLevel < 0) "无信号"
                        else com.gakj.weilie.core.rating.SignalRating.label(simLevel),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (simLevel < 0) WColors.OnBgDim
                        else Color(com.gakj.weilie.core.rating.SignalRating.colorArgb(simLevel).toInt()),
                        maxLines = 1,
                    )
                }
            }
            HorizontalDivider(color = WColors.GlassBorder)
            Spacer(Modifier.height(6.dp))
        }
        Text(
            "$carrierText · $ratText",
            style = MaterialTheme.typography.labelSmall,
            color = WColors.OnBgDim,
        )
        Spacer(Modifier.height(6.dp))

        // 已连接基站（点按折叠全文）
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleServing)
                .padding(vertical = 4.dp),
        ) {
            PulsingDot(if (running) WColors.Success else Color(0xFF64748B), 7.dp)
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    if (servingText != null) "已连接基站 · 点按${if (showServingFull) "收起" else "查看详情"}" else "未连接基站",
                    style = MaterialTheme.typography.labelSmall,
                    color = WColors.OnBgDim,
                )
                if (servingText != null && showServingFull) {
                    Text(
                        servingText,
                        style = MonoText.style,
                        color = WColors.OnBg,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))

        // 五格信号 + 评级文字（仅当上下槽均未选「信号强度」时显示，避免与槽位重复）
        if (showSignalRow) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SignalBars(level)
                Spacer(Modifier.width(10.dp))
                Text(
                    if (level < 0) "无信号" else "信号${levelLabelText}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (level < 0) WColors.OnBgDim else Color(com.gakj.weilie.core.rating.SignalRating.colorArgb(level).toInt()),
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        // 附近基站
        Text(
            "附近还有 ${neighborCis.size} 个基站" + if (neighborCis.isEmpty()) "" else " · 点按查看",
            style = MaterialTheme.typography.labelMedium,
            color = WColors.Primary,
            modifier = Modifier.clickable(onClick = onToggleNeighbors),
        )
        AnimatedVisibility(showNeighborList && neighborCis.isNotEmpty()) {
            Column(Modifier.padding(top = 4.dp)) {
                neighborCis.take(8).forEach { ci ->
                    Text("· $ci", style = MonoText.style, color = WColors.OnBgDim)
                }
            }
        }

        if (confidenceText != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                "拟合置信度：$confidenceText",
                style = MaterialTheme.typography.labelSmall,
                color = WColors.OnBgDim,
            )
        }
    }
}
