package com.gakj.weilie.ui.camera

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.baidu.mapapi.model.LatLng
import com.baidu.mapapi.search.core.SearchResult
import com.baidu.mapapi.search.geocode.GeoCodeResult
import com.baidu.mapapi.search.geocode.GeoCoder
import com.baidu.mapapi.search.geocode.OnGetGeoCoderResultListener
import com.baidu.mapapi.search.geocode.ReverseGeoCodeOption
import com.baidu.mapapi.search.geocode.ReverseGeoCodeResult
import com.baidu.mapapi.search.weather.OnGetWeatherResultListener
import com.baidu.mapapi.search.weather.WeatherDataType
import com.baidu.mapapi.search.weather.WeatherResult
import com.baidu.mapapi.search.weather.WeatherSearch
import com.baidu.mapapi.search.weather.WeatherSearchOption
import com.gakj.weilie.core.photo.PhotoRecord
import com.gakj.weilie.core.telephony.CellWatermark
import com.gakj.weilie.ui.AppGraph
import com.gakj.weilie.ui.common.GlassIconButton
import com.gakj.weilie.ui.common.GlassTextField
import com.gakj.weilie.ui.common.hasPerm
import com.gakj.weilie.ui.common.toast
import com.gakj.weilie.ui.common.vibrate
import com.gakj.weilie.ui.map.BaiduBootstrap
import com.gakj.weilie.ui.map.BaiduCoords
import com.gakj.weilie.ui.theme.GlowButton
import com.gakj.weilie.ui.theme.Motion
import com.gakj.weilie.ui.theme.OutlineButton
import com.gakj.weilie.ui.theme.Radius
import com.gakj.weilie.ui.theme.Space
import com.gakj.weilie.ui.theme.WColors
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 取证类型 chip：kind + deviceType 的组合（与 [PhotoRecord] 落盘口径保持一致）。
 * 普通拍照 kind=PHOTO；可疑设备 kind=SUSPICIOUS 并带设备类型编码。
 */
private data class PhotoKindChip(val label: String, val kind: String, val deviceType: String)

private val PHOTO_KIND_CHIPS = listOf(
    PhotoKindChip("普通取证", "PHOTO", ""),
    PhotoKindChip("隐蔽摄像头", "SUSPICIOUS", "HIDDEN_CAM"),
    PhotoKindChip("可疑摄像头", "SUSPICIOUS", "SUSPICIOUS_CAM"),
    PhotoKindChip("可疑设备", "SUSPICIOUS", "SUSPICIOUS_DEVICE"),
    PhotoKindChip("其他", "PHOTO", "OTHER"),
)

/**
 * 水印数据：屏幕实时块、烧进照片、[PhotoRecord.lines] 三处都从这里渲染，保证同源。
 *
 * @param title          第一行「备注地点 / 标题」（可编辑，默认取任务标题）
 * @param timeHm         超大时间 HH:mm
 * @param date           yyyy-MM-dd
 * @param weekdayWeather 星期 + 天气（天气来自百度天气 SDK，拿不到时只有星期）
 * @param address        逆地理编码得到的「省市区·POI」地址行
 * @param cellLines      蜂窝技术块（[CellWatermark.flatLines] 原样输出）
 * @param hasFix         是否拿到可信定位；false 时水印尾行给黄字提示
 */
private data class WatermarkSpec(
    val title: String,
    val timeHm: String,
    val date: String,
    val weekdayWeather: String,
    val address: String,
    val cellLines: List<String>,
    val hasFix: Boolean,
)

/** 水印文字阴影：亮景下白字也要看得清（屏幕 / 烧图同一观感） */
private val WmShadowStyle = TextStyle(
    shadow = Shadow(color = Color(0x99000000), offset = Offset(0f, 2f), blurRadius = 8f),
)

private const val WM_LABEL = "围猎 · 现场数据"
private const val WM_NO_FIX = "未获取到定位，标记将落在近似点"
private const val WM_RESOLVING = "定位解析中…"
private const val WM_FALLBACK_TITLE = "现场取证"

// 地址解析：百度 15s 无回调即降级；系统 Geocoder 5s 超时；50m / 30s 节流。
private const val GEO_TAG = "WeiLieGeo"
private const val BAIDU_GEO_TIMEOUT_MS = 15_000L
private const val SYSTEM_GEO_TIMEOUT_MS = 5_000L
private const val GEO_MOVE_M = 50.0
private const val GEO_REFRESH_MS = 30_000L

/**
 * 现场取证相机（全屏 CameraX，微信水印相机风格）。
 *
 * 设计要点：
 * - 水印三处同源：[buildWatermarkLines] 产出的行 = 屏幕实时块 = 烧进照片的文字，
 *   第一行备注、超大时间、日期/星期天气、逆地理地址、蜂窝技术块；
 * - 地址用百度 SDK 逆地理编码（WGS84→BD09），50m/30s 节流，失败只显示「定位解析中…」，绝不阻塞相机；
 * - 拍照链路全程 runCatching：相机不可用 / 权限被拒 / 编码失败都只 toast，绝不 FATAL；
 * - 照片落 [AppGraph.photoStore]，file 字段存纯文件名（PhotoStore.fileOf 约定）。
 *
 * @param initialKind 预选 chip 的 kind（PHOTO / SUSPICIOUS），默认普通取证
 * @param currentLat 当前纬度（无定位时也照常传，由 hasFix 标记可信度）
 * @param currentLon 当前经度
 * @param hasFix 是否拿到可信定位；false 时水印尾部加黄字提示
 * @param onCaptured 拍照并落盘成功后的回调（调用方负责导航/刷新）
 * @param taskId 照片归属的任务 id（单人=task.id，多人="room-<roomId>"）；用于「换任务不显示旧标点」
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PhotoCameraScreen(
    initialKind: String = "PHOTO",
    currentLat: Double,
    currentLon: Double,
    hasFix: Boolean,
    taskId: String = "",
    onClose: () -> Unit,
    onCaptured: (PhotoRecord) -> Unit,
    onOpenGallery: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val encodeExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) {
        onDispose { runCatching { encodeExecutor.shutdown() } }
    }

    // ---------- 相机权限 ----------
    var granted by remember { mutableStateOf(hasPerm(ctx, Manifest.permission.CAMERA)) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
    }
    LaunchedEffect(Unit) {
        if (!granted) runCatching { permLauncher.launch(Manifest.permission.CAMERA) }
    }

    // ---------- 相机与拍摄状态 ----------
    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
    var flashOn by remember { mutableStateOf(false) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var capturing by remember { mutableStateOf(false) }
    var camError by remember { mutableStateOf<String?>(null) }
    var selectedChip by remember(initialKind) {
        mutableStateOf(PHOTO_KIND_CHIPS.firstOrNull { it.kind == initialKind } ?: PHOTO_KIND_CHIPS.first())
    }

    // ---------- 备注标题（第一行，可编辑，写进 PhotoRecord.note） ----------
    val defaultTitle = remember {
        runCatching { AppGraph.currentController?.task?.title?.trim() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: WM_FALLBACK_TITLE
    }
    var wmTitle by remember { mutableStateOf(defaultTitle) }
    var titleEdited by remember { mutableStateOf(false) }
    var noteDialogOpen by remember { mutableStateOf(false) }
    var noteInput by remember { mutableStateOf("") }

    // ---------- 地址 / 天气（百度 SDK + 系统 Geocoder 兜底，失败不阻塞相机） ----------
    var addressLine by remember { mutableStateOf(WM_RESOLVING) }
    var weatherText by remember { mutableStateOf("") }
    var lastGeoAt by remember { mutableStateOf(0L) }
    var lastGeoLat by remember { mutableStateOf(Double.NaN) }
    var lastGeoLon by remember { mutableStateOf(Double.NaN) }
    var geoPending by remember { mutableStateOf(false) }        // 百度请求在途
    var geoSource by remember { mutableStateOf("") }            // "" / "baidu" / "system"
    var baiduFailed by remember { mutableStateOf(false) }       // 百度失败后本会话优先系统
    var systemPending by remember { mutableStateOf(false) }     // 系统 Geocoder 在途
    var systemAt by remember { mutableStateOf(0L) }
    val systemToken = remember { AtomicInteger(0) }
    val systemGeocoder = remember {
        val present = runCatching { Geocoder.isPresent() }.getOrDefault(false)
        Log.d(GEO_TAG, "system=present $present")
        runCatching { Geocoder(ctx, Locale.SIMPLIFIED_CHINESE) }.getOrNull()
    }

    // 系统 Geocoder 结果唯一出口：只认中文地址，英文/空一律保持「定位解析中…」
    fun applySystemAddress(raw: String?) {
        val line = raw?.trim().orEmpty()
        when {
            line.isBlank() -> Log.d(GEO_TAG, "system=fail empty")
            !line.any { it.code in 0x4E00..0x9FFF } -> Log.d(GEO_TAG, "system=fail non-cjk")
            else -> {
                addressLine = line
                geoSource = "system"
                Log.d(GEO_TAG, "system=ok $line")
            }
        }
    }

    val geoCoder = remember {
        runCatching {
            BaiduBootstrap.init(ctx)
            GeoCoder.newInstance()
        }.getOrNull()
    }
    val weatherSearch = remember { runCatching { WeatherSearch.newInstance() }.getOrNull() }

    val geoListener = remember {
        object : OnGetGeoCoderResultListener {
            override fun onGetGeoCodeResult(result: GeoCodeResult) {
                // 只用逆地理，正向结果忽略
            }

            override fun onGetReverseGeoCodeResult(result: ReverseGeoCodeResult) {
                runCatching {
                    if (result.error == SearchResult.ERRORNO.NO_ERROR) {
                        val line = formatReverseAddress(result)
                        if (line.isNotBlank()) {
                            addressLine = line
                            geoSource = "baidu"
                            Log.d(GEO_TAG, "baidu=ok $line")
                        }
                    } else {
                        // 鉴权失败（AK 230）/ 无结果 → 立刻降级系统 Geocoder
                        baiduFailed = true
                        lastGeoAt = 0L
                        Log.d(GEO_TAG, "baidu=fail ${result.error}")
                    }
                }
                geoPending = false
            }
        }
    }
    val weatherListener = remember {
        object : OnGetWeatherResultListener {
            override fun onGetWeatherResultListener(result: WeatherResult) {
                runCatching {
                    if (result.error != SearchResult.ERRORNO.NO_ERROR) return@runCatching
                    val rt = result.realTimeWeather ?: return@runCatching
                    val phenomenon = (rt.phenomenon ?: "").trim()
                    if (phenomenon.isNotBlank()) weatherText = "$phenomenon ${rt.temperature}℃"
                }
            }
        }
    }
    DisposableEffect(Unit) {
        runCatching { geoCoder?.setOnGetGeoCodeResultListener(geoListener) }
        runCatching { weatherSearch?.setWeatherSearchResultListener(weatherListener) }
        onDispose {
            runCatching { geoCoder?.destroy() }
            runCatching { weatherSearch?.destroy() }
        }
    }

    val curLat = rememberUpdatedState(currentLat)
    val curLon = rememberUpdatedState(currentLon)
    val curHasFix = rememberUpdatedState(hasFix)
    LaunchedEffect(Unit) {
        while (true) {
            runCatching {
                val now = System.currentTimeMillis()
                // 百度 15s 无回调 → 判失败并放行下一轮，立即降级系统 Geocoder
                if (geoPending && now - lastGeoAt > BAIDU_GEO_TIMEOUT_MS) {
                    geoPending = false
                    if (!baiduFailed) {
                        baiduFailed = true
                        lastGeoAt = 0L
                        Log.d(GEO_TAG, "baidu=fail timeout")
                    }
                }
                // 系统 5s 无回调 → 作废本次（迟到的回调靠 token 丢弃）
                if (systemPending && now - systemAt > SYSTEM_GEO_TIMEOUT_MS) {
                    systemPending = false
                    systemToken.incrementAndGet()
                    Log.d(GEO_TAG, "system=fail timeout")
                }
                if (!curHasFix.value) return@runCatching
                val moved = distanceMeters(lastGeoLat, lastGeoLon, curLat.value, curLon.value)
                val stale = now - lastGeoAt >= GEO_REFRESH_MS
                val due = lastGeoAt == 0L || moved >= GEO_MOVE_M || stale
                if (!due || geoPending || systemPending) return@runCatching
                lastGeoAt = now
                lastGeoLat = curLat.value
                lastGeoLon = curLon.value
                // 系统 Geocoder 成功过 / 百度已失败 → 直接走系统（真机更快，也不依赖 AK）
                val useSystem = geoSource == "system" || baiduFailed
                if (useSystem) {
                    val geo = systemGeocoder
                    if (geo == null) {
                        Log.d(GEO_TAG, "system=fail no-geocoder")
                        return@runCatching
                    }
                    val lat = curLat.value
                    val lon = curLon.value
                    val token = systemToken.incrementAndGet()
                    systemPending = true
                    systemAt = now
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        runCatching {
                            geo.getFromLocation(lat, lon, 1, object : Geocoder.GeocodeListener {
                                override fun onGeocode(addresses: MutableList<Address>) {
                                    mainHandler.post {
                                        if (systemToken.get() != token) {
                                            Log.d(GEO_TAG, "system=late-drop")
                                            return@post
                                        }
                                        systemPending = false
                                        applySystemAddress(
                                            addresses.firstOrNull()?.let(::formatSystemAddress),
                                        )
                                    }
                                }

                                override fun onError(errorMessage: String?) {
                                    mainHandler.post {
                                        if (systemToken.get() != token) {
                                            Log.d(GEO_TAG, "system=late-drop")
                                            return@post
                                        }
                                        systemPending = false
                                        Log.d(GEO_TAG, "system=fail ${errorMessage ?: "error"}")
                                    }
                                }
                            })
                        }.onFailure { e ->
                            systemPending = false
                            Log.d(GEO_TAG, "system=fail ${e.message ?: e.javaClass.simpleName}")
                        }
                    } else {
                        scope.launch {
                            val line = geocodeSync(geo, lat, lon)
                            if (systemToken.get() != token) return@launch
                            systemPending = false
                            applySystemAddress(line)
                        }
                    }
                } else {
                    val coder = geoCoder
                    if (coder == null) {
                        baiduFailed = true
                        Log.d(GEO_TAG, "baidu=fail no-coder")
                        return@runCatching
                    }
                    geoPending = true
                    val (bdLat, bdLon) = runCatching {
                        BaiduCoords.wgsToBd09(curLat.value, curLon.value)
                    }.getOrDefault(curLat.value to curLon.value)
                    val loc = LatLng(bdLat, bdLon)
                    runCatching {
                        coder.reverseGeoCode(ReverseGeoCodeOption().location(loc))
                    }.onFailure {
                        geoPending = false
                        baiduFailed = true
                        Log.d(GEO_TAG, "baidu=fail ${it.message ?: it.javaClass.simpleName}")
                    }
                    val ws = weatherSearch
                    if (ws != null) {
                        runCatching {
                            ws.request(
                                WeatherSearchOption()
                                    .location(loc)
                                    .weatherDataType(WeatherDataType.WEATHER_DATA_TYPE_REAL_TIME),
                            )
                        }
                    }
                }
            }
            delay(1_000)
        }
    }

    // ---------- 最近一张缩略图 ----------
    var latest by remember { mutableStateOf<PhotoRecord?>(null) }
    var thumb by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(Unit) {
        latest = withContext(Dispatchers.IO) {
            runCatching { AppGraph.photoStore.latest() }.getOrNull()
        }
    }
    LaunchedEffect(latest?.id) {
        val rec = latest
        thumb = null
        if (rec != null) {
            thumb = withContext(Dispatchers.IO) {
                runCatching {
                    val f = AppGraph.photoStore.fileOf(rec)
                    if (f.isFile && f.length() > 0L) decodeThumbnailFile(f, 120) else null
                }.getOrNull()
            }
        }
    }

    // ---------- 实时水印（500ms 刷新，时间在走） ----------
    var tick by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            tick = System.currentTimeMillis()
        }
    }
    val sims = remember(tick) {
        runCatching { AppGraph.currentController?.snapshot?.value?.sims }.getOrNull() ?: emptyList()
    }
    val spec = remember(tick, wmTitle, addressLine, weatherText, hasFix, sims) {
        val cellLines = runCatching {
            CellWatermark.flatLines(sims, currentLat, currentLon, tick, hasFix = hasFix)
        }.getOrNull() ?: emptyList()
        buildWatermarkSpec(
            ts = tick,
            title = wmTitle,
            weather = weatherText,
            address = addressLine,
            hasFix = hasFix,
            cellLines = cellLines,
        )
    }

    // ---------- 快门反馈 ----------
    val flashAnim = remember { Animatable(0f) }
    val triggerFlash: () -> Unit = {
        scope.launch {
            runCatching {
                flashAnim.snapTo(0.6f)
                flashAnim.animateTo(0f, tween(230))
            }
        }
    }

    // ---------- 拍照 ----------
    val onShutter: () -> Unit = shutter@{
        if (noteDialogOpen) return@shutter
        if (capturing) return@shutter
        val cap = imageCapture
        if (cap == null) {
            toast(ctx, "相机尚未就绪，请稍候")
            return@shutter
        }
        val chip = selectedChip
        val tsNow = System.currentTimeMillis()
        val simsNow = runCatching { AppGraph.currentController?.snapshot?.value?.sims }.getOrNull()
            ?: emptyList()
        val cellLines = runCatching {
            CellWatermark.flatLines(simsNow, currentLat, currentLon, tsNow, hasFix = hasFix)
        }.getOrNull() ?: emptyList()
        // 屏幕 / 照片 / index.jsonl 共用同一份 spec → 同一份 lines
        val shotSpec = buildWatermarkSpec(
            ts = tsNow,
            title = wmTitle,
            weather = weatherText,
            address = addressLine,
            hasFix = hasFix,
            cellLines = cellLines,
        )
        val lines = buildWatermarkLines(shotSpec)
        val note = if (titleEdited) wmTitle else ""
        capturing = true
        triggerFlash()
        runCatching {
            cap.takePicture(
                ContextCompat.getMainExecutor(ctx),
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        val rotation = image.imageInfo.rotationDegrees
                        val bytes = runCatching { jpegBytesOf(image) }.getOrNull()
                        runCatching { image.close() }
                        if (bytes == null) {
                            mainHandler.post {
                                capturing = false
                                toast(ctx, "保存照片失败：照片解码失败")
                            }
                            return
                        }
                        encodeExecutor.execute {
                            val result = runCatching {
                                savePhoto(
                                    bytes = bytes,
                                    rotationDegrees = rotation,
                                    spec = shotSpec,
                                    lines = lines,
                                    chip = chip,
                                    ts = tsNow,
                                    lat = currentLat,
                                    lon = currentLon,
                                    note = note,
                                    taskId = taskId,
                                )
                            }
                            mainHandler.post {
                                capturing = false
                                result.onSuccess { rec ->
                                    latest = rec
                                    vibrate(ctx, 40)
                                    onCaptured(rec)
                                }.onFailure { e ->
                                    toast(ctx, "保存照片失败：${e.message ?: "未知错误"}")
                                }
                            }
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        mainHandler.post {
                            capturing = false
                            toast(ctx, "保存照片失败：${exception.message ?: "拍摄失败"}")
                        }
                    }
                },
            )
        }.onFailure { e ->
            capturing = false
            toast(ctx, "保存照片失败：${e.message ?: "未知错误"}")
        }
    }

    // ---------- 布局 ----------
    // 底部控制区（chips + 快门）实际高度：水印块据此上移，避免两类按钮压在水印上
    var controlsPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            CameraPreview(
                lensFacing = lensFacing,
                onCaptureReady = { cap ->
                    imageCapture = cap
                    camError = null
                    runCatching {
                        cap.flashMode = if (flashOn) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
                    }
                },
                onError = { msg -> camError = msg },
            )

            // 底部渐变压暗：亮景下白字水印也看得清（烧图里同款遮罩）
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(430.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color(0x99000000)),
                        ),
                    ),
            )

            // 左下角水印块（微信水印相机样式，卡片圆角 Radius.lgShape）
            WatermarkBlock(
                spec = spec,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    .padding(
                        start = Space.screenH,
                        bottom = with(density) { controlsPx.toDp() } + Space.x6,
                    )
                    .widthIn(max = 352.dp),
                onEditTitle = {
                    noteInput = wmTitle
                    noteDialogOpen = true
                },
            )

            // 顶部栏：返回 + 品牌 + 闪光 / 切换摄像头（48dp 玻璃圆钮）
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = Space.screenH, vertical = Space.x2),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlassIconButton(icon = Icons.Rounded.Close, size = 48.dp) { onClose() }
                    Spacer(Modifier.weight(1f))
                    GlassIconButton(
                        icon = if (flashOn) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff,
                        size = 48.dp,
                    ) {
                        val next = !flashOn
                        flashOn = next
                        runCatching {
                            imageCapture?.flashMode =
                                if (next) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
                        }
                    }
                    Spacer(Modifier.width(Space.x2))
                    GlassIconButton(icon = Icons.Rounded.Cameraswitch, size = 48.dp) {
                        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                            CameraSelector.LENS_FACING_FRONT
                        } else {
                            CameraSelector.LENS_FACING_BACK
                        }
                        imageCapture = null
                    }
                }
                Text(
                    text = "围猎 · 现场取证",
                    color = WColors.OnBg,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .clip(Radius.pillShape)
                        .background(Color(0x8C000000))
                        .padding(horizontal = Space.x3, vertical = Space.x1),
                )
            }

            // 底部：取证类型 chips + 快门（88dp 白环，按下 0.96）/ 相册缩略图
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .onGloballyPositioned { controlsPx = it.size.height }
                    .padding(bottom = Space.x3),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 类型 chips：选中实心主色 + OnPrimary，未选中 ChipBg + OnBgDim
                FlowRow(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Space.screenH),
                    horizontalArrangement = Arrangement.spacedBy(Space.x2),
                    verticalArrangement = Arrangement.spacedBy(Space.x2),
                    maxItemsInEachRow = 3,
                ) {
                    PHOTO_KIND_CHIPS.forEach { chip ->
                        val on = chip == selectedChip
                        Box(
                            Modifier
                                .clip(Radius.pillShape)
                                .background(if (on) WColors.Primary else WColors.ChipBg)
                                .border(1.dp, if (on) WColors.Primary else WColors.ChipBorder, Radius.pillShape)
                                .clickable { selectedChip = chip }
                                .padding(horizontal = Space.row, vertical = Space.x2),
                        ) {
                            Text(
                                text = chip.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (on) WColors.OnPrimary else WColors.OnBgDim,
                                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(Space.x3))

                Row(
                    Modifier
                        .clip(Radius.pillShape)
                        .background(Color(0x99000000))
                        .padding(horizontal = Space.x4, vertical = Space.x2),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.x8),
                ) {
                    // 左侧留白：让快门在圆条里保持水平居中
                    Spacer(Modifier.size(48.dp))

                    // 快门：88dp 白色环 + 内白圆，按下缩到 0.96
                    val shutterInteraction = remember { MutableInteractionSource() }
                    val shutterPressed by shutterInteraction.collectIsPressedAsState()
                    val shutterScale by animateFloatAsState(
                        targetValue = if (shutterPressed) 0.96f else 1f,
                        animationSpec = Motion.bouncy(),
                        label = "shutterScale",
                    )
                    Box(
                        Modifier
                            .size(88.dp)
                            .scale(shutterScale)
                            .clip(CircleShape)
                            .border(3.dp, Color(0xE6FFFFFF), CircleShape)
                            .clickable(interactionSource = shutterInteraction, indication = null) { onShutter() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .size(68.dp)
                                .clip(CircleShape)
                                .background(Color.White),
                        )
                    }

                    // 右侧相册缩略图（最近一张，点击打开相册）
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(Radius.mdShape)
                            .background(WColors.ChipBg)
                            .border(1.dp, WColors.GlassBorder, Radius.mdShape)
                            .clickable { onOpenGallery() },
                        contentAlignment = Alignment.Center,
                    ) {
                        val bitmap = thumb
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "最近一张取证照片",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().clip(Radius.mdShape),
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.PhotoLibrary,
                                contentDescription = "相册",
                                tint = WColors.OnBgDim,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }
            }

            // 相机启动失败提示（不崩，只提示）
            val err = camError
            if (err != null) {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = Space.x8)
                        .clip(Radius.mdShape)
                        .background(Color(0xCC000000))
                        .padding(horizontal = Space.x4, vertical = Space.x2),
                ) {
                    Text(
                        text = "相机启动失败：$err",
                        color = WColors.Warning,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            // 快门闪光（最上层，短暂盖住整个画面）
            val flashAlpha = flashAnim.value
            if (flashAlpha > 0.01f) {
                Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = flashAlpha)))
            }
        } else {
            PermissionDenied(
                onRequest = { runCatching { permLauncher.launch(Manifest.permission.CAMERA) } },
                onClose = onClose,
            )
        }
    }

    // 备注编辑框：弹出时快门被 onShutter 里的 noteDialogOpen 拦掉
    if (noteDialogOpen) {
        AlertDialog(
            onDismissRequest = { noteDialogOpen = false },
            containerColor = WColors.Bg1,
            title = {
                Text(
                    text = "备注地点 / 标题",
                    color = WColors.OnBg,
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            text = {
                GlassTextField(
                    value = noteInput,
                    onValueChange = { noteInput = it.take(40) },
                    placeholder = "例如：十号楼四层西侧水房",
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val v = noteInput.trim()
                        wmTitle = v.ifBlank { defaultTitle }
                        titleEdited = v.isNotBlank()
                        noteDialogOpen = false
                    },
                ) { Text("确定", color = WColors.Primary) }
            },
            dismissButton = {
                TextButton(onClick = { noteDialogOpen = false }) {
                    Text("取消", color = WColors.OnBgDim)
                }
            },
        )
    }
}

/**
 * 微信水印相机样式的左下角水印块：备注标题 / 超大时间 + 日期星期天气 / 地址 / 蜂窝技术块。
 * 文本内容全部来自 [WatermarkSpec]，与 [buildWatermarkLines]（落库）和 [burnWatermark]（烧图）同源。
 */
@Composable
private fun WatermarkBlock(
    spec: WatermarkSpec,
    modifier: Modifier = Modifier,
    onEditTitle: () -> Unit,
) {
    Column(
        modifier = modifier
            .clip(Radius.lgShape)
            .background(Color(0x66000000))
            .padding(Space.x4),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(Radius.smShape)
                .clickable { onEditTitle() }
                .padding(vertical = Space.x1),
        ) {
            Text(
                text = spec.title,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 19.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = WmShadowStyle,
                modifier = Modifier.widthIn(max = 300.dp),
            )
            Spacer(Modifier.width(Space.x2))
            Icon(
                imageVector = Icons.Rounded.Edit,
                contentDescription = "编辑备注",
                tint = Color.White.copy(alpha = 0.55f),
                modifier = Modifier.size(13.dp),
            )
        }
        Spacer(Modifier.height(Space.x2))

        // 第二行：超大时间 + 竖直分隔线 + 日期 / 星期天气
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = spec.timeHm,
                color = Color.White,
                fontSize = 42.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 44.sp,
                letterSpacing = (-1).sp,
                style = WmShadowStyle,
            )
            Spacer(Modifier.width(Space.x2))
            Box(
                Modifier
                    .width(2.dp)
                    .height(34.dp)
                    .background(Color.White.copy(alpha = 0.3f)),
            )
            Spacer(Modifier.width(Space.x2))
            Column {
                Text(
                    text = spec.date,
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    style = WmShadowStyle,
                )
                Text(
                    text = spec.weekdayWeather,
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    style = WmShadowStyle,
                )
            }
        }
        Spacer(Modifier.height(Space.x1))

        // 地址
        Text(
            text = spec.address,
            color = Color.White.copy(alpha = 0.88f),
            fontSize = 12.sp,
            lineHeight = 16.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = WmShadowStyle,
        )
        Spacer(Modifier.height(Space.x2))

        // 蜂窝技术块（CellWatermark.flatLines 原样输出）
        Text(
            text = WM_LABEL,
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 10.sp,
            lineHeight = 13.sp,
            style = WmShadowStyle,
        )
        Spacer(Modifier.height(Space.x1))
        spec.cellLines.forEach { line ->
            Text(
                text = line,
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 11.sp,
                lineHeight = 14.sp,
                fontFamily = FontFamily.Monospace,
                style = WmShadowStyle,
            )
        }
        if (!spec.hasFix) {
            Spacer(Modifier.height(Space.x1))
            Text(
                text = WM_NO_FIX,
                color = Color(0xFFFBBF24),
                fontSize = 11.sp,
                lineHeight = 14.sp,
                style = WmShadowStyle,
            )
        }
    }
}

/** 无相机权限：黑底 + 文案 + 关闭（绝不崩） */
@Composable
private fun PermissionDenied(onRequest: () -> Unit, onClose: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(Space.x8),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "需要相机权限才能取证",
                style = MaterialTheme.typography.titleMedium,
                color = WColors.OnBg,
            )
            Spacer(Modifier.height(Space.x3))
            Text(
                text = "照片只保存在本机，不会上传",
                style = MaterialTheme.typography.bodySmall,
                color = WColors.OnBgDim,
            )
            Spacer(Modifier.height(Space.x6))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.x3)) {
                GlowButton(text = "重新申请权限", onClick = onRequest)
                OutlineButton(text = "关闭", onClick = onClose)
            }
        }
    }
}

/**
 * 全屏取景：PreviewView + CameraX，绑定生命周期；lensFacing 变化时整体重建并重绑。
 * 相机不可用时通过 onError 上报，由调用方展示提示。
 */
@Composable
private fun CameraPreview(
    lensFacing: Int,
    onCaptureReady: (ImageCapture) -> Unit,
    onError: (String) -> Unit,
) {
    val viewCtx = LocalContext.current
    val owner = remember(viewCtx) { viewCtx.findLifecycleOwner() }
    val providerRef = remember { mutableStateOf<ProcessCameraProvider?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            runCatching { providerRef.value?.unbindAll() }
            providerRef.value = null
        }
    }
    key(lensFacing) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewCtx ->
                val previewView = PreviewView(viewCtx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                }
                val future = ProcessCameraProvider.getInstance(viewCtx)
                future.addListener(
                    Runnable {
                        runCatching {
                            val lifeOwner = owner
                            if (lifeOwner == null) {
                                onError("相机启动失败：缺少生命周期宿主")
                                return@runCatching
                            }
                            val provider = future.get()
                            providerRef.value = provider
                            val preview = Preview.Builder().build().also {
                                it.surfaceProvider = previewView.surfaceProvider
                            }
                            val capture = ImageCapture.Builder()
                                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                                .build()
                            val selector = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                                CameraSelector.DEFAULT_FRONT_CAMERA
                            } else {
                                CameraSelector.DEFAULT_BACK_CAMERA
                            }
                            provider.unbindAll()
                            provider.bindToLifecycle(lifeOwner, selector, preview, capture)
                            previewView.post {
                                runCatching {
                                    capture.targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0
                                }
                            }
                            onCaptureReady(capture)
                        }.onFailure { e ->
                            onError(e.message ?: "相机启动失败")
                        }
                    },
                    ContextCompat.getMainExecutor(viewCtx),
                )
                previewView
            },
        )
    }
}

// ---------------------------------------------------------------------------
// 水印内容：同一份 spec → 屏幕 / 烧图 / record.lines
// ---------------------------------------------------------------------------

/** 组装水印数据（时间在每一帧/每一拍都重新取，保证「时间在走」） */
private fun buildWatermarkSpec(
    ts: Long,
    title: String,
    weather: String,
    address: String,
    hasFix: Boolean,
    cellLines: List<String>,
): WatermarkSpec {
    val date = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(ts))
    val weekday = SimpleDateFormat("EEEE", Locale.CHINA).format(Date(ts))
    val weekdayWeather = if (weather.isBlank()) weekday else "$weekday $weather"
    return WatermarkSpec(
        title = title.ifBlank { WM_FALLBACK_TITLE },
        timeHm = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(ts)),
        date = date,
        weekdayWeather = weekdayWeather,
        address = address.ifBlank { WM_RESOLVING },
        cellLines = cellLines,
        hasFix = hasFix,
    )
}

/**
 * 水印三处同源的唯一出口：屏幕实时块、烧进照片的文字、[PhotoRecord.lines] 全部由这里产出。
 * 顺序固定：备注标题 / 时分 / 日期 / 星期天气 / 地址 / 技术块小标 / 蜂窝行 / 无定位提示。
 */
private fun buildWatermarkLines(spec: WatermarkSpec): List<String> {
    val out = ArrayList<String>(spec.cellLines.size + 7)
    out += spec.title
    out += spec.timeHm
    out += spec.date
    out += spec.weekdayWeather
    out += spec.address
    out += WM_LABEL
    out += spec.cellLines
    if (!spec.hasFix) out += WM_NO_FIX
    return out
}

/** 逆地理结果 → 「省市区·POI名」；POI 优先，其次 getAddress() */
private fun formatReverseAddress(result: ReverseGeoCodeResult): String {
    val detail = result.addressDetail
    val region = buildString {
        val seen = HashSet<String>()
        listOf(detail?.province, detail?.city, detail?.district).forEach { part ->
            val p = part?.trim().orEmpty()
            if (p.isNotEmpty() && seen.add(p)) append(p)
        }
    }
    val poi = result.poiList?.asSequence()
        ?.mapNotNull { it.name?.trim() }
        ?.firstOrNull { it.isNotEmpty() }
        .orEmpty()
    val addr = result.address?.trim().orEmpty()
    return when {
        poi.isNotEmpty() && region.isNotEmpty() -> "$region·$poi"
        poi.isNotEmpty() -> poi
        addr.isNotEmpty() -> addr
        else -> ""
    }
}

/** 两条经纬度间的近似距离（米），用于 50m 节流 */
private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    if (lat1.isNaN() || lon1.isNaN()) return Double.MAX_VALUE
    val dLat = (lat2 - lat1) * 111_320.0
    val dLon = (lon2 - lon1) * 111_320.0 * Math.cos(Math.toRadians((lat1 + lat2) / 2.0))
    return Math.hypot(dLat, dLon)
}

// ---------------------------------------------------------------------------
// 拍照落盘（全程 runCatching，由调用方捕获后 toast）
// ---------------------------------------------------------------------------

/** ImageProxy（JPEG 输出）→ JPEG 字节；拷贝后立即由调用方 close，避免长时间持锁 */
private fun jpegBytesOf(image: ImageProxy): ByteArray? {
    val buffer = image.planes.firstOrNull()?.buffer ?: return null
    buffer.rewind()
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    return bytes
}

/**
 * 解码 → 纠正旋转 → 最长边缩到 1600 → 烧水印 → JPEG q88 写盘 → 落 PhotoRecord。
 * 任何一步失败抛异常，由 OnImageCapturedCallback 的调用方 toast，不吞不崩。
 */
private fun savePhoto(
    bytes: ByteArray,
    rotationDegrees: Int,
    spec: WatermarkSpec,
    lines: List<String>,
    chip: PhotoKindChip,
    ts: Long,
    lat: Double,
    lon: Double,
    note: String,
    taskId: String = "",
): PhotoRecord {
    val decoded = decodeJpegBytes(bytes, 1600) ?: error("照片解码失败")
    val upright = rotateBitmap(decoded, rotationDegrees)
    val scaled = scaleDown(upright, 1600)
    val stamped = burnWatermark(scaled, spec, ts)
    // file 只写文件名：PhotoStore.fileOf() = File(dir, record.file)，两者必须同口径
    val file = AppGraph.photoStore.newFile()
    FileOutputStream(file).use { out ->
        if (!stamped.compress(Bitmap.CompressFormat.JPEG, 88, out)) error("JPEG 编码失败")
    }
    if (!file.isFile || file.length() <= 0L) error("照片写入失败")
    val record = PhotoRecord(
        ts = ts,
        kind = chip.kind,
        deviceType = chip.deviceType,
        note = note,
        lat = lat,
        lon = lon,
        file = file.name,
        lines = lines,
        taskId = taskId,
    )
    AppGraph.photoStore.add(record)
    return record
}

/** 按采样率解码 JPEG 字节，避免原图 4000px 级别的 OOM */
private fun decodeJpegBytes(bytes: ByteArray, maxSide: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
}

/** 竖屏拍摄时 JPEG 常带 90° 旋转，按 CameraX 给的 rotationDegrees 摆正 */
private fun rotateBitmap(src: Bitmap, degrees: Int): Bitmap {
    if (degrees % 360 == 0) return src
    return runCatching {
        Bitmap.createBitmap(
            src, 0, 0, src.width, src.height,
            Matrix().apply { postRotate(degrees.toFloat()) },
            true,
        )
    }.getOrElse { src }
}

/** 最长边等比缩到 maxSide 以内 */
private fun scaleDown(src: Bitmap, maxSide: Int): Bitmap {
    val longSide = maxOf(src.width, src.height)
    if (longSide <= maxSide) return src
    val ratio = maxSide.toFloat() / longSide.toFloat()
    val w = (src.width * ratio).toInt().coerceAtLeast(1)
    val h = (src.height * ratio).toInt().coerceAtLeast(1)
    return runCatching { Bitmap.createScaledBitmap(src, w, h, true) }.getOrElse { src }
}

/**
 * 把水印烧进照片左下角（微信水印相机布局）：
 * 备注标题 → 超大时分 + 竖线 + 日期/星期天气 → 地址 → 技术块小标 → 蜂窝行 →（无定位黄字提醒），
 * 右下角烧「围猎 · yyyy.MM.dd HH:mm:ss」。尺寸全部按图片宽度等比，任何屏幕/照片比例都好看。
 */
private fun burnWatermark(src: Bitmap, spec: WatermarkSpec, ts: Long): Bitmap {
    val bmp = runCatching {
        if (src.isMutable) src else src.copy(Bitmap.Config.ARGB_8888, true)
    }.getOrNull() ?: src
    runCatching {
        val canvas = Canvas(bmp)
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        if (w <= 1f || h <= 1f) return bmp

        // 与屏幕同款底部压暗，保证白字在亮景上可读
        val scrim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, h * 0.55f, 0f, h,
                intArrayOf(AndroidColor.TRANSPARENT, AndroidColor.argb(140, 0, 0, 0)),
                null,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, h * 0.55f, w, h, scrim)

        // 1080 宽手机屏上的字号占比换算成图片像素（15sp/42sp/12sp/11sp 等）
        val titlePx = w * 0.036f
        val timePx = w * 0.100f
        val smallPx = w * 0.027f
        val addrPx = w * 0.029f
        val labelPx = w * 0.022f
        val cellPx = w * 0.027f
        val gapTitle = w * 0.008f
        val gapTime = w * 0.014f
        val gapAddr = w * 0.018f
        val gapLabel = w * 0.006f
        val cellLineH = cellPx * 1.28f
        val left = w * 0.028f
        val bottom = h * 0.955f

        val titlePaint = wmPaint(titlePx, AndroidColor.WHITE, Typeface.DEFAULT, w * 0.006f)
        val timePaint = wmPaint(timePx, AndroidColor.WHITE, Typeface.DEFAULT_BOLD, w * 0.008f)
        val smallPaint = wmPaint(smallPx, AndroidColor.argb(204, 255, 255, 255), Typeface.DEFAULT, w * 0.005f)
        val addrPaint = wmPaint(addrPx, AndroidColor.argb(224, 255, 255, 255), Typeface.DEFAULT, w * 0.005f)
        val labelPaint = wmPaint(labelPx, AndroidColor.argb(140, 255, 255, 255), Typeface.DEFAULT, w * 0.004f)
        val cellPaint = wmPaint(cellPx, AndroidColor.argb(184, 255, 255, 255), Typeface.MONOSPACE, w * 0.004f)
        val warnPaint = wmPaint(addrPx * 0.92f, AndroidColor.rgb(251, 191, 36), Typeface.DEFAULT, w * 0.004f)

        val titleLines = wrapText(titlePaint, spec.title, w * 0.82f).take(2)
        val addrLines = wrapText(addrPaint, spec.address, w * 0.88f).take(2)
        val titleLineH = lineHeightOf(titlePaint) * 1.06f
        val addrLineH = lineHeightOf(addrPaint) * 1.12f
        val timeBlockH = timePx * 1.16f
        val labelBlockH = lineHeightOf(labelPaint)
        val cellBlockH = spec.cellLines.size * cellLineH
        val warnBlockH = if (spec.hasFix) 0f else lineHeightOf(warnPaint) + w * 0.004f

        val totalH = titleLines.size * titleLineH + gapTitle +
            timeBlockH + gapTime +
            addrLines.size * addrLineH + gapAddr +
            labelBlockH + gapLabel +
            cellBlockH + warnBlockH
        var y = (bottom - totalH).coerceAtLeast(h * 0.30f)

        // 1) 备注标题
        val titleFm = titlePaint.fontMetrics
        titleLines.forEach { line ->
            canvas.drawText(line, left, y - titleFm.ascent, titlePaint)
            y += titleLineH
        }
        y += gapTitle

        // 2) 超大时间 + 竖线 + 日期 / 星期天气
        val timeFm = timePaint.fontMetrics
        canvas.drawText(spec.timeHm, left, y - timeFm.ascent, timePaint)
        val timeW = timePaint.measureText(spec.timeHm)
        val dividerX = left + timeW + w * 0.016f
        val divTop = y + timePx * 0.10f
        val divBottom = y + timePx * 0.94f
        val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.argb(77, 255, 255, 255)
        }
        canvas.drawRect(dividerX, divTop, dividerX + w * 0.0022f, divBottom, dividerPaint)
        val rightX = dividerX + w * 0.016f
        val smallFm = smallPaint.fontMetrics
        canvas.drawText(spec.date, rightX, y + smallPx * 1.30f, smallPaint)
        canvas.drawText(spec.weekdayWeather, rightX, divBottom - smallFm.descent, smallPaint)
        y += timeBlockH + gapTime

        // 3) 地址
        val addrFm = addrPaint.fontMetrics
        addrLines.forEach { line ->
            canvas.drawText(line, left, y - addrFm.ascent, addrPaint)
            y += addrLineH
        }
        y += gapAddr

        // 4) 蜂窝技术块
        canvas.drawText(WM_LABEL, left, y - labelPaint.fontMetrics.ascent, labelPaint)
        y += labelBlockH + gapLabel
        val cellFm = cellPaint.fontMetrics
        spec.cellLines.forEach { line ->
            canvas.drawText(line, left, y - cellFm.ascent, cellPaint)
            y += cellLineH
        }
        if (!spec.hasFix) {
            canvas.drawText(WM_NO_FIX, left, y - warnPaint.fontMetrics.ascent + w * 0.004f, warnPaint)
        }

        // 5) 右下角品牌 + 拍摄时间（保留原约定）
        val brandPaint = wmPaint(smallPx * 0.95f, AndroidColor.argb(232, 255, 255, 255), Typeface.DEFAULT_BOLD, w * 0.005f)
        val brand = "围猎 · " + SimpleDateFormat("yyyy.MM.dd HH:mm:ss", Locale.CHINA).format(Date(ts))
        val brandRight = w - left
        val brandBaseline = h - w * 0.030f
        canvas.drawText(
            brand,
            brandRight - brandPaint.measureText(brand),
            brandBaseline,
            brandPaint,
        )
    }
    return bmp
}

private fun wmPaint(sizePx: Float, color: Int, typeface: Typeface, shadowPx: Float): Paint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = sizePx
        this.typeface = typeface
        isSubpixelText = true
        if (shadowPx > 0f) setShadowLayer(shadowPx, 0f, shadowPx * 0.22f, AndroidColor.argb(150, 0, 0, 0))
    }

private fun lineHeightOf(paint: Paint): Float = paint.fontMetrics.run { descent - ascent }

/** 按像素宽度硬换行（中文没有空格可断，用 Paint.breakText 逐段切） */
private fun wrapText(paint: Paint, text: String, maxWidth: Float): List<String> {
    if (text.isEmpty()) return listOf("")
    if (maxWidth <= 1f) return listOf(text)
    val rows = ArrayList<String>()
    var start = 0
    while (start < text.length) {
        val count = paint.breakText(text, start, text.length, true, maxWidth, null).coerceAtLeast(1)
        val end = (start + count).coerceAtMost(text.length)
        rows += text.substring(start, end)
        start = end
    }
    return rows
}

/** 底部缩略图：先量尺寸再按 2 的幂下采样，44dp 的方块不加载整张图 */
private fun decodeThumbnailFile(file: File, targetPx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= targetPx) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return runCatching { BitmapFactory.decodeFile(file.absolutePath, opts) }.getOrNull()
}

/**
 * 系统 Geocoder 地址拼接口径（WGS84 原坐标）：省市区 + 路号，POI/featureName 优先。
 * 例：北京市东城区·东长安街1号 —— 与百度那条的「省市区·POI」观感保持一致。
 */
private fun formatSystemAddress(addr: Address): String {
    fun clean(s: String?): String = s?.trim().orEmpty()
    val locality = clean(addr.locality)
    val subLocality = clean(addr.subLocality)
    val thoroughfare = clean(addr.thoroughfare)
    val subThoroughfare = clean(addr.subThoroughfare)
    val feature = clean(addr.featureName)
    val region = buildString {
        if (locality.isNotEmpty()) append(locality)
        if (subLocality.isNotEmpty() && subLocality != locality) append(subLocality)
    }
    val street = buildString {
        if (thoroughfare.isNotEmpty()) append(thoroughfare)
        if (subThoroughfare.isNotEmpty() && subThoroughfare != thoroughfare) append(subThoroughfare)
    }
    val poi = if (feature.isEmpty() || feature == region || feature == street) "" else feature
    return when {
        region.isNotEmpty() && street.isNotEmpty() -> "$region·$street"
        region.isNotEmpty() && poi.isNotEmpty() -> "$region·$poi"
        street.isNotEmpty() && poi.isNotEmpty() && !street.contains(poi) -> "$street·$poi"
        street.isNotEmpty() -> street
        poi.isNotEmpty() -> poi
        else -> clean(addr.getAddressLine(0))
    }
}

/** 从 Compose 的 LocalContext 向上找 LifecycleOwner（不依赖 lifecycle-runtime-compose） */
private fun Context.findLifecycleOwner(): LifecycleOwner? {
    var node: Context? = this
    var guard = 0
    while (node != null && guard < 8) {
        if (node is LifecycleOwner) return node
        node = (node as? ContextWrapper)?.baseContext
        guard++
    }
    return null
}

/** API < 33 的同步逆地理（仅兜底分支用；必须在 IO 线程跑，异常一律吞掉） */
@Suppress("DEPRECATION")
private suspend fun geocodeSync(geo: Geocoder, lat: Double, lon: Double): String? =
    withContext(Dispatchers.IO) {
        runCatching { geo.getFromLocation(lat, lon, 1) }
            .getOrNull()
            ?.firstOrNull()
            ?.let { formatSystemAddress(it) }
    }
