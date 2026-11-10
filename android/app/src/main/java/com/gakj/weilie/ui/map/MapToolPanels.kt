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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gakj.weilie.ui.common.KeyValueRow
import com.gakj.weilie.ui.theme.MonoText
import com.gakj.weilie.ui.theme.SectionHeader
import com.gakj.weilie.ui.theme.WColors

// ==================================================================
// A4 复杂工具浮窗内容（STRICT_SPEC 2.3）：导出 / 导航 / 帮助 / 拍照 / 设置
// ==================================================================

/** 数据导出浮窗：CSV / GeoJSON / KML / 轨迹图 PNG / 带底图的现场图 / 理论覆盖区 / 封闭区域 */
@Composable
fun ExportPanel(
    onCsv: () -> Unit,
    onGeoJson: () -> Unit,
    onKml: () -> Unit,
    onTrackPng: () -> Unit,
    onMapPng: () -> Unit,
    onTheory: () -> Unit,
    onArea: () -> Unit,
    theoryOn: Boolean = false,
) {
    SectionHeader("数据导出")
    Spacer(Modifier.height(10.dp))
    PanelActionRow("导出 CSV（采样明细）", icon = Icons.Rounded.IosShare, onClick = onCsv)
    Spacer(Modifier.height(8.dp))
    PanelActionRow("导出 GeoJSON（含拟合边界）", icon = Icons.Rounded.IosShare, onClick = onGeoJson)
    Spacer(Modifier.height(8.dp))
    PanelActionRow("导出 KML（可在地图软件打开）", icon = Icons.Rounded.IosShare, onClick = onKml)
    Spacer(Modifier.height(8.dp))
    PanelActionRow(
        "导出轨迹图 PNG（含真实地图底图）",
        icon = Icons.Rounded.IosShare,
        onClick = onMapPng,
    )
    Spacer(Modifier.height(8.dp))
    PanelActionRow("导出示意轨迹图 PNG（无底图，离线可用）", icon = Icons.Rounded.IosShare, onClick = onTrackPng)
    Spacer(Modifier.height(14.dp))
    SectionHeader("覆盖分析")
    Spacer(Modifier.height(10.dp))
    PanelActionRow(
        if (theoryOn) "基站理论覆盖区（已绘制，点击修改）" else "基站理论覆盖区（画圆/扇形/TA 带）",
        icon = Icons.Rounded.Place,
        onClick = onTheory,
    )
    Spacer(Modifier.height(8.dp))
    PanelActionRow("生成实测覆盖封闭区域（实心点成面）", icon = Icons.Rounded.Layers, onClick = onArea)
    Spacer(Modifier.height(10.dp))
    Text(
        "导出后自动调起系统分享，可选择保存或发送。带底图的现场图会把当前地图上的实心/空心/彩色邻区点与理论区一起截下来。",
        style = MaterialTheme.typography.labelSmall,
        color = WColors.OnBgDim,
    )
}

/** 定位与导航浮窗：我的坐标/精度 + 导航到目标 */
@Composable
fun NavPanel(
    myLat: Double?,
    myLon: Double?,
    myAcc: Float?,
    targetLat: Double?,
    targetLon: Double?,
    targetLabel: String,
    straightKm: Double?,
    onNavigate: () -> Unit,
) {
    SectionHeader("定位与导航")
    Spacer(Modifier.height(10.dp))
    if (myLat != null && myLon != null) {
        KeyValueRow("我的纬度", String.format(java.util.Locale.US, "%.6f", myLat))
        KeyValueRow("我的经度", String.format(java.util.Locale.US, "%.6f", myLon))
        KeyValueRow("定位精度", myAcc?.let { String.format("%.0f 米", it) } ?: "未知")
    } else {
        Text("暂无定位，请检查定位权限。", style = MaterialTheme.typography.bodyMedium, color = WColors.Warning)
    }
    Spacer(Modifier.height(8.dp))
    if (targetLat != null && targetLon != null) {
        KeyValueRow("目标位置", targetLabel)
        straightKm?.let { KeyValueRow("直线距离", String.format("%.2f 公里", it)) }
        Spacer(Modifier.height(12.dp))
        PanelActionRow("导航到目标（调起外部地图）", onClick = onNavigate)
    } else {
        Text("当前任务无目标坐标，无法导航。", style = MaterialTheme.typography.bodyMedium, color = WColors.OnBgDim)
    }
}

/** 帮助浮窗：关于 / 说明书摘要 / 法律声明 */
@Composable
fun HelpPanel(legalText: String) {
    SectionHeader("帮助")
    Spacer(Modifier.height(10.dp))
    Text(
        "围猎 · 蜂窝信号走测工具 v${com.gakj.weilie.BuildConfig.VERSION_NAME}",
        style = MaterialTheme.typography.titleSmall,
        color = WColors.OnBg,
    )
    Spacer(Modifier.height(8.dp))
    HelpBlock(
        "说明书摘要",
        "1. 在大厅选择单人模式，输入目标基站或坐标创建任务；\n" +
            "2. 进入地图后点底部采集按钮开始走测；\n" +
            "3. 沿目标区域多方向行走，注意观察信号条与目标驻留提示；\n" +
            "4. 采集完成后停止，可在历史记录中回放与导出；\n" +
            "5. 多人模式由队长创建房间，队员凭队伍码加入协同走测。",
    )
    Spacer(Modifier.height(8.dp))
    HelpBlock("法律声明", legalText)
}

@Composable
private fun HelpBlock(title: String, body: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(WColors.ChipBg, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = WColors.OnBg)
        Spacer(Modifier.height(4.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = WColors.OnBgDim)
    }
}

/**
 * 拍照浮窗：水印相机入口 + 云上传说明 + 地图数字标点列表 + 可疑设备标记。
 * photoPoints = 已添加的数字标点描述列表（"1 · 12:03:22 · 39.9042,116.4074"）。
 */
@Composable
fun PhotoPanel(
    photoPoints: List<String>,
    onTakePhoto: () -> Unit,
    onMarkSuspicious: () -> Unit,
) {
    SectionHeader("拍照取证")
    Spacer(Modifier.height(10.dp))
    PanelActionRow("拍摄照片（自动加时间水印并在地图标点）", icon = Icons.Rounded.PhotoCamera, onClick = onTakePhoto)
    Spacer(Modifier.height(8.dp))
    PanelActionRow("标记可疑设备（地图加 📹 标点）", icon = Icons.Rounded.Videocam, onClick = onMarkSuspicious)
    Spacer(Modifier.height(10.dp))
    Text(
        "云端上传：登录后照片自动同步到云端（预留）。",
        style = MaterialTheme.typography.labelSmall,
        color = WColors.OnBgDim,
    )
    if (photoPoints.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        SectionHeader("地图数字标点")
        Spacer(Modifier.height(6.dp))
        photoPoints.forEach { p ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val idx = p.substringBefore(" ·")
                Box(
                    Modifier
                        .size(20.dp)
                        .background(WColors.Primary.copy(alpha = 0.25f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(idx, style = MaterialTheme.typography.labelSmall, color = WColors.Primary)
                }
                Spacer(Modifier.width(8.dp))
                Text(p.substringAfter(" · "), style = MonoText.style, color = WColors.OnBgDim)
            }
        }
    }
}

/** 设置浮窗：协同服务器 / 百度AK / AI key 快捷配置 + 完整设置入口 */
@Composable
fun QuickSettingsPanel(
    server: String,
    baiduAk: String,
    aiKey: String,
    onServer: (String) -> Unit,
    onBaiduAk: (String) -> Unit,
    onAiKey: (String) -> Unit,
    onSave: () -> Unit,
    onOpenFull: () -> Unit,
) {
    SectionHeader("设置")
    Spacer(Modifier.height(10.dp))
    OutlinedTextField(
        value = server,
        onValueChange = onServer,
        label = { Text("协同服务器地址") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = WColors.Primary,
            unfocusedBorderColor = WColors.GlassBorder,
            focusedTextColor = WColors.OnBg,
            unfocusedTextColor = WColors.OnBg,
        ),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = baiduAk,
        onValueChange = onBaiduAk,
        label = { Text("百度地图 AK") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = WColors.Primary,
            unfocusedBorderColor = WColors.GlassBorder,
            focusedTextColor = WColors.OnBg,
            unfocusedTextColor = WColors.OnBg,
        ),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = aiKey,
        onValueChange = onAiKey,
        label = { Text("AI 助手 Key") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = WColors.Primary,
            unfocusedBorderColor = WColors.GlassBorder,
            focusedTextColor = WColors.OnBg,
            unfocusedTextColor = WColors.OnBg,
        ),
    )
    Spacer(Modifier.height(12.dp))
    PanelActionRow("保存以上配置", onClick = onSave)
    Spacer(Modifier.height(8.dp))
    PanelActionRow("打开完整设置页", onClick = onOpenFull)
    Spacer(Modifier.height(8.dp))
    Text(
        "离线地图：暂未启用，后续版本支持按城市缓存。",
        style = MaterialTheme.typography.labelSmall,
        color = WColors.OnBgDim,
    )
}
