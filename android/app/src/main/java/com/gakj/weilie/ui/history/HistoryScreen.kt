package com.gakj.weilie.ui.history

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gakj.weilie.core.store.SessionManifest
import com.gakj.weilie.ui.AppGraph
import com.gakj.weilie.ui.common.EmptyState
import com.gakj.weilie.ui.common.GlassTextButton
import com.gakj.weilie.ui.common.ScreenTopBar
import com.gakj.weilie.ui.common.StaggerIn
import com.gakj.weilie.ui.common.fmtDur
import com.gakj.weilie.ui.common.fmtTs
import com.gakj.weilie.ui.common.toast
import com.gakj.weilie.ui.theme.Eyebrow
import com.gakj.weilie.ui.theme.GlassCard
import com.gakj.weilie.ui.theme.Hairline.hairline
import com.gakj.weilie.ui.theme.MonoText
import com.gakj.weilie.ui.theme.Radius
import com.gakj.weilie.ui.theme.Space
import com.gakj.weilie.ui.theme.WColors
import java.util.Calendar

/**
 * 一次删除请求：
 * - [ids] 要删的 taskId；
 * - [all] 走"删除全部"（连孤儿目录一起清），此时 [ids] 只用于展示条数。
 */
private data class DeleteRequest(val ids: List<String>, val all: Boolean)

/**
 * 历史记录：SessionStore.listSessions() 卡片列表，回前台自动刷新。
 *
 * 删除能力（用户要求）：
 * 1. 长按卡片 → 直接弹删除确认；
 * 2. 「管理」进入选择模式 → 逐条多选删除；
 * 3. 选择模式内提供「全部删除」。
 */
@Composable
fun HistoryScreen(onBack: () -> Unit, onOpenSession: (String) -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var sessions by remember { mutableStateOf<List<SessionManifest>>(emptyList()) }
    var reload by remember { mutableStateOf(0) }

    // ---- 删除相关状态 ----
    var selectMode by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() }
    var request by remember { mutableStateOf<DeleteRequest?>(null) }

    LaunchedEffect(reload) {
        sessions = AppGraph.store.listSessions()
        // 列表清空后自动退出选择模式，避免"已选 1 项但列表是空的"
        if (sessions.isEmpty()) {
            selectMode = false
            selected.clear()
        } else {
            // 已删掉的 id 从选择集里剔除
            val alive = sessions.map { it.taskId }.toSet()
            selected.removeAll { it !in alive }
        }
    }
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) reload++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    // 选择模式下系统返回键先退出选择，而不是退回上一页
    BackHandler(enabled = selectMode) {
        selectMode = false
        selected.clear()
    }

    fun toggle(taskId: String) {
        if (selected.contains(taskId)) selected.remove(taskId) else selected.add(taskId)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(WColors.Bg0)
            .statusBarsPadding(),
    ) {
        ScreenTopBar(
            title = if (selectMode) "已选 ${selected.size} 项" else "历史记录",
            subtitle = if (selectMode) {
                "点卡片多选，再点一次取消"
            } else {
                val samplesTotal = sessions.sumOf { it.sampleCount }
                "共 ${sessions.size} 次走测 · ${samplesTotal} 个样本 · 长按可删除"
            },
            onBack = {
                if (selectMode) {
                    selectMode = false
                    selected.clear()
                } else {
                    onBack()
                }
            },
            trailing = {
                if (sessions.isNotEmpty()) {
                    if (selectMode) {
                        val allSelected = selected.size == sessions.size
                        TextButton(
                            onClick = {
                                selected.clear()
                                if (!allSelected) sessions.forEach { selected.add(it.taskId) }
                            },
                        ) {
                            Text(
                                if (allSelected) "取消全选" else "全选",
                                style = MaterialTheme.typography.labelMedium,
                                color = WColors.OnBg,
                            )
                        }
                    } else {
                        TextButton(onClick = { selectMode = true }) {
                            Text("管理", style = MaterialTheme.typography.labelMedium, color = WColors.Primary)
                        }
                    }
                }
            },
        )
        if (sessions.isEmpty()) {
            EmptyState("还没有走测记录", "完成一次采集后，会话会出现在这里", Icons.Rounded.History)
        } else {
            Box(Modifier.weight(1f)) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = Space.screenH, end = Space.screenH, top = Space.x1, bottom = Space.x6),
                    verticalArrangement = Arrangement.spacedBy(Space.x3),
                ) {
                    // 日期分组：开始时间按天归并，组标题用 Eyebrow
                    val groups = sessions.groupBy { dayKey(it.startedAtEpochMs) }
                    groups.entries.forEachIndexed { gi, (day, list) ->
                        item(key = "day-$day") {
                            StaggerIn(gi) {
                                Eyebrow(dayLabel(day), Modifier.padding(top = if (gi == 0) Space.x1 else Space.x4, bottom = Space.x1))
                            }
                        }
                        itemsIndexed(list, key = { _, s -> s.taskId }) { i, s ->
                            StaggerIn(i) {
                                SessionCard(
                                    m = s,
                                    selecting = selectMode,
                                    isSelected = selected.contains(s.taskId),
                                    onClick = {
                                        if (selectMode) toggle(s.taskId) else onOpenSession(s.taskId)
                                    },
                                    onLongClick = {
                                        if (selectMode) {
                                            toggle(s.taskId)
                                        } else {
                                            request = DeleteRequest(listOf(s.taskId), all = false)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
            // ---------- 选择模式底部操作条 ----------
            if (selectMode) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = Space.screenH, vertical = Space.x3),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlassTextButton("全部删除", Modifier.weight(1f)) {
                        request = DeleteRequest(sessions.map { it.taskId }, all = true)
                    }
                    Spacer(Modifier.width(Space.x3))
                    GlassTextButton(
                        text = if (selected.isEmpty()) "删除选中" else "删除选中（${selected.size}）",
                        modifier = Modifier.weight(1f),
                        enabled = selected.isNotEmpty(),
                    ) {
                        request = DeleteRequest(selected.toList(), all = false)
                    }
                }
            }
        }
    }

    // ---------- 删除确认弹窗 ----------
    request?.let { req ->
        val sampleTotal = sessions.filter { it.taskId in req.ids }.sumOf { it.sampleCount }
        val title = when {
            req.all -> "删除全部记录？"
            req.ids.size == 1 -> "删除这条记录？"
            else -> "删除选中的 ${req.ids.size} 条记录？"
        }
        AlertDialog(
            onDismissRequest = { request = null },
            containerColor = WColors.Bg1,
            title = { Text(title, color = WColors.OnBg) },
            text = {
                Text(
                    "将删除 ${req.ids.size} 条记录、共 $sampleTotal 个采样点" +
                        "（含该会话的导出文件），删除后无法恢复。",
                    color = WColors.OnBgDim,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val n = if (req.all) {
                            AppGraph.store.deleteAllSessions()
                        } else {
                            req.ids.count { id -> runCatching { AppGraph.store.deleteSession(id) }.getOrDefault(false) }
                        }
                        request = null
                        selectMode = false
                        selected.clear()
                        reload++
                        toast(ctx, if (n > 0) "已删除 $n 条记录" else "没有可删除的记录")
                    },
                ) {
                    Text("删除", color = WColors.Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { request = null }) {
                    Text("取消", color = WColors.OnBgDim)
                }
            },
        )
    }
}

@Composable
private fun SessionCard(
    m: SessionManifest,
    selecting: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    // 选中态 = 实心主色薄底 + 发丝描边（不用叠加粗边框）
    val cardModifier = Modifier
        .fillMaxWidth()
        .then(
            if (isSelected) {
                Modifier
                    .background(WColors.Primary.copy(alpha = 0.14f), Radius.lgShape)
                    .hairline(Radius.lgShape, strong = true)
            } else {
                Modifier
            },
        )
    GlassCard(cardModifier, shape = Radius.lgShape, onClick = onClick, onLongClick = onLongClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selecting) {
                Icon(
                    if (isSelected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                    contentDescription = if (isSelected) "已选中" else "未选中",
                    tint = if (isSelected) WColors.Primary else WColors.OnBgDim,
                    modifier = Modifier.size(22.dp),
                )
            } else {
                Box(
                    Modifier
                        .size(38.dp)
                        .background(WColors.Primary.copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Description, contentDescription = null, tint = WColors.Primary, modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.width(Space.x3))
            Column(Modifier.weight(1f)) {
                Text(
                    m.title.ifBlank { "未命名任务" },
                    style = MaterialTheme.typography.titleMedium,
                    color = WColors.OnBg,
                    maxLines = 1,
                )
                Spacer(Modifier.height(Space.x1))
                Text(
                    metaText(m),
                    style = MonoText.style,
                    color = WColors.OnBgDim,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(Space.x2))
            if (selecting) {
                Icon(
                    Icons.Rounded.DeleteOutline,
                    contentDescription = null,
                    tint = WColors.OnBgDim.copy(alpha = 0.5f),
                    modifier = Modifier.size(16.dp),
                )
            } else {
                Icon(
                    Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = WColors.OnBgDim,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** 单行元信息（等宽）：样本数 · 时长 · 目标 · 时间 */
private fun metaText(m: SessionManifest): AnnotatedString {
    val ended = m.endedAtEpochMs
    val dur = if (ended != null) fmtDur(ended - m.startedAtEpochMs) else "进行中"
    return buildAnnotatedString {
        append("${m.sampleCount} 点 · ")
        pushStyle(SpanStyle(color = if (ended != null) WColors.OnBgDim else WColors.Warning))
        append(dur)
        pop()
        append(" · ${formatShort(m.targetFormat)} · ${fmtTs(m.startedAtEpochMs)}")
    }
}

/** 按天分组键（本地时区） */
private fun dayKey(ts: Long): Int {
    val c = Calendar.getInstance().apply { timeInMillis = ts }
    return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
}

private fun dayLabel(key: Int): String {
    val today = Calendar.getInstance()
    val todayKey = today.get(Calendar.YEAR) * 1000 + today.get(Calendar.DAY_OF_YEAR)
    return when (key) {
        todayKey -> "今天"
        todayKey - 1 -> "昨天"
        else -> {
            val c = Calendar.getInstance().apply {
                set(Calendar.YEAR, key / 1000)
                set(Calendar.DAY_OF_YEAR, key % 1000)
            }
            "${c.get(Calendar.MONTH) + 1}月${c.get(Calendar.DAY_OF_MONTH)}日"
        }
    }
}

private fun formatShort(f: String): String = when (f) {
    "ECI" -> "ECI 目标"
    "NCI" -> "NCI 目标"
    "LAC_CI" -> "LAC+CI 目标"
    "LATLON_RADIUS" -> "经纬度目标"
    else -> "目标"
}
