package com.gakj.weilie.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gakj.weilie.core.fitting.AreaBuilder
import com.gakj.weilie.core.fitting.TrackBufferArea
import com.gakj.weilie.core.model.SampleRecord
import com.gakj.weilie.ui.theme.MonoText
import com.gakj.weilie.ui.common.HelpHint
import com.gakj.weilie.ui.common.HelpTexts
import com.gakj.weilie.ui.theme.WColors
import com.gakj.weilie.ui.theme.glass
import com.gakj.weilie.ui.theme.mapGlass
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 地图图层模式：
 * NORMAL / SATELLITE 交给 MapEngine（百度接入后由 SDK 渲染底图）；
 * TRACK = 轨迹强化模式（Canvas 叠加方向箭头）；
 * CELLS = 基站图层（本地缓存站址点）。
 */
enum class MapLayerMode(val label: String, val desc: String) {
    NORMAL("普通地图", "标准底图"),
    SATELLITE("卫星地图", "百度卫星影像"),
    TRACK("轨迹地图", "方向箭头 · 可导出 PNG"),
    CELLS("基站地图", "已采站址 · 可导出 PNG"),
}

/**
 * 图层选择浮层：四项单选 + （多人模式）队友位置/任务范围开关。
 */
@Composable
fun MapLayersPanel(
    current: MapLayerMode,
    onSelect: (MapLayerMode) -> Unit,
    showTeamToggles: Boolean = false,
    teammateVisible: Boolean = true,
    zoneVisible: Boolean = true,
    onTeammateToggle: (Boolean) -> Unit = {},
    onZoneToggle: (Boolean) -> Unit = {},
    heatVisible: Boolean = false,
    heatParams: HeatOverlayBuilder.Params = HeatOverlayBuilder.Params(),
    onHeatToggle: (Boolean) -> Unit = {},
    onHeatParams: (HeatOverlayBuilder.Params) -> Unit = {},
    areaVisible: Boolean = false,
    onAreaToggle: (Boolean) -> Unit = {},
    areaMode: AreaBuilder.Mode = AreaBuilder.Mode.AUTO,
    onAreaModeChange: (AreaBuilder.Mode) -> Unit = {},
    areaBufferRadiusM: Double = 30.0,
    onAreaBufferRadius: (Double) -> Unit = {},
    areaBufferShape: TrackBufferArea.Shape = TrackBufferArea.Shape.CIRCLE,
    onAreaBufferShape: (TrackBufferArea.Shape) -> Unit = {},
    libraryVisible: Boolean = false,
    libraryCount: Int = 0,
    onLibraryToggle: (Boolean) -> Unit = {},
    onLibrarySearch: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    Box(
        Modifier
            .clickable(onClick = onDismiss) // 点空白关闭
            .padding(top = 52.dp),
        contentAlignment = Alignment.TopStart,
    ) {
        Column(
            Modifier
                .padding(start = 12.dp)
                .width(252.dp)
                // 叠加图层越加越多（热力参数 5 个滑杆 + 封闭区域成面方式 + 基站库），
                // 超出屏高时整块面板会被裁掉、下面的选项点不到 → 限制高度并允许滚动
                .heightIn(max = (LocalConfiguration.current.screenHeightDp.dp - 140.dp))
                .verticalScroll(rememberScrollState())
                // 先铺一层近不透明底，避免浮层压在数据栏上时底层文字透出叠字
                .background(WColors.Bg1.copy(alpha = 0.97f), RoundedCornerShape(18.dp))
                .mapGlass(RoundedCornerShape(18.dp))
                .clickable(
                    interactionSource = androidx.compose.foundation.interaction.MutableInteractionSource(),
                    indication = null,
                ) { } // 吃掉点击，避免穿透关闭
                .padding(14.dp),
        ) {
            Text("地图图层", style = MaterialTheme.typography.titleSmall, color = WColors.OnBg)
            Spacer(Modifier.height(8.dp))
            MapLayerMode.entries.forEach { m ->
                val selected = m == current
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            if (selected) WColors.Primary.copy(alpha = 0.08f) else Color.Transparent,
                            RoundedCornerShape(10.dp),
                        )
                        .clickable {
                            when (m) {
                                MapLayerMode.NORMAL -> activeMapEngine.setLayer(MapLayerType.NORMAL)
                                MapLayerMode.SATELLITE -> activeMapEngine.setLayer(MapLayerType.SATELLITE)
                                else -> Unit
                            }
                            onSelect(m)
                        }
                        .padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(16.dp)
                            .background(
                                if (selected) WColors.Primary else Color.Transparent,
                                CircleShape,
                            )
                            .background(WColors.GlassBorder, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) {
                            Box(Modifier.size(7.dp).background(WColors.OnPrimary, CircleShape))
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(m.label, style = MaterialTheme.typography.bodyMedium, color = WColors.OnBg)
                        Text(m.desc, style = MaterialTheme.typography.labelSmall, color = WColors.OnBgDim)
                    }
                }
                Spacer(Modifier.height(2.dp))
            }

            if (showTeamToggles) {
                Spacer(Modifier.height(8.dp))
                LayerToggleRow("队友位置", teammateVisible, onTeammateToggle)
                LayerToggleRow("任务范围", zoneVisible, onZoneToggle)
            }

            // ---- 叠加图层（需求 6 / 8）：独立开关，不占主图层单选 ----
            Spacer(Modifier.height(10.dp))
            Text("叠加图层", style = MaterialTheme.typography.labelSmall, color = WColors.OnBgDim)
            LayerToggleRow("信号热力图", heatVisible, onHeatToggle)
            if (heatVisible) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "热力图参数",
                        style = MaterialTheme.typography.labelSmall,
                        color = WColors.OnBgDim,
                    )
                    Spacer(Modifier.width(6.dp))
                    HelpHint("热力图参数怎么看", HelpTexts.heatParams)
                }
                HeatParamSlider(
                    label = "网格间隔",
                    valueText = "${heatParams.cellMeters.roundToInt()} m",
                    value = heatParams.cellMeters.toFloat(),
                    range = 5f..60f,
                    onChange = { onHeatParams(heatParams.copy(cellMeters = it.toDouble())) },
                )
                HeatParamSlider(
                    label = "扩散半径",
                    valueText = "${heatParams.radiusM.roundToInt()} m",
                    value = heatParams.radiusM.toFloat(),
                    range = 10f..200f,
                    onChange = { onHeatParams(heatParams.copy(radiusM = it.toDouble())) },
                )
                HeatParamSlider(
                    label = "透明度",
                    valueText = "${(heatParams.alpha * 100 / 255)}%",
                    value = heatParams.alpha.toFloat(),
                    range = 30f..255f,
                    onChange = { onHeatParams(heatParams.copy(alpha = it.roundToInt())) },
                )
                    // 需求 6：强/中/弱阈值可配（强阈值以上青绿，弱阈值以下橙红，中间黄）
                    HeatParamSlider(
                        label = "强信号阈值",
                        valueText = "${heatParams.strongDbm.roundToInt()} dBm",
                        value = heatParams.strongDbm.toFloat(),
                        range = -110f..-60f,
                        onChange = {
                            onHeatParams(heatParams.copy(strongDbm = it.toDouble().coerceAtLeast(heatParams.weakDbm + 5.0)))
                        },
                    )
                    HeatParamSlider(
                        label = "弱信号阈值",
                        valueText = "${heatParams.weakDbm.roundToInt()} dBm",
                        value = heatParams.weakDbm.toFloat(),
                        range = -125f..-80f,
                        onChange = {
                            onHeatParams(heatParams.copy(weakDbm = it.toDouble().coerceAtMost(heatParams.strongDbm - 5.0)))
                        },
                    )
            }
            LayerToggleRow("实测封闭区域", areaVisible, onAreaToggle)
            if (areaVisible) {
                // 成面方式：高精度凹包 vs 以轨迹点画圆/方的「模拟实测区域」
                Column(Modifier.fillMaxWidth().padding(start = 2.dp)) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "成面方式",
                            style = MaterialTheme.typography.labelSmall,
                            color = WColors.OnBgDim,
                            modifier = Modifier.weight(1f),
                        )
                        AreaBuilder.Mode.entries.forEach { m ->
                            val on = m == areaMode
                            Text(
                                m.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (on) WColors.OnPrimary else WColors.OnBgDim,
                                modifier = Modifier
                                    .padding(start = 4.dp)
                                    .background(
                                        if (on) WColors.Primary else WColors.ChipBg,
                                        RoundedCornerShape(8.dp),
                                    )
                                    .clickable { onAreaModeChange(m) }
                                    .padding(horizontal = 6.dp, vertical = 3.dp),
                            )
                        }
                    }
                    if (areaMode == AreaBuilder.Mode.SIMULATED) {
                        Text(
                            "走到哪儿就把那儿画成实测区域；圆/方块越大，覆盖范围越宽",
                            style = MaterialTheme.typography.labelSmall,
                            color = WColors.OnBgDim.copy(alpha = 0.7f),
                            modifier = Modifier.padding(top = 2.dp),
                        )
                        HeatParamSlider(
                            label = "圆/方块大小",
                            valueText = "±${areaBufferRadiusM.roundToInt()} m",
                            value = areaBufferRadiusM.toFloat(),
                            range = 5f..200f,
                            onChange = { onAreaBufferRadius(it.toDouble()) },
                        )
                        Row(Modifier.fillMaxWidth().padding(bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "形状",
                                style = MaterialTheme.typography.labelSmall,
                                color = WColors.OnBgDim,
                                modifier = Modifier.weight(1f),
                            )
                            TrackBufferArea.Shape.entries.forEach { s ->
                                val on = s == areaBufferShape
                                Text(
                                    s.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (on) WColors.OnPrimary else WColors.OnBgDim,
                                    modifier = Modifier
                                        .padding(start = 4.dp)
                                        .background(
                                            if (on) WColors.Primary else WColors.ChipBg,
                                            RoundedCornerShape(8.dp),
                                        )
                                        .clickable { onAreaBufferShape(s) }
                                        .padding(horizontal = 8.dp, vertical = 3.dp),
                                )
                            }
                        }
                    } else {
                        Text(
                            "贴合实际轨迹形状；点太少或共线时会退化成沿线带",
                            style = MaterialTheme.typography.labelSmall,
                            color = WColors.OnBgDim.copy(alpha = 0.7f),
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }

            // ---- 基站位置库（OpenCelliD 众包坐标）：参考图层，不是"全部基站" ----
            Spacer(Modifier.height(6.dp))
            LayerToggleRow("基站库（OpenCelliD）", libraryVisible, onLibraryToggle)
            if (libraryVisible) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 2.dp)
                        .clickable { onLibrarySearch() },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (libraryCount > 0) "范围内 $libraryCount 个 · 点此重新查询" else "点此查询基站位置",
                        style = MaterialTheme.typography.labelSmall,
                        color = WColors.Primary,
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    "众包数据，坐标约百米量级 · OpenCelliD CC BY-SA 4.0",
                    style = MaterialTheme.typography.labelSmall,
                    color = WColors.OnBgDim.copy(alpha = 0.75f),
                )
            }
        }
    }
}

@Composable
private fun LayerToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = WColors.OnBgDim, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = WColors.Primary),
            modifier = Modifier.height(26.dp),
        )
    }
}

@Composable
private fun HeatParamSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = WColors.OnBgDim, modifier = Modifier.weight(1f))
            Text(valueText, style = MonoText.style.copy(fontSize = 10.sp), color = WColors.Primary)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp),
            colors = SliderDefaults.colors(
                thumbColor = WColors.Primary,
                activeTrackColor = WColors.Primary,
                inactiveTrackColor = WColors.GlassBorder,
            ),
        )
    }
}

/**
 * 轨迹方向箭头叠加层（轨迹地图模式）：沿采样轨迹每隔约 56px 画一个小箭头。
 * 投影公式与 MapCanvas / 位置点一致。
 */
@Composable
fun TrackArrowsOverlay(
    camera: MapCamera,
    samples: List<SampleRecord>,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        if (samples.size < 2) return@Canvas
        val w = size.width
        val h = size.height
        val mpp = camera.metersPerPixel
        fun toScreen(lat: Double, lon: Double): Offset? {
            val x = (w / 2 + ((lon - camera.centerLon) * 111_320.0 * cos(Math.toRadians(camera.centerLat)) / mpp)).toFloat()
            val y = (h / 2 - ((lat - camera.centerLat) * 110_540.0 / mpp)).toFloat()
            return if (x in -60f..(w + 60f) && y in -60f..(h + 60f)) Offset(x, y) else null
        }
        val arrowColor = Color(0xFFA78BFA)
        var lastPoint: Offset? = null
        var acc = 0f
        val spacing = 56.dp.toPx()
        samples.forEach { s ->
            val p = toScreen(s.lat, s.lon) ?: run { lastPoint = null; acc = spacing; return@forEach }
            val prev = lastPoint
            if (prev != null) acc += kotlin.math.hypot((p.x - prev.x).toDouble(), (p.y - prev.y).toDouble()).toFloat()
            lastPoint = p
            if (acc >= spacing && prev != null) {
                acc = 0f
                val ang = atan2((p.y - prev.y).toDouble(), (p.x - prev.x).toDouble()).toFloat()
                val cx = (p.x + prev.x) / 2f
                val cy = (p.y + prev.y) / 2f
                val s2 = 6.dp.toPx()
                rotate(degrees = Math.toDegrees(ang.toDouble()).toFloat(), pivot = Offset(cx, cy)) {
                    val tri = Path().apply {
                        moveTo(cx + s2 * 1.7f, cy)
                        lineTo(cx - s2, cy - s2 * 0.9f)
                        lineTo(cx - s2, cy + s2 * 0.9f)
                        close()
                    }
                    drawPath(tri, arrowColor)
                }
            }
        }
    }
}

/**
 * 基站图层叠加：绘制本地缓存站址点（同一服务小区最近一次采样位置）。
 */
@Composable
fun CellSitesOverlay(
    camera: MapCamera,
    sites: List<CellSite>,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val mpp = camera.metersPerPixel
        val fill = Color(0x66F59E0B)
        val ring = Color(0xFFF59E0B)
        sites.forEach { site ->
            val x = (w / 2 + ((site.lon - camera.centerLon) * 111_320.0 * cos(Math.toRadians(camera.centerLat)) / mpp)).toFloat()
            val y = (h / 2 - ((site.lat - camera.centerLat) * 110_540.0 / mpp)).toFloat()
            if (x in -30f..(w + 30f) && y in -30f..(h + 30f)) {
                drawCircle(fill, radius = 13.dp.toPx(), center = Offset(x, y))
                drawCircle(ring, radius = 13.dp.toPx(), center = Offset(x, y), style = Stroke(width = 2.dp.toPx()))
                // 信号塔三笔
                val s = 5.dp.toPx()
                drawLine(ring, Offset(x, y + s * 1.6f), Offset(x, y - s * 1.6f), strokeWidth = 2.dp.toPx())
                drawLine(ring, Offset(x - s, y + s), Offset(x, y), strokeWidth = 2.dp.toPx())
                drawLine(ring, Offset(x + s, y + s), Offset(x, y), strokeWidth = 2.dp.toPx())
            }
        }
    }
}

/** 本地缓存站址点：服务小区 CI + 最近一次测到它的位置 */
data class CellSite(val ci: Long?, val lat: Double, val lon: Double, val label: String)

/** 从样本中提取缓存站址（同一 CI 取最近一次位置） */
fun extractCellSites(samples: List<SampleRecord>): List<CellSite> {
    val byCi = LinkedHashMap<String, SampleRecord>()
    samples.forEach { s ->
        val ci = s.servingCi ?: return@forEach
        val key = ci.toString()
        val cur = byCi[key]
        if (cur == null || s.tsEpochMs >= cur.tsEpochMs) byCi[key] = s
    }
    return byCi.map { (ci, s) ->
        CellSite(
            ci = ci.toLongOrNull(),
            lat = s.lat,
            lon = s.lon,
            label = "0x" + (ci.toLongOrNull()?.toString(16)?.uppercase()?.padStart(8, '0') ?: ci),
        )
    }
}
