package com.gakj.weilie.server

import java.util.concurrent.atomic.AtomicLong

/** 进程级指标（v1.3）：供 /metrics 与 /api/rooms 观测用，不做持久化 */
object Metrics {
    val wsConnections = AtomicLong()
    val messages = AtomicLong()
    val rateLimited = AtomicLong()
    val samples = AtomicLong()
    val photosUploaded = AtomicLong()
    val exports = AtomicLong()
    val errors = AtomicLong()

    fun render(store: RoomStore, limiter: RateLimiter, uptimeSec: Long): String = buildString {
        appendLine("# HELP weilie_rooms 当前房间数")
        appendLine("# TYPE weilie_rooms gauge")
        appendLine("weilie_rooms ${store.roomCount()}")
        appendLine("# HELP weilie_online_members 在线成员数")
        appendLine("# TYPE weilie_online_members gauge")
        appendLine("weilie_online_members ${store.onlineMemberCount()}")
        appendLine("# HELP weilie_ws_connections 累计 WS 连接")
        appendLine("# TYPE weilie_ws_connections counter")
        appendLine("weilie_ws_connections ${wsConnections.get()}")
        appendLine("# HELP weilie_ws_messages 累计 WS 消息")
        appendLine("# TYPE weilie_ws_messages counter")
        appendLine("weilie_ws_messages ${messages.get()}")
        appendLine("# HELP weilie_samples 累计采样点")
        appendLine("# TYPE weilie_samples counter")
        appendLine("weilie_samples ${samples.get()}")
        appendLine("# HELP weilie_photos_uploaded 累计照片上传")
        appendLine("# TYPE weilie_photos_uploaded counter")
        appendLine("weilie_photos_uploaded ${photosUploaded.get()}")
        appendLine("# HELP weilie_rate_limited 被限流次数")
        appendLine("# TYPE weilie_rate_limited counter")
        appendLine("weilie_rate_limited ${limiter.deniedCount()}")
        appendLine("# HELP weilie_errors 内部错误数")
        appendLine("# TYPE weilie_errors counter")
        appendLine("weilie_errors ${errors.get()}")
        appendLine("# HELP weilie_exports 导出次数")
        appendLine("# TYPE weilie_exports counter")
        appendLine("weilie_exports ${exports.get()}")
        appendLine("# HELP weilie_uptime_seconds 运行时长")
        appendLine("# TYPE weilie_uptime_seconds gauge")
        appendLine("weilie_uptime_seconds $uptimeSec")
    }
}
