package com.gakj.weilie.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.gakj.weilie.core.model.SampleRecord
import com.gakj.weilie.ui.theme.WColors
import com.gakj.weilie.ui.theme.glass
import com.gakj.weilie.ui.theme.mapGlass
import kotlin.math.atan2
import kotlin.math.cos
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
