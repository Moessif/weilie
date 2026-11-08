package com.gakj.weilie.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.baidu.mapapi.map.BaiduMap
import com.baidu.mapapi.map.BaiduMapOptions
import com.baidu.mapapi.map.BitmapDescriptor
import com.baidu.mapapi.map.BitmapDescriptorFactory
import com.baidu.mapapi.map.CircleOptions
import com.baidu.mapapi.map.MapStatus
import com.baidu.mapapi.map.MapStatusUpdateFactory
import com.baidu.mapapi.map.MapPoi
import com.baidu.mapapi.map.MapView
import com.baidu.mapapi.map.Marker
import com.baidu.mapapi.map.MarkerOptions
import com.baidu.mapapi.map.Overlay
import com.baidu.mapapi.map.PolygonOptions
import com.baidu.mapapi.map.PolylineOptions
import com.baidu.mapapi.map.Stroke
import com.baidu.mapapi.model.LatLng
import com.gakj.weilie.core.model.SampleRecord
import com.gakj.weilie.core.model.TargetCell
import com.gakj.weilie.core.sync.MemberView
import com.gakj.weilie.core.sync.WsMsg
import kotlinx.coroutines.delay

/**
 * 百度地图渲染层（真实瓦片底图 + 真实地理坐标覆盖物）。
 *
 * 与 [CanvasMap] 数据契约完全一致：轨迹/拟合边界/等值线/目标圈/队友/标点/任务范围
 * 全部转成百度地图原生覆盖物（Polyline/Polygon/Circle/Marker），随地图拖拽缩放自动贴合。
 *
 * 相机双向同步：
 * - UI 侧 [MapCamera] 变化（定位、缩放按钮、全览）→ 通过 [MapCamera.revision] 推给地图；
 * - 用户手势拖动地图 → [MapCamera.syncFromMap] 反向写回，不产生回环。
 */
@Composable
fun BaiduMapCanvas(
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
    /** 当前位置（WGS84）：必须由地图引擎绘制，不能用相机公式在 Compose 层画（手势拖动时会跟错） */
    selfLatLon: Pair<Double, Double>? = null,
    /** 轨迹方向箭头图层 */
    trackArrows: Boolean = false,
    /** 基站站址图层 */
    cellSites: List<CellSite> = emptyList(),
    /** 标点（拍照/可疑设备）被点击：回传原始 WsMsg.Marker，null 表示不响应 */
    onMarkerClick: ((WsMsg.Marker) -> Unit)? = null,
) {
    val context = LocalContext.current
    val mapView = remember {
        val opts = BaiduMapOptions()
            .zoomControlsEnabled(false)
            .scaleControlEnabled(false)
            .compassEnabled(false)
            .rotateGesturesEnabled(false)
            .overlookingGesturesEnabled(false)
            .mapType(BaiduMap.MAP_TYPE_NORMAL)
        MapView(context, opts)
    }
    val baiduMap = remember(mapView) { mapView.map }
    val renderer = remember(baiduMap) { BaiduOverlayRenderer(baiduMap) }
    val currentTap by rememberUpdatedState(onTapLatLon)
    val currentMarkerClick by rememberUpdatedState(onMarkerClick)
    // LaunchedEffect 只按 baiduMap 启动一次，闭包里的 markers 会是首帧的空列表 → 必须用最新值
    val currentMarkers by rememberUpdatedState(markers)
    var mapReady by remember { mutableStateOf(false) }

    // 生命周期：MapView 需跟随页面 resume/pause/destroy，否则 GL 线程泄漏。
    // 这里不引入 lifecycle-runtime-compose 依赖：进入组合即 onResume，离开组合即 onPause + onDestroy。
    DisposableEffect(mapView) {
        runCatching { mapView.onResume() }
        onDispose {
            runCatching { mapView.onPause() }
            runCatching { mapView.onDestroy() }
        }
    }

    // 图层切换走统一抽象（地图尚未就绪时先记住，就绪后应用）
    DisposableEffect(baiduMap) {
        BaiduMapEngine.attach(baiduMap)
        activeMapEngine = BaiduMapEngine
        onDispose {
            BaiduMapEngine.detach()
            activeMapEngine = NoopMapEngine
        }
    }

    LaunchedEffect(baiduMap) {
        baiduMap.setOnMapLoadedCallback { mapReady = true }
        // OnMapClickListener 有两个方法（onMapClick/onMapPoiClick），不能用 SAM lambda
        baiduMap.setOnMapClickListener(object : BaiduMap.OnMapClickListener {
            override fun onMapClick(latLng: LatLng?) {
                latLng ?: return
                // 百度回传的是 BD09，工程内部要 WGS84
                val (wLat, wLon) = BaiduCoords.bd09ToWgs84(latLng.latitude, latLng.longitude)
                // 兜底：万一 Marker 点击没命中（百度热区偏小），按「点击点到标点≤约 32dp」再判一次
                val toleranceM = (camera.metersPerPixel * 32f).coerceIn(15.0, 140.0)
                val hit = currentMarkers.firstOrNull { mk ->
                    val dx = (mk.lon - wLon) * 111320.0 * kotlin.math.cos(Math.toRadians(wLat))
                    val dy = (mk.lat - wLat) * 110540.0
                    kotlin.math.hypot(dx, dy) <= toleranceM
                }
                val cb = currentMarkerClick
                if (hit != null && cb != null) {
                    cb(hit)
                    return
                }
                currentTap?.invoke(wLat, wLon)
            }

            override fun onMapPoiClick(poi: MapPoi?) = Unit
        })
        // 标点点击：百度回传的 Marker 拿不到自定义 Bundle（Marker 没有 getExtraInfo），
        // 所以由渲染器用 IdentityHashMap 记账反查业务对象。
        baiduMap.setOnMarkerClickListener(object : BaiduMap.OnMarkerClickListener {
            override fun onMarkerClick(marker: Marker): Boolean {
                val mk = renderer.ownerOf(marker)
                if (mk == null) return false
                val cb = currentMarkerClick ?: return false
                cb(mk)
                return true
            }
        })
        baiduMap.setOnMapStatusChangeListener(object : BaiduMap.OnMapStatusChangeListener {
            override fun onMapStatusChangeStart(status: MapStatus?) = Unit
            override fun onMapStatusChangeStart(status: MapStatus?, reason: Int) = Unit
            override fun onMapStatusChange(status: MapStatus?) = Unit
            override fun onMapStatusChangeFinish(status: MapStatus?) {
                val t = status?.target ?: return
                // 用户手势拖动/缩放地图 → 反向同步回相机（不递增 revision，避免回环）
                val (wLat, wLon) = BaiduCoords.bd09ToWgs84(t.latitude, t.longitude)
                camera.syncFromMap(
                    wLat,
                    wLon,
                    (status.zoom - BAIDU_ZOOM_OFFSET)
                        .coerceIn(camera.minZoom, camera.maxZoom),
                )
            }
        })
    }

    // 相机 → 地图（只在相机被程序主动移动时推送）。
    // 注意：不要用 mapReady 作为闸门——setOnMapLoadedCallback 在瓦片已被缓存时会漏回调，
    // 一旦错过闸门就永远不推相机，地图会停在百度默认的北京中心。百度允许在瓦片就绪前调用
    // setMapStatus（指令排队，就绪后生效），因此这里直接推送，mapReady 只作为兜底重推的触发源。
    LaunchedEffect(camera.revision, mapReady) {
        runCatching {
            baiduMap.setMapStatus(
                MapStatusUpdateFactory.newLatLngZoom(
                    bll(camera.centerLat, camera.centerLon),
                    (camera.zoom + BAIDU_ZOOM_OFFSET).coerceIn(BAIDU_MIN_ZOOM, BAIDU_MAX_ZOOM),
                ),
            )
        }
    }

    // 兜底：首次进入（或从多任务恢复）时 GL/瓦片线程可能还没起来，补推两三次的相机位置
    LaunchedEffect(baiduMap) {
        // 先把相机的缩放范围对齐到百度 SDK 的真实范围：
        // 之前相机上限 21、百度推送上限却写死 19，捏合到最大（百度侧可到 21）后再点 +
        // 会被抬到 22 又被夹回 19 —— 表现为「放到最大后点放大反而变小」。
        // 现在 push 与 pull 用同一把尺子（相机上限 = 百度上限 - 刻度偏移），不会再跳。
        runCatching {
            val bMax = baiduMap.maxZoomLevel
            val bMin = baiduMap.minZoomLevel
            camera.setZoomRange(
                (bMin - BAIDU_ZOOM_OFFSET).coerceAtLeast(6f),
                bMax - BAIDU_ZOOM_OFFSET,
            )
        }
        repeat(3) { i ->
            delay(if (i == 0) 250L else 1_200L)
            runCatching {
                baiduMap.setMapStatus(
                    MapStatusUpdateFactory.newLatLngZoom(
                        bll(camera.centerLat, camera.centerLon),
                        (camera.zoom + BAIDU_ZOOM_OFFSET).coerceIn(BAIDU_MIN_ZOOM, BAIDU_MAX_ZOOM),
                    ),
                )
            }
        }
    }

    // 数据 → 覆盖物（内部按内容指纹去重，避免 1Hz 无谓重建）
    SideEffect {
        renderer.render(
            target, samples, hullLatLon, bufferedLatLon, isolines, members, markers, zone, zones,
            selfLatLon, trackArrows, cellSites,
        )
    }

    AndroidView(modifier = modifier, factory = { mapView })
}

/**
 * 百度地图缩放级别的硬边界（SDK 常量 BaiduMap.REAL_MIN_ZOOM_LEVEL=4f / REAL_MAX_ZOOM_LEVEL=22f）。
 * 这里只当"防止越界崩 SDK"的护栏，真正可用的上下限由 BaiduMap.getMinZoomLevel()/getMaxZoomLevel()
 * 在 LaunchedEffect(baiduMap) 里读出来回填给 MapCamera.setZoomRange(...)。
 */
private const val BAIDU_MIN_ZOOM = 4f
private const val BAIDU_MAX_ZOOM = 22f

/**
 * 百度地图的 zoom 级别与 Web Mercator 标准级别不是一个刻度：实测百度级别 z 的可见范围
 * 约等于标准级别 z-1（百度的级别里多了一级"世界视图"）。MapCamera 全程用标准级别，
 * 推给百度时 +1、读回来时 -1，避免"fit 全览后地图仍缩在城市级"的问题。
 */
private const val BAIDU_ZOOM_OFFSET = 1f

/**
 * 地图引擎实现：把 UI 的图层切换（普通/卫星）落到百度 SDK 上。
 */
object BaiduMapEngine : MapEngine {

    private var map: BaiduMap? = null
    private var layer: MapLayerType = MapLayerType.NORMAL

    fun attach(m: BaiduMap?) {
        map = m
        applyLayer()
    }

    fun detach() {
        map = null
    }

    override fun setLayer(type: MapLayerType) {
        layer = type
        applyLayer()
    }

    private fun applyLayer() {
        val m = map ?: return
        runCatching {
            m.setMapType(
                when (layer) {
                    MapLayerType.NORMAL -> BaiduMap.MAP_TYPE_NORMAL
                    MapLayerType.SATELLITE -> BaiduMap.MAP_TYPE_SATELLITE
                },
            )
        }
    }
}

/**
 * 覆盖物渲染器：把业务数据翻译成百度地图原生覆盖物。
 * 用内容指纹做脏检查，指纹不变则完全跳过（地图手势拖动无需重建）。
 */
private class BaiduOverlayRenderer(private val map: BaiduMap) {

    private var lastKey: String? = null
    private val icons = HashMap<String, BitmapDescriptor>()
    private var addedCount = 0
    private val live = ArrayList<Overlay>()

    /**
     * 覆盖物 → 业务标点。百度点击回调给的是覆盖物对象本身（没有自定义 Bundle 可取），
     * 只能自己记账反查；覆盖物每轮重建时同步清空。
     */
    private val markerOwners = java.util.IdentityHashMap<Overlay, WsMsg.Marker>()

    /** 点击回调反查用：返回该覆盖物对应的业务标点 */
    fun ownerOf(overlay: Overlay): WsMsg.Marker? = markerOwners[overlay]

    /** 诊断用：当前记账的标点数量 */
    fun ownerCount(): Int = markerOwners.size

    fun render(
        target: TargetCell?,
        samples: List<SampleRecord>,
        hull: List<Pair<Double, Double>>?,
        buffered: List<Pair<Double, Double>>?,
        isolines: List<List<Pair<Double, Double>>>,
        members: List<MemberView>,
        markers: List<WsMsg.Marker>,
        zone: WsMsg.Zone?,
        zones: List<WsMsg.Zone>,
        selfLatLon: Pair<Double, Double>? = null,
        trackArrows: Boolean = false,
        cellSites: List<CellSite> = emptyList(),
    ) {
        val key = buildString {
            append(samples.size).append(':').append(samples.lastOrNull()?.tsEpochMs ?: 0L).append('|')
            append(target?.rawInput).append(':').append(target?.givenRadiusM).append('|')
            append(hull?.size ?: -1).append('|').append(buffered?.size ?: -1).append('|')
            append(isolines.size).append(':').append(isolines.sumOf { it.size }).append('|')
            members.forEach { append(it.userId).append(it.lat).append(it.lon).append(it.onTarget) }
            append('|')
            markers.forEach { append(it.ts).append(it.kind) }
            append('|').append(zone?.ts).append(':')
            zones.forEach { append(it.ts) }
            append('|').append(selfLatLon?.first).append(',').append(selfLatLon?.second)
            append('|').append(trackArrows)
            cellSites.forEach { append(it.ci).append(it.lat).append(it.lon) }
        }
        if (key == lastKey) return
        lastKey = key
        addedCount = 0

        // 不要用 map.clear()：百度把它投递到地图线程排队，偶尔会排在紧随其后的 addOverlay 之后，
        // 结果刚加的覆盖物被清掉（现象就是"日志说加了、屏幕上一根线都没有"）。
        // 改成自己记账、逐个 remove，顺序确定。
        live.forEach { runCatching { it.remove() } }
        live.clear()
        markerOwners.clear()

        // 1. 任务范围（多块分片 + 单圈）
        (listOfNotNull(zone) + zones.filter { it !== zone }).forEach { z ->
            when (z.kind) {
                "CIRCLE" -> {
                    val la = z.centerLat ?: return@forEach
                    val lo = z.centerLon ?: return@forEach
                    val r = z.radiusM ?: return@forEach
                    add {
                        CircleOptions()
                            .center(bll(la, lo))
                            .radius(r.toInt().coerceAtLeast(20))
                            .fillColor(0x1434D399)
                            .stroke(Stroke(3f, 0xCC34D399.toInt()))
                    }
                }
                "POLYGON" -> {
                    val pts = z.polygon.mapNotNull { p -> if (p.size >= 2) bll(p[0], p[1]) else null }
                    if (pts.size >= 3) add {
                        PolygonOptions()
                            .points(pts)
                            .fillColor(0x1434D399)
                            .stroke(Stroke(3f, 0xCC34D399.toInt()))
                    }
                }
            }
        }

        // 2. 覆盖拟合：缓冲带 → 凸包 → RSRP 等值线
        polygonOf(buffered, 0x0D22D3EE, 0x00000000)?.let { o -> add { o } }
        polygonOf(hull, 0x1A22D3EE, 0xB322D3EE.toInt())?.let { o -> add { o } }
        isolines.forEach { line ->
            val pts = line.map { bll(it.first, it.second) }
            if (pts.size >= 2) add {
                PolylineOptions()
                    .points(pts)
                    .color(0xCCA78BFA.toInt())
                    .width(3f)
            }
        }

        // 3. 目标基站参考圈 + 圆心
        target?.let { t ->
            val la = t.centerLat
            val lo = t.centerLon
            if (la != null && lo != null) {
                val center = bll(la, lo)
                t.givenRadiusM?.let { r ->
                    add {
                        CircleOptions()
                            .center(center)
                            .radius(r.toInt().coerceAtLeast(30))
                            .fillColor(0x12FF6B6B)
                            .stroke(Stroke(3f, 0xCCFF6B6B.toInt()))
                    }
                }
                add { MarkerOptions().position(center).icon(dot(0xFFFF6B6B.toInt(), 30)).anchor(0.5f, 0.5f) }
            }
        }

        // 4. 采样轨迹（点太多时抽稀，保证滑动流畅）
        val stride = if (samples.size > 1200) samples.size / 1200 else 1
        val trace = samples.filterIndexed { i, _ -> i % stride == 0 || i == samples.lastIndex }
            .map { bll(it.lat, it.lon) }
        if (trace.size >= 2) add {
            PolylineOptions()
                .points(trace)
                .color(0xE638BDF8.toInt())
                .width(7f)
        }
        samples.lastOrNull()?.let { s ->
            // 用 Circle 而不是 Marker：Marker 会吞掉点击事件，导致照片标点点不开
            circleDot(bll(s.lat, s.lon), 0xFF38BDF8.toInt(), 13f, null)
        }

        // 5. 队友位置（目标命中时加外环）
        members.forEach { m ->
            val la = m.lat ?: return@forEach
            val lo = m.lon ?: return@forEach
            val color = if (m.onTarget) 0xFFFFC531.toInt() else 0xFF4ADE80.toInt()
            add {
                MarkerOptions()
                    .position(bll(la, lo))
                    .icon(dot(color, 34))
                    .anchor(0.5f, 0.5f)
                    .title(m.name)
            }
        }

        // 6. 拍照/可疑设备标点（可点击 → 打开取证记录）
        markers.forEach { mk ->
            val suspicious = mk.kind == "SUSPICIOUS"
            val overlay = add {
                MarkerOptions()
                    .position(bll(mk.lat, mk.lon))
                    .icon(label(if (suspicious) "!" else "旗", if (suspicious) 0xFFF87171.toInt() else 0xFFF59E0B.toInt()))
                    .anchor(0.5f, 0.5f)
                    .clickable(true)
                    .title(mk.note.ifBlank { mk.byName })
            }
            if (overlay != null) markerOwners[overlay] = mk

        }

        // 7. 基站站址（基站图层）：橙色圆点 + 竖线小塔
        cellSites.forEach { site ->
            circleDot(bll(site.lat, site.lon), 0xFFF59E0B.toInt(), 10f, null)
        }

        // 8. 轨迹方向箭头（按地理方位角旋转），抽样到 40 个以内
        if (trackArrows && samples.size >= 2) {
            val maxArrows = 40
            val step = (samples.size / maxArrows).coerceAtLeast(1)
            var i = step
            while (i < samples.size) {
                val prev = samples[i - step]
                val cur = samples[i]
                val deg = bearingDegrees(prev.lat, prev.lon, cur.lat, cur.lon)
                add {
                    MarkerOptions()
                        .position(bll(cur.lat, cur.lon))
                        .icon(arrowIcon(0xFFA78BFA.toInt()))
                        .anchor(0.5f, 0.5f)
                        .rotate(deg)
                }
                i += step
            }
        }

        // 9. 当前位置（GPS 蓝点）：作为地图覆盖物绘制，随底图 1:1 平移缩放
        selfLatLon?.let { (la, lo) ->
            // 同样用 Circle：Marker 会吞掉该点的点击，用户点标点时永远拿不到回调
            circleDot(bll(la, lo), 0xFF38BDF8.toInt(), 18f, 0xFFFFFFFF.toInt())
        }
    }

    /** 两点间的初始方位角（0=正北，顺时针 0..360） */
    private fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val y = Math.sin(dLon) * Math.cos(p2)
        val x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dLon)
        return ((Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0).toFloat()
    }

    private fun polygonOf(
        ring: List<Pair<Double, Double>>?,
        fill: Int,
        stroke: Int,
    ): PolygonOptions? {
        if (ring == null || ring.size < 3) return null
        val opts = PolygonOptions()
            .points(ring.map { bll(it.first, it.second) })
            .fillColor(fill)
        if (stroke != 0) opts.stroke(Stroke(3f, stroke))
        return opts
    }

    private fun add(build: () -> com.baidu.mapapi.map.OverlayOptions): Overlay? {
        return try {
            val overlay = map.addOverlay(build())
            overlay?.let { live.add(it) }
            addedCount++
            if (addedCount == 1) android.util.Log.d("Weilie", "首个覆盖物已添加 (key=${lastKey?.take(24)}, live=${live.size})")
            overlay
        } catch (t: Throwable) {
            android.util.Log.w("Weilie", "addOverlay 失败: ${t.javaClass.simpleName}: ${t.message}", t)
            null
        }
    }

    /**
     * 圆形覆盖物：装饰性点位一律用它，不用 Marker。
     * 原因：百度的 Marker 会吞掉点击事件（既不回调业务，也不再触发 onMapClick），
     * 当前位置蓝点又正好压在用户刚拍的照片标点上，导致「点标点打不开记录」。
     */
    private fun circleDot(center: com.baidu.mapapi.model.LatLng, color: Int, radiusPx: Float, ring: Int?) {
        val radiusM = (radiusPx * metersPerPixelAt(center.latitude)).coerceAtLeast(0.8)
        add {
            CircleOptions()
                .center(center)
                .radius(radiusM.toInt().coerceAtLeast(1))
                .fillColor(color)
                .stroke(Stroke(2f, ring ?: color))
                .zIndex(9)
        }
    }

    /** 当前 zoom 下 1 像素对应多少米（用于把 px 半径换算成米） */
    private fun metersPerPixelAt(lat: Double): Double {
        val z = runCatching { map.mapStatus.zoom }.getOrDefault(16f).toDouble()
        return 156543.03392 * kotlin.math.cos(Math.toRadians(lat)) / Math.pow(2.0, z)
    }

    /** 圆点图标（外圈半透明光晕 + 实心点），按颜色缓存 */
    private fun dot(color: Int, sizePx: Int): BitmapDescriptor = icons.getOrPut("dot:$color:$sizePx") {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0x40FFFFFF
        c.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, p)
        p.color = color
        c.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f * 0.62f, p)
        BitmapDescriptorFactory.fromBitmap(bmp)
    }

    /** 文字标签图标（旗标 / 可疑设备感叹号） */
    private fun label(text: String, bg: Int): BitmapDescriptor = icons.getOrPut("label:$text:$bg") {
        val size = 64
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = bg
        c.drawCircle(size / 2f, size / 2f, size / 2f - 3f, p)
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF0B1020.toInt()
            textSize = size * 0.52f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        c.drawText(text, size / 2f, size / 2f + size * 0.19f, t)
        BitmapDescriptorFactory.fromBitmap(bmp)
    }

    /** 白边实心圆点（当前位置 GPS 点） */
    private fun dotRing(color: Int, sizePx: Int): BitmapDescriptor = icons.getOrPut("ring:$color:$sizePx") {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = sizePx / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0x330B1020
        c.drawCircle(cx, cx, sizePx / 2f, p)
        p.color = 0xFFFFFFFF.toInt()
        c.drawCircle(cx, cx, sizePx / 2f * 0.66f, p)
        p.color = color
        c.drawCircle(cx, cx, sizePx / 2f * 0.46f, p)
        BitmapDescriptorFactory.fromBitmap(bmp)
    }

    /** 方向箭头（箭头默认朝上，靠 MarkerOptions.rotate(方位角) 指向行进方向） */
    private fun arrowIcon(color: Int): BitmapDescriptor = icons.getOrPut("arrow:$color") {
        val w = 30
        val h = 36
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = color
        val path = android.graphics.Path().apply {
            moveTo(w / 2f, 1f)
            lineTo(w - 3f, h - 2f)
            lineTo(w / 2f, h * 0.70f)
            lineTo(3f, h - 2f)
            close()
        }
        c.drawPath(path, p)
        BitmapDescriptorFactory.fromBitmap(bmp)
    }
}

/** WGS84 经纬度 → 百度覆盖物 / 相机用的 BD09 LatLng（不换算会整体偏 300~700 米） */
private fun bll(lat: Double, lon: Double): com.baidu.mapapi.model.LatLng {
    val (la, lo) = BaiduCoords.wgsToBd09(lat, lon)
    return com.baidu.mapapi.model.LatLng(la, lo)
}
