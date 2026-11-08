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
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.gakj.weilie.MainActivity
import com.gakj.weilie.R
import com.gakj.weilie.ui.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 走测采样前台服务：采集期间常驻通知（含样本计数与「停止采集」动作），
 * 保证 1Hz 采样循环在后台不被系统回收。
 */
class SamplingForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        // 通知常驻更新：每 2s 刷一次样本计数
        scope.launch {
            while (isActive) {
                delay(2000)
                val c = AppGraph.currentController ?: break
                notifySafely(buildNotification(c.task.title, c.sampleCount.value, c.running.value))
                if (!c.running.value) break
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startInForeground() {
        ensureChannel(this)
        val c = AppGraph.currentController
        val n = buildNotification(
            title = c?.task?.title ?: "围猎走测",
            count = c?.sampleCount?.value ?: 0,
            running = c?.running?.value ?: true,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIF_ID, n)
        }
    }

    private fun notifySafely(n: Notification) {
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, n)
        }
    }

    private fun buildNotification(title: String, count: Int, running: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, SamplingForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(if (running) "走测采集中" else "采集已结束")
            .setContentText("$title · 已采 $count 个样本点")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "停止采集", stop)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "weilie_sampling"
        const val NOTIF_ID = 1001
        const val ACTION_STOP = "com.gakj.weilie.action.STOP_SAMPLING"

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                CHANNEL_ID, "走测采样", NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "采集进行期间的常驻状态通知"
                setShowBadge(false)
            }
            nm.createNotificationChannel(ch)
        }

        fun start(context: Context) {
            ensureChannel(context)
            ContextCompat.startForegroundService(
                context,
                Intent(context, SamplingForegroundService::class.java),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, SamplingForegroundService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
