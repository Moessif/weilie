package com.gakj.weilie.ui.training

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gakj.weilie.core.model.SampleRecord
import com.gakj.weilie.core.rating.SignalRating
import com.gakj.weilie.ui.AppGraph
import com.gakj.weilie.ui.common.EmptyState
import com.gakj.weilie.ui.common.ScreenTopBar
import com.gakj.weilie.ui.common.fmtDur
import com.gakj.weilie.ui.map.MapCanvas
import com.gakj.weilie.ui.map.MapCamera
import com.gakj.weilie.ui.theme.GlassCard
import com.gakj.weilie.ui.theme.Motion
import com.gakj.weilie.ui.theme.MonoText
import com.gakj.weilie.ui.theme.PulsingDot
import com.gakj.weilie.ui.theme.SignalBars
import com.gakj.weilie.ui.theme.StatChip
import com.gakj.weilie.ui.theme.WColors
import com.gakj.weilie.ui.theme.glass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * 培训模式（STRICT_SPEC §4）：回放内置演示样本 assets/training/demo_session.jsonl
 * （逐行 SampleRecord JSON）；演示缺失时回退最近一次真实会话；两者都无则空态提示。
 */
@Composable
fun TrainingScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var samples by remember { mutableStateOf<List<SampleRecord>?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("培训演示") }
    var isFallback by remember { mutableStateOf(false) }
    var guides by remember { mutableStateOf<List<GuideCard>>(emptyList()) }

    // 数据加载：内置演示 → 最近真实会话兜底
    LaunchedEffect(Unit) {
        val json = Json { ignoreUnknownKeys = true }
        val demo = withContext(Dispatchers.IO) {
            runCatching {
                ctx.assets.open("training/demo_session.jsonl").bufferedReader().useLines { lines ->
                    lines.mapNotNull { line ->
                        val t = line.trim()
                        if (t.isEmpty()) null else runCatching { json.decodeFromString<SampleRecord>(t) }.getOrNull()
                    }.toList()
                }
            }.getOrNull().orEmpty()
        }
        if (demo.size >= 2) {
            samples = demo
        } else {
            val latest = AppGraph.store.listSessions().firstOrNull()
            if (latest != null) {
                val s = AppGraph.store.loadSamples(latest.taskId)
                if (s.size >= 2) {
                    samples = s
                    title = latest.title.ifBlank { "最近走测回放" }
                    isFallback = true
                }
            }
        }
        // 解说卡（STRICT_SPEC §4）：缺失或解析失败静默忽略，不报错
        guides = withContext(Dispatchers.IO) {
            runCatching {
                val raw = ctx.assets.open("training/demo_guide.json").bufferedReader().use { it.readText() }
                json.decodeFromString<List<GuideCard>>(raw)
            }.getOrDefault(emptyList())
        }
        loaded = true
    }

    val all = samples
    when {
        all != null -> TrainingPlayer(samples = all, title = title, isFallback = isFallback, guides = guides, onBack = onBack)
        !loaded -> Column(
            Modifier
                .fillMaxSize()
                .background(WColors.Bg0)
                .statusBarsPadding(),
        ) {
            ScreenTopBar("培训模式", "内置演示数据回放", onBack)
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                EmptyState("正在加载培训数据", "读取内置演示样本…")
            }
        }
        else -> Column(
            Modifier
                .fillMaxSize()
                .background(WColors.Bg0)
                .statusBarsPadding(),
        ) {
            ScreenTopBar("培训模式", "内置演示数据回放", onBack)
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                EmptyState(
                    "暂无培训数据",
                    "将演示文件放入 assets/training/demo_session.jsonl，或先完成一次真实走测",
                )
            }
        }
    }
}

@Composable
private fun TrainingPlayer(samples: List<SampleRecord>, title: String, isFallback: Boolean, guides: List<GuideCard>, onBack: () -> Unit) {
    val total = samples.size
    var progress by remember { mutableFloatStateOf(1f) }
    var playing by remember { mutableStateOf(true) }
    val idx = progress.toInt().coerceIn(1, total)
    val camera = remember { MapCamera() }
    var framed by remember { mutableStateOf(false) }

    // 首次取景：贴着起点（街道级），后续像跟拍一样跟着当前点走，讲解时轨迹始终在画面里
    LaunchedEffect(samples) {
        if (!framed && samples.isNotEmpty()) {
            camera.focusOn(samples.first().lat, samples.first().lon, TRAINING_ZOOM)
            framed = true
        }
    }

    // 自动播放：每 220ms 推进一个采样点
    LaunchedEffect(playing, total) {
        while (playing && progress < total) {
            delay(220)
            progress = (progress + 1f).coerceAtMost(total.toFloat())
        }
        if (progress >= total) playing = false
    }

    val shown = samples.take(idx)
    val cur = shown.lastOrNull()

    // 跟拍：当前采样点始终居中（播放时地图自己走，演示观感更接近真实作业）
    LaunchedEffect(cur) {
        cur?.let { camera.focusOn(it.lat, it.lon) }
    }
    val t0 = samples.first().tsEpochMs
    val elapsed = ((cur?.tsEpochMs ?: t0) - t0).coerceAtLeast(0)
    val level = cur?.rsrp?.let { SignalRating.level(it) } ?: -1
    val onTargetCount = shown.count { it.onTarget }

    // 当前解说卡：样本索引（0 基优先，未命中再按 1 基）落在 [from, to]
    val cur0 = idx - 1
    val guide = guides.lastOrNull { cur0 in it.from..it.to }
        ?: guides.lastOrNull { idx in it.from..it.to }

    Box(
        Modifier
            .fillMaxSize()
            .background(WColors.Bg0),
    ) {
        MapCanvas(
            modifier = Modifier.fillMaxSize(),
            samples = shown,
            camera = camera,
        )

        Column(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(10.dp),
        ) {
            ScreenTopBar("培训模式", if (isFallback) "内置演示缺失，回放最近真实会话" else "内置演示数据回放", onBack, onMap = true)
        }

        // ---------- 底部：解说卡（§4）+ 播放器 ----------
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(10.dp),
        ) {
            // 解说卡：进入新区间时滑入；无数据静默不显示
            val g = guide
            key(g?.title) {
                AnimatedVisibility(
                    visible = g != null,
                    enter = slideInVertically(
                        initialOffsetY = { it / 2 },
                        animationSpec = tween(Motion.normal, easing = Motion.EaseOut),
                    ) + fadeIn(tween(Motion.normal)),
                    exit = fadeOut(tween(Motion.fast)),
                ) {
                    if (g != null) {
                        GlassCard(
                            Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            shape = RoundedCornerShape(16.dp),
                            onMap = true,
                        ) {
                            Column {
                                Text(
                                    g.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = WColors.Primary,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    g.text,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = WColors.OnBgDim,
                                )
                            }
                        }
                    }
                }
            }
            GlassCard(Modifier.fillMaxWidth(), onMap = true) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(46.dp)
                            .glass(shape = CircleShape)
                            .clickable { playing = !playing },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (playing) "暂停" else "播放",
                            tint = WColors.Primary,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleSmall, color = WColors.OnBg)
                        Text(
                            "第 $idx / $total 点 · 已走 ${fmtDur(elapsed)}",
                            style = MonoText.style,
                            color = WColors.OnBgDim,
                        )
                    }
                    if (playing) PulsingDot(WColors.Primary, 8.dp)
                    Spacer(Modifier.width(8.dp))
                    SignalBars(level)
                    Spacer(Modifier.width(8.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            cur?.rsrp?.toString() ?: "--",
                            style = MonoText.style.copy(fontSize = 17.sp),
                            color = WColors.OnBg,
                        )
                        Text("RSRP", style = MaterialTheme.typography.labelSmall, color = WColors.OnBgDim)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Slider(
                    value = progress,
                    onValueChange = {
                        playing = false
                        progress = it
                    },
                    valueRange = 1f..total.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = WColors.Primary,
                        activeTrackColor = WColors.Primary,
                        inactiveTrackColor = WColors.Bg2,
                    ),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    StatChip("样本", "$total", Modifier.weight(1f))
                    StatChip("目标驻留", "$onTargetCount", Modifier.weight(1f), valueColor = WColors.Success)
                    StatChip("回放进度", "${idx * 100 / total}%", Modifier.weight(1f))
                }
            }
            }
        }
    }
}

/** 培训回放取景级别：演示轨迹约 1.3km，16.5 级正好街道级可读、又不丢整体走向 */
private const val TRAINING_ZOOM = 16.5f
