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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gakj.weilie.ui.AppGraph
import com.gakj.weilie.ui.Route
import com.gakj.weilie.ui.common.GlassTextField
import com.gakj.weilie.ui.common.GlassTextButton
import com.gakj.weilie.ui.common.ScreenTopBar
import com.gakj.weilie.ui.common.randomRoomCode
import com.gakj.weilie.ui.common.toast
import com.gakj.weilie.ui.theme.GlassCard
import com.gakj.weilie.ui.theme.Hairline.hairline
import com.gakj.weilie.ui.theme.GlowButton
import com.gakj.weilie.ui.theme.MonoText
import com.gakj.weilie.ui.theme.Radius
import com.gakj.weilie.ui.theme.SectionHeader
import com.gakj.weilie.ui.theme.Space
import com.gakj.weilie.ui.theme.WColors
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 多人协同入口：昵称 + 服务器地址 + 创建房间（POST /api/teams）+ 加入房间
 * （GET /api/teams/by-code/{code}），身份可选队员 / 观战。
 */
@Composable
fun TeamScreen(onBack: () -> Unit, onScan: () -> Unit, onJoined: (String) -> Unit) {
    val ctx = LocalContext.current
    var nickname by remember { mutableStateOf(AppGraph.prefs.getString(Route.PREF_NICKNAME, "") ?: "") }
    var server by remember { mutableStateOf(AppGraph.prefs.getString(Route.PREF_SERVER, "") ?: "") }
    LaunchedEffect(Unit) { if (server.isBlank()) server = AppGraph.DEFAULT_SERVER }
    var code by remember { mutableStateOf(AppGraph.lastJoinCode ?: "") }
    var role by remember { mutableStateOf("MEMBER") } // 加入身份 MEMBER / OBSERVER
    var createRole by remember { mutableStateOf("CAPTAIN") } // 创建身份 CAPTAIN / MEMBER / OBSERVER
    var tab by remember { mutableIntStateOf(0) } // 0 创建房间 / 1 加入房间
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // 扫码返回：消费 AppGraph.lastScanCode 自动填入房号
    LaunchedEffect(Unit) {
        AppGraph.lastScanCode?.let { scanned ->
            AppGraph.lastScanCode = null
            code = scanned
            err = null
        }
    }

    fun normalizeBase(): String = server.trim().trimEnd('/')

    fun createRoom() {
        val base = normalizeBase()
        if (nickname.isBlank() || base.isBlank()) {
            err = "请先填写昵称与服务器地址"; toast(ctx, err!!)
            return
        }
        busy = true
        err = null
        scope.launch {
            try {
                val client = HttpClient(CIO)
                val resp = client.post("$base/api/teams") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"${nickname.trim().replace("\"", "'")}"}""")
                }
                val text = resp.bodyAsText()
                client.close()
                if (resp.status.value !in 200..299) {
                    err = "创建失败：HTTP ${resp.status.value} ${text.take(120)}"
                } else {
                    val obj = Json.parseToJsonElement(text).jsonObject
                    val roomId = (obj["roomId"] ?: obj["room_id"] ?: obj["id"])?.jsonPrimitive?.contentOrNull
                    val joinCode = (obj["joinCode"] ?: obj["join_code"] ?: obj["code"])?.jsonPrimitive?.contentOrNull
                    if (roomId.isNullOrBlank()) {
                        err = "服务器响应缺少 roomId：${text.take(120)}"
                    } else {
                        AppGraph.prefs.edit()
                            .putString(Route.PREF_NICKNAME, nickname.trim())
                            .putString(Route.PREF_SERVER, base)
                            .apply()
                        AppGraph.lastJoinCode = joinCode ?: randomRoomCode()
                        AppGraph.lastRole = createRole
                        onJoined(roomId)
                    }
                }
            } catch (e: Exception) {
                err = "网络错误：${e.message ?: e.javaClass.simpleName}"
            }
            busy = false
        }
    }

    fun joinRoom() {
        val base = normalizeBase()
        val c = code.trim().uppercase()
        if (base.isBlank()) {
            err = "请先填写服务器地址"; toast(ctx, err!!)
            return
        }
        if (c.length < 4) {
            err = "队伍码至少 4 位"; toast(ctx, err!!)
            return
        }
        busy = true
        err = null
        scope.launch {
            try {
                val client = HttpClient(CIO)
                val resp = client.get("$base/api/teams/by-code/$c")
                val text = resp.bodyAsText()
                client.close()
                if (resp.status.value !in 200..299) {
                    // QA-10：不要把 HTTP 状态码与原始 JSON 直接抛给用户
                    err = when (resp.status.value) {
                        404 -> "队伍码不存在，请向队长确认后重新输入"
                        400 -> "队伍码格式有误，请核对后重新输入"
                        in 500..599 -> "服务器开小差了（${resp.status.value}），请稍后重试"
                        else -> "加入失败（${resp.status.value}），请稍后重试"
                    }
                } else {
                    val obj = Json.parseToJsonElement(text).jsonObject
                    val roomId = (obj["roomId"] ?: obj["room_id"] ?: obj["id"])?.jsonPrimitive?.contentOrNull
                    if (roomId.isNullOrBlank()) {
                        err = "服务器响应缺少 roomId：${text.take(120)}"
                    } else {
                        AppGraph.prefs.edit()
                            .putString(Route.PREF_NICKNAME, nickname.trim())
                            .putString(Route.PREF_SERVER, base)
                            .apply()
                        AppGraph.lastJoinCode = c
                        AppGraph.lastRole = role
                        onJoined(roomId)
                    }
                }
            } catch (e: Exception) {
                err = "网络错误：${e.message ?: e.javaClass.simpleName}"
            }
            busy = false
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(WColors.Bg0)
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenTopBar("多人协同", "与队友共享位置、标记与任务圈", onBack)
        Spacer(Modifier.height(6.dp))

        SectionHeader("我的身份", modifier = Modifier.padding(horizontal = Space.screenH))
        GlassCard(Modifier.padding(horizontal = Space.screenH)) {
            GlassTextField(
                value = nickname,
                onValueChange = { nickname = it.take(16) },
                label = "昵称",
                placeholder = "队员昵称",
            )
        }
        Spacer(Modifier.height(Space.x3))

        SectionHeader("服务器", modifier = Modifier.padding(horizontal = Space.screenH))
        GlassCard(Modifier.padding(horizontal = Space.screenH)) {
            Column {
                GlassTextField(
                    value = server,
                    onValueChange = { server = it },
                    label = "协同服务器地址",
                    placeholder = "http://服务器IP:8080",
                    mono = true,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "由队伍统一部署，形如 http://阿里云IP:8080；房间通过 WebSocket 同步",
                    style = MaterialTheme.typography.labelSmall,
                    color = WColors.OnBgDim,
                )
            }
        }
        Spacer(Modifier.height(Space.x4))

        SectionHeader("房间", modifier = Modifier.padding(horizontal = Space.screenH))
        // 创建/加入合成一个分段控件：同屏只有一个主按钮，避免两条大按钮抢注意力
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.screenH)
                .clip(Radius.pillShape)
                .background(WColors.ChipBg)
                .padding(5.dp),
        ) {
            TeamTab("创建房间", tab == 0, Modifier.weight(1f)) { tab = 0 }
            TeamTab("加入房间", tab == 1, Modifier.weight(1f)) { tab = 1 }
        }
        Spacer(Modifier.height(Space.x3))

        if (tab == 0) {
            GlassCard(Modifier.padding(horizontal = Space.screenH)) {
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        RoleOption("队长", createRole == "CAPTAIN", Modifier.weight(1f)) { createRole = "CAPTAIN" }
                        RoleOption("队员", createRole == "MEMBER", Modifier.weight(1f)) { createRole = "MEMBER" }
                        RoleOption("观战", createRole == "OBSERVER", Modifier.weight(1f)) { createRole = "OBSERVER" }
                    }
                    Spacer(Modifier.height(Space.x3))
                    Text(
                        if (createRole == "CAPTAIN") "队长：可划分任务区并下发给队员"
                        else if (createRole == "MEMBER") "队员：接收任务圈并上报位置与样本"
                        else "观战：只读查看队伍动态，不上报位置",
                        style = MaterialTheme.typography.labelSmall,
                        color = WColors.OnBgDim,
                    )
                    Spacer(Modifier.height(Space.x3))
                    GlowButton("创建房间", Modifier.fillMaxWidth(), enabled = !busy) { createRoom() }
                }
            }
        } else {
            GlassCard(Modifier.padding(horizontal = Space.screenH)) {
                Column {
                    GlassTextField(
                        value = code,
                        onValueChange = { v ->
                            code = v.uppercase().filter { it.isLetterOrDigit() }.take(8)
                        },
                        label = "队伍码（4~16 位）",
                        placeholder = "ABC123",
                        mono = true,
                    )
                    Spacer(Modifier.height(Space.x2))
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.QrCodeScanner,
                            contentDescription = null,
                            tint = WColors.Primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "也可扫描队友出示的队伍码",
                            style = MaterialTheme.typography.labelSmall,
                            color = WColors.OnBgDim,
                            modifier = Modifier.weight(1f),
                        )
                        GlassTextButton("扫一扫") {
                            onScan()
                        }
                    }
                    Spacer(Modifier.height(Space.x3))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        RoleOption("队员", role == "MEMBER", Modifier.weight(1f)) { role = "MEMBER" }
                        RoleOption("观战（只读）", role == "OBSERVER", Modifier.weight(1f)) { role = "OBSERVER" }
                    }
                    Spacer(Modifier.height(Space.x3))
                    GlowButton("加入房间", Modifier.fillMaxWidth(), enabled = !busy) { joinRoom() }
                }
            }
        }

        err?.let {
            Spacer(Modifier.height(Space.x3))
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = WColors.Danger,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.screenH),
            )
        }
        Spacer(Modifier.height(Space.x5))
        TeamTips()
        Spacer(Modifier.navigationBarsPadding().height(24.dp))
    }
}

/** 底部玩法说明：三行，讲清队长/队员/观战的差别，避免半屏空白 */
@Composable
private fun TeamTips() {
    GlassCard(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.screenH),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.x3)) {
            TeamTipLine("队长", "在地图上划任务区，支持矩形、圈层、扇区和自由绘制")
            TeamTipLine("队员", "进入任务地图自动上报位置与采样点，可导航到队长的标记")
            TeamTipLine("观战", "只读房间状态，不采集、不上报，适合指挥席看全局")
        }
    }
}

@Composable
private fun TeamTipLine(tag: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .clip(Radius.smShape)
                .background(WColors.ChipBg)
                .hairline(Radius.smShape)
                .padding(horizontal = Space.x2, vertical = 3.dp),
        ) {
            Text(tag, style = MaterialTheme.typography.labelSmall, color = WColors.Primary)
        }
        Spacer(Modifier.width(Space.x3))
        Text(text, style = MaterialTheme.typography.bodySmall, color = WColors.OnBgDim, modifier = Modifier.weight(1f))
    }
}

/** 分段控件的一个页签：选中时实心主色，未选中只留弱文字 */
@Composable
private fun TeamTab(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(Radius.pillShape)
            .background(if (selected) WColors.Primary else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) WColors.OnPrimary else WColors.OnBgDim,
        )
    }
}

/** 身份选择块（队员 / 观战） */
@Composable
private fun RoleOption(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val border = if (selected) WColors.Primary else WColors.GlassBorder
    val bg = if (selected) WColors.Primary.copy(alpha = 0.12f) else WColors.Glass
    Column(
        modifier
            .background(bg, RoundedCornerShape(14.dp))
            .border(1.dp, border, RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(vertical = 12.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
    ) {
        Text(
            text,
            style = MonoText.style,
            color = if (selected) WColors.Primary else WColors.OnBgDim,
        )
    }
}
