package com.gakj.weilie.core.sampling

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.gakj.weilie.core.fitting.Fix
import com.gakj.weilie.core.fitting.GateResult
import com.gakj.weilie.core.fitting.TrackGate
import com.gakj.weilie.core.model.CoverageTask
import com.gakj.weilie.core.model.SampleRecord
import com.gakj.weilie.core.model.TargetFormat
import com.gakj.weilie.core.store.SessionManifest
import com.gakj.weilie.core.store.SessionStore
import com.gakj.weilie.core.telephony.AndroidCellReader
import com.gakj.weilie.core.telephony.CellSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 小区切换事件（白话时间线的素材） */
data class CellChangeEvent(
    val tsEpochMs: Long,
    val fromCi: Long?,
    val toCi: Long?,
    val fromText: String,
    val toText: String,
    val plainText: String, // 白话：例如「23:41 离开了小区 12345，进入 67890」
)

/**
 * 采样控制器：把 CellReader + 定位 + 目标判定 + 存储串成 1Hz 采集循环。
 * 全部状态经 StateFlow 暴露给 UI；采集期间由前台服务保活。
 */
class SamplingController(
    private val context: Context,
    private val scope: CoroutineScope,
    val task: CoverageTask,
    private val store: SessionStore = SessionStore(context),
) {
    private val reader = AndroidCellReader(context)
    private val sessionId: String = task.id

    private val _samples = MutableStateFlow<List<SampleRecord>>(emptyList())
    val samples: StateFlow<List<SampleRecord>> = _samples

    private val _events = MutableStateFlow<List<CellChangeEvent>>(emptyList())
    val events: StateFlow<List<CellChangeEvent>> = _events

    private val _snapshot = MutableStateFlow<CellSnapshot?>(null)
    val snapshot: StateFlow<CellSnapshot?> = _snapshot

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    private val _sampleCount = MutableStateFlow(0)
    val sampleCount: StateFlow<Int> = _sampleCount

    /** GPS 双重安检的提示（最近一次被丢弃/合并的说明；无事件时为空） */
    private val _gateNotice = MutableStateFlow<String?>(null)
    val gateNotice: StateFlow<String?> = _gateNotice

    /** 打点间隔（毫秒）：默认 1s，可在工具箱里调整 */
    var sampleIntervalMs: Long = 1_000L
        set(value) {
            field = value.coerceIn(200L, 600_000L)
        }

    /**
     * GPS 双重安检 + 卡尔曼平滑：
     * 精度 > [maxAccuracyM] 丢弃；与上一点 < [minDistanceM] 合并（不新增空间点，只更新上点信号均值）；飞点丢弃。
     * 两个门槛可在设置页调整（需求 2.1/2.2「可调」）。
     */
    private var gate = TrackGate(maxAccuracyM = 50.0, minDistanceM = 1.0, maxSpeedMs = 40.0)

    /** 精度门槛（米）：超过即丢弃该点，默认 50 */
    var maxAccuracyM: Double = 50.0
        set(value) {
            field = value.coerceIn(5.0, 200.0)
            rebuildGate()
        }

    /** 防抖距离门槛（米）：距上一点小于该值则合并，默认 1（可在设置里改） */
    var minDistanceM: Double = 1.0
        set(value) {
            field = value.coerceIn(0.5, 30.0)
            rebuildGate()
        }

    private fun rebuildGate() {
        gate = TrackGate(maxAccuracyM = maxAccuracyM, minDistanceM = minDistanceM, maxSpeedMs = 40.0)
    }

    /** 当前点已合并的次数（用于合并时求信号均值） */
    private var mergeCount = 0

    var lastLocation: Location? = null
        private set

    private var loopJob: Job? = null
    private var startedAt = 0L

    // ---------- 定位 ----------

    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val locationListener = LocationListener { loc ->
        lastLocation = loc
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (_running.value) return
        // 恢复已有会话：沿用磁盘上的原始标题/目标/开始时间，并保留历史样本数
        // （否则「继续」会把原任务改名、目标清空、样本数只统计本次进程）
        val existing = runCatching { store.loadManifest(sessionId) }.getOrNull()
        startedAt = existing?.startedAtEpochMs ?: System.currentTimeMillis()
        store.newSessionDir(sessionId)
        store.writeManifest(
            SessionManifest(
                taskId = sessionId,
                title = existing?.title ?: task.title,
                targetRawInput = existing?.targetRawInput ?: task.target.rawInput,
                targetFormat = existing?.targetFormat ?: task.target.format.name,
                startedAtEpochMs = startedAt,
                sampleCount = runCatching { store.loadSamples(sessionId).size }.getOrDefault(0),
            ),
        )
        reader.start()
        gate.reset()
        mergeCount = 0
        _gateNotice.value = null
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            try {
                if (lm.allProviders.contains(LocationManager.GPS_PROVIDER)) {
                    lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener, Looper.getMainLooper())
                }
                if (lm.allProviders.contains(LocationManager.NETWORK_PROVIDER)) {
                    lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 0f, locationListener, Looper.getMainLooper())
                }
            } catch (_: Exception) { }
        }
        loopJob = scope.launch { samplingLoop() }
        _running.value = true
    }

    fun stop() {
        if (!_running.value) return
        loopJob?.cancel(); loopJob = null
        runCatching { lm.removeUpdates(locationListener) }
        reader.stop()
        store.writeManifest(
            (store.loadManifest(sessionId) ?: SessionManifest(
                taskId = sessionId, title = task.title,
                targetRawInput = task.target.rawInput, targetFormat = task.target.format.name,
                startedAtEpochMs = startedAt,
            )).apply {
                endedAtEpochMs = System.currentTimeMillis()
                // 以磁盘样本总数为准（含恢复前的历史样本），与详情页 samples.size 口径一致
                sampleCount = runCatching { store.loadSamples(sessionId).size }
                    .getOrDefault(_sampleCount.value)
            },
        )
        _running.value = false
    }

    private suspend fun samplingLoop() {
        var lastServingCi: Long? = null
        var lastServingText = ""
        while (scope.isActive) {
            delay(sampleIntervalMs)
            reader.pollNow()
            val snap = reader.snapshot.value ?: continue
            _snapshot.value = snap
            val loc = lastLocation ?: continue
            val serving = snap.serving
            val servingCi = serving?.ci
            val targetCi = task.target.unifiedCi()
            val targetPci = task.target.pci
            // 服务小区候选：SA 时是 NR 小区；NSA 时是 LTE 锚点 + NR 辅载波。
            // 双卡串台会在这里被彻底排除（AndroidCellReader 已只给默认数据卡的小区）。
            val servingCells = buildList {
                snap.serving?.let { add(it) }
                snap.nrServing?.takeIf { c -> c.ci != snap.serving?.ci }?.let { add(it) }
                snap.anchorLte?.takeIf { c -> c.ci != snap.serving?.ci }?.let { add(it) }
            }
            fun matches(c: com.gakj.weilie.core.model.CellIdentity): Boolean =
                (targetCi != null && c.ci == targetCi) ||
                    (targetPci != null && c.pci == targetPci &&
                        c.mcc == task.target.mcc && c.mnc == task.target.mnc)
            val servingHit = servingCells.firstOrNull { matches(it) }
            val onTarget = servingHit != null
            // 邻区指纹匹配（未注册小区）
            val neighborHit = snap.neighbors.firstOrNull { matches(it) }
            val targetSeen = onTarget || neighborHit != null
            // 目标信号：命中 NR 辅载波时取 NR 的 SS-RSRP，其余取服务小区测量值
            val targetRsrp = when {
                onTarget && snap.nrServing != null && servingHit?.ci == snap.nrServing.ci ->
                    snap.nrSignal?.rsrp ?: snap.signal?.rsrp
                onTarget -> snap.signal?.rsrp
                else -> neighborHit?.let { hit ->
                    snap.neighborCells.firstOrNull { it.identity === hit }?.signal?.rsrp
                        ?: snap.neighborCells.firstOrNull { it.identity.ci == hit.ci }?.signal?.rsrp
                }
            }

            // ---- GPS 双重安检 + 卡尔曼平滑 ----
            val accM = if (loc.hasAccuracy()) loc.accuracy.toDouble() else 9999.0
            when (val g = gate.push(Fix(loc.latitude, loc.longitude, accM, snap.tsEpochMs))) {
                is GateResult.Skip -> {
                    _gateNotice.value = g.message
                    continue
                }
                is GateResult.Merge -> {
                    _gateNotice.value = g.message
                    // 合并：不新增空间点，只把这次的信号并入上一个点的滑动均值
                    mergeCount++
                    val list = _samples.value
                    val last = list.lastOrNull()
                    if (last != null) {
                        val n = mergeCount + 1
                        val avg = { old: Int?, new: Int? ->
                            when {
                                new == null -> old
                                old == null -> new
                                else -> ((old.toLong() * (n - 1) + new) / n).toInt()
                            }
                        }
                        _samples.value = list.dropLast(1) + last.copy(
                            rsrp = avg(last.rsrp, snap.signal?.rsrp),
                            rsrq = avg(last.rsrq, snap.signal?.rsrq),
                            sinr = avg(last.sinr, snap.signal?.sinr),
                            rssi = avg(last.rssi, snap.signal?.rssi),
                            targetRsrp = if (onTarget || neighborHit != null) avg(last.targetRsrp, targetRsrp) else last.targetRsrp,
                        )
                    }
                    continue
                }
                is GateResult.Accept -> {
                    _gateNotice.value = null
                    mergeCount = 0
                    recordSample(
                        snap = snap, lat = g.lat, lon = g.lon,
                        servingCi = servingCi, serving = serving,
                        onTarget = onTarget, targetSeen = targetSeen, targetRsrp = targetRsrp,
                    )
                }
            }

            // 小区切换事件（append-only 时间线）
            if (servingCi != lastServingCi) {
                val toText = serving?.display() ?: "未知小区"
                val ts = _samples.value.lastOrNull()?.tsEpochMs ?: snap.tsEpochMs
                val evt = CellChangeEvent(
                    tsEpochMs = ts, fromCi = lastServingCi, toCi = servingCi,
                    fromText = lastServingText, toText = toText,
                    plainText = if (lastServingCi == null) "开始记录，当前驻留 $toText"
                    else "离开了 ${lastServingText.ifBlank { "上一小区" }}，进入 $toText",
                )
                _events.value = _events.value + evt
                lastServingCi = servingCi
                lastServingText = toText
            }
        }
    }

    /** 落一个通过安检的采样点（坐标已由卡尔曼滤波平滑） */
    private fun recordSample(
        snap: CellSnapshot,
        lat: Double,
        lon: Double,
        servingCi: Long?,
        serving: com.gakj.weilie.core.model.CellIdentity?,
        onTarget: Boolean,
        targetSeen: Boolean,
        targetRsrp: Int?,
    ) {
        val rec = SampleRecord(
            tsEpochMs = snap.tsEpochMs,
            lat = lat, lon = lon,
            accuracyM = snap.let {
                lastLocation?.accuracy?.takeIf { a -> !a.isNaN() }
            },
            provider = lastLocation?.provider,
            servingRat = serving?.rat, servingMcc = serving?.mcc, servingMnc = serving?.mnc,
            servingLacTac = serving?.lacOrTac, servingCi = servingCi,
            servingPci = serving?.pci, servingEarfcn = serving?.arfcn,
            rsrp = snap.signal?.rsrp, rsrq = snap.signal?.rsrq,
            sinr = snap.signal?.sinr, rssi = snap.signal?.rssi,
            neighborCount = snap.neighbors.size,
            neighborCis = snap.neighbors.mapNotNull { it.ci?.toString() },
            targetSeen = targetSeen, targetRsrp = targetRsrp, onTarget = onTarget,
        )
        runCatching { store.appendSample(sessionId, rec) }
        _samples.value = _samples.value + rec
        _sampleCount.value = _samples.value.size
    }

    fun targetFormatLabel(): String = when (task.target.format) {
        TargetFormat.ECI -> "ECI (4G)"
        TargetFormat.NCI -> "NCI (5G)"
        TargetFormat.LAC_CI -> "LAC+CI (2G/3G)"
        TargetFormat.LATLON_RADIUS -> "经纬度+半径"
    }
}
