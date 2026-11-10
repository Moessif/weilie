package com.gakj.weilie.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gakj.weilie.core.cellmap.CellLibraryClient
import com.gakj.weilie.core.cellmap.CellSite as KnownCell
import com.gakj.weilie.ui.AppGraph
import com.gakj.weilie.ui.theme.MonoText
import com.gakj.weilie.ui.theme.WColors
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

/**
 * 基站位置查询弹窗：
 * - 「附近基站」：按经纬度 + 半径查询（半径 300 m / 500 m / 900 m / 1.5 km / 2.5 km）；
 * - 「按编号查询」：MCC/MNC/LAC(TAC)/CellID 精确查（对应 ECI/NCI 的十进制编号）。
 *
 * 数据来自服务端代理的 OpenCelliD 众包库（CC BY-SA 4.0），坐标约为百米量级，
 * 只作"参考位置"，不能当实测值使用——所以要在这里明写署名与精度提示。
 */
@Composable
fun CellSearchDialog(
    onDismiss: () -> Unit,
    onPickSite: (KnownCell) -> Unit,
    initLat: Double? = null,
    initLon: Double? = null,
    initRadiusM: Int = CellLibraryClient.DEFAULT_RADIUS_M,
    title: String = "查询基站位置",
) {
    val scope = rememberCoroutineScope()
    val client = remember { CellLibraryClient(AppGraph.serverHttpUrl()) }
    DisposableEffect(client) {
        onDispose { runCatching { client.close() } }
    }

    var tab by remember { mutableStateOf(0) }
    var latText by remember { mutableStateOf(initLat?.let { String.format(Locale.US, "%.6f", it) } ?: "") }
    var lonText by remember { mutableStateOf(initLon?.let { String.format(Locale.US, "%.6f", it) } ?: "") }
    var radiusIdx by remember {
        mutableStateOf(
            CellLibraryClient.RADIUS_STEPS.indexOfFirst { it == initRadiusM }
                .let { if (it >= 0) it else CellLibraryClient.RADIUS_STEPS.indexOf(CellLibraryClient.DEFAULT_RADIUS_M) },
        )
    }
    var mccText by remember { mutableStateOf("460") }
    var mncText by remember { mutableStateOf("0") }
    var lacText by remember { mutableStateOf("") }
    var cellText by remember { mutableStateOf("") }

    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var sites by remember { mutableStateOf<List<KnownCell>>(emptyList()) }
    var single by remember { mutableStateOf<KnownCell?>(null) }

    fun runNear() {
        if (loading) return
        val lat = latText.trim().toDoubleOrNull()
        val lon = lonText.trim().toDoubleOrNull()
        if (lat == null || lon == null || abs(lat) > 85.0 || abs(lon) > 180.0) {
            message = "请填写有效的经纬度（纬度 -85~85，经度 -180~180）"
            return
        }
        loading = true
        message = null
        single = null
        scope.launch {
            val resp = client.near(lat, lon, CellLibraryClient.RADIUS_STEPS[radiusIdx], 100)
            loading = false
            sites = resp.sites
            message = when {
                !resp.ok -> resp.message.orEmpty().ifBlank { "查询失败，请检查网络或服务器地址" }
                resp.sites.isEmpty() -> "该范围内没有已知基站。众包库覆盖有限（城市更密、郊区稀疏），可换更大半径或改用编号查询。"
                else -> null
            }
        }
    }

    fun runSingle() {
        if (loading) return
        val mcc = mccText.trim().toIntOrNull()
        val mnc = mncText.trim().toIntOrNull()
        val lac = lacText.trim().toIntOrNull()
        val cid = cellText.trim().toLongOrNull()
        if (mcc == null || mnc == null || lac == null || cid == null) {
            message = "请填写 MCC / MNC / LAC(TAC) / CellID 四个数字"
            return
        }
        loading = true
        message = null
        sites = emptyList()
        scope.launch {
            val resp = client.lookup(mcc, mnc, lac, cid)
            loading = false
            single = resp.site
            message = when {
                !resp.ok -> resp.message.orEmpty().ifBlank { "未查询到该小区" }
                resp.site == null -> "库里没有这个小区。NCI/ECI 需要拆成 LAC(TAC) 与 CellID 再查，5G NR 记录尤其稀少。"
                else -> null
            }
        }
    }

    GlassPanelDialog(title, onDismiss) {
        // ---- 两个 tab ----
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TabPill("附近基站", tab == 0) { tab = 0 }
            TabPill("按编号查询", tab == 1) { tab = 1 }
        }
        Spacer(Modifier.height(12.dp))

        if (tab == 0) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField(latText, { latText = it }, "纬度", Modifier.weight(1f))
                NumField(lonText, { lonText = it }, "经度", Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Text("搜索半径", style = MaterialTheme.typography.labelSmall, color = WColors.OnBgDim)
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CellLibraryClient.RADIUS_STEPS.forEachIndexed { i, r ->
                    val label = if (r < 1000) "$r m" else "%.1f km".format(Locale.US, r / 1000.0)
                    TabPill(label, radiusIdx == i) { radiusIdx = i }
                }
            }
            Spacer(Modifier.height(10.dp))
            PanelActionRow(
                text = if (loading) "查询中…" else "查询附近基站",
                enabled = !loading,
                onClick = { runNear() },
            )
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField(mccText, { mccText = it }, "MCC", Modifier.weight(1f))
                NumField(mncText, { mncText = it }, "MNC", Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField(lacText, { lacText = it }, "LAC / TAC", Modifier.weight(1f))
                NumField(cellText, { cellText = it }, "CellID", Modifier.weight(1f))
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "ECI/NCI 是十进制编号，需要按库的字段拆开：LAC(TAC) 与 CellID 分别填。",
                style = MaterialTheme.typography.labelSmall,
                color = WColors.OnBgDim,
            )
            Spacer(Modifier.height(10.dp))
            PanelActionRow(
                text = if (loading) "查询中…" else "按编号查询",
                enabled = !loading,
                onClick = { runSingle() },
            )
        }

        message?.let { msg ->
            Spacer(Modifier.height(8.dp))
            Text(msg, style = MaterialTheme.typography.labelSmall, color = WColors.Warning)
        }

        // ---- 结果 ----
        val results = listOfNotNull(single) + sites
        if (results.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                "结果 ${results.size} 个 · 点击选为任务中心或查看详情",
                style = MaterialTheme.typography.labelSmall,
                color = WColors.OnBgDim,
            )
            Spacer(Modifier.height(4.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val baseLat = latText.trim().toDoubleOrNull()
                val baseLon = lonText.trim().toDoubleOrNull()
                results.forEach { site ->
                    val dist = if (baseLat != null && baseLon != null) {
                        CellLibraryClient.formatDistance(site.distanceM(baseLat, baseLon))
                    } else {
                        null
                    }
                    SiteRow(site, dist) { onPickSite(site) }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            KnownCell.SOURCE_ATTRIBUTION,
            style = MaterialTheme.typography.labelSmall,
            color = WColors.OnBgDim.copy(alpha = 0.8f),
        )
        Text(
            "众包坐标：室内/农村可能偏移上百米，仅作参考；请以实测为准。",
            style = MaterialTheme.typography.labelSmall,
            color = WColors.OnBgDim.copy(alpha = 0.8f),
        )
    }
}

@Composable
private fun TabPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = if (selected) WColors.OnPrimary else WColors.OnBgDim,
        modifier = Modifier
            .background(
                if (selected) WColors.Primary else WColors.ChipBg,
                RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
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
private fun SiteRow(site: KnownCell, distText: String?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(WColors.ChipBg, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 制式色点：和地图上的站点颜色一致
        ColorDot(cellSiteColor(site.rat))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                site.title,
                style = MaterialTheme.typography.bodyMedium,
                color = WColors.OnBg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                site.cellText,
                style = MonoText.style.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                color = WColors.OnBgDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(cellSiteShort(site), style = MonoText.style, color = cellSiteColor(site.rat))
            distText?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = WColors.OnBgDim)
            }
        }
    }
}

/** 制式色小圆点 */
@Composable
private fun ColorDot(color: androidx.compose.ui.graphics.Color) {
    Spacer(
        Modifier
            .size(9.dp)
            .background(color, CircleShape),
    )
}
