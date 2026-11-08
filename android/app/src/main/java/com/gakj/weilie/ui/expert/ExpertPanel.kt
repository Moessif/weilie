package com.gakj.weilie.ui.expert

import android.location.Location
import android.location.LocationManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gakj.weilie.core.model.CellIdentity
import com.gakj.weilie.core.model.Rat
import com.gakj.weilie.core.model.SampleRecord
import com.gakj.weilie.core.model.SignalData
import com.gakj.weilie.core.model.WifiAp
import com.gakj.weilie.core.model.ciLabel
import com.gakj.weilie.core.sampling.CellChangeEvent
import com.gakj.weilie.core.telephony.CellCodec
import com.gakj.weilie.core.telephony.CellSnapshot
import com.gakj.weilie.core.telephony.CellTrace
import com.gakj.weilie.core.telephony.NeighborCell
import com.gakj.weilie.core.telephony.SignalLevel
import com.gakj.weilie.ui.common.KeyValueRow
import com.gakj.weilie.ui.common.StatusBadge
import com.gakj.weilie.ui.common.fmtTs
import com.gakj.weilie.ui.theme.GlassCard
import com.gakj.weilie.ui.theme.MonoText
import com.gakj.weilie.ui.theme.SectionHeader
import com.gakj.weilie.ui.theme.WColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 专家数据内容（可复用）：手机能拿到的全部射频/邻区/WiFi 字段一览 + 小区切换白话时间线。
 * 供 ExpertPanel（底部抽屉）与地图页数据栏专家模式 / A4-实时信息弹窗复用。
 *
 * 分组（Basement 规格）：时间与位置 / 实时快照 / 信号 / 邻区 / WiFi / 小区变化轨迹 / 最新采样点。
 * [location] 由地图页传入（实时定位，含海拔/精度）；不传时用最近一个采样点兜底。
 */
@Composable
fun ExpertPanelContent(
    snapshot: CellSnapshot?,
    lastSample: SampleRecord?,
    events: List<CellChangeEvent>,
    location: Location? = null,
) {
    Column {
        // ---------- 1) 时间与位置 ----------
        SectionHeader("时间与位置")
        GlassCard(Modifier.fillMaxWidth()) {
            Column {
                KeyValueRow("时间", fmtTsMillis(snapshot?.tsEpochMs ?: lastSample?.tsEpochMs))
                KeyValueRow("经纬度", fmtLatLon(location, lastSample))
                KeyValueRow("坐标来源", fmtProvider(location?.provider ?: lastSample?.provider))
                KeyValueRow("定位精度", fmtAccuracy(location?.accuracy, lastSample?.accuracyM))
                KeyValueRow("海拔", fmtAltitude(location))
            }
        }
        Spacer(Modifier.height(16.dp))

        // ---------- 2) 实时快照（小区身份 + 编号分解） ----------
        SectionHeader("实时快照")
        GlassCard(Modifier.fillMaxWidth()) {
            Column {
                if (snapshot == null || snapshot.serving == null) {
                    Text(
                        "暂无数据：请检查定位权限与 SIM 状态",
                        style = MaterialTheme.typography.bodyMedium,
                        color = WColors.OnBgDim,
                    )
                } else {
                    val s = snapshot.serving
                    val ci = s.ci
                    // NSA 组网下 registered 小区是 LTE 锚点、NR 在邻区里：制式按锚点/NR 邻区判断，不能直接报 serving.rat
                    KeyValueRow(
                        "制式 RAT",
                        CellCodec.displayRat(s.rat, snapshot.anchorRat, snapshot.nrNeighborCount),
                    )
                    KeyValueRow("MCC / MNC", "${s.mcc ?: "—"} / ${s.mnc ?: "—"}")
                    KeyValueRow("LAC/TAC", s.lacOrTac?.toString() ?: "—")
                    // 用户习惯写法：NCI 5677244420(gNB 1386046-4) / ECI 123456789(eNB 482253-21)
                    KeyValueRow("基站编号", ci?.let { CellCodec.formatCellId(s.rat, it) } ?: "—")
                    // 专家补充：十六进制 + 高位 ID/CellID 十进制
                    // （NCI 36bit = gNB + 12bit CellID；ECI 28bit = eNB + 8bit CellID）
                    KeyValueRow("${s.rat.ciLabel()} (HEX)", ci?.let { CellCodec.hexOf(it) } ?: "—")
                    if (ci != null) {
                        when (s.rat) {
                            Rat.NR -> {
                                val (gnb, cid) = CellCodec.decodeNci(ci)
                                KeyValueRow("gNB", gnb.toString())
                                KeyValueRow("CID", cid.toString())
                            }
                            Rat.LTE -> {
                                val (enb, cid) = CellCodec.decodeEci(ci)
                                KeyValueRow("eNB", enb.toString())
                                KeyValueRow("CID", cid.toString())
                            }
                            else -> Unit
                        }
                    }
                    KeyValueRow("PCI", s.pci?.toString() ?: "—")
                    KeyValueRow("频点", s.arfcn?.toString() ?: "—")
                    KeyValueRow("频段 Band", s.band?.toString() ?: "—")
                    Spacer(Modifier.height(6.dp))
                    HorizontalDivider(color = WColors.GlassBorder)
                    Spacer(Modifier.height(6.dp))
                    KeyValueRow("邻区数量", snapshot.neighbors.size.toString())
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        // ---------- 3) 信号 ----------
        SectionHeader("信号")
        GlassCard(Modifier.fillMaxWidth()) {
            Column {
                val sig = snapshot?.signal
                // 有哪个显示哪个；缺失的带单位显示「— dBm / — dB」，不留空
                KeyValueRow("RSRP", fmtDbm(sig?.rsrp))
                KeyValueRow("RSRQ", fmtDb(sig?.rsrq))
                KeyValueRow("SINR", fmtDb(sig?.sinr))
                KeyValueRow("RSSI", fmtDbm(sig?.rssi))
                KeyValueRow("信号等级", SignalLevel.grade(sig?.rsrp))
            }
        }
        Spacer(Modifier.height(16.dp))

        // ---------- 4) 邻区（最多 8 条） ----------
        val neighborCells = neighborCellsOf(snapshot)
        SectionHeader(
            "邻区",
            trailing = {
                Text(
                    "${neighborCells.size} 个",
                    style = MaterialTheme.typography.labelSmall,
                    color = WColors.OnBgDim,
                )
            },
        )
        GlassCard(Modifier.fillMaxWidth()) {
            Column {
                if (neighborCells.isEmpty()) {
                    Text("未扫描到邻区", style = MaterialTheme.typography.bodyMedium, color = WColors.OnBgDim)
                } else {
                    neighborCells.take(8).forEachIndexed { i, n ->
                        Text(
                            neighborLine(i, n.identity, n.signal),
                            style = MonoText.style,
                            color = WColors.OnBg,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        // ---------- 5) WiFi（最多 5 条） ----------
        val wifi = snapshot?.wifi.orEmpty()
        SectionHeader(
            "WiFi",
            trailing = {
                Text(
                    "${wifi.size} 个",
                    style = MaterialTheme.typography.labelSmall,
                    color = WColors.OnBgDim,
                )
            },
        )
        GlassCard(Modifier.fillMaxWidth()) {
            Column {
                if (wifi.isEmpty()) {
                    Text("未扫描到", style = MaterialTheme.typography.bodyMedium, color = WColors.OnBgDim)
                } else {
                    wifi.take(5).forEach { w ->
                        Text(
                            wifiLine(w),
                            style = MonoText.style,
                            color = WColors.OnBg,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        // ---------- 6) 小区变化轨迹（最近 5 条） ----------
        SectionHeader(
            "小区变化轨迹",
            trailing = {
                Text(
                    "${events.size} 条",
                    style = MaterialTheme.typography.labelSmall,
                    color = WColors.OnBgDim,
                )
            },
        )
        GlassCard(Modifier.fillMaxWidth()) {
            Column {
                if (events.isEmpty()) {
                    Text("暂无切换", style = MaterialTheme.typography.bodyMedium, color = WColors.OnBgDim)
                } else {
                    events.asReversed().take(5).forEach { e ->
                        Text(
                            CellTrace.line(e.tsEpochMs, e.fromText, e.toText),
                            style = MonoText.style,
                            color = WColors.OnBg,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        // ---------- 7) 最新采样点 ----------
        SectionHeader("最新采样点")
        GlassCard(Modifier.fillMaxWidth()) {
            Column {
                if (lastSample == null) {
                    Text("尚无采样点", style = MaterialTheme.typography.bodyMedium, color = WColors.OnBgDim)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (lastSample.onTarget) StatusBadge("目标驻留", WColors.Success)
                        if (lastSample.targetSeen && !lastSample.onTarget) StatusBadge("邻区可见", WColors.Primary)
                        StatusBadge("定位 ${lastSample.provider ?: "—"}", WColors.OnBgDim)
                    }
                    Spacer(Modifier.height(8.dp))
                    KeyValueRow("时间", fmtTs(lastSample.tsEpochMs))
                    KeyValueRow("经纬度", "%.6f, %.6f".format(Locale.US, lastSample.lat, lastSample.lon))
                    KeyValueRow("精度", lastSample.accuracyM?.let { "±%.1f m".format(Locale.US, it) } ?: "—")
                    KeyValueRow("目标 RSRP", lastSample.targetRsrp?.let { "$it dBm" } ?: "—")
                }
            }
        }
    }
}

// ---------- 展示格式化（纯函数，便于以后抽到 core 层做单测） ----------

private val tsMilliFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

/** `yyyy-MM-dd HH:mm:ss.SSS +08:00`（毫秒必须，带时区偏移）。 */
private fun fmtTsMillis(epochMs: Long?): String {
    if (epochMs == null) return "—"
    val zone = ZoneId.systemDefault()
    val instant = Instant.ofEpochMilli(epochMs)
    val text = tsMilliFmt.format(instant.atZone(zone))
    val offset = zone.rules.getOffset(instant).id
    return "$text $offset"
}

private fun fmtLatLon(location: Location?, sample: SampleRecord?): String = when {
    location != null -> "%.6f, %.6f".format(Locale.US, location.latitude, location.longitude)
    sample != null -> "%.6f, %.6f".format(Locale.US, sample.lat, sample.lon)
    else -> "—"
}

/** 坐标来源：GPS / 网络 / 融合 / 未获取。 */
private fun fmtProvider(provider: String?): String = when {
    provider.isNullOrBlank() -> "未获取"
    provider.equals(LocationManager.GPS_PROVIDER, ignoreCase = true) -> "GPS"
    provider.equals(LocationManager.NETWORK_PROVIDER, ignoreCase = true) -> "网络"
    provider.contains("fused", ignoreCase = true) -> "融合（GPS/网络）"
    provider.contains("gps", ignoreCase = true) -> "GPS"
    provider.contains("network", ignoreCase = true) -> "网络"
    else -> provider
}

private fun fmtAccuracy(accuracy: Float?, fallback: Float?): String {
    val acc = accuracy?.takeIf { !it.isNaN() } ?: fallback
    return acc?.let { "±%.1f m".format(Locale.US, it) } ?: "—"
}

private fun fmtAltitude(location: Location?): String =
    if (location != null && location.hasAltitude()) "%.1f m".format(Locale.US, location.altitude) else "—"

private fun fmtDbm(v: Int?): String = v?.let { "$it dBm" } ?: "— dBm"

private fun fmtDb(v: Int?): String = v?.let { "$it dB" } ?: "— dB"

/** 邻区列表：优先用带信号值的 neighborCells；老快照/兜底时回退到 neighbors（无 RSRP）。 */
private fun neighborCellsOf(snapshot: CellSnapshot?): List<NeighborCell> {
    val detailed = snapshot?.neighborCells.orEmpty()
    if (detailed.isNotEmpty()) return detailed
    return snapshot?.neighbors.orEmpty().map { NeighborCell(it, null) }
}

/** `#1  LTE  PCI 231  123456789(eNB 482253-21)  RSRP -95 dBm` */
private fun neighborLine(index: Int, id: CellIdentity, signal: SignalData?): String {
    val pci = id.pci?.toString() ?: "—"
    val number = id.ci?.let { CellCodec.formatCellId(id.rat, it) } ?: "—"
    val rsrp = signal?.rsrp?.let { "$it dBm" } ?: "—"
    return "#${index + 1}  ${id.rat.displayName}  PCI $pci  $number  RSRP $rsrp"
}

/** `CMCC-5G  aa:bb:cc:dd:ee:ff  信道 149  华为  -58 dBm` */
private fun wifiLine(w: WifiAp): String {
    val ssid = w.ssid?.takeIf { it.isNotBlank() } ?: "隐藏SSID"
    val bssid = w.bssid ?: "—"
    val channel = w.channel?.toString() ?: "—"
    val vendor = w.vendor ?: "—"
    val rssi = w.rssi?.let { "$it dBm" } ?: "—"
    return "$ssid  $bssid  信道 $channel  $vendor  $rssi"
}

/**
 * 专家数据面板（底部抽屉包装）。
 *
 * @param location 地图页实时定位（用于海拔/精度/坐标来源）；不传则用最新采样点兜底。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpertPanel(
    snapshot: CellSnapshot?,
    lastSample: SampleRecord?,
    events: List<CellChangeEvent>,
    location: Location? = null,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = WColors.Bg1) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 36.dp),
        ) {
            ExpertPanelContent(snapshot, lastSample, events, location)
        }
    }
}
