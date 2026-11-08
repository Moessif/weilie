package com.gakj.weilie.ui.ai

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.gakj.weilie.core.ai.AiClient
import com.gakj.weilie.core.ai.AiGuard
import com.gakj.weilie.ui.AppGraph
import com.gakj.weilie.ui.common.GlassTextButton
import com.gakj.weilie.ui.theme.GlassCard
import com.gakj.weilie.ui.theme.WColors
import com.gakj.weilie.ui.theme.glass
import kotlinx.coroutines.launch

/**
 * AI 通信助手面板：内置合规门（AiGuard），携带实测上下文摘要调用 AiClient，
 * 三条预设问题 + 自由输入。
 */
data class AiMsg(val fromUser: Boolean, val text: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSheet(contextSummary: String, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val msgs = remember { mutableStateListOf<AiMsg>() }
    var input by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    LaunchedEffect(msgs.size) {
        if (msgs.isNotEmpty()) listState.animateScrollToItem(msgs.size - 1)
    }

    fun send(raw: String) {
        val q = raw.trim()
        if (q.isEmpty() || loading) return
        input = ""
        val guard = AiGuard.check(q)
        if (guard != null) {
            msgs.add(AiMsg(false, guard))
            return
        }
        msgs.add(AiMsg(true, q))
        loading = true
        scope.launch {
            val ai = AppGraph.aiClient
            val prompt = ai.docAnswerPrompt(q, contextSummary)
            val result = ai.chat(
                listOf(
                    AiClient.AiMessage("system", ai.systemPrompt()),
                    AiClient.AiMessage("user", prompt),
                ),
            )
            msgs.add(AiMsg(false, result.getOrElse { "AI 请求失败：${it.message ?: "未知错误"}" }))
            loading = false
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = WColors.Bg1) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.SmartToy, contentDescription = null, tint = WColors.Secondary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("AI 通信助手", style = MaterialTheme.typography.titleMedium, color = WColors.OnBg)
                Spacer(Modifier.weight(1f))
                Text("依据设置页密钥工作", style = MaterialTheme.typography.labelSmall, color = WColors.OnBgDim)
            }
            Spacer(Modifier.height(12.dp))

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(340.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (msgs.isEmpty()) {
                    item {
                        GlassCard(Modifier.fillMaxWidth()) {
                            Text(
                                "我是走测场景的通信技术助手：可以解答 RSRP/RSRQ/SINR 含义、解读当前信号、建议走测路线。涉密与违法请求会被拒绝。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = WColors.OnBgDim,
                            )
                        }
                    }
                }
                items(msgs) { m -> Bubble(m) }
                if (loading) item { TypingDots() }
            }
            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassTextButton("现在信号怎么样？") { send("现在信号怎么样？") }
                GlassTextButton("接下来往哪走？") { send("接下来往哪走？") }
                GlassTextButton("RSRP 是什么？") { send("RSRP 是什么？") }
            }
            Spacer(Modifier.height(10.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("输入问题…", color = WColors.OnBgDim, style = MaterialTheme.typography.bodyMedium) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    textStyle = MaterialTheme.typography.bodyLarge,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = WColors.Primary,
                        unfocusedBorderColor = WColors.GlassBorder,
                        focusedContainerColor = WColors.FieldBg,
                        unfocusedContainerColor = WColors.FieldBgIdle,
                        cursorColor = WColors.Primary,
                        focusedTextColor = WColors.OnBg,
                        unfocusedTextColor = WColors.OnBg,
                    ),
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .size(46.dp)
                        .glass(shape = CircleShape)
                        .clip(CircleShape)
                        .then(
                            if (!loading && input.isNotBlank()) Modifier.clickable { send(input) } else Modifier,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.Send,
                        contentDescription = "发送",
                        tint = if (input.isNotBlank() && !loading) WColors.Primary else WColors.OnBgDim,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Spacer(Modifier.navigationBarsPadding().height(20.dp))
        }
    }
}

@Composable
private fun Bubble(m: AiMsg) {
    Row(Modifier.fillMaxWidth()) {
        if (m.fromUser) Spacer(Modifier.weight(1f))
        GlassCard(
            Modifier.fillMaxWidth(0.82f),
        ) {
            Text(m.text, style = MaterialTheme.typography.bodyMedium, color = WColors.OnBg)
        }
        if (!m.fromUser) Spacer(Modifier.weight(1f))
    }
}

/** AI 正在输入：三个跳动圆点 */
@Composable
private fun TypingDots() {
    val transition = rememberInfiniteTransition(label = "typing")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )
    Row(Modifier.padding(start = 8.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(3) { i ->
            val offset = ((phase * 3 - i).mod(1f)) // 0..1 循环
            val lift = (offset * (offset * (3 - 2 * offset))).toFloat() // smoothstep
            Box(
                Modifier
                    .size(7.dp)
                    .graphicsLayer { translationY = -6f * lift }
                    .clip(CircleShape)
                    .background(WColors.Secondary.copy(alpha = 0.4f + 0.6f * lift)),
            )
        }
    }
}
