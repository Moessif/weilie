package com.gakj.weilie.ui.team

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gakj.weilie.core.sync.ConnState
import com.gakj.weilie.core.sync.MemberView
import com.gakj.weilie.core.sync.RoomClient
import com.gakj.weilie.core.sync.WsMsg
import com.gakj.weilie.ui.AppGraph
import com.gakj.weilie.ui.Route
import com.gakj.weilie.ui.common.ScreenTopBar
import com.gakj.weilie.ui.common.randomRoomCode
import com.gakj.weilie.ui.common.toast
import com.gakj.weilie.ui.theme.GlassCard
import com.gakj.weilie.ui.theme.GlowButton
import com.gakj.weilie.ui.theme.MonoText
import com.gakj.weilie.ui.theme.PulsingDot
import com.gakj.weilie.ui.theme.SectionHeader
import com.gakj.weilie.ui.theme.WColors
import com.gakj.weilie.ui.theme.glass
import kotlinx.coroutines.launch

/**
 * 房间管理页（STRICT_SPEC §3）：队伍码+二维码出示、成员列表、
 * 聊天大厅+对讲开关（UI 预留）、进入任务地图按钮；观战只读。
 */
@Composable
fun RoomScreen(roomId: String, onBack: () -> Unit, onEnterTask: () -> Unit) {
    val ctx = LocalContext.current
    val userId = remember { AppGraph.userId() }
    val name = remember {
        (AppGraph.prefs.getString(Route.PREF_NICKNAME, "") ?: "").ifBlank { "队员${userId.take(4)}" }
    }
    val role = remember { AppGraph.lastRole ?: "MEMBER" }
    val code = remember { AppGraph.lastJoinCode ?: randomRoomCode() }
    val wsBase = remember {
        val http = (AppGraph.prefs.getString(Route.PREF_SERVER, "") ?: "").trim().trimEnd('/')
            .ifBlank { AppGraph.DEFAULT_SERVER }
        when {
            http.isBlank() -> ""
            http.startsWith("https://") -> http.replaceFirst("https://", "wss://")
            http.startsWith("http://") -> http.replaceFirst("http://", "ws://")
            else -> "ws://$http"
        }
    }

    // 复用全局连接：若 TeamRoom 已连接同一房间则不重建
    val room = remember(roomId) {
        val existing = AppGraph.currentRoom
        if (existing != null) existing else {
            RoomClient(AppGraph.appScope, wsBase.ifBlank { "ws://127.0.0.1:8080" }).also {
                AppGraph.currentRoom = it
            }
        }
    }
    var connectedHere by remember { mutableStateOf(false) }
    DisposableEffect(roomId) {
        // 关键修复：connect() 内部用 running CAS 做了幂等，必须无条件调用。
        // 旧代码写的是 `if (AppGraph.currentRoom !== room) room.connect(...)`，
        // 而上面 remember 刚把 room 赋给了 AppGraph.currentRoom，条件恒为 false ——
        // 结果整个 App 的「房间管理页」从来没有建立过 WebSocket，界面一直显示「离线」、
        // 成员列表永远 0 人（用户真机反馈的"创建房间后不对劲"就是这个）。
        room.connect(WsMsg.Hello(roomId = roomId, userId = userId, name = name, role = role))
        connectedHere = true
        onDispose {
            if (connectedHere && AppGraph.currentRoom === room) {
                room.disconnect()
                AppGraph.currentRoom = null
            }
        }
    }

    val conn by room.state.collectAsState()
    val state by room.room.collectAsState()
    val chats by room.chats.collectAsState()
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var intercom by remember { mutableStateOf(false) }

    val (connText, connColor) = when (conn) {
        ConnState.CONNECTED -> "已连接" to WColors.Success
        ConnState.CONNECTING -> "连接中" to WColors.Warning
        ConnState.RETRYING -> "重连中" to WColors.Warning
        ConnState.DISCONNECTED -> "离线" to WColors.OnBgDim
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(WColors.Bg0),
    ) {
        // 可滚动内容区（占满底部按钮以上的全部空间）
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            ScreenTopBar("房间管理", "队伍码 · 成员 · 聊天", onBack)
            Spacer(Modifier.height(6.dp))

            // ---------- 房间信息 + 二维码 ----------
            SectionHeader("队伍码", modifier = Modifier.padding(horizontal = 20.dp))
            GlassCard(Modifier.padding(horizontal = 20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Groups, contentDescription = null, tint = WColors.Secondary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            PulsingDot(if (conn == ConnState.CONNECTED) WColors.Success else WColors.Warning, 8.dp)
                            Spacer(Modifier.width(4.dp))
                            Text(connText, style = MaterialTheme.typography.labelSmall, color = connColor)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(code, style = MonoText.style.copy(fontSize = 30.sp), color = WColors.Primary)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "队友可输入队伍码，或扫描右侧二维码加入本房间",
                            style = MaterialTheme.typography.labelSmall,
                            color = WColors.OnBgDim,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "房间 ID：$roomId",
                            style = MonoText.style,
                            color = WColors.OnBgDim,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    QrCodeImage("WEILIE:$code", size = 116)
                }
            }
            Spacer(Modifier.height(14.dp))

            // ---------- 对讲开关（UI 预留） ----------
            SectionHeader("语音对讲", modifier = Modifier.padding(horizontal = 20.dp))
            GlassCard(Modifier.padding(horizontal = 20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("实时对讲", style = MaterialTheme.typography.bodyMedium, color = WColors.OnBg)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "队伍语音通道（音频链路将在后续版本接入）",
                            style = MaterialTheme.typography.labelSmall,
                            color = WColors.OnBgDim,
                        )
                    }
                    Switch(
                        checked = intercom,
                        onCheckedChange = {
                            intercom = it
                            if (it) toast(ctx, "对讲通道预留，当前版本暂不传输语音")
                        },
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = WColors.Primary,
                            uncheckedTrackColor = WColors.Bg2,
                            checkedBorderColor = WColors.Primary,
                            uncheckedBorderColor = WColors.GlassBorder,
                        ),
                    )
                }
            }
            Spacer(Modifier.height(14.dp))

            // ---------- 成员列表 ----------
            SectionHeader(
                "队伍成员",
                modifier = Modifier.padding(horizontal = 20.dp),
                trailing = { Text("共 ${state.members.size} 人", style = MaterialTheme.typography.labelSmall, color = WColors.OnBgDim) },
            )
            GlassCard(Modifier.padding(horizontal = 20.dp)) {
                if (state.members.isEmpty()) {
                    Text(
                        "暂无其他成员，等待队友加入…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = WColors.OnBgDim,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        state.members.forEach { m -> MemberRow(m) }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))

            // ---------- 聊天大厅 ----------
            SectionHeader("聊天大厅", modifier = Modifier.padding(horizontal = 20.dp))
            GlassCard(Modifier.padding(horizontal = 20.dp)) {
                Column {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(220.dp),
                    ) {
                        if (chats.isEmpty()) {
                            Text(
                                "还没有消息，向队友打个招呼吧",
                                style = MaterialTheme.typography.bodyMedium,
                                color = WColors.OnBgDim,
                                modifier = Modifier.align(Alignment.Center),
                            )
                        } else {
                            Column(
                                Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                chats.takeLast(30).forEach { c ->
                                    val mine = c.from == userId
                                    Column(
                                        Modifier.fillMaxWidth(),
                                        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
                                    ) {
                                        Text(
                                            c.fromName,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = WColors.OnBgDim,
                                        )
                                        Spacer(Modifier.height(2.dp))
                                        Box(
                                            Modifier
                                                .background(
                                                    if (mine) WColors.Primary.copy(alpha = 0.16f) else WColors.Glass,
                                                    RoundedCornerShape(12.dp),
                                                )
                                                .border(
                                                    1.dp,
                                                    if (mine) WColors.Primary.copy(alpha = 0.4f) else WColors.GlassBorder,
                                                    RoundedCornerShape(12.dp),
                                                )
                                                .padding(horizontal = 10.dp, vertical = 6.dp),
                                        ) {
                                            Text(c.text, style = MaterialTheme.typography.bodyMedium, color = WColors.OnBg)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    if (role == "OBSERVER") {
                        Text(
                            "观战模式（只读，不能发送消息）",
                            style = MaterialTheme.typography.labelMedium,
                            color = WColors.OnBgDim,
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = input,
                                onValueChange = { input = it.take(120) },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("发送给队友…", color = WColors.OnBgDim, style = MaterialTheme.typography.bodyMedium) },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                textStyle = MaterialTheme.typography.bodyLarge,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = WColors.Primary,
                                    unfocusedBorderColor = WColors.GlassBorder,
                                    cursorColor = WColors.Primary,
                                    focusedTextColor = WColors.OnBg,
                                    unfocusedTextColor = WColors.OnBg,
                                ),
                            )
                            Spacer(Modifier.width(8.dp))
                            Box(
                                Modifier
                                    .size(42.dp)
                                    .glass(shape = CircleShape)
                                    .clickable {
                                        val t = input.trim()
                                        if (t.isNotEmpty()) {
                                            input = ""
                                            scope.launch { room.chat(t, userId, name) }
                                        }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Rounded.Send,
                                    contentDescription = "发送",
                                    tint = if (input.isNotBlank()) WColors.Primary else WColors.OnBgDim,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // ---------- 进入任务（固定在页面底部，一进房间即可见） ----------
        GlowButton(
            "进入任务地图",
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp),
        ) { onEnterTask() }
    }
}

/** 成员行：状态点 + 昵称 + 角色 + 采样数 + 目标驻留 */
@Composable
private fun MemberRow(m: MemberView) {
    val isCaptain = m.role == "CAPTAIN"
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(10.dp)
                .background(
                    if (m.onTarget) WColors.Success
                    else if (m.lat != null) WColors.Teammate
                    else WColors.OnBgDim.copy(alpha = 0.4f),
                    CircleShape,
                ),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            m.name + if (isCaptain) " · 队长" else if (m.role == "OBSERVER") " · 观战" else "",
            style = MaterialTheme.typography.bodyMedium,
            color = WColors.OnBg,
        )
        Spacer(Modifier.weight(1f))
        Text("${m.sampleCount} 采", style = MonoText.style, color = WColors.OnBgDim)
    }
}
