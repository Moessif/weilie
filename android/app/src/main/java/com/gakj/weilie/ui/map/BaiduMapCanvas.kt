package com.gakj.weilie.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Point
import android.view.MotionEvent
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
import com.gakj.weilie.core.cellmap.CellSite as KnownCell
import com.gakj.weilie.core.fitting.Geo
import com.gakj.weilie.core.model.SampleRecord
import com.gakj.weilie.core.model.TargetCell
import com.gakj.weilie.core.sync.MemberView
import com.gakj.weilie.core.sync.WsMsg
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log2

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
/** 屏幕坐标 → WGS84，交给自由绘制回调（双指拖动时不取点） */
private fun emitDrawPoint(
    map: BaiduMap,
    x: Float,
    y: Float,
    cb: ((Double, Double) -> Unit)?,
) {
    if (cb == null) return
    runCatching {
        val p = android.graphics.Point(x.toInt(), y.toInt())
        val ll = map.projection.fromScreenLocation(p) ?: return
        val (wLat, wLon) = BaiduCoords.bd09ToWgs84(ll.latitude, ll.longitude)
        if (wLat.isFinite() && wLon.isFinite()) cb(wLat, wLon)
    }
}

/**
 * 当前缩放级别下，屏幕 1px 对应的东西向/南北向米数。
 *
 * 不能用 `156543.03392*cos(lat)/2^zoom` 直接算：那是 256px 瓦片、无屏幕密度的理论值，
 * 真机上投影像素是设备像素（density≈2.6 时误差 2.6 倍），双指拖动会一下子甩出去很远。
 * 这里用百度投影实测：把屏幕点偏移 100px 反查经纬度，再量距离。
 */
private fun screenMetersPerPixel(map: BaiduMap, x: Float, y: Float): Pair<Double, Double> {
    return runCatching {
        val proj = map.projection
        val a = proj.fromScreenLocation(Point(x.toInt(), y.toInt())) ?: return 0.0 to 0.0
        val bx = proj.fromScreenLocation(Point(x.toInt() + 100, y.toInt())) ?: return 0.0 to 0.0
        val by = proj.fromScreenLocation(Point(x.toInt(), y.toInt() + 100)) ?: return 0.0 to 0.0
        val mx = Geo.haversineMeters(a.latitude, a.longitude, bx.latitude, bx.longitude) / 100.0
        val my = Geo.haversineMeters(a.latitude, a.longitude, by.latitude, by.longitude) / 100.0
        mx to my
    }.getOrDefault(0.0 to 0.0)
}

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
    /** 自由绘制中的手指轨迹（WGS84）：实时画成折线，配合绘制中的多边形一起刷新 */
    draftPath: List<Pair<Double, Double>> = emptyList(),
    camera: MapCamera = remember { MapCamera() },
    onTapLatLon: ((Double, Double) -> Unit)? = null,
    /** 当前位置（WGS84）：必须由地图引擎绘制，不能用相机公式在 Compose 层画（手势拖动时会跟错） */
    selfLatLon: Pair<Double, Double>? = null,
    /** 轨迹方向箭头图层 */
    trackArrows: Boolean = false,
    /** 基站站址图层 */
    cellSites: List<CellSite> = emptyList(),
    /** 逐点分类显示：主区命中实心 / 邻区命中彩色实心 / 脱离目标空心 */
    sampleMarks: Boolean = true,
    /** 封闭区域（有效实心点的凹包，或按路线生成的两侧条带） */
    area: ClosedArea? = null,
    /** 基站理论覆盖区（全向圆 / 扇形 / TA 距离环带，可叠加多个） */
    theories: List<TheoryShape> = emptyList(),
    /** 信号热力图图层 */
    heat: HeatLayer? = null,
    /** 基站库（OpenCelliD 众包坐标）图层：按制式着色，可点击看详情 */
    cellLibrary: List<KnownCell> = emptyList(),
    /** 基站库中当前选中的站址（[KnownCell.key]），渲染时加白环 */
    selectedCellKey: String? = null,
    /** 基站库站址被点击：回传该站址（null 表示不响应） */
    onCellSiteClick: ((KnownCell) -> Unit)? = null,
    /** 自由绘制模式：单指画范围（拖动时持续回调坐标），双指平移地图 */
    drawMode: Boolean = false,
    /** 自由绘制时手指经过的坐标（WGS84） */
    onDrawPoint: ((Double, Double) -> Unit)? = null,
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
    val currentCellClick by rememberUpdatedState(onCellSiteClick)
    val currentDrawMode by rememberUpdatedState(drawMode)
    val currentDrawPoint by rememberUpdatedState(onDrawPoint)
    // LaunchedEffect 只按 baiduMap 启动一次，闭包里的 markers 会是首帧的空列表 → 必须用最新值
    val currentMarkers by rememberUpdatedState(markers)
    // ---- 自由绘制 ----
    // 单指：拖动连续取点；
    // 双指：拖动平移 + 捏合缩放。绘制期间必须关掉地图自身手势（否则单指画线会把底图一起拖走），
    // 所以双指也得由这里手动换算——关键是**所有位移/缩放都相对双指落下那一刻的起点计算**，
    // 不能逐帧累加：逐帧累加会把投影误差和事件延迟一起攒起来，真机上表现为「手一放地图就飘」。
    var lastDrawX by remember { mutableStateOf(0f) }
    var lastDrawY by remember { mutableStateOf(0f) }
    var multiActive by remember { mutableStateOf(false) }
    // 双指结束后剩下的那根手指不能再补点（否则松手瞬间会甩出一段直线）
    var strokeBlocked by remember { mutableStateOf(false) }
    var multiStartZoom by remember { mutableStateOf(0f) }
    var multiStartLat by remember { mutableStateOf(0.0) }
    var multiStartLon by remember { mutableStateOf(0.0) }
    var multiStartCx by remember { mutableStateOf(0f) }
    var multiStartCy by remember { mutableStateOf(0f) }
    var multiStartSpan by remember { mutableStateOf(1f) }
    var multiMppX by remember { mutableStateOf(0.0) }
    var multiMppY by remember { mutableStateOf(0.0) }
    LaunchedEffect(baiduMap, drawMode) {
        runCatching {
            // 绘制态：关掉地图手势，单指交给画笔；双指由 OnMapTouchListener 手动平移/缩放
            baiduMap.uiSettings.setScrollGesturesEnabled(!drawMode)
            baiduMap.uiSettings.setZoomGesturesEnabled(!drawMode)
        }
    }
    DisposableEffect(baiduMap) {
        fun endGesture() {
            multiActive = false
            lastDrawX = 0f
            lastDrawY = 0f
            // 双指手势结束后把地图状态回写给相机，避免相机还停在双指落下前的位置
            if (!multiActive) return
            val st = runCatching { baiduMap.mapStatus }.getOrNull()
            val t = st?.target
            if (t != null) {
                runCatching {
                    val (wLat, wLon) = BaiduCoords.bd09ToWgs84(t.latitude, t.longitude)
                    val z = (st.zoom - BAIDU_ZOOM_OFFSET).coerceIn(camera.minZoom, camera.maxZoom)
                    camera.syncFromMap(wLat, wLon, z)
                }
            }
        }
        val listener = BaiduMap.OnMapTouchListener { e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    multiActive = false
                    strokeBlocked = false
                    lastDrawX = e.x
                    lastDrawY = e.y
                    if (currentDrawMode && e.pointerCount == 1) {
                        emitDrawPoint(baiduMap, e.x, e.y, currentDrawPoint)
                    }
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (!currentDrawMode) return@OnMapTouchListener
                    val st = runCatching { baiduMap.mapStatus }.getOrNull() ?: return@OnMapTouchListener
                    val target = st.target ?: return@OnMapTouchListener
                    multiActive = true
                    strokeBlocked = true
                    multiStartZoom = st.zoom
                    multiStartLat = target.latitude
                    multiStartLon = target.longitude
                    multiStartCx = (e.getX(0) + e.getX(1)) / 2f
                    multiStartCy = (e.getY(0) + e.getY(1)) / 2f
                    multiStartSpan = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1)).coerceAtLeast(1f)
                    val (mx, my) = screenMetersPerPixel(baiduMap, multiStartCx, multiStartCy)
                    multiMppX = mx
                    multiMppY = my
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!currentDrawMode) return@OnMapTouchListener
                    if (multiActive && e.pointerCount >= 2) {
                        val cx = (e.getX(0) + e.getX(1)) / 2f
                        val cy = (e.getY(0) + e.getY(1)) / 2f
                        val span = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1)).coerceAtLeast(1f)
                        // 捏合：span 变大 → 放大（百度 zoom 级别 +log2(比例)）
                        val zoomMin = camera.minZoom + BAIDU_ZOOM_OFFSET
                        val zoomMax = camera.maxZoom + BAIDU_ZOOM_OFFSET
                        val newZoom = (multiStartZoom + log2(span / multiStartSpan)).coerceIn(zoomMin, zoomMax)
                        // 平移：以双指落点时的中心为基准，手指右移 → 中心西移
                        val dxM = (cx - multiStartCx) * multiMppX
                        val dyM = (cy - multiStartCy) * multiMppY
                        // 手指向下拖（dy>0）→ 视野内容跟着下移 → 中心点北移（纬度增大）
                        val newLat = multiStartLat + dyM / 110_540.0
                        val newLon = multiStartLon - dxM / (111_320.0 * cos(Math.toRadians(multiStartLat)))
                        runCatching {
                            val status = MapStatus.Builder()
                                .target(LatLng(newLat, newLon))
                                .zoom(newZoom)
                                .build()
                            baiduMap.setMapStatus(MapStatusUpdateFactory.newMapStatus(status))
                        }
                    } else if (!multiActive && e.pointerCount == 1 && !strokeBlocked) {
                        val dx = e.x - lastDrawX
                        val dy = e.y - lastDrawY
                        if (dx * dx + dy * dy >= 14f * 14f) {
                            lastDrawX = e.x
                            lastDrawY = e.y
                            emitDrawPoint(baiduMap, e.x, e.y, currentDrawPoint)
                        }
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    // 双指变单指：保持 multiActive，剩下的手指不画线；等全部抬起再复位
                    if (multiActive) strokeBlocked = true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    endGesture()
                    strokeBlocked = false
                }
            }
        }
        runCatching { baiduMap.setOnMapTouchListener(listener) }
        onDispose { runCatching { baiduMap.setOnMapTouchListener(null) } }
    }
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
        // 需求 7：导出「含真实地图底图」的轨迹图需要拿到当前 BaiduMap 做 snapshot()
        MapSnapshot.baiduMap = baiduMap
        onDispose {
            MapSnapshot.baiduMap = null
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
                // 兜底：万一 Marker 点击没命中（百度热区偏小），按「点击点到标点 ≤ 约 12dp」再判一次。
                // 早先给的是 32dp（至少 15 米），结果点按钮/地图空白处也会弹标点详情（用户反馈误触），
                // 这里收紧到图标本体大小，仍保留一点手指容差。
                val toleranceM = (camera.metersPerPixel * 12f).coerceIn(6.0, 32.0)
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
                // 先看是不是基站库的站址（用户点了会想看详情/以它为圆心划区）
                val site = renderer.cellOwnerOf(marker)
                if (site != null) {
                    val cb = currentCellClick ?: return false
                    cb(site)
                    return true
                }
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
    // 在组合作用域里读 camera.zoom：圆点半径依赖缩放，缩放变化要触发重组重绘
    val zoomBaiduNow = camera.zoom + BAIDU_ZOOM_OFFSET
    SideEffect {
        renderer.render(
            target, samples, hullLatLon, bufferedLatLon, isolines, members, markers, zone, zones,
            selfLatLon, trackArrows, cellSites, sampleMarks, area, theories, heat,
            cellLibrary, selectedCellKey, draftPath,
            // 圆点半径要按“相机当前缩放”换算：地图刚加载时 mapStatus 还是 0/低级别，
            // 用它会算出几公里大的圆斑（用户反馈：新建任务刚进地图有个很大的点，过一会儿才正常）
            zoomBaiduNow,
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
     * 绘制圆点用的缩放级别（百度口径，已含 +1 偏移），由相机在每次 render 时传入。
     * 不能用 map.mapStatus.zoom：地图刚加载时它还是 0 或低级别，换算出的半径会有几公里，
     * 真机现象就是「新建任务刚进地图有一个非常大的点覆盖，过一会儿才缩小正常」。
     */
    private var scaleZoom: Float = 16f

    /**
     * 覆盖物 → 业务标点。百度点击回调给的是覆盖物对象本身（没有自定义 Bundle 可取），
     * 只能自己记账反查；覆盖物每轮重建时同步清空。
     */
    private val markerOwners = java.util.IdentityHashMap<Overlay, WsMsg.Marker>()

    /**
     * 基站库站址 → 覆盖物反查。和 markerOwners 同理：百度 Marker 点击只回传覆盖物本身，
     * 拿不到业务对象，只能自己记账。
     */
    private val cellOwners = java.util.IdentityHashMap<Overlay, KnownCell>()

    /** 点击回调反查用：返回该覆盖物对应的业务标点 */
    fun ownerOf(overlay: Overlay): WsMsg.Marker? = markerOwners[overlay]

    /** 点击回调反查用：返回该覆盖物对应的基站库站址 */
    fun cellOwnerOf(overlay: Overlay): KnownCell? = cellOwners[overlay]

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
        sampleMarks: Boolean = true,
        area: ClosedArea? = null,
        theories: List<TheoryShape> = emptyList(),
        heat: HeatLayer? = null,
        cellLibrary: List<KnownCell> = emptyList(),
        selectedCellKey: String? = null,
        /** 自由绘制中的手指轨迹（WGS84）：每加一个点都要重绘，所以必须进内容指纹 */
        draftPath: List<Pair<Double, Double>> = emptyList(),
        /** 相机当前的百度缩放级别（camera.zoom + 1）：圆点半径按它换算，保证刚进地图就不出现巨圆 */
        zoomBaidu: Float = 16f,
    ) {
        scaleZoom = zoomBaidu.coerceIn(BAIDU_MIN_ZOOM, BAIDU_MAX_ZOOM)
        val key = buildString {
            append(samples.size).append(':').append(samples.lastOrNull()?.tsEpochMs ?: 0L).append('|')
            append(target?.rawInput).append(':').append(target?.givenRadiusM).append('|')
            append(hull?.size ?: -1).append('|').append(buffered?.size ?: -1).append('|')
            append(isolines.size).append(':').append(isolines.sumOf { it.size }).append('|')
            members.forEach { append(it.userId).append(it.lat).append(it.lon).append(it.onTarget) }
            append('|')
            markers.forEach { append(it.ts).append(it.kind) }
            append('|').append(zone?.ts).append(':')
            zones.forEach { append(it.ts).append(it.polygon.size).append(it.polygon.hashCode()) }
            append('|').append(draftPath.size).append(':').append(draftPath.hashCode())
            append('|').append(selfLatLon?.first).append(',').append(selfLatLon?.second)
            append('|').append(trackArrows)
            cellSites.forEach { append(it.ci).append(it.lat).append(it.lon) }
            append('|').append(sampleMarks)
            append('|').append(area?.ring?.hashCode() ?: -1).append(':').append(area?.label)
            theories.forEach { append(it.kind).append(it.centerLat).append(it.centerLon).append(it.radiusM).append(it.innerRadiusM).append(it.startBearing).append(it.sweepDeg) }
            append('|').append(heat?.version)
            // 基站库：站址集合 + 选中态。必须进指纹，否则开关图层/换选中不会重绘
            append('|').append(selectedCellKey)
            cellLibrary.forEach { append(it.key).append(it.lat).append(it.lon) }
            // 缩放级别进指纹：圆点半径是「像素 × 当前米/像素」算出来的。
            // 这里用相机传进来的 scaleZoom，而不是 map.mapStatus.zoom（后者在地图刚加载时不可信）。
            append('|').append(scaleZoom.toInt())
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
        cellOwners.clear()

        // 0. 信号热力图（栅格位图铺在地理包围盒上，最底层）
        heat?.let { h ->
            runCatching {
                val bounds = com.baidu.mapapi.model.LatLngBounds.Builder()
                    .include(bll(h.minLat, h.minLon))
                    .include(bll(h.maxLat, h.maxLon))
                    .build()
                add {
                    com.baidu.mapapi.map.GroundOverlayOptions()
                        .image(BitmapDescriptorFactory.fromBitmap(h.bitmap))
                        .positionFromBounds(bounds)
                        .zIndex(1)
                }
            }.onFailure { android.util.Log.w("Weilie", "热力图覆盖物失败: ${it.message}") }
        }

        // 0.1 基站理论覆盖区（蓝=理论 / 橙=TA 距离带）
        theories.forEach { t ->
            when (t.kind) {
                TheoryShape.Kind.CIRCLE -> add {
                    CircleOptions()
                        .center(bll(t.centerLat, t.centerLon))
                        .radius(t.radiusM.toInt().coerceAtLeast(20))
                        .fillColor(0x1F3B82F6)
                        .stroke(Stroke(2f, 0xCC3B82F6.toInt()))
                        .zIndex(2)
                }
                TheoryShape.Kind.SECTOR -> {
                    val pts = sectorPolygon(t.centerLat, t.centerLon, t.radiusM, t.startBearing, t.sweepDeg)
                    if (pts.size >= 3) add {
                        PolygonOptions()
                            .points(pts.map { bll(it.first, it.second) })
                            .fillColor(0x1F3B82F6)
                            .stroke(Stroke(2f, 0xCC3B82F6.toInt()))
                            .zIndex(2)
                    }
                }
                TheoryShape.Kind.RING -> {
                    val outer = t.radiusM.coerceAtLeast(10.0)
                    val inner = t.innerRadiusM.coerceIn(0.0, outer * 0.95)
                    annulusQuads(t.centerLat, t.centerLon, inner, outer, 48).forEach { quad ->
                        add {
                            PolygonOptions()
                                .points(quad.map { bll(it.first, it.second) })
                                .fillColor(0x33FB923C)
                                .stroke(Stroke(1f, 0x66FB923C))
                                .zIndex(2)
                        }
                    }
                }
            }
        }

        // 0.2 封闭区域（有效实心点凹包 / 条带兜底）：半透明，不遮底图
        area?.let { a ->
            polygonOf(a.ring, a.fillColor, a.strokeColor)?.let { o ->
                o.zIndex(3)
                add { o }
            }
            // 0.3 理论 ∩ 实测（需求第 7 条图例里的紫色）
            theories.forEach { t ->
                val clip = if (t.kind == TheoryShape.Kind.RING) {
                    // 环带不凸，退化成外圆参与求交；带内区域不会误判成"覆盖到"太多
                    com.gakj.weilie.core.fitting.CoverageIntersect.circlePolygon(
                        t.centerLat, t.centerLon, t.radiusM.coerceAtLeast(10.0), 48,
                    )
                } else {
                    sectorPolygon(t.centerLat, t.centerLon, t.radiusM.coerceAtLeast(10.0), t.startBearing, t.sweepDeg)
                }
                val inter = com.gakj.weilie.core.fitting.CoverageIntersect.intersect(a.ring, clip)
                if (inter.size >= 3) {
                    polygonOf(inter, 0x40A855F7, 0xCCA855F7.toInt())?.let { o ->
                        o.zIndex(4)
                        add { o }
                    }
                }
            }
        }

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

        // 1.1 自由绘制中的手指轨迹：每加一个点就重绘（绘制中的多边形由调用方以 zone 传入）
        if (draftPath.size >= 2) {
            val pts = draftPath.map { bll(it.first, it.second) }
            add {
                PolylineOptions()
                    .points(pts)
                    .color(0xE634D399.toInt())
                    .width(5f)
                    .zIndex(7)
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

        // 4. 采样轨迹：命中段=蓝色，脱离目标段=灰色（点太多时抽稀，保证滑动流畅）
        val stride = if (samples.size > 1200) samples.size / 1200 else 1
        val trace = samples.filterIndexed { i, _ -> i % stride == 0 || i == samples.lastIndex }
        if (trace.size >= 2) {
            val hitColor = 0xE638BDF8.toInt()
            val outColor = 0xCC94A3B8.toInt()
            var runStart = 0
            var runOut = SampleMark.of(trace[0]) == SampleMark.OUT
            var runs = 0
            fun flushRun(endInclusive: Int, isOut: Boolean) {
                if (endInclusive - runStart < 1 || runs >= 240) return
                val pts = trace.subList(runStart, endInclusive + 1).map { bll(it.lat, it.lon) }
                if (pts.size >= 2) add {
                    PolylineOptions()
                        .points(pts)
                        .color(if (isOut) outColor else hitColor)
                        .width(if (isOut) 6f else 7f)
                        .zIndex(3)
                }
                runs++
            }
            for (i in 1 until trace.size) {
                val out = SampleMark.of(trace[i]) == SampleMark.OUT
                if (out != runOut) {
                    flushRun(i, runOut)
                    runStart = i
                    runOut = out
                }
            }
            flushRun(trace.lastIndex, runOut)
        }
        samples.lastOrNull()?.let { s ->
            // 用 Circle 而不是 Marker：Marker 会吞掉点击事件，导致照片标点点不开
            circleDot(bll(s.lat, s.lon), 0xFF38BDF8.toInt(), 13f, null)
        }

        // 4.5 逐点分类：主区命中=普通实心点 / 邻区命中=彩色实心点（不同基站不同色）/ 脱离=空心点
        if (sampleMarks && samples.isNotEmpty()) {
            val maxDots = 400
            val step = if (samples.size > maxDots) (samples.size / maxDots) + 1 else 1
            samples.forEachIndexed { i, s ->
                if (i % step != 0 && i != samples.lastIndex) return@forEachIndexed
                                val center = bll(s.lat, s.lon)
                // 用固定像素的 Marker 图标画采样点：Circle 的半径是「米」，放大后会变成巨大的圆斑
                val mark = SampleMark.of(s)
                val icon = when (mark) {
                    SampleMark.MAIN -> dot(MarkStyles.MAIN_FILL, 26)
                    SampleMark.NEIGHBOR -> dot(MarkStyles.baseColor(s.servingCi), 28)
                    SampleMark.OUT -> dotRing(MarkStyles.OUT_RING, 20)
                }
                add {
                    MarkerOptions()
                        .position(center)
                        .icon(icon)
                        .anchor(0.5f, 0.5f)
                        .zIndex(8)
                }
            }
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
        //    取证类型要在地图上就能分辨（用户反馈四种类型 UI 无区别）：emoji 图形 + 类型底色，
        //    有自定义名称时把 emoji 接在名字前面，名称仍然是用户自己打的那几个字。
        markers.forEach { mk ->
            val visual = mk.visualKind()
            val flagName = mk.name.trim()
            val overlay = add {
                MarkerOptions()
                    .position(bll(mk.lat, mk.lon))
                    .icon(
                        when {
                            flagName.isEmpty() -> kindIcon(visual.emoji, visual.bg, visual.fg)
                            visual == MarkerKindVisual.FLAG -> nameTag(flagName, visual.bg, visual.fg)
                            else -> nameTag("${visual.emoji} $flagName", visual.bg, visual.fg)
                        },
                    )
                    .anchor(0.5f, 0.5f)
                    .clickable(true)
                    .title(
                        flagName.ifBlank {
                            mk.note.ifBlank { "${visual.label} · ${mk.byName}" }
                        },
                    )
            }
            if (overlay != null) markerOwners[overlay] = mk

        }

        // 6.5 基站库站址（OpenCelliD 众包坐标）：制式着色 + 短标签，点击可看详情/以它为圆心划区
        if (cellLibrary.isNotEmpty()) {
            val maxSites = 200
            val step = if (cellLibrary.size > maxSites) (cellLibrary.size / maxSites) + 1 else 1
            cellLibrary.forEachIndexed { i, site ->
                if (i % step != 0) return@forEachIndexed
                if (!site.isValid()) return@forEachIndexed
                val selected = selectedCellKey != null && site.key == selectedCellKey
                val overlay = add {
                    MarkerOptions()
                        .position(bll(site.lat, site.lon))
                        .icon(cellIcon(cellLibraryShort(site), cellLibraryColor(site.rat), selected))
                        .anchor(0.5f, 0.5f)
                        .clickable(true)
                        .title(site.title)
                }
                if (overlay != null) cellOwners[overlay] = site
            }
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

    /** 当前渲染缩放级别下 1 像素对应多少米（用于把 px 半径换算成米） */
    private fun metersPerPixelAt(lat: Double): Double {
        val z = scaleZoom.toDouble().coerceIn(6.0, 22.0)
        return 156543.03392 * kotlin.math.cos(Math.toRadians(lat)) / Math.pow(2.0, z)
    }

    /** 空心圆点：只描边不填充（"脱离目标"轨迹点） */
    private fun circleRing(center: com.baidu.mapapi.model.LatLng, color: Int, radiusPx: Float) {
        val radiusM = (radiusPx * metersPerPixelAt(center.latitude)).coerceAtLeast(0.8)
        add {
            CircleOptions()
                .center(center)
                .radius(radiusM.toInt().coerceAtLeast(1))
                .fillColor(0x00000000)
                .stroke(Stroke(2f, color))
                .zIndex(8)
        }
    }

    /** 扇形多边形（中心点 + 圆弧点），方位角 0=正北、顺时针 */
    private fun sectorPolygon(
        lat: Double,
        lon: Double,
        radiusM: Double,
        startBearing: Float,
        sweepDeg: Float,
    ): List<Pair<Double, Double>> {
        val sweep = sweepDeg.coerceIn(5f, 360f)
        val steps = ((sweep / 6f).toInt() + 2).coerceIn(8, 72)
        val out = ArrayList<Pair<Double, Double>>(steps + 2)
        out.add(lat to lon)
        for (i in 0..steps) {
            val bearing = startBearing + sweep * i / steps
            out.add(destination(lat, lon, radiusM, bearing))
        }
        return out
    }

    /** 环带用的小四边形（内半径 → 外半径），近似 TA 距离带 */
    private fun annulusQuads(
        lat: Double,
        lon: Double,
        innerM: Double,
        outerM: Double,
        segments: Int,
    ): List<List<Pair<Double, Double>>> {
        val n = segments.coerceIn(12, 96)
        val quads = ArrayList<List<Pair<Double, Double>>>(n)
        for (i in 0 until n) {
            val b1 = 360f * i / n
            val b2 = 360f * (i + 1) / n
            val o1 = destination(lat, lon, outerM, b1)
            val o2 = destination(lat, lon, outerM, b2)
            val i1 = destination(lat, lon, innerM, b1)
            val i2 = destination(lat, lon, innerM, b2)
            quads.add(listOf(o1, o2, i2, i1))
        }
        return quads
    }

    /** 已知起点、距离、方位角求终点（WGS84 球面近似） */
    private fun destination(lat: Double, lon: Double, distM: Double, bearingDeg: Float): Pair<Double, Double> {
        val d = distM / 6371000.0
        val brg = Math.toRadians(bearingDeg.toDouble())
        val lat1 = Math.toRadians(lat)
        val lon1 = Math.toRadians(lon)
        val lat2 = Math.asin(
            Math.sin(lat1) * Math.cos(d) + Math.cos(lat1) * Math.sin(d) * Math.cos(brg),
        )
        val lon2 = lon1 + Math.atan2(
            Math.sin(brg) * Math.sin(d) * Math.cos(lat1),
            Math.cos(d) - Math.sin(lat1) * Math.sin(lat2),
        )
        return Math.toDegrees(lat2) to Math.toDegrees(lon2)
    }

    /** 基站库站址配色：须与 CellLibraryOverlay 的 cellSiteColor 保持一致 */
    private fun cellLibraryColor(rat: String): Int = when (rat.uppercase()) {
        "LTE" -> 0xFF22D3EE.toInt()
        "NR", "5GNR" -> 0xFFA855F7.toInt()
        "GSM" -> 0xFFFB923C.toInt()
        "UMTS", "WCDMA" -> 0xFF34D399.toInt()
        else -> 0xFF94A3B8.toInt()
    }

    /** 基站库短标签：用制式而不是运营商，地图上更好认 */
    private fun cellLibraryShort(site: KnownCell): String = when (site.rat.uppercase()) {
        "LTE" -> "4G"
        "NR", "5GNR" -> "5G"
        "GSM" -> "2G"
        "UMTS", "WCDMA" -> "3G"
        else -> "?"
    }

    /** 基站库站点图标：制式短标签圆点；选中态加白边并放大，和拍照标点区分开 */
    private fun cellIcon(text: String, bg: Int, selected: Boolean): BitmapDescriptor =
        icons.getOrPut("cell:$text:$bg:$selected") {
            val size = if (selected) 76 else 56
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            if (selected) {
                p.color = 0xFFFFFFFF.toInt()
                c.drawCircle(size / 2f, size / 2f, size / 2f - 1f, p)
                p.color = bg
                c.drawCircle(size / 2f, size / 2f, size / 2f - 5f, p)
            } else {
                p.color = 0x330B1020
                c.drawCircle(size / 2f, size / 2f + 1f, size / 2f, p)
                p.color = bg
                c.drawCircle(size / 2f, size / 2f, size / 2f - 4f, p)
            }
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF0B1020.toInt()
                textSize = size * (if (selected) 0.38f else 0.42f)
                textAlign = Paint.Align.CENTER
                isFakeBoldText = true
            }
            c.drawText(text, size / 2f, size / 2f + tp.textSize * 0.36f, tp)
            BitmapDescriptorFactory.fromBitmap(bmp)
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

    /**
     * 标点名称标签（用户自己输入的名字）：圆角药丸，过长截断。
     * 截断按「码点」算，直接把 emoji 截成半个代理对会渲染成方块。
     */
    private fun nameTag(text: String, bg: Int, fg: Int = 0xFF0B1020.toInt()): BitmapDescriptor {
        val maxChars = 8
        val t = if (text.codePointCount(0, text.length) > maxChars) {
            val end = text.offsetByCodePoints(0, maxChars)
            text.substring(0, end) + "…"
        } else {
            text
        }
        val h = 56
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fg
            textSize = 24f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        val w = (tp.measureText(t) + 30f).toInt().coerceIn(56, 300)
        return icons.getOrPut("tag:$t:$bg:$fg:$w") {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.color = 0x33000000
            c.drawRoundRect(2f, 4f, w - 2f, h.toFloat(), h / 2f, h / 2f, p)
            p.color = bg
            c.drawRoundRect(0f, 0f, w - 3f, h - 5f, (h - 5f) / 2f, (h - 5f) / 2f, p)
            c.drawText(t, (w - 3f) / 2f, (h - 5f) / 2f + tp.textSize * 0.36f, tp)
            BitmapDescriptorFactory.fromBitmap(bmp)
        }
    }

    /**
     * 取证类型图标：彩色圆底 + 白描边 + emoji（📷/🕵/📹/⚠/📄/🚩）。
     * 比纯文字标签更容易在缩小的地图上一眼分辨；emoji 由系统字体渲染，不需要打包图片。
     */
    private fun kindIcon(emoji: String, bg: Int, fg: Int): BitmapDescriptor =
        icons.getOrPut("kind:$emoji:$bg:$fg") {
            val size = 72
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x330B1020 }
            c.drawCircle(size / 2f, size / 2f + 1.5f, size / 2f - 2f, shadow)
            val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg }
            c.drawCircle(size / 2f, size / 2f, size / 2f - 4f, body)
            val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt()
                style = Paint.Style.STROKE
                strokeWidth = 3f
            }
            c.drawCircle(size / 2f, size / 2f, size / 2f - 4f, ring)
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = fg
                textSize = size * 0.50f
                textAlign = Paint.Align.CENTER
            }
            val fm = tp.fontMetrics
            val baseline = size / 2f - (fm.ascent + fm.descent) / 2f
            c.drawText(emoji, size / 2f, baseline, tp)
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
