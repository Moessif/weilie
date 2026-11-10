package com.gakj.weilie.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.gakj.weilie.MainActivity
import com.gakj.weilie.R
import com.gakj.weilie.core.tracking.SilentTrackLogic
import com.gakj.weilie.core.tracking.TrackState
import com.gakj.weilie.core.tracking.VibrationPlan
import com.gakj.weilie.ui.AppGraph
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 静默跟踪前台服务：只震动、不发声、不弹窗、不亮屏。
 *
 * 数据来源刻意复用走测采样控制器（[AppGraph.currentController] 的 snapshot），
 * 自己不做定位：避免与 SamplingForegroundService 抢 GPS、也避免重复耗电。
 * 震动方案全部来自 [SilentTrackLogic.plan]，服务只负责「何时震、怎么停」。
 */
class SilentTrackService : Service() {

    // Main.immediate：状态判定/通知刷新都在主线程串行，避免通知与震动时序错乱
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private var started = false
    private var vibrator: Vibrator? = null
    private var currentPlan: VibrationPlan? = null
    private var lastKey: String? = null

    /** 单次脉冲 + Handler 定时重复：MAIN/NEIGHBOR 用 */
    private val pulseRunnable = object : Runnable {
        override fun run() {
            val plan = currentPlan ?: return
            vibrateOnce(plan.pulseMs)
            handler.postDelayed(this, plan.intervalMs)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 注意：ACTION_STOP 必须在 startForeground 之前处理，否则会白闪一条通知
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        if (!started) startTracking()
        return START_STICKY
    }

    override fun onDestroy() {
        shutdown()
        // 无论系统回收还是主动停止，都必须取消后续轮询/快照订阅，避免泄漏
        scope.cancel()
        super.onDestroy()
    }

    // ---------- 启动/停止 ----------

    private fun startTracking() {
        started = true
        _running.value = true
        lastKey = null
        ensureChannel(this)
        startInForeground()
        vibrator = obtainVibrator()
        restoreStrength(this)
        _lastEvent.value = "${timeFmt.format(Date())} 等待采样数据"
        // 控制器可能还没创建（例如用户尚未进入任务地图）：轮询等待，
        // 且每次控制器被替换（A1 换目标 / 重新 ensure）后重新订阅新的 snapshot。
        scope.launch {
            while (isActive) {
                val controller = AppGraph.currentController
                if (controller == null) {
                    delay(1000)
                    continue
                }
                val job = launch {
                    controller.snapshot.collect { snap -> if (snap != null) onSnapshot(snap.serving?.ci, snap.neighbors.mapNotNull { it.ci }) }
                }
                while (isActive && AppGraph.currentController === controller) delay(500)
                job.cancel()
            }
        }
    }

    /** 停止：清震动（含持续波形）→ 撤通知 → 停服务。重复调用安全。 */
    private fun shutdown() {
        started = false
        _running.value = false
        handler.removeCallbacks(pulseRunnable)
        runCatching { vibrator?.cancel() }
        vibrator = null
        currentPlan = null
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    // ---------- 快照判定 ----------

    private fun onSnapshot(servingCi: Long?, neighborCis: List<Long>) {
        val controller = AppGraph.currentController ?: return
        val targetCi = controller.task.target.unifiedCi()
        // 经纬度/半径类任务没有小区编号，主区·邻区无从判定：此时若继续按 NONE 持续震动，
        // 用户只会感到"一直在震"却永远恢复不了——直接停掉并说明原因。
        if (targetCi == null) {
            Log.w("SilentTrack", "当前任务没有目标小区编号（经纬度任务），静默跟踪不适用，自动停止")
            _lastEvent.value = "当前任务无目标小区编号，静默跟踪不适用"
            stopSelf()
            return
        }
        val state = SilentTrackLogic.stateOf(servingCi, targetCi, neighborCis)
        // 用「状态 + 目标」做去重键：驻留/邻区列表频繁变化，但同状态不重复震动、不刷通知
        val key = "$state|$targetCi"
        if (key == lastKey) return
        lastKey = key

        val plan = SilentTrackLogic.plan(state)
        val event = "${timeFmt.format(Date())} ${plan.label} ${targetCi?.toString() ?: "--"}"
        _state.value = state
        _lastEvent.value = event
        Log.d("SilentTrack", "state=$state target=$targetCi serving=$servingCi neighbors=${neighborCis.size}")

        applyVibration(plan)
        notifySafely(buildNotification(plan.label, event))
        appendLog(event)
    }

    /**
     * 需求 10：状态变化写日志（时间 / 状态 / 目标标识）。
     * 除了 Logcat，再落到 filesDir/silent_track.log，方便现场事后复盘震动提示的时间线。
     */
    private fun appendLog(line: String) {
        runCatching {
            val f = java.io.File(filesDir, "silent_track.log")
            if (f.exists() && f.length() > 512 * 1024) f.delete()
            f.appendText(line + "\n")
        }
    }

    // ---------- 震动 ----------

    private fun obtainVibrator(): Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.getSystemService(this, VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }.getOrNull()

    private fun applyVibration(plan: VibrationPlan) {
        // 先清掉上一种节奏，避免主区/邻区切换时两种脉冲叠加
        handler.removeCallbacks(pulseRunnable)
        runCatching { vibrator?.cancel() }
        currentPlan = plan
        if (plan.repeat) {
            // 脱离：波形循环（0ms 延时 + 1000ms 震动 + 200ms 间隔），持续到状态改变
            runCatching {
                vibrator?.vibrate(
                    VibrationEffect.createWaveform(
                        longArrayOf(0L, plan.pulseMs, 200L),
                        intArrayOf(0, amplitudeOf(strength), 0),
                        0,
                    ),
                )
            }
        } else {
            vibrateOnce(plan.pulseMs)
            handler.postDelayed(pulseRunnable, plan.intervalMs)
        }
    }

    private fun vibrateOnce(ms: Long) {
        runCatching {
            vibrator?.vibrate(
                VibrationEffect.createOneShot(ms, amplitudeOf(strength)),
            )
        }
    }

    // ---------- 通知 ----------

    private fun startInForeground() {
        val n = buildNotification("等待采样", "${timeFmt.format(Date())} 介质未就绪，等待走测任务")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ 必须显式带 FGS 类型（清单里声明为 specialUse）
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun notifySafely(n: Notification) {
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, n)
        }
    }

    private fun buildNotification(label: String, content: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, SilentTrackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("静默跟踪 · $label")
            .setContentText(content)
            .setOngoing(true)
            .setSilent(true) // 不发声、不震动提示（震动是我们的业务信号，不走通知）
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "停止", stop)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "silent_track"
        const val NOTIF_ID = 2001
        const val ACTION_START = "com.gakj.weilie.action.START_SILENT_TRACK"
        const val ACTION_STOP = "com.gakj.weilie.action.STOP_SILENT_TRACK"

        private const val PREF_STRENGTH = "silent_strength"

        /** 震动强度 1（轻）~ 5（强），由工具箱设置；默认 3（中）。 */
        @Volatile
        var strength: Int = 3
            private set

        fun setStrength(ctx: Context, level: Int) {
            strength = level.coerceIn(1, 5)
            runCatching {
                AppGraph.prefs.edit().putInt(PREF_STRENGTH, strength).apply()
            }
        }

        private fun restoreStrength(ctx: Context) {
            strength = runCatching {
                AppGraph.prefs.getInt(PREF_STRENGTH, 3)
            }.getOrDefault(3).coerceIn(1, 5)
        }

        /** 1..5 → 60..255 振幅，避免最弱档位几乎感觉不到、最强档位震得发麻 */
        private fun amplitudeOf(level: Int): Int =
            when (level.coerceIn(1, 5)) {
                1 -> 60
                2 -> 110
                3 -> 160
                4 -> 205
                else -> 255
            }

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        private val _state = MutableStateFlow(TrackState.NONE)
        val state: StateFlow<TrackState> = _state.asStateFlow()

        private val _lastEvent = MutableStateFlow("")
        val lastEvent: StateFlow<String> = _lastEvent.asStateFlow()

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                CHANNEL_ID, "静默跟踪", NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "静默跟踪期间的常驻状态通知（无声、不亮屏）"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
            }
            nm.createNotificationChannel(ch)
        }

        fun start(context: Context) {
            ensureChannel(context)
            ContextCompat.startForegroundService(
                context,
                Intent(context, SilentTrackService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            // 服务在跑时 startService 会直接复用；未跑时捕获异常，避免调用方崩溃
            runCatching {
                context.startService(
                    Intent(context, SilentTrackService::class.java).setAction(ACTION_STOP),
                )
            }
        }
    }
}
