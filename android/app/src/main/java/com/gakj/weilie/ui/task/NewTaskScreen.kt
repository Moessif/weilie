package com.gakj.weilie.ui.task

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gakj.weilie.core.model.CoverageTask
import com.gakj.weilie.core.model.TargetFormat
import com.gakj.weilie.core.parsing.TaskInputParser
import com.gakj.weilie.ui.AppGraph
import com.gakj.weilie.ui.common.GlassTextField
import com.gakj.weilie.ui.common.GlassTextButton
import com.gakj.weilie.ui.common.KeyValueRow
import com.gakj.weilie.ui.common.ScreenTopBar
import com.gakj.weilie.ui.common.StatusBadge
import com.gakj.weilie.ui.theme.GlassCard
import com.gakj.weilie.ui.theme.GlowButton
import com.gakj.weilie.ui.theme.MonoText
import com.gakj.weilie.ui.theme.SectionHeader
import com.gakj.weilie.ui.theme.StatChip
import com.gakj.weilie.ui.theme.WColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 新建走测任务：目标参数实时解析（TaskInputParser）、格式提示、示例填充、
 * 阈值滑条（-95..-125，默认 -110）、创建即带 pendingTask 跳转地图屏。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewTaskScreen(onBack: () -> Unit, onCreated: (String) -> Unit) {
    val ctx = LocalContext.current
    var input by remember { mutableStateOf("") }
    var name by remember {
        mutableStateOf(
            "任务 " + SimpleDateFormat("yyyyMMdd-HHmm", Locale.getDefault()).format(Date()),
        )
    }
    var threshold by remember { mutableStateOf(AppGraph.pendingThresholdDbm.toFloat()) }

    val parsed = remember(input) { TaskInputParser.parse(input) }
    val target = parsed.target
    val canCreate = target != null && name.isNotBlank()

    Column(
        Modifier
            .fillMaxSize()
            .background(WColors.Bg0)
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenTopBar("新建走测任务", "解析目标参数，规划走测路线", onBack)
        Spacer(Modifier.height(6.dp))

        // ---------- 目标参数 ----------
        SectionHeader("目标参数", modifier = Modifier.padding(horizontal = 20.dp))
        GlassCard(Modifier.padding(horizontal = 20.dp)) {
            Column {
                GlassTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = "粘贴上级提供的目标参数",
                    placeholder = "例：ECI 123456789 或 31.2304 121.4737 R=800",
                    singleLine = false,
                    minLines = 2,
                    mono = true,
                )
                Spacer(Modifier.height(10.dp))
                // 示例填充：FlowRow 自动换行，窄屏也不会把第 4 个 chip 裁在屏幕外
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    GlassTextButton("ECI 示例") { input = "ECI 123456789" }
                    GlassTextButton("NCI 示例") { input = "NCI 5677244420" }
                    GlassTextButton("LAC+CI 示例") { input = "LAC 12345 CI 67890" }
                    GlassTextButton("经纬度示例") { input = "31.2304 121.4737 R=800" }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---------- 解析结果 ----------
        SectionHeader("解析结果", modifier = Modifier.padding(horizontal = 20.dp))
        GlassCard(Modifier.padding(horizontal = 20.dp)) {
            Column {
                if (input.isBlank()) {
                    Text("等待输入…", style = MonoText.style, color = WColors.OnBgDim)
                } else if (target == null) {
                    Text(
                        parsed.error ?: "无法识别该参数",
                        style = MaterialTheme.typography.bodyMedium,
                        color = WColors.Danger,
                    )
                    if (parsed.normalized.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(parsed.normalized, style = MonoText.style, color = WColors.OnBgDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                } else {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        StatusBadge(formatLabel(target.format), WColors.Primary)
                        Spacer(Modifier.width(8.dp))
                        StatusBadge(
                            if (target.warnings.isEmpty()) "解析通过" else "带警告",
                            if (target.warnings.isEmpty()) WColors.Success else WColors.Warning,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    KeyValueRow("统一编号", target.unifiedCi()?.toString() ?: "—")
                    target.eci?.let {
                        KeyValueRow("ECI", it.toString())
                        KeyValueRow("十六进制", com.gakj.weilie.core.telephony.CellCodec.hexOf(it))
                        val (e, c) = com.gakj.weilie.core.telephony.CellCodec.decodeEci(it)
                        KeyValueRow("eNB / CID", "$e / $c")
                    }
                    target.nci?.let {
                        KeyValueRow("NCI", it.toString())
                        KeyValueRow("十六进制", com.gakj.weilie.core.telephony.CellCodec.hexOf(it))
                        val (g, c) = com.gakj.weilie.core.telephony.CellCodec.decodeNci(it)
                        KeyValueRow("gNB / CID", "$g / $c")
                    }
                    target.cid?.let { KeyValueRow("CID", it.toString()) }
                    target.lac?.let { KeyValueRow("LAC/TAC", it.toString()) }
                    target.mcc?.let { m -> KeyValueRow("MCC / MNC", "$m / ${target.mnc ?: "—"}") }
                    target.centerLat?.let { la ->
                        target.centerLon?.let { lo ->
                            KeyValueRow("中心经纬度", "%.6f, %.6f".format(la, lo))
                        }
                    }
                    target.givenRadiusM?.let { KeyValueRow("参考半径", "%.0f 米".format(it)) }
                    target.warnings.forEach { w ->
                        Spacer(Modifier.height(4.dp))
                        Text("⚠ $w", style = MaterialTheme.typography.labelMedium, color = WColors.Warning)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(parsed.normalized, style = MonoText.style, color = WColors.OnBgDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---------- 任务名称 ----------
        SectionHeader("任务名称", modifier = Modifier.padding(horizontal = 20.dp))
        GlassCard(Modifier.padding(horizontal = 20.dp)) {
            GlassTextField(
                value = name,
                onValueChange = { name = it },
                label = "任务名称（用于历史记录与导出标题）",
            )
        }
        Spacer(Modifier.height(12.dp))

        // ---------- 判定阈值 ----------
        SectionHeader(
            "判定阈值",
            trailing = {
                Text(
                    "RSRP ≥ ${threshold.toInt()} dBm 记为有效覆盖",
                    style = MaterialTheme.typography.labelSmall,
                    color = WColors.Primary,
                )
            },
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        GlassCard(Modifier.padding(horizontal = 20.dp)) {
            Column {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("-125", style = MonoText.style, color = WColors.OnBgDim)
                    Spacer(Modifier.width(10.dp))
                    Slider(
                        value = threshold,
                        onValueChange = { threshold = it },
                        valueRange = -125f..-95f,
                        steps = 29,
                        colors = SliderDefaults.colors(
                            thumbColor = WColors.Primary,
                            activeTrackColor = WColors.Primary,
                            inactiveTrackColor = WColors.GlassBorder,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("-95", style = MonoText.style, color = WColors.OnBgDim)
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatChip("当前阈值", "${threshold.toInt()} dBm", valueColor = WColors.Primary)
                    StatChip("经验值", "-110 dBm")
                }
            }
        }
        Spacer(Modifier.height(20.dp))

        // ---------- 创建 ----------
        GlowButton(
            text = "创建并开始",
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            enabled = canCreate,
        ) {
            val t = target ?: return@GlowButton
            val task = CoverageTask(
                id = UUID.randomUUID().toString().take(8).uppercase(Locale.ROOT),
                title = name.trim().ifBlank { "未命名任务" },
                target = t,
                createdAtEpochMs = System.currentTimeMillis(),
            )
            AppGraph.pendingTask = task
            AppGraph.pendingThresholdDbm = threshold.toInt()
            onCreated(task.id)
        }
        Spacer(Modifier.navigationBarsPadding().height(24.dp))
    }
}

@Composable
private fun formatLabel(f: TargetFormat): String = when (f) {
    TargetFormat.ECI -> "ECI · 4G 小区"
    TargetFormat.NCI -> "NCI · 5G 小区"
    TargetFormat.LAC_CI -> "LAC + CI"
    TargetFormat.LATLON_RADIUS -> "经纬度 + 半径"
}
