package com.gakj.weilie.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gakj.weilie.core.cellmap.CellSite as KnownCell
import com.gakj.weilie.core.model.SampleRecord
import com.gakj.weilie.core.model.TargetCell
import com.gakj.weilie.core.rating.SignalRating
import com.gakj.weilie.core.sync.MemberView
import com.gakj.weilie.core.sync.WsMsg
import com.gakj.weilie.ui.theme.WColors
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max

/**
 * 轻量自绘地图（禁用百度/高德 SDK 的合规方案）：
 * 经纬度↔屏幕像素的等距圆柱投影 + 双指缩放/拖拽 + Canvas 分层绘制
 * 网格/任务圈/轨迹/样本着色/拟合边界/等值线/队友/标记。
 */
class MapCamera {

    var centerLat by mutableStateOf(39.9042)
    var centerLon by mutableStateOf(116.4074)
    var zoom by mutableFloatStateOf(15f)

    /**
     * 可缩放的上下限。默认是标准 Web Mercator 级别范围，接入百度地图后由渲染层
     * 按 SDK 真实范围（getMinZoomLevel/getMaxZoomLevel - 百度刻度偏移）回填，
     * 保证「捏合到最大」与「点 + 按钮」用的是同一把尺子，不会出现按 + 反而变小的怪象。
     */
    var minZoom by mutableFloatStateOf(MIN_ZOOM)
        private set
    var maxZoom by mutableFloatStateOf(MAX_ZOOM)
        private set

    /** 由地图 SDK 回填真实缩放范围（对齐后 push/pull 完全对称） */
    fun setZoomRange(min: Float, max: Float) {
        val lo = min.coerceIn(2f, 24f)
        val hi = max.coerceIn(lo, 24f)
        minZoom = lo
        maxZoom = hi
        zoom = zoom.coerceIn(minZoom, maxZoom)
    }

    /**
     * 视图版本号：任何"程序主动移动相机"都会 +1（拖拽/缩放/定位/全览）。
     * 百度地图渲染层监听它来同步 MapStatus；
     * 用户在百度地图上手势拖动时走 [syncFromMap]（不递增），避免来回打架。
     */
    var revision by androidx.compose.runtime.mutableIntStateOf(0)
        private set

    private fun touch() {
        revision++
    }

    /** 由地图 SDK 反向同步（用户手势拖动地图），不递增 revision 以免回环 */
    fun syncFromMap(lat: Double, lon: Double, z: Float) {
        centerLat = lat.coerceIn(-85.0, 85.0)
        centerLon = lon.coerceIn(-180.0, 180.0)
        zoom = z.coerceIn(minZoom, maxZoom)
    }

    /** 当前缩放级别下每像素代表多少米 */
    val metersPerPixel: Double
        get() = 156543.03392 * cos(Math.toRadians(centerLat)) /
            Math.pow(2.0, zoom.toDouble())

    /** 手指拖拽（px）→ 中心点移动；手指向右拖内容右移即中心西移 */
    fun panBy(dxPx: Double, dyPx: Double) {
        val mpp = metersPerPixel
        centerLon -= dxPx * mpp / (111_320.0 * cos(Math.toRadians(centerLat)))
        centerLat += dyPx * mpp / 110_540.0
        clamp()
    }

    fun zoomBy(factor: Float) {
        zoom = (zoom * factor).coerceIn(minZoom, maxZoom)
        touch()
    }

    fun focusOn(lat: Double, lon: Double, z: Float? = null) {
        centerLat = lat
        centerLon = lon
        z?.let { zoom = it.coerceIn(minZoom, maxZoom) }
        clamp()
    }

    /**
     * 让一组经纬度点全部可见（全览）。
     *
     * [maxFitZoom] 是自动取景的缩放上限：只有 1~2 个几乎重合的点时，
     * 从 21 级开始递归会一路停在上限，等于"一有采样就拉到最大缩放"，
     * 既看不出周边环境，也更容易踩到比例尺宽度溢出（Compose Constraints 262143px）的问题。
     */
    fun fit(points: List<Pair<Double, Double>>, maxFitZoom: Float = 18f) {
        if (points.isEmpty()) return
        val minLat = points.minOf { it.first }
        val maxLat = points.maxOf { it.first }
        val minLon = points.minOf { it.second }
        val maxLon = points.maxOf { it.second }
        centerLat = (minLat + maxLat) / 2
        centerLon = (minLon + maxLon) / 2
        val spanLatM = max((maxLat - minLat) * 110_540.0, 1.0)
        val spanLonM = max(
            (maxLon - minLon) * 111_320.0 * cos(Math.toRadians(centerLat)),
            1.0,
        )
        val spanM = max(spanLatM, spanLonM * 1.2)
        var z = 21.0
        while (z > minZoom &&
            156543.03392 * cos(Math.toRadians(centerLat)) / Math.pow(2.0, z) * 600 < spanM
        ) {
            z -= 0.5
        }
        zoom = z.toFloat().coerceIn(minZoom, minOf(maxZoom, maxFitZoom))
        clamp()
    }

    private fun clamp() {
        centerLat = centerLat.coerceIn(-85.0, 85.0)
        centerLon = centerLon.coerceIn(-180.0, 180.0)
        touch()
    }

    companion object {
        const val MIN_ZOOM = 10f
        const val MAX_ZOOM = 21f
    }
}

/** 屏幕坐标 → 经纬度 */
fun screenToLatLon(
    x: Double,
    y: Double,
    camera: MapCamera,
    width: Double,
    height: Double,
): Pair<Double, Double> {
    val mpp = camera.metersPerPixel
    val lon = camera.centerLon +
        (x - width / 2) * mpp / (111_320.0 * cos(Math.toRadians(camera.centerLat)))
    val lat = camera.centerLat - (y - height / 2) * mpp / 110_540.0
    return lat to lon
}

private fun DrawScope.project(lat: Double, lon: Double, camera: MapCamera): Offset {
    val mpp = camera.metersPerPixel
    val x = (
        size.width / 2f +
            (lon - camera.centerLon) * 111_320.0 * cos(Math.toRadians(camera.centerLat)) / mpp
        ).toFloat()
    val y = (
        size.height / 2f -
            (lat - camera.centerLat) * 110_540.0 / mpp
        ).toFloat()
    return Offset(x, y)
}

private fun DrawScope.ringPath(
    points: List<Pair<Double, Double>>,
    camera: MapCamera,
    close: Boolean,
): Path {
    val p = Path()
    points.forEachIndexed { i, (lat, lon) ->
        val o = project(lat, lon, camera)
        if (i == 0) p.moveTo(o.x, o.y) else p.lineTo(o.x, o.y)
    }
    if (close && points.isNotEmpty()) p.close()
    return p
}

private fun graticuleStep(mpp: Double): Double {
    val targetMeters = mpp * 90.0 // 网格线间距约 90px
    val steps = listOf(
        0.0001, 0.0002, 0.0005, 0.001, 0.002, 0.005,
        0.01, 0.02, 0.05, 0.1, 0.2, 0.5, 1.0, 2.0, 5.0,
    )
    return steps.firstOrNull { it * 110_540.0 >= targetMeters } ?: 5.0
}

@Composable
fun CanvasMap(
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
    /** 自由绘制中的手指轨迹（WGS84）：实时折线预览 */
    draftPath: List<Pair<Double, Double>> = emptyList(),
    camera: MapCamera = remember { MapCamera() },
    onTapLatLon: ((Double, Double) -> Unit)? = null,
    /** 当前位置（WGS84）：由地图引擎自己投影绘制，避免用相机公式在 Compose 层画导致与底图不同步 */
    selfLatLon: Pair<Double, Double>? = null,
    /** 轨迹方向箭头图层 */
    trackArrows: Boolean = false,
    /** 基站站址图层 */
    cellSites: List<CellSite> = emptyList(),
    /** 逐点分类显示：主区命中实心 / 邻区命中彩色实心 / 脱离目标空心 */
    sampleMarks: Boolean = true,
    /** 封闭区域（凹包 / 条带） */
    area: ClosedArea? = null,
    /** 基站理论覆盖区 */
    theories: List<TheoryShape> = emptyList(),
    /** 热力图图层（Canvas 兜底路径按下采样点色块绘制） */
    heat: HeatLayer? = null,
    /** 基站库（OpenCelliD 众包坐标）图层 */
    cellLibrary: List<KnownCell> = emptyList(),
    /** 基站库中当前选中的站址 key */
    selectedCellKey: String? = null,
    /** 基站库站址被点击 */
    onCellSiteClick: ((KnownCell) -> Unit)? = null,
    /** 自由绘制参数（Canvas 兜底路径不拦截手势，仅保持签名一致） */
    drawMode: Boolean = false,
    onDrawPoint: ((Double, Double) -> Unit)? = null,
    /** 标点（拍照/可疑设备）被点击：回传原始 WsMsg.Marker（Canvas 兜底路径用命中测试实现） */
    onMarkerClick: ((WsMsg.Marker) -> Unit)? = null,
) {
    val textMeasurer = rememberTextMeasurer()

    Canvas(
        modifier = modifier
            .pointerInput(camera) {
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    if (gestureZoom != 1f) camera.zoomBy(gestureZoom)
                    camera.panBy(pan.x.toDouble(), pan.y.toDouble())
                }
            }
            .pointerInput(onTapLatLon, onMarkerClick, markers, cellLibrary) {
                detectTapGestures { offset ->
                    // 先做标点命中测试（16dp 内视为点中图标），未命中再当作地图点选。
                    // 24dp 时用户反馈「点标点旁边的空白也会弹出详情」，收紧到图标本身大小。
                    val hitR = 16.dp.toPx()
                    val mpp = camera.metersPerPixel.coerceAtLeast(0.01)
                    val cx = size.width / 2.0
                    val cy = size.height / 2.0
                    val kx = 111320.0 * kotlin.math.cos(Math.toRadians(camera.centerLat)) / mpp
                    val ky = 110540.0 / mpp
                    val hit = markers.lastOrNull { mk ->
                        val dx = cx + (mk.lon - camera.centerLon) * kx - offset.x
                        val dy = cy - (mk.lat - camera.centerLat) * ky - offset.y
                        dx * dx + dy * dy <= hitR * hitR
                    }
                    val cellHit = cellLibrary.lastOrNull { site ->
                        val dx = cx + (site.lon - camera.centerLon) * kx - offset.x
                        val dy = cy - (site.lat - camera.centerLat) * ky - offset.y
                        dx * dx + dy * dy <= hitR * hitR
                    }
                    val cellCb = onCellSiteClick
                    val cb = onMarkerClick
                    if (cellHit != null && cellCb != null) {
                        cellCb(cellHit)
                    } else if (hit != null && cb != null) {
                        cb(hit)
                    } else {
                        val p = screenToLatLon(
                            offset.x.toDouble(), offset.y.toDouble(),
                            camera, size.width.toDouble(), size.height.toDouble(),
                        )
                        onTapLatLon?.invoke(p.first, p.second)
                    }
                }
            },
    ) {
        // 1. 经纬网格
        drawGraticule(camera)
        // 2. 队长下发的任务范围（单圈与多块分片均支持）
        zone?.let { drawZone(it, camera) }
        zones.forEach { if (it !== zone) drawZone(it, camera) }
        // 2.1 自由绘制中的手指轨迹（实时折线）
        if (draftPath.size >= 2) {
            val pts = draftPath.map { project(it.first, it.second, camera) }
            val path = Path()
            path.moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
            drawPath(path, WColors.Secondary, style = Stroke(width = 3.dp.toPx()))
        }
        // 3. 拟合边界（缓冲带填充 → 凸包 → 等值线）
        bufferedLatLon?.takeIf { it.size >= 3 }?.let {
            drawPath(ringPath(it, camera, true), WColors.Primary.copy(alpha = 0.10f))
        }
        hullLatLon?.takeIf { it.size >= 3 }?.let {
            drawPath(
                ringPath(it, camera, true),
                WColors.Primary.copy(alpha = 0.9f),
                style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.cornerPathEffect(6f)),
            )
        }
        isolines.forEach { line ->
            if (line.size >= 3) drawPath(
                ringPath(line, camera, false),
                WColors.Secondary.copy(alpha = 0.85f),
                style = Stroke(width = 1.5.dp.toPx()),
            )
        }
        // 3.5 信号热力图（Canvas 兜底：按包围盒加一层暖光，细粒度交给百度底图路径）
        heat?.let { h ->
            val a = project(h.maxLat, h.minLon, camera)
            val b = project(h.minLat, h.maxLon, camera)
            val l = minOf(a.x, b.x)
            val t = minOf(a.y, b.y)
            drawRect(
                Color(0x22F59E0B),
                topLeft = Offset(l, t),
                size = Size(abs(b.x - a.x), abs(b.y - a.y)),
            )
        }
        // 3.6 封闭区域（凹包 / 条带）：半透明填充不遮底图
        area?.ring?.takeIf { it.size >= 3 }?.let {
            drawPath(ringPath(it, camera, true), Color(0x5534D399))
            drawPath(
                ringPath(it, camera, true),
                Color(0xCC34D399.toInt()),
                style = Stroke(width = 2.dp.toPx()),
            )
        }
        // 3.7 基站理论覆盖区（蓝=理论 / 橙=TA 距离带）
        theories.forEach { t ->
            when (t.kind) {
                TheoryShape.Kind.CIRCLE -> {
                    val o = project(t.centerLat, t.centerLon, camera)
                    val r = (t.radiusM / camera.metersPerPixel).toFloat()
                    drawCircle(Color(0x1F3B82F6), radius = r, center = o)
                    drawCircle(Color(0xCC3B82F6.toInt()), radius = r, center = o, style = Stroke(1.5.dp.toPx()))
                }
                TheoryShape.Kind.RING -> {
                    val o = project(t.centerLat, t.centerLon, camera)
                    val rOut = (t.radiusM / camera.metersPerPixel).toFloat()
                    val rIn = (t.innerRadiusM / camera.metersPerPixel).toFloat()
                    drawCircle(Color(0x33FB923C), radius = rOut, center = o, style = Stroke((rOut - rIn).coerceAtLeast(2f)))
                }
                TheoryShape.Kind.SECTOR -> {
                    val o = project(t.centerLat, t.centerLon, camera)
                    val r = (t.radiusM / camera.metersPerPixel).toFloat()
                    drawArc(
                        Color(0x1F3B82F6),
                        startAngle = t.startBearing - 90f,
                        sweepAngle = t.sweepDeg,
                        useCenter = true,
                        topLeft = Offset(o.x - r, o.y - r),
                        size = Size(r * 2, r * 2),
                    )
                }
            }
        }
        // 4. 目标参考圈 + 十字准星
        target?.let { drawTarget(it, camera) }
        // 5. 轨迹
        drawTrajectory(samples, camera)
        // 6. 样本点：逐点分类（主区实心 / 邻区彩色实心 / 脱离空心）
        if (sampleMarks) {
            samples.forEach { s ->
                val o = project(s.lat, s.lon, camera)
                if (o.x < -40 || o.y < -40 || o.x > size.width + 40 || o.y > size.height + 40) return@forEach
                when (SampleMark.of(s)) {
                    SampleMark.MAIN -> {
                        drawCircle(Color(MarkStyles.MAIN_FILL), radius = 4.5.dp.toPx(), center = o)
                        drawCircle(Color.White, radius = 4.5.dp.toPx(), center = o, style = Stroke(1.dp.toPx()))
                    }
                    SampleMark.NEIGHBOR -> {
                        drawCircle(Color(MarkStyles.baseColor(s.servingCi)), radius = 5.dp.toPx(), center = o)
                        drawCircle(Color.White, radius = 5.dp.toPx(), center = o, style = Stroke(1.dp.toPx()))
                    }
                    SampleMark.OUT -> {
                        drawCircle(
                            Color(MarkStyles.OUT_RING),
                            radius = 3.5.dp.toPx(),
                            center = o,
                            style = Stroke(1.5.dp.toPx()),
                        )
                    }
                }
            }
        } else {
            samples.forEach { s ->
                val o = project(s.lat, s.lon, camera)
                if (o.x < -40 || o.y < -40 || o.x > size.width + 40 || o.y > size.height + 40) return@forEach
                val level = SignalRating.level(s.rsrp)
                val c = if (level < 0) Color(0xFF94A3B8) else Color(SignalRating.colorArgb(level).toInt())
                drawCircle(c, radius = 4.dp.toPx(), center = o)
                drawCircle(Color(0xB30B1020), radius = 4.dp.toPx(), center = o, style = Stroke(1.dp.toPx()))
                if (s.onTarget) {
                    drawCircle(WColors.Target, radius = 7.dp.toPx(), center = o, style = Stroke(1.5.dp.toPx()))
                }
            }
        }
        // 7. 队友
        members.forEach { m ->
            val la = m.lat ?: return@forEach
            val lo = m.lon ?: return@forEach
            val o = project(la, lo, camera)
            drawCircle(Color(0xB30B1020), radius = 8.dp.toPx(), center = o)
            drawCircle(WColors.Teammate, radius = 6.dp.toPx(), center = o)
            if (m.onTarget) {
                drawCircle(WColors.Teammate, radius = 10.dp.toPx(), center = o, style = Stroke(1.5.dp.toPx()))
            }
            drawText(
                textMeasurer = textMeasurer,
                text = m.name,
                topLeft = Offset(o.x - 40f, o.y + 8.dp.toPx()),
                style = TextStyle(color = WColors.Teammate, fontSize = 9.sp),
            )
        }
        // 8. 标记（取证类型 / 手动旗标）——与百度渲染保持同一套语义：
        //    彩色圆底 + emoji，一眼区分普通取证/隐蔽摄像头/可疑摄像头/可疑设备
        markers.forEach { mk ->
            val o = project(mk.lat, mk.lon, camera)
            val visual = mk.visualKind()
            val r = 9.dp.toPx()
            drawCircle(Color(0x330B1020), radius = r + 1.dp.toPx(), center = Offset(o.x, o.y + 1.dp.toPx()))
            drawCircle(Color(visual.bg), radius = r, center = o)
            drawCircle(Color.White, radius = r, center = o, style = Stroke(1.5.dp.toPx()))
            drawText(
                textMeasurer = textMeasurer,
                text = visual.emoji,
                topLeft = Offset(o.x - 5.dp.toPx(), o.y - 7.dp.toPx()),
                style = TextStyle(fontSize = 11.sp),
            )
            val shown = mk.name.trim().ifEmpty { "" }
            if (shown.isNotEmpty()) {
                drawText(
                    textMeasurer = textMeasurer,
                    text = if (shown.length > 6) shown.take(6) + "…" else shown,
                    topLeft = Offset(o.x + 8.dp.toPx(), o.y - 18.dp.toPx()),
                    style = TextStyle(color = WColors.OnBg, fontSize = 10.sp),
                )
            }
        }
        // 8.5 基站库站址（OpenCelliD）：制式着色小点，选中态加白环
        if (cellLibrary.isNotEmpty()) {
            val maxSites = 200
            val step = if (cellLibrary.size > maxSites) (cellLibrary.size / maxSites) + 1 else 1
            cellLibrary.forEachIndexed { i, site ->
                if (i % step != 0) return@forEachIndexed
                if (!site.isValid()) return@forEachIndexed
                val o = project(site.lat, site.lon, camera)
                if (o.x < -30 || o.y < -30 || o.x > size.width + 30 || o.y > size.height + 30) return@forEachIndexed
                val color = cellSiteColor(site.rat)
                val selected = selectedCellKey != null && site.key == selectedCellKey
                val r = if (selected) 9.dp.toPx() else 6.dp.toPx()
                drawCircle(color.copy(alpha = 0.85f), radius = r, center = o)
                drawCircle(Color.White, radius = r, center = o, style = Stroke(1.5.dp.toPx()))
                if (selected) drawCircle(color, radius = r + 3.dp.toPx(), center = o, style = Stroke(2.dp.toPx()))
            }
        }
        // 9. 基站站址（基站图层）
        cellSites.forEach { site ->
            val o = project(site.lat, site.lon, camera)
            if (o.x < -30 || o.y < -30 || o.x > size.width + 30 || o.y > size.height + 30) return@forEach
            drawCircle(Color(0x66F59E0B), radius = 10.dp.toPx(), center = o)
            drawCircle(Color(0xFFF59E0B), radius = 10.dp.toPx(), center = o, style = Stroke(1.5.dp.toPx()))
            drawCircle(Color(0xFFF59E0B), radius = 1.8.dp.toPx(), center = o)
        }
        // 10. 轨迹方向箭头
        if (trackArrows && samples.size >= 2) {
            val spacing = 56.dp.toPx()
            var acc = spacing
            var prev: Offset? = null
            samples.forEach { s ->
                val p = project(s.lat, s.lon, camera)
                val q = prev
                if (q != null) {
                    acc += hypot((p.x - q.x).toDouble(), (p.y - q.y).toDouble()).toFloat()
                    if (acc >= spacing) {
                        acc = 0f
                        val deg = Math.toDegrees(
                            atan2((p.y - q.y).toDouble(), (p.x - q.x).toDouble()),
                        ).toFloat()
                        val cx = (p.x + q.x) / 2f
                        val cy = (p.y + q.y) / 2f
                        val s2 = 5.dp.toPx()
                        rotate(degrees = deg, pivot = Offset(cx, cy)) {
                            val tri = Path().apply {
                                moveTo(cx + s2 * 1.7f, cy)
                                lineTo(cx - s2, cy - s2 * 0.9f)
                                lineTo(cx - s2, cy + s2 * 0.9f)
                                close()
                            }
                            drawPath(tri, Color(0xFFA78BFA))
                        }
                    }
                }
                prev = p
            }
        }
        // 11. 当前位置（GPS 点）：与底图同一投影，拖动时严格 1:1 跟随
        selfLatLon?.let { (la, lo) ->
            val o = project(la, lo, camera)
            if (o.x in -40f..(size.width + 40f) && o.y in -40f..(size.height + 40f)) {
                drawCircle(WColors.Track.copy(alpha = 0.16f), radius = 18.dp.toPx(), center = o)
                drawCircle(Color.White, radius = 8.dp.toPx(), center = o)
                drawCircle(WColors.Track, radius = 5.5.dp.toPx(), center = o)
            }
        }
    }
}

private fun DrawScope.drawGraticule(camera: MapCamera) {
    val step = graticuleStep(camera.metersPerPixel)
    val halfLonDeg = size.width * camera.metersPerPixel /
        (111_320.0 * cos(Math.toRadians(camera.centerLat)))
    val halfLatDeg = size.height * camera.metersPerPixel / 110_540.0
    val gridColor = WColors.GridLine

    var lon = floor((camera.centerLon - halfLonDeg) / step) * step
    while (lon <= camera.centerLon + halfLonDeg + step) {
        val x = project(camera.centerLat, lon, camera).x
        drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
        lon += step
    }
    var lat = floor((camera.centerLat - halfLatDeg) / step) * step
    while (lat <= camera.centerLat + halfLatDeg + step) {
        val y = project(lat, camera.centerLon, camera).y
        drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        lat += step
    }
}

private fun DrawScope.drawTarget(t: TargetCell, camera: MapCamera) {
    val la = t.centerLat ?: return
    val lo = t.centerLon ?: return
    val o = project(la, lo, camera)
    if (o.x < -200 || o.y < -200 || o.x > size.width + 200 || o.y > size.height + 200) return
    val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
    t.givenRadiusM?.let { r ->
        val rPx = (r / camera.metersPerPixel).toFloat()
        if (rPx > 2f) {
            drawCircle(
                WColors.Target.copy(alpha = 0.9f), radius = rPx, center = o,
                style = Stroke(width = 2.dp.toPx(), pathEffect = dash),
            )
            drawCircle(WColors.Target.copy(alpha = 0.07f), radius = rPx, center = o)
        }
    }
    // 十字准星
    val arm = 12.dp.toPx()
    drawLine(WColors.Target, Offset(o.x - arm, o.y), Offset(o.x + arm, o.y), 2.dp.toPx())
    drawLine(WColors.Target, Offset(o.x, o.y - arm), Offset(o.x, o.y + arm), 2.dp.toPx())
    drawCircle(WColors.Target, radius = 4.dp.toPx(), center = o, style = Stroke(2.dp.toPx()))
}

private fun DrawScope.drawZone(z: WsMsg.Zone, camera: MapCamera) {
    when (z.kind) {
        "CIRCLE" -> {
            val la = z.centerLat ?: return
            val lo = z.centerLon ?: return
            val r = z.radiusM ?: return
            val o = project(la, lo, camera)
            val rPx = (r / camera.metersPerPixel).toFloat()
            if (rPx > 2f) {
                drawCircle(
                    WColors.Secondary.copy(alpha = 0.9f), radius = rPx, center = o,
                    style = Stroke(
                        width = 2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
                    ),
                )
            }
        }
        "POLYGON" -> {
            val pts = z.polygon.mapNotNull { p ->
                if (p.size >= 2) p[0] to p[1] else null
            }
            if (pts.size >= 3) {
                drawPath(ringPath(pts, camera, true), WColors.Secondary.copy(alpha = 0.08f))
                drawPath(
                    ringPath(pts, camera, true), WColors.Secondary,
                    style = Stroke(
                        width = 2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
                    ),
                )
            }
        }
    }
}

private fun DrawScope.drawTrajectory(samples: List<SampleRecord>, camera: MapCamera) {
    if (samples.size < 2) return
    // 命中段（主区/邻区）用轨迹蓝，脱离目标段用灰色：让"走出去的那一段"一眼可辨
    val hitColor = WColors.Track.copy(alpha = 0.75f)
    val outColor = Color(0xFF94A3B8).copy(alpha = 0.85f)
    fun drawRun(from: Int, to: Int, isOut: Boolean) {
        if (to - from < 1) return
        val p = Path()
        for (i in from..to) {
            val o = project(samples[i].lat, samples[i].lon, camera)
            if (i == from) p.moveTo(o.x, o.y) else p.lineTo(o.x, o.y)
        }
        drawPath(
            p,
            if (isOut) outColor else hitColor,
            style = Stroke(width = if (isOut) 1.8.dp.toPx() else 2.dp.toPx()),
        )
    }
    var runStart = 0
    var runOut = SampleMark.of(samples[0]) == SampleMark.OUT
    for (i in 1 until samples.size) {
        val out = SampleMark.of(samples[i]) == SampleMark.OUT
        if (out != runOut) {
            drawRun(runStart, i, runOut)
            runStart = i
            runOut = out
        }
    }
    drawRun(runStart, samples.lastIndex, runOut)
}
