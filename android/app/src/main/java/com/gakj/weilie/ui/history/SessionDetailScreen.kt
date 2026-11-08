package com.gakj.weilie.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.gakj.weilie.core.fitting.CoverageFitter
import com.gakj.weilie.core.fitting.CoverageResult
import com.gakj.weilie.core.model.CoverageTask
import com.gakj.weilie.core.model.SampleRecord
import com.gakj.weilie.core.rating.SignalRating
import com.gakj.weilie.core.store.SessionManifest
import com.gakj.weilie.ui.AppGraph
import com.gakj.weilie.ui.common.GlassTextButton
import com.gakj.weilie.ui.common.KeyValueRow
import com.gakj.weilie.ui.common.ScreenTopBar
import com.gakj.weilie.ui.common.StatusBadge
import com.gakj.weilie.ui.common.exportCsvAndShare
import com.gakj.weilie.ui.common.exportGeoJsonAndShare
import com.gakj.weilie.ui.common.exportKmlAndShare
import com.gakj.weilie.ui.common.fmtDur
import com.gakj.weilie.ui.common.fmtTs
import com.gakj.weilie.ui.common.toast
import com.gakj.weilie.ui.map.MapCamera
import com.gakj.weilie.ui.map.MapCanvas
import com.gakj.weilie.ui.theme.GlassCard
import com.gakj.weilie.ui.theme.SectionHeader
import com.gakj.weilie.ui.theme.StatChip
import com.gakj.weilie.ui.theme.WColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 会话详情：统计卡片 + 小地图回放（滑杆逐点重放）+ 导出三件套。
 */
@Composable
fun SessionDetailScreen(taskId: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var manifest by remember { mutableStateOf<SessionManifest?>(null) }
    var samples by remember { mutableStateOf<List<SampleRecord>>(emptyList()) }
    var fit by remember { mutableStateOf<CoverageResult?>(null) }
    var replayIdx by remember { mutableFloatStateOf(0f) }
    var playing by remember { mutableStateOf(false) }
    val camera = remember { MapCamera() }

    LaunchedEffect(taskId) {
        val m = withContext(Dispatchers.IO) { AppGraph.store.loadManifest(taskId) }
        val s = withContext(Dispatchers.IO) { AppGraph.store.loadSamples(taskId) }
        manifest = m
        samples = s
        if (s.size >= 8) {
            fit = withContext(Dispatchers.Default) {
                CoverageFitter.fit(s, CoverageFitter.Options())
            }
        }
        if (s.isNotEmpty()) {
            camera.fit(s.map { it.lat to it.lon })
            replayIdx = s.size.toFloat()
        }
    }

    // 回放播放：8 点/秒；若已在末尾（打开即全量显示），点播放先回到起点再播
    LaunchedEffect(playing, samples.size) {
        if (playing && samples.isNotEmpty()) {
            if (replayIdx >= samples.size) replayIdx = 0f
            while (replayIdx < samples.size) {
                replayIdx += 0.125f
                kotlinx.coroutines.delay(125)
            }
            playing = false
        }
    }

    val shown = samples.take(replayIdx.roundToInt().coerceAtLeast(1))
    val m = manifest

    // ---------- 统计 ----------
    val onTargetN = samples.count { it.onTarget }
    val seenN = samples.count { it.targetSeen }
    val rsrps = samples.mapNotNull { it.rsrp }
    val avgRsrp = if (rsrps.isNotEmpty()) rsrps.average().roundToInt() else null
    val bestRsrp = rsrps.maxOrNull()
    val durationMs = (m?.endedAtEpochMs ?: samples.lastOrNull()?.tsEpochMs ?: 0L) - (m?.startedAtEpochMs ?: 0L)
    val distM = samples.zipWithNext().sumOf { (a, b) ->
        com.gakj.weilie.core.fitting.Geo.haversineMeters(a.lat, a.lon, b.lat, b.lon)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(WColors.Bg0)
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenTopBar(
            m?.title?.ifBlank { "会话详情" } ?: "会话详情",
            "ID $taskId · ${fmtTs(m?.startedAtEpochMs ?: 0L)}",
            onBack,
        )

        // ---------- 小地图回放 ----------
        SectionHeader("轨迹回放", modifier = Modifier.padding(horizontal = 20.dp))
        GlassCard(Modifier.padding(horizontal = 20.dp)) {
            Column {
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .fillMaxWidth()
                        .height(240.dp),
                ) {
                    MapCanvas(
                        modifier = Modifier.fillMaxSize(),
                        target = null,
                        samples = shown,
                        hullLatLon = if (replayIdx >= samples.size) fit?.hullLatLon else null,
                        bufferedLatLon = if (replayIdx >= samples.size) fit?.bufferedLatLon else null,
                        isolines = if (replayIdx >= samples.size) fit?.isolineLoopsLatLon ?: emptyList() else emptyList(),
                        camera = camera,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GlassTextButton(if (playing) "暂停" else "播放") { playing = !playing }
                    Spacer(Modifier.size(8.dp))
                    GlassTextButton("结尾") { replayIdx = samples.size.toFloat(); playing = false }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${shown.size} / ${samples.size} 点",
                        style = com.gakj.weilie.ui.theme.MonoText.style,
                        color = WColors.OnBgDim,
                    )
                }
                Slider(
                    value = replayIdx,
                    onValueChange = {
                        replayIdx = it
                        playing = false
                    },
                    valueRange = 1f..(samples.size.coerceAtLeast(1)).toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = WColors.Primary,
                        activeTrackColor = WColors.Primary,
                        inactiveTrackColor = WColors.GlassBorder,
                    ),
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        // ---------- 统计 ----------
        SectionHeader("统计", modifier = Modifier.padding(horizontal = 20.dp))
        GlassCard(Modifier.padding(horizontal = 20.dp)) {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatChip("样本点", "${samples.size}", valueColor = WColors.Primary)
                    StatChip("总时长", fmtDur(durationMs))
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatChip("目标驻留", "$onTargetN 点", valueColor = WColors.Success)
                    StatChip("目标可见", "$seenN 点", valueColor = WColors.Track)
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatChip("平均 RSRP", avgRsrp?.let { "$it dBm" } ?: "—")
                    StatChip("最优 RSRP", bestRsrp?.let { "$it dBm" } ?: "—")
                }
                Spacer(Modifier.height(10.dp))
                KeyValueRow("轨迹长度", "%.0f 米".format(distM))
                fit?.confidence?.let { c ->
                    KeyValueRow("拟合置信度", "${c.level.name} · ${c.score0to100} 分 · 均误差 %.0f 米".format(c.meanAccuracyM))
                    KeyValueRow("参与拟合", "${fit?.inlierCount} / ${fit?.totalSamples} 点")
                }
                m?.targetRawInput?.let { KeyValueRow("目标输入", it, WColors.Primary) }
            }
        }
        Spacer(Modifier.height(16.dp))

        // ---------- 导出 ----------
        SectionHeader("导出", modifier = Modifier.padding(horizontal = 20.dp))
        GlassCard(Modifier.padding(horizontal = 20.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassTextButton("CSV", Modifier.weight(1f)) { exportCsvAndShare(ctx, AppGraph.store, taskId) }
                GlassTextButton("GeoJSON", Modifier.weight(1f)) {
                    exportGeoJsonAndShare(ctx, taskId, samples, null, fit)
                }
                GlassTextButton("KML", Modifier.weight(1f)) {
                    exportKmlAndShare(ctx, taskId, m?.title ?: taskId, samples, fit)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "导出文件保存在应用 exports 目录并弹出分享面板",
                style = MaterialTheme.typography.labelSmall,
                color = WColors.OnBgDim,
            )
        }
        Spacer(Modifier.navigationBarsPadding().height(24.dp))
    }
}
