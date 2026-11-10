package com.gakj.weilie.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.gakj.weilie.core.fitting.TheoreticalCoverage
import com.gakj.weilie.core.fitting.TheorySpec
import com.gakj.weilie.ui.theme.MonoText
import com.gakj.weilie.ui.theme.SectionHeader
import com.gakj.weilie.ui.theme.WColors
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 基站理论覆盖区面板（需求 7）：
 * - 输入经纬度 + 可选方位角 + 可选距离；
 * - 方位角留空 → 全向圆；填 0..360 → 扇形（默认张角 120°）；
 * - 距离留空 → 蓝色理论区；填了距离 → 橙色 TA 距离带（±15% 半径容差）。
 */
@Composable
fun TheoryPanel(
    initLat: Double?,
    initLon: Double?,
    current: TheorySpec?,
    onApply: (TheorySpec) -> Unit,
    onClear: () -> Unit,
) {
    var latText by remember {
        mutableStateOf(current?.lat?.let { String.format(Locale.US, "%.6f", it) } ?: initLat?.let { String.format(Locale.US, "%.6f", it) } ?: "")
    }
    var lonText by remember {
        mutableStateOf(current?.lon?.let { String.format(Locale.US, "%.6f", it) } ?: initLon?.let { String.format(Locale.US, "%.6f", it) } ?: "")
    }
    var azText by remember { mutableStateOf(current?.azimuthDeg?.let { String.format(Locale.US, "%.0f", it) } ?: "") }
    var swapText by remember { mutableStateOf(current?.sectorWidthDeg?.roundToInt()?.toString() ?: "120") }
    var radiusText by remember { mutableStateOf(current?.radiusM?.roundToInt()?.toString() ?: "") }
    var err by remember { mutableStateOf<String?>(null) }

    SectionHeader("基站理论覆盖区")
    Spacer(Modifier.height(8.dp))

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumField(latText, { latText = it }, "纬度", Modifier.weight(1f))
        NumField(lonText, { lonText = it }, "经度", Modifier.weight(1f))
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumField(azText, { azText = it }, "方位角 0-360（可空）", Modifier.weight(1f))
        NumField(swapText, { swapText = it }, "张角°", Modifier.weight(0.6f))
    }
    Spacer(Modifier.height(8.dp))
    NumField(radiusText, { radiusText = it }, "距离 m（可空=理论区）", Modifier.fillMaxWidth())
    Spacer(Modifier.height(6.dp))
    Text(
        "方位角留空生成全向圆，填写则生成扇形；填写距离后叠加橙色 TA 距离带（半径 ±15%）。",
        style = MaterialTheme.typography.labelSmall,
        color = WColors.OnBgDim,
    )
    err?.let {
        Spacer(Modifier.height(6.dp))
        Text(it, style = MaterialTheme.typography.labelSmall, color = WColors.Warning)
    }
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PanelActionRow(
            "在地图上绘制",
            modifier = Modifier.weight(1f),
            onClick = {
                val la = latText.trim().toDoubleOrNull()
                val lo = lonText.trim().toDoubleOrNull()
                if (la == null || lo == null) {
                    err = "纬度/经度必填，且必须是数字"
                    return@PanelActionRow
                }
                if (la !in -90.0..90.0 || lo !in -180.0..180.0) {
                    err = "纬度应在 -90..90，经度应在 -180..180"
                    return@PanelActionRow
                }
                val az = azText.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
                if (azText.trim().isNotEmpty() && az == null) {
                    err = "方位角必须是数字或留空"
                    return@PanelActionRow
                }
                val r = radiusText.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
                if (radiusText.trim().isNotEmpty() && (r == null || r <= 0)) {
                    err = "距离必须是正数或留空"
                    return@PanelActionRow
                }
                val sweep = swapText.trim().toDoubleOrNull()?.coerceIn(10.0, 360.0) ?: 120.0
                err = null
                onApply(
                    TheorySpec(
                        lat = la, lon = lo,
                        azimuthDeg = az?.let { ((it % 360) + 360) % 360 },
                        sectorWidthDeg = sweep,
                        radiusM = r,
                        innerRadiusM = if (r != null) r * 0.85 else 0.0,
                        defaultRadiusM = 1000.0,
                    ),
                )
            },
        )
        PanelActionRow("清除", modifier = Modifier.width(96.dp), onClick = onClear)
    }
    Spacer(Modifier.height(12.dp))
    SectionHeader("图例")
    Spacer(Modifier.height(6.dp))
    TheoryLegend()
}

@Composable
private fun NumField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        singleLine = true,
        textStyle = MonoText.style,
        modifier = modifier,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = WColors.Primary,
            unfocusedBorderColor = WColors.GlassBorder,
            focusedTextColor = WColors.OnBg,
            unfocusedTextColor = WColors.OnBg,
        ),
    )
}

@Composable
private fun TheoryLegend() {
    Column(
        Modifier
            .fillMaxWidth()
            .background(WColors.ChipBg, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        LegendRow(TheoryShape.BLUE, "理论覆盖区（全向圆 / 扇形）")
        LegendRow(TheoryShape.ORANGE, "TA 距离带（给了距离时的圆环带）")
        LegendRow(MarkStyles.MAIN_FILL, "实测轨迹 · 主区命中")
        LegendRow(TheoryShape.PURPLE, "理论区 ∩ 实测（重叠部分）")
    }
}

@Composable
private fun LegendRow(color: Int, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(10.dp)
                .background(Color(color), CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = WColors.OnBgDim)
    }
}

/**
 * 理论参数 → 地图覆盖物：
 * - 无距离：蓝色 CIRCLE / SECTOR；
 * - 有距离：橙色 RING（全向）/ SECTOR（带方向时用扇环多边形，由地图层按内半径裁剪）。
 */
fun buildTheoryShapes(spec: TheorySpec?): List<TheoryShape> {
    if (spec == null) return emptyList()
    val r = spec.radiusM ?: spec.defaultRadiusM
    val hasDist = spec.radiusM != null
    val color = if (hasDist) TheoryShape.ORANGE else TheoryShape.BLUE
    return if (spec.azimuthDeg == null) {
        if (hasDist) {
            listOf(
                TheoryShape(
                    kind = TheoryShape.Kind.RING,
                    centerLat = spec.lat, centerLon = spec.lon,
                    radiusM = r, innerRadiusM = spec.innerRadiusM.courseOrZero(),
                    color = TheoryShape.ORANGE,
                ),
            )
        } else {
            listOf(
                TheoryShape(
                    kind = TheoryShape.Kind.CIRCLE,
                    centerLat = spec.lat, centerLon = spec.lon,
                    radiusM = r, color = TheoryShape.BLUE,
                ),
            )
        }
    } else {
        listOf(
            TheoryShape(
                kind = TheoryShape.Kind.SECTOR,
                centerLat = spec.lat, centerLon = spec.lon,
                radiusM = r,
                innerRadiusM = spec.innerRadiusM.courseOrZero(),
                startBearing = (spec.azimuthDeg - spec.sectorWidthDeg / 2.0).toFloat(),
                sweepDeg = spec.sectorWidthDeg.toFloat(),
                color = color,
            ),
        )
    }
}

private fun Double.courseOrZero(): Double = if (isFinite() && this > 0.0) this else 0.0

/** 理论区外环（用于导出时的叠加判断与面积统计） */
fun theoryOutline(spec: TheorySpec?): List<Pair<Double, Double>> =
    spec?.let { TheoreticalCoverage.outline(it) } ?: emptyList()
