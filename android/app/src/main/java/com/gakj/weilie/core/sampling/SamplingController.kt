package com.gakj.weilie.core.sampling

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
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
            delay(1000)
            reader.pollNow()
            val snap = reader.snapshot.value ?: continue
            _snapshot.value = snap
            val loc = lastLocation ?: continue
            val serving = snap.serving
            val servingCi = serving?.ci
            val onTarget = servingCi != null && servingCi == task.target.unifiedCi()
            val targetSeen = onTarget || snap.neighbors.any { it.ci == task.target.unifiedCi() }
            val targetRsrp = if (onTarget) snap.signal?.rsrp else null

            val rec = SampleRecord(
                tsEpochMs = snap.tsEpochMs,
                lat = loc.latitude, lon = loc.longitude,
                accuracyM = loc.accuracy.takeIf { !it.isNaN() },
                provider = loc.provider,
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

            // 小区切换事件（append-only 时间线）
            if (servingCi != lastServingCi) {
                val toText = serving?.display() ?: "未知小区"
                val evt = CellChangeEvent(
                    tsEpochMs = rec.tsEpochMs, fromCi = lastServingCi, toCi = servingCi,
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

    fun targetFormatLabel(): String = when (task.target.format) {
        TargetFormat.ECI -> "ECI (4G)"
        TargetFormat.NCI -> "NCI (5G)"
        TargetFormat.LAC_CI -> "LAC+CI (2G/3G)"
        TargetFormat.LATLON_RADIUS -> "经纬度+半径"
    }
}
