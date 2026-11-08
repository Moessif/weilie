package com.gakj.weilie.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.History
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.gakj.weilie.core.model.TargetFormat
import com.gakj.weilie.core.store.SessionManifest
import com.gakj.weilie.ui.AppGraph
import com.gakj.weilie.ui.Route
import com.gakj.weilie.ui.common.EmptyState
import com.gakj.weilie.ui.common.ScreenTopBar
import com.gakj.weilie.ui.common.StaggerIn
import com.gakj.weilie.ui.common.StatusBadge
import com.gakj.weilie.ui.common.fmtDay
import com.gakj.weilie.ui.common.fmtDur
import com.gakj.weilie.ui.common.fmtTs
import com.gakj.weilie.ui.theme.GlassCard
import com.gakj.weilie.ui.theme.MonoText
import com.gakj.weilie.ui.theme.WColors

/**
 * 历史记录：SessionStore.listSessions() 卡片列表，回前台自动刷新。
 */
@Composable
fun HistoryScreen(onBack: () -> Unit, onOpenSession: (String) -> Unit) {
    val owner = LocalLifecycleOwner.current
    var sessions by remember { mutableStateOf<List<SessionManifest>>(emptyList()) }
    var reload by remember { mutableStateOf(0) }

    androidx.compose.runtime.LaunchedEffect(reload) {
        sessions = AppGraph.store.listSessions()
    }
    androidx.compose.runtime.DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) reload++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(WColors.Bg0)
            .statusBarsPadding(),
    ) {
        ScreenTopBar("历史记录", "全部走测会话（按时间倒序）", onBack)
        if (sessions.isEmpty()) {
            EmptyState("还没有走测记录", "完成一次采集后，会话会出现在这里", Icons.Rounded.History)
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(sessions) { i, s ->
                    StaggerIn(i) {
                        SessionCard(s) { onOpenSession(s.taskId) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionCard(m: SessionManifest, onClick: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(38.dp)
                        .background(WColors.Primary.copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Description, contentDescription = null, tint = WColors.Primary, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        m.title.ifBlank { "未命名任务" },
                        style = MaterialTheme.typography.titleMedium,
                        color = WColors.OnBg,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "ID ${m.taskId} · ${fmtDay(m.startedAtEpochMs)}",
                        style = MonoText.style,
                        color = WColors.OnBgDim,
                    )
                }
                StatusBadge("${m.sampleCount} 点", WColors.Primary)
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(formatShort(m.targetFormat), WColors.Secondary)
                Spacer(Modifier.size(8.dp))
                val ended = m.endedAtEpochMs
                val dur = if (ended != null) fmtDur(ended - m.startedAtEpochMs) else "进行中"
                StatusBadge(dur, if (ended != null) WColors.OnBgDim else WColors.Warning)
                Spacer(Modifier.weight(1f))
                Text(
                    fmtTs(m.startedAtEpochMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = WColors.OnBgDim,
                )
            }
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
