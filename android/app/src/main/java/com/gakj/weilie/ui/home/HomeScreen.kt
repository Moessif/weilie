package com.gakj.weilie.ui.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gakj.weilie.ui.AppGraph
import com.gakj.weilie.ui.Route
import com.gakj.weilie.ui.common.AvatarCircle
import com.gakj.weilie.ui.common.fmtDay
import com.gakj.weilie.ui.common.hasLocationPermission
import com.gakj.weilie.ui.common.toast
import com.gakj.weilie.ui.login.AccountStore
import com.gakj.weilie.ui.theme.GlassCard
import com.gakj.weilie.ui.theme.MonoText
import com.gakj.weilie.ui.theme.Radius
import com.gakj.weilie.ui.theme.Space
import com.gakj.weilie.ui.theme.WColors
import com.gakj.weilie.ui.theme.glass
import kotlin.math.cos
import kotlin.math.sin

/**
 * 大厅（v0.12 质感版）。
 *
 * 结构从「拼图式」改成三段式：
 * 1. 页头：品牌 eyebrow + 问候 + 右上角通知/头像（只留真正有用的两个）；
 * 2. 主视觉：一块会呼吸的雷达仪表（空闲/采集中两种状态），下方是状态说明；
 * 3. 底部：最近任务卡 + 培训入口 + 四格停靠栏。
 *
 * 视觉规则：单屏不堆卡片；所有可点区域在同一栅格线上；强调色只出现在
 * 「正在采集」和主入口两处，其余一律弱色，避免满屏彩色。
 */
@Composable
fun HomeScreen(
    onNewTask: () -> Unit,
    onTeam: () -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
    onAvatar: () -> Unit,
    onContinueTask: (String) -> Unit,
    onTraining: () -> Unit,
) {
    val ctx = LocalContext.current

    // 采集状态：回前台重读 + 持续收集
    var running by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(tick) {
        val c = AppGraph.currentController
        running = c?.running?.value ?: false
        c?.running?.collect { running = it }
    }

    val logged = remember(tick) { AccountStore.isLoggedIn() }
    val nick = remember(tick) { AccountStore.nickname() }
    val org = remember(tick) { AccountStore.org() }
    val greet = if (logged) "您好，$nick" else "您好，游客"
    val recent = remember(tick) { AppGraph.store.listSessions().firstOrNull() }

    // 本地通知列表（无后端，按当前状态即时生成）
    val notifs = remember(tick, running) {
        buildList {
            add("开发版本 v${com.gakj.weilie.BuildConfig.VERSION_NAME}")
            if (!hasLocationPermission(AppGraph.ctx)) add("定位权限未授予，走测采集不可用")
            if (AppGraph.prefs.getString(Route.PREF_AI_KEY, "").isNullOrEmpty()) add("AI 助手尚未配置，可在设置中填写密钥")
            if (running) add("当前任务正在采集样本")
        }
    }
    var showNotif by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(WColors.Bg0),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = Space.screenH),
        ) {
            Spacer(Modifier.height(Space.x3))

            // ---------- 页头 ----------
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "围猎 · FIELD SCOUT",
                        style = MaterialTheme.typography.labelSmall,
                        color = WColors.Primary.copy(alpha = 0.85f),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        greet,
                        style = MaterialTheme.typography.headlineMedium,
                        color = WColors.OnBg,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        when {
                            !logged -> "点击右上角头像登录"
                            org.isBlank() -> "欢迎回来"
                            else -> org
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = WColors.OnBgDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.size(Space.x2))
                TopBarIcon(Icons.Rounded.Notifications, "通知", hasDot = notifs.size > 1) { showNotif = !showNotif }
                Spacer(Modifier.size(Space.x2))
                AvatarCircle(42.dp, nick, logged) { onAvatar() }
            }

            // ---------- 主视觉：雷达仪表 ----------
            RadarPanel(
                running = running,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Space.x5),
            )

            // ---------- 采集参数（真实偏好值，不写死） ----------
            Spacer(Modifier.height(Space.x5))
            TelemetryStrip()
            Spacer(Modifier.weight(1f))

            // ---------- 最近任务 ----------
            if (recent != null) {
                Text(
                    "继续上次走测",
                    style = MaterialTheme.typography.labelSmall,
                    color = WColors.OnBgDim,
                    modifier = Modifier.padding(bottom = Space.x2),
                )
                GlassCard(
                    Modifier.fillMaxWidth(),
                    shape = Radius.lgShape,
                    onClick = { onContinueTask(recent.taskId) },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(42.dp)
                                .glass(shape = CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.History, contentDescription = null, tint = WColors.Primary, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.size(Space.x3))
                        Column(Modifier.weight(1f)) {
                            Text(
                                recent.title,
                                style = MaterialTheme.typography.titleMedium,
                                color = WColors.OnBg,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                "${recent.sampleCount} 个样本 · ${fmtDay(recent.startedAtEpochMs)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = WColors.OnBgDim,
                                maxLines = 1,
                            )
                        }
                        Spacer(Modifier.size(Space.x2))
                        Text(
                            "继续",
                            style = MaterialTheme.typography.labelLarge,
                            color = WColors.Primary,
                            modifier = Modifier.clickable(onClick = { onContinueTask(recent.taskId) }),
                        )
                        Spacer(Modifier.size(Space.x1))
                        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = WColors.OnBgDim, modifier = Modifier.size(18.dp))
                    }
                }
                Spacer(Modifier.height(Space.x2))
            } else {
                Text(
                    "准备就绪",
                    style = MaterialTheme.typography.labelSmall,
                    color = WColors.OnBgDim,
                    modifier = Modifier.padding(bottom = Space.x2),
                )
                GlassCard(
                    Modifier.fillMaxWidth(),
                    shape = Radius.lgShape,
                    onClick = onNewTask,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(42.dp)
                                .glass(shape = CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.Radar, contentDescription = null, tint = WColors.Primary, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.size(Space.x3))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "开始一次走测",
                                style = MaterialTheme.typography.titleMedium,
                                color = WColors.OnBg,
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                "单人模式 · 选中基站后沿路实测覆盖范围",
                                style = MaterialTheme.typography.bodySmall,
                                color = WColors.OnBgDim,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = WColors.OnBgDim, modifier = Modifier.size(18.dp))
                    }
                }
                Spacer(Modifier.height(Space.x2))
            }

            // ---------- 培训入口（弱化的一行） ----------
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(Radius.mdShape)
                    .clickable(onClick = onTraining)
                    .padding(horizontal = Space.x1, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.School, contentDescription = null, tint = WColors.OnBgDim, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(Space.x2))
                Text("培训模式", style = MaterialTheme.typography.bodyMedium, color = WColors.OnBg)
                Spacer(Modifier.size(Space.x2))
                Text(
                    "先练手，不计入正式任务",
                    style = MaterialTheme.typography.bodySmall,
                    color = WColors.OnBgDim,
                    modifier = Modifier.weight(1f),
                )
                Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = WColors.OnBgDim, modifier = Modifier.size(16.dp))
            }

            Spacer(Modifier.height(Space.x3))

            // ---------- 底部停靠栏 ----------
            Row(
                Modifier
                    .fillMaxWidth()
                    .glass(shape = Radius.pillShape)
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DockItem(Icons.Rounded.Radar, "单人走测", Modifier.weight(1f), accent = true, onClick = onNewTask)
                DockItem(Icons.Rounded.Groups, "多人协同", Modifier.weight(1f), onClick = onTeam)
                DockItem(Icons.Rounded.History, "历史", Modifier.weight(1f), onClick = onHistory)
                DockItem(Icons.Rounded.Settings, "设置", Modifier.weight(1f), onClick = onSettings)
            }
            Spacer(Modifier.navigationBarsPadding().height(Space.x3))
        }

        // ---------- 通知抽屉 ----------
        if (showNotif) {
            Box(
                Modifier
                    .matchParentSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { showNotif = false },
            )
            GlassCard(
                Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 66.dp, end = Space.screenH)
                    .width(300.dp),
                shape = Radius.lgShape,
            ) {
                Column {
                    Text("通知", style = MaterialTheme.typography.labelSmall, color = WColors.Primary.copy(alpha = 0.85f))
                    Spacer(Modifier.height(Space.x3))
                    notifs.forEachIndexed { i, n ->
                        if (i > 0) Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(4.dp).background(WColors.Primary, CircleShape))
                            Spacer(Modifier.size(Space.x2))
                            Text(n, style = MaterialTheme.typography.bodySmall, color = WColors.OnBg, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

/**
 * 采集参数条：三个真实值（采样间隔 / 精度门槛 / 定位权限），
 * 让大厅在不开任务时也能回答「现在的采集策略是什么」。
 */
@Composable
private fun TelemetryStrip() {
    val ctx = LocalContext.current
    val intervalMs = AppGraph.prefs.getLong(Route.PREF_SAMPLE_INTERVAL, 1000L)
    val gateAcc = AppGraph.prefs.getFloat(Route.PREF_GATE_ACCURACY, 50f)
    val locOk = hasLocationPermission(ctx)
    val intervalText = if (intervalMs % 1000L == 0L) {
        "${intervalMs / 1000L} s"
    } else {
        String.format(java.util.Locale.US, "%.1f s", intervalMs / 1000.0)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .glass(shape = Radius.mdShape)
            .padding(horizontal = Space.x3, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TelemetryItem("采样间隔", intervalText, Modifier.weight(1f))
        Box(Modifier.width(1.dp).height(20.dp).background(WColors.GlassBorderSoft))
        TelemetryItem("精度门槛", "≤ ${gateAcc.toInt()} m", Modifier.weight(1f))
        Box(Modifier.width(1.dp).height(20.dp).background(WColors.GlassBorderSoft))
        TelemetryItem("定位权限", if (locOk) "已授予" else "未授予", Modifier.weight(1f), if (locOk) WColors.OnBg else WColors.Warning)
    }
}

@Composable
private fun TelemetryItem(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = WColors.OnBg) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = WColors.OnBgDim)
        Spacer(Modifier.height(3.dp))
        Text(value, style = MonoText.style, color = valueColor, maxLines = 1)
    }
}

/** 右上角小圆形按钮（带可选的强调小点） */
@Composable
private fun TopBarIcon(icon: ImageVector, label: String, hasDot: Boolean = false, onClick: () -> Unit) {    Box(contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(42.dp)
                .glass(shape = CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = WColors.OnBgDim, modifier = Modifier.size(19.dp))
        }
        if (hasDot) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(7.dp)
                    .background(WColors.Warning, CircleShape),
            )
        }
    }
}

/**
 * 雷达仪表：同心环 + 方位刻度 + 渐隐扫描尾迹 + 目标光点。
 *
 * 旧版的扫描扇形用 sweepGradient 撑满 360°，实际渲染成一块「半透明饼」，
 * 在大屏上很像装饰图。这里改成 24 段 α 递减的窄扇，尾巴自然消失；
 * 环线也更细（0.6dp），只保留四条。
 */
@Composable
private fun RadarPanel(running: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "radar")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(5200, easing = LinearEasing), RepeatMode.Restart),
        label = "sweep",
    )
    val blip by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1500), RepeatMode.Reverse),
        label = "blip",
    )

    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.size(300.dp)) {
                val c = Offset(size.width / 2f, size.height / 2f)
                val r = size.minDimension / 2f

                // 同心环：4 条，外圈略亮
                for (i in 1..4) {
                    drawCircle(
                        color = if (i == 4) WColors.GlassBorder else WColors.GlassBorderSoft,
                        radius = r * i / 4f,
                        center = c,
                        style = Stroke(width = if (i == 4) 1.1.dp.toPx() else 0.6.dp.toPx()),
                    )
                }
                // 方位刻度：每 30° 一根短线（像真实的航向盘）
                for (deg in 0 until 360 step 30) {
                    val a = Math.toRadians(deg.toDouble())
                    val inner = r * 0.94f
                    val outer = r
                    drawLine(
                        color = if (deg % 90 == 0) WColors.GlassBorder else WColors.GlassBorderSoft,
                        start = Offset(c.x + (inner * cos(a)).toFloat(), c.y + (inner * sin(a)).toFloat()),
                        end = Offset(c.x + (outer * cos(a)).toFloat(), c.y + (outer * sin(a)).toFloat()),
                        strokeWidth = 1.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }

                // 扫描尾迹：18 段窄扇，越靠后越淡（尾巴短一点，像真的扫过去）
                rotate(degrees = sweep, pivot = c) {
                    for (i in 0 until 18) {
                        val f = 1f - i / 18f
                        val alpha = 0.16f * f * f
                        drawArc(
                            color = WColors.Primary.copy(alpha = alpha),
                            startAngle = -i * 2.2f,
                            sweepAngle = 2.2f,
                            useCenter = true,
                        )
                    }
                    drawLine(
                        color = WColors.Primary.copy(alpha = 0.40f),
                        start = c,
                        end = Offset(c.x, c.y - r),
                        strokeWidth = 1.dp.toPx(),
                    )
                }

                // 目标光点：两个足够，第三个会让画面变成装饰
                val blips = listOf(
                    Triple(0.62f, 38f, WColors.Primary),
                    Triple(0.42f, 238f, WColors.Target),
                )
                blips.forEach { (fr, ang, col) ->
                    val a = Math.toRadians(ang.toDouble())
                    val pr = r * fr
                    val p = Offset(c.x + (pr * cos(a)).toFloat(), c.y + (pr * sin(a)).toFloat())
                    drawCircle(col.copy(alpha = 0.10f + 0.10f * blip), radius = 9.dp.toPx(), center = p)
                    drawCircle(col, radius = 2.6.dp.toPx(), center = p)
                }

                // 中心：当前位置
                drawCircle(WColors.Primary.copy(alpha = 0.18f), radius = 9.dp.toPx(), center = c)
                drawCircle(WColors.Primary, radius = 3.dp.toPx(), center = c)
            }

            Spacer(Modifier.height(Space.x4))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(6.dp)
                        .background(if (running) WColors.Success else WColors.OnBgDim.copy(alpha = 0.5f), CircleShape),
                )
                Spacer(Modifier.size(Space.x2))
                Text(
                    if (running) "正在采集 · 保持屏幕常亮" else "待机中 · 尚未开始采集",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (running) WColors.OnBg else WColors.OnBgDim,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "逐秒采样 · GPS 双重安检 · 自动拟合覆盖范围",
                style = MonoText.style.copy(fontSize = 10.5.sp),
                color = WColors.OnBgDim.copy(alpha = 0.7f),
            )
        }
    }
}

/** 停靠栏入口：图标 + 小字，选中/强调态用主色 */
@Composable
private fun DockItem(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    val tint = if (accent) WColors.Primary else WColors.OnBgDim
    Column(
        modifier
            .clip(Radius.pillShape)
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = title, tint = tint, modifier = Modifier.size(21.dp))
        Spacer(Modifier.height(4.dp))
        Text(
            title,
            style = MaterialTheme.typography.labelSmall,
            color = if (accent) WColors.OnBg else WColors.OnBgDim,
            maxLines = 1,
        )
    }
}
