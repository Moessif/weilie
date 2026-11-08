package com.gakj.weilie.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.gakj.weilie.core.model.SampleRecord
import com.gakj.weilie.core.model.TargetCell
import com.gakj.weilie.core.sync.MemberView
import com.gakj.weilie.core.sync.WsMsg

/**
 * 地图门面：UI 只调用这个组合函数，不关心底层是百度地图还是 Canvas 自绘。
 *
 * - 百度 SDK 初始化成功 → [BaiduMapCanvas]（真地图瓦片 + 真实地理坐标覆盖物 + 手势）
 * - 初始化失败（AK 未绑定/离线/SO 缺失）→ [CanvasMap]（等距圆柱投影网格底图，功能等价）
 *
 * 两条路径接收完全相同的数据与相机，因此采集、拟合、协同、回放等功能代码不需要任何分支。
 */
@Composable
fun MapCanvas(
    modifier: Modifier = Modifier,
    target: TargetCell? = null,
    samples: List<SampleRecord> = emptyList(),
    hullLatLon: List<Pair<Double, Double>>? = null,
    bufferedLatLon: List<Pair<Double, Double>>? = null,
    isolines: List<List<Pair<Double, Double>>> = emptyList(),
    members: List<MemberView> = emptyList(),
    markers: List<WsMsg.Marker> = emptyList(),
    zone: WsMsg.Zone? = null,
    zones: List<WsMsg.Zone> = emptyList(),
    camera: MapCamera = remember { MapCamera() },
    onTapLatLon: ((Double, Double) -> Unit)? = null,
    selfLatLon: Pair<Double, Double>? = null,
    trackArrows: Boolean = false,
    cellSites: List<CellSite> = emptyList(),
    onMarkerClick: ((WsMsg.Marker) -> Unit)? = null,
) {
    if (BaiduBootstrap.ready) {
        BaiduMapCanvas(
            modifier = modifier,
            target = target,
            samples = samples,
            hullLatLon = hullLatLon,
            bufferedLatLon = bufferedLatLon,
            isolines = isolines,
            members = members,
            markers = markers,
            zone = zone,
            zones = zones,
            camera = camera,
            onTapLatLon = onTapLatLon,
            selfLatLon = selfLatLon,
            trackArrows = trackArrows,
            cellSites = cellSites,
            onMarkerClick = onMarkerClick,
        )
    } else {
        CanvasMap(
            modifier = modifier,
            target = target,
            samples = samples,
            hullLatLon = hullLatLon,
            bufferedLatLon = bufferedLatLon,
            isolines = isolines,
            members = members,
            markers = markers,
            zone = zone,
            zones = zones,
            camera = camera,
            onTapLatLon = onTapLatLon,
            selfLatLon = selfLatLon,
            trackArrows = trackArrows,
            cellSites = cellSites,
            onMarkerClick = onMarkerClick,
        )
    }
}
