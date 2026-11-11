package com.gakj.weilie.ui.task

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.gakj.weilie.core.model.CoverageTask
import com.gakj.weilie.core.model.TargetCell
import com.gakj.weilie.core.model.TargetFormat
import com.gakj.weilie.core.parsing.TaskInputParser
import com.gakj.weilie.core.telephony.AndroidCellReader
import com.gakj.weilie.core.telephony.CellSnapshot
import com.gakj.weilie.core.telephony.CellTargetShortcut
import com.gakj.weilie.ui.AppGraph
import com.gakj.weilie.ui.common.GlassTextButton
import com.gakj.weilie.ui.common.GlassTextField
import com.gakj.weilie.ui.common.HelpHint
import com.gakj.weilie.ui.common.HelpTexts
import com.gakj.weilie.ui.common.KeyValueRow
import com.gakj.weilie.ui.common.ScreenTopBar
import com.gakj.weilie.ui.common.StatusBadge
import com.gakj.weilie.ui.common.toast
import com.gakj.weilie.ui.theme.GlassCard
import com.gakj.weilie.ui.theme.GlowButton
import com.gakj.weilie.ui.theme.MonoText
import com.gakj.weilie.ui.theme.OutlineButton
import com.gakj.weilie.ui.theme.SectionHeader
import com.gakj.weilie.ui.theme.Space
import com.gakj.weilie.ui.theme.StatChip
import com.gakj.weilie.ui.theme.WColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 新建走测任务：
 * - 目标参数「分别输入 - 保存」：可以一次只录一条，存进清单，清单里点一条作为本次任务目标；
 * - 规格校验：不符合规格（编号越界、经纬度越界等）不让创建，红字说明原因；
 * - 每个参数区都有圆圈感叹号，点开是给民警看的通俗说明。
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

    // 已保存的目标参数清单：只在「本次填写任务」内有效。
    // 用户反馈「新建任务时上一个任务已保存的目标还在」→ 进入页面就清空，避免跨任务串参数。
    var saved by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedIdx by remember { mutableIntStateOf(-1) }
    remember { saveSavedTargets(emptyList()) }

    // 「用本机当前基站」：一键把当前驻留小区填成目标。
    // 读 telephony 需要「电话」权限（订阅/小区信息）与「定位」权限（getAllCellInfo 的硬性前置）。
    var readingCell by remember { mutableStateOf(false) }
    var cellHint by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun startFillFromCell() {
        if (readingCell) return
        readingCell = true
        scope.launch {
            val fill = runCatching { readServingCellInput(ctx) }.getOrNull()
            readingCell = false
            if (fill == null) {
                toast(ctx, "读不到当前基站：确认已插卡，并允许「电话」与「定位」权限")
                return@launch
            }
            input = fill.raw
            cellHint = "已填入：" + fill.hint
            val raw = fill.raw.trim()
            val list = if (saved.any { it.trim() == raw }) saved else saved + raw
            saved = list
            selectedIdx = list.indexOfFirst { it.trim() == raw }
            saveSavedTargets(list)
            toast(ctx, "已把当前基站设为本次目标")
        }
    }

    val cellPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.isEmpty() || grants.values.all { it }) {
            startFillFromCell()
        } else {
            toast(ctx, "需要「电话」与「定位」权限才能读取当前基站")
        }
    }

    val validation = remember(input) { TaskInputParser.validate(input) }
    val parsed = remember(input) { TaskInputParser.parse(input) }
    val inputIsBlank = input.isBlank()
    val canAdd = !inputIsBlank && validation.ok

    val selectedRaw: String? = saved.getOrNull(selectedIdx)
    val selectedValidation = remember(selectedRaw) {
        selectedRaw?.let { TaskInputParser.validate(it) }
    }
    val selectedTarget: TargetCell? = remember(selectedRaw) {
        selectedRaw?.let { TaskInputParser.parse(it).target }
    }
    val canCreate = selectedTarget != null &&
        (selectedValidation?.ok == true) &&
        name.isNotBlank()

    Column(
        Modifier
            .fillMaxSize()
            .background(WColors.Bg0)
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenTopBar("新建走测任务", "解析目标参数，规划走测路线", onBack)
        Spacer(Modifier.height(6.dp))

        // ---------- 目标参数 ----------
        SectionHeader(
            "目标参数",
            modifier = Modifier.padding(horizontal = Space.screenH),
            trailing = { HelpHint("目标参数怎么填", HelpTexts.targetParams) },
        )
        GlassCard(Modifier.padding(horizontal = Space.screenH)) {
            Column {
                GlassTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = "粘贴上级提供的一条目标参数",
                    placeholder = "例：ECI 123456789 或 31.2304 121.4737 R=800",
                    singleLine = false,
                    minLines = 2,
                    mono = true,
                )
                Spacer(Modifier.height(10.dp))
                // 快捷：把本机当前驻留小区设为目标（不用再问上级/手抄编号）
                OutlineButton(
                    text = if (readingCell) "正在读取本机基站…" else "用本机当前基站",
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !readingCell,
                ) {
                    val missing = missingCellPermissions(ctx)
                    if (missing.isEmpty()) {
                        startFillFromCell()
                    } else {
                        cellPermLauncher.launch(missing.toTypedArray())
                    }
                }
                if (cellHint.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = cellHint,
                        style = MaterialTheme.typography.labelMedium,
                        color = WColors.OnBgDim,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(10.dp))
                // 示例填充
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
                Spacer(Modifier.height(12.dp))
                // 规格校验结果（不通过就不给保存/创建）
                if (!inputIsBlank) {
                    if (validation.ok) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusBadge("规格校验通过", WColors.Success)
                            if (validation.warnings.isNotEmpty()) {
                                Spacer(Modifier.width(8.dp))
                                StatusBadge("有 ${validation.warnings.size} 条提醒", WColors.Warning)
                            }
                        }
                        validation.warnings.forEach { w ->
                            Spacer(Modifier.height(4.dp))
                            Text("⚠ $w", style = MaterialTheme.typography.labelMedium, color = WColors.Warning)
                        }
                    } else {
                        validation.blocking.forEach { e ->
                            Text("✕ $e", style = MaterialTheme.typography.bodyMedium, color = WColors.Danger)
                            Spacer(Modifier.height(2.dp))
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
                GlowButton(
                    text = if (canAdd) "保存到目标清单" else "修正参数后才能保存",
                    modifier = Modifier.fillMaxWidth(),
                    enabled = canAdd,
                ) {
                    val raw = input.trim()
                    val exists = saved.any { it.trim() == raw }
                    if (exists) {
                        toast(ctx, "这条参数已经在清单里了")
                        selectedIdx = saved.indexOfFirst { it.trim() == raw }
                    } else {
                        saved = saved + raw
                        selectedIdx = saved.lastIndex
                        saveSavedTargets(saved)
                        toast(ctx, "已保存（共 ${saved.size} 条）")
                    }
                    input = ""
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---------- 已保存的目标清单 ----------
        SectionHeader(
            "已保存的目标（${saved.size}）",
            modifier = Modifier.padding(horizontal = Space.screenH),
            trailing = {
                if (saved.isNotEmpty()) {
                    GlassTextButton("清空") {
                        saved = emptyList()
                        selectedIdx = -1
                        saveSavedTargets(saved)
                    }
                }
            },
        )
        GlassCard(Modifier.padding(horizontal = Space.screenH)) {
            Column {
                if (saved.isEmpty()) {
                    Text(
                        "还没有目标。输入一条参数后点上面的按钮存进来；上级给了几条就分几次存。",
                        style = MaterialTheme.typography.bodySmall,
                        color = WColors.OnBgDim,
                    )
                } else {
                    saved.forEachIndexed { idx, raw ->
                        val v = TaskInputParser.validate(raw)
                        val t = TaskInputParser.parse(raw).target
                        val isSel = idx == selectedIdx
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .then(
                                    if (isSel) {
                                        Modifier
                                            .background(WColors.Primary.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                                            .border(1.dp, WColors.Primary.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                                    } else {
                                        Modifier
                                    },
                                )
                                .clickable { selectedIdx = idx }
                                .padding(horizontal = 10.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (isSel) Icons.Rounded.RadioButtonChecked else Icons.Rounded.RadioButtonUnchecked,
                                contentDescription = null,
                                tint = if (isSel) WColors.Primary else WColors.OnBgDim,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    t?.let { summaryOf(it) } ?: raw,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (v.ok) WColors.OnBg else WColors.Danger,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (!v.ok) {
                                    Text(
                                        v.blocking.firstOrNull().orEmpty(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = WColors.Danger,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = "从清单移除",
                                tint = WColors.OnBgDim,
                                modifier = Modifier
                                    .size(20.dp)
                                    .clickable {
                                        saved = saved.filterIndexed { i, _ -> i != idx }
                                        if (selectedIdx >= saved.size) selectedIdx = saved.lastIndex
                                        saveSavedTargets(saved)
                                    },
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---------- 本次目标解析详情 ----------
        SectionHeader("本次目标", modifier = Modifier.padding(horizontal = Space.screenH))
        GlassCard(Modifier.padding(horizontal = Space.screenH)) {
            Column {
                val target = selectedTarget
                if (target == null) {
                    Text("在上面的清单里点一条作为本次走测目标", style = MonoText.style, color = WColors.OnBgDim)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
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
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---------- 任务名称 ----------
        SectionHeader("任务名称", modifier = Modifier.padding(horizontal = Space.screenH))
        GlassCard(Modifier.padding(horizontal = Space.screenH)) {
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "≥ ${threshold.toInt()} dBm 记为有效覆盖",
                        style = MaterialTheme.typography.labelSmall,
                        color = WColors.Primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    HelpHint("信号强弱怎么算", HelpTexts.threshold)
                }
            },
            modifier = Modifier.padding(horizontal = Space.screenH),
        )
        GlassCard(Modifier.padding(horizontal = Space.screenH)) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("-125", style = MonoText.style, color = WColors.OnBgDim)
                    Spacer(Modifier.width(10.dp))
                    Slider(
                        value = threshold,
                        onValueChange = { threshold = it },
                        valueRange = -125f..-95f,
                        steps = 0,
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
                .padding(horizontal = Space.screenH),
            enabled = canCreate,
        ) {
            val t = selectedTarget ?: return@GlowButton
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
        if (!canCreate) {
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    selectedTarget == null -> "请先在「已保存的目标」里选一条"
                    selectedValidation?.ok != true -> "所选参数不符合规格，请按红字提示修正"
                    else -> "请填写任务名称"
                },
                style = MaterialTheme.typography.labelSmall,
                color = WColors.OnBgDim,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
        Spacer(Modifier.navigationBarsPadding().height(24.dp))
    }
}

/** 目标清单的一行摘要：格式 + 关键编号，列表里一行看清 */
private fun summaryOf(t: TargetCell): String {
    val head = when (t.format) {
        TargetFormat.ECI -> "ECI ${t.eci}"
        TargetFormat.NCI -> "NCI ${t.nci}"
        TargetFormat.LAC_CI -> "LAC ${t.lac} · CI ${t.cid}"
        TargetFormat.LATLON_RADIUS -> "%.5f, %.5f".format(t.centerLat ?: 0.0, t.centerLon ?: 0.0)
    }
    val mcc = t.mcc?.let { m -> " · $m-${t.mnc ?: "—"}" } ?: ""
    return head + mcc
}

private const val PREF_SAVED_TARGETS = "saved_targets"
private const val TARGET_SEP = "\u0001"

/** 目标清单持久化：每条参数是单行文本，用不可见分隔符拼接，避免 JSON 依赖 */
private fun loadSavedTargets(): List<String> {
    val raw = AppGraph.prefs.getString(PREF_SAVED_TARGETS, "").orEmpty()
    if (raw.isBlank()) return emptyList()
    return raw.split(TARGET_SEP).map { it.trim() }.filter { it.isNotBlank() }
}

private fun saveSavedTargets(list: List<String>) {
    val joined = list.map { it.replace("\n", " ").trim() }.filter { it.isNotBlank() }.joinToString(TARGET_SEP)
    AppGraph.prefs.edit().putString(PREF_SAVED_TARGETS, joined).apply()
}

@Composable
private fun formatLabel(f: TargetFormat): String = when (f) {
    TargetFormat.ECI -> "ECI · 4G 小区"
    TargetFormat.NCI -> "NCI · 5G 小区"
    TargetFormat.LAC_CI -> "LAC + CI"
    TargetFormat.LATLON_RADIUS -> "经纬度 + 半径"
}

/** 一键填当前基站的结果：可直接粘进输入框的文本 + 给用户看的一句话 */
private data class CurrentCellFill(val raw: String, val hint: String)

/** 读小区信息缺哪些运行时权限（电话权限读订阅/小区，定位权限是 getAllCellInfo 的前置） */
private fun missingCellPermissions(ctx: Context): List<String> = buildList {
    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
        add(Manifest.permission.READ_PHONE_STATE)
    }
    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}

/**
 * 取「本机数据卡当前驻留小区」并拼成解析器认识的写法：
 * 5G → `NCI <原始 NCI>`，4G → `ECI <原始 ECI>`；只认 NR / LTE（2G/3G 不做一键目标）。
 *
 * 为什么新建一个 reader：任务还没创建，全局控制器尚未绑定；这里临时读一次就停，
 * 避免在任务页后台常驻一个 telephony 监听。
 */
private suspend fun readServingCellInput(ctx: Context): CurrentCellFill? = withContext(Dispatchers.IO) {
    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
        return@withContext null
    }
    val reader = AndroidCellReader(ctx)
    try {
        reader.start()
        var snap: CellSnapshot? = null
        // 首次轮询/回调可能还没到，最多等 2 秒
        for (i in 0 until 20) {
            reader.pollNow()
            snap = reader.snapshot.value
            val ready = snap?.sims?.any { s ->
                s.cellId > 0L && (s.rat.equals("NR", true) || s.rat.equals("LTE", true))
            } == true
            if (ready) break
            delay(100)
        }
        val s = snap ?: return@withContext null
        // 优先跟「数据卡当前注册小区」走（双卡时用户说的就是这张卡）
        val cell = CellTargetShortcut.pick(s.sims, s.serving?.ci) ?: return@withContext null
        val raw = CellTargetShortcut.rawOf(cell) ?: return@withContext null
        CurrentCellFill(raw = raw, hint = CellTargetShortcut.hintOf(cell))
    } finally {
        runCatching { reader.stop() }
    }
}
