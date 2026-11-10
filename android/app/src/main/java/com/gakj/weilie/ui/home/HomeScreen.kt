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
import androidx.compose.material.icons.rounded.SystemUpdate
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import com.gakj.weilie.ui.theme.WColors
import com.gakj.weilie.ui.theme.glass
import kotlin.math.cos
import kotlin.math.sin

/**
 * 大厅（STRICT_SPEC §1，PCL2 启动器形式）：
 * 右上 更新/通知/登录头像 + 左上问候区 + 中央雷达大视觉
 * + 底部胶囊抽屉（齿轮 | 单人模式 | 多人协同 | 头像）+ 胶囊下方最近会话宽卡片。
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
    // 未登录（含刚退出登录）一律显示「游客」，避免继续显示上一次登录的昵称
    val greet = if (logged) "您好，$nick" else "您好，游客"
    val recent = remember(tick) { AppGraph.store.listSessions().firstOrNull() }

    // 本地通知列表（无后端，按当前状态即时生成）
    val notifs = remember(tick, running) {
        buildList {
            add("开发版本 v${com.gakj.weilie.BuildConfig.VERSION_NAME}（尚未接入更新服务）")
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
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(14.dp))

            // ---------- 顶行：问候区 + 右上角横排 ----------
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        greet,
                        style = MaterialTheme.typography.headlineSmall,
                        color = WColors.OnBg,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        when {
                            !logged -> "点击右上角头像登录"
                            org.isBlank() -> "欢迎回来"
                            else -> "$org · 欢迎回来"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = WColors.OnBgDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.size(10.dp))

                // 更新提醒（带角标圆点）
                Box(contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .size(38.dp)
                            .glass(shape = CircleShape)
                            .clickable { toast(ctx, "当前为开发版本 v${com.gakj.weilie.BuildConfig.VERSION_NAME}，暂未接入更新服务") },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.SystemUpdate, contentDescription = "更新", tint = WColors.OnBgDim, modifier = Modifier.size(18.dp))
                    }
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .size(8.dp)
                            .background(WColors.Warning, CircleShape),
                    )
                }
                Spacer(Modifier.size(10.dp))

                // 通知（点开抽屉列表）
                Box(
                    Modifier
                        .size(38.dp)
                        .glass(shape = CircleShape)
                        .clickable { showNotif = !showNotif },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Notifications, contentDescription = "通知", tint = WColors.OnBgDim, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.size(10.dp))

                // 登录悬浮头像
                AvatarCircle(38.dp, nick, logged) { onAvatar() }
            }

            // ---------- 中央雷达大视觉 ----------
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                RadarVisual(running = running)
            }

            // ---------- 最近会话宽卡片 ----------
            GlassCard(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                onClick = { recent?.let { onContinueTask(it.taskId) } ?: onNewTask() },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .glass(shape = CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (recent != null) Icons.Rounded.History else Icons.Rounded.Radar,
                            contentDescription = null,
                            tint = WColors.Primary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            recent?.title ?: "围猎 v${com.gakj.weilie.BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.titleMedium,
                            color = WColors.OnBg,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            if (recent != null) {
                                "${recent.sampleCount} 个样本 · ${fmtDay(recent.startedAtEpochMs)} · 点击继续"
                            } else {
                                "点击开始第一次走测"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = WColors.OnBgDim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.size(6.dp))
                    Text(
                        "全部记录",
                        style = MaterialTheme.typography.labelSmall,
                        color = WColors.Primary,
                        modifier = Modifier.clickable(onClick = onHistory),
                    )
                    Spacer(Modifier.size(4.dp))
                    Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = WColors.OnBgDim, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.height(12.dp))

            // ---------- 底部胶囊抽屉 ----------
            Row(
                Modifier
                    .fillMaxWidth()
                    .glass(shape = RoundedCornerShape(999.dp))
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 最左：设置齿轮
                Box(
                    Modifier
                        .size(40.dp)
                        .glass(shape = CircleShape)
                        .clickable(onClick = onSettings),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Settings, contentDescription = "设置", tint = WColors.OnBgDim, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.size(8.dp))
                // 培训模式入口（图标圆钮）
                Box(
                    Modifier
                        .size(40.dp)
                        .glass(shape = CircleShape)
                        .clickable(onClick = onTraining),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.School, contentDescription = "培训模式", tint = WColors.Secondary, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.size(8.dp))
                CapsuleEntry("单人模式", Icons.Rounded.Radar, Modifier.weight(1f), onNewTask)
                Spacer(Modifier.size(8.dp))
                CapsuleEntry("多人协同", Icons.Rounded.Groups, Modifier.weight(1f), onTeam)
            }
            Spacer(Modifier.navigationBarsPadding().height(14.dp))
        }

        // ---------- 通知抽屉（玻璃卡下拉） ----------
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
                    .padding(top = 64.dp, end = 20.dp)
                    .width(300.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column {
                    Text("通知", style = MaterialTheme.typography.titleSmall, color = WColors.OnBg)
                    Spacer(Modifier.height(10.dp))
                    if (notifs.isEmpty()) {
                        Text("暂无通知", style = MaterialTheme.typography.bodySmall, color = WColors.OnBgDim)
                    } else {
                        notifs.forEach { n ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(5.dp).background(WColors.Primary, CircleShape))
                                Spacer(Modifier.size(8.dp))
                                Text(n, style = MaterialTheme.typography.bodySmall, color = WColors.OnBg, modifier = Modifier.weight(1f))
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }
}

/** 中央雷达：同心圆 + 旋转扫描尾迹 + 脉冲光点，液态玻璃质感 */
@Composable
private fun RadarVisual(modifier: Modifier = Modifier, running: Boolean) {
    val transition = rememberInfiniteTransition(label = "radar")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(3600, easing = LinearEasing), RepeatMode.Restart),
        label = "sweep",
    )
    val blip by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600), RepeatMode.Reverse),
        label = "blip",
    )

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(252.dp)) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val r = size.minDimension / 2f
            // 同心圆
            for (i in 1..4) {
                drawCircle(
                    color = WColors.GlassBorder,
                    radius = r * i / 4f,
                    center = c,
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
            // 十字准线
            drawLine(WColors.GlassBorder, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1.dp.toPx())
            drawLine(WColors.GlassBorder, Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1.dp.toPx())
            // 旋转扫描尾迹（沿角度渐亮的扇形）
            rotate(degrees = sweep, pivot = c) {
                drawArc(
                    brush = Brush.sweepGradient(
                        0f to WColors.Primary.copy(alpha = 0.45f),
                        0.18f to Color.Transparent,
                        1f to Color.Transparent,
                    ),
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = true,
                )
            }
            // 脉冲光点（模拟已捕获目标）
            val blips = listOf(
                Triple(0.55f, 40f, WColors.Primary),
                Triple(0.78f, 168f, WColors.Track),
                Triple(0.36f, 262f, WColors.Danger),
            )
            blips.forEach { (fr, ang, col) ->
                val a = Math.toRadians(ang.toDouble())
                val pr = r * fr
                val p = Offset(c.x + (pr * cos(a)).toFloat(), c.y + (pr * sin(a)).toFloat())
                drawCircle(col.copy(alpha = 0.18f + 0.2f * blip), radius = 10.dp.toPx(), center = p)
                drawCircle(col.copy(alpha = 0.35f + 0.6f * blip), radius = 3.5.dp.toPx(), center = p)
            }
            // 中心点
            drawCircle(WColors.Primary.copy(alpha = 0.25f), radius = 12.dp.toPx(), center = c)
            drawCircle(WColors.Primary, radius = 4.dp.toPx(), center = c)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            if (running) "正在追踪目标信号 · 采集中" else "雷达待机 · 等待任务",
            style = MonoText.style,
            color = if (running) WColors.Primary else WColors.OnBgDim,
        )
    }
}

/** 胶囊抽屉大入口：图标 + 文字，胶囊形可点击 */
@Composable
private fun CapsuleEntry(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(999.dp))
            // 跟随主题：浅色主题下 0x14FFFFFF 在白底上完全看不见，改用 ChipBg 自适应
            .background(WColors.ChipBg)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = title, tint = WColors.Primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.size(8.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, color = WColors.OnBg)
    }
}
