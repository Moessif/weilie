package com.gakj.weilie.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

/** v1.9：清理房间的强类型响应（Ktor 不能序列化 Bool+Int 混合 Map） */
@Serializable
data class PurgeResult(val ok: Boolean, val removed: Int, val scanned: Int)

@Serializable
data class RoomSummary(
    val roomId: String,
    val name: String,
    val members: Int,
    val zones: Int,
    val markers: Int,
    val createdAt: Long,
    val lastActiveAt: Long,
)

/**
 * 运维接口（v1.3）：
 * - GET  /metrics                          Prometheus 文本指标
 * - GET  /api/rooms                        房间概览（不含邀请码）
 * - POST /api/teams/{roomId}/close?code=   关闭房间（凭 joinCode，仅成员知道）
 * - GET  /api/teams/{roomId}/export?code=&format=kml|geojson|csv
 * - GET  /api/teams/{roomId}/samples?code=&limit=
 */
fun Route.opsRoutes(
    store: RoomStore,
    sampleStore: RoomSampleStore,
    audit: AuditLog,
    limiter: RateLimiter,
    startedAt: Long,
    adminToken: String = "",
) {
    /** 管理接口鉴权：配置了 WEILIE_ADMIN_TOKEN 时必须带 X-Admin-Token 头 */
    fun io.ktor.server.application.ApplicationCall.adminOk(): Boolean =
        adminToken.isBlank() || request.headers["X-Admin-Token"] == adminToken

    fun io.ktor.server.application.ApplicationCall.adminConfigured(): Boolean = adminToken.isNotBlank()
    get("/metrics") {
        // 安全加固：指标含房间数/在线数/照片数等运营信息，配置管理密钥后要求 X-Admin-Token
        if (!call.adminOk()) {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "需要 X-Admin-Token"))
            return@get
        }
        val uptime = (System.currentTimeMillis() - startedAt) / 1000
        call.respondText(
            Metrics.render(store, limiter, uptime),
            ContentType.Text.Plain,
            HttpStatusCode.OK,
        )
    }

    get("/api/rooms") {
        val ip = call.request.origin.remoteHost
        if (!limiter.allow("rooms:$ip", 60)) {
            call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "请求过于频繁"))
            return@get
        }
        // v1.9：配置了管理密钥后，房间清单不再公开
        if (!call.adminOk()) {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "需要 X-Admin-Token"))
            return@get
        }
        val list = store.allRooms().map { r ->
            RoomSummary(
                roomId = r.roomId,
                name = r.name,
                members = r.members.size,
                zones = r.zones.count { !it.deleted },
                markers = r.markers.size,
                createdAt = r.createdAt,
                lastActiveAt = r.lastActiveAt,
            )
        }
        call.respond(list)
    }

    post("/api/teams/{roomId}/close") {
        val ip = call.request.origin.remoteHost
        if (!limiter.allow("close:$ip", 10)) {
            call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "请求过于频繁"))
            return@post
        }
        val roomId = call.parameters["roomId"].orEmpty()
        val code = call.parameters["code"].orEmpty()
        val room = store.findRoom(roomId)
        if (room == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "房间不存在"))
            return@post
        }
        if (!constantTimeEquals(room.joinCode.uppercase(), code.trim().uppercase())) {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "邀请码不正确"))
            return@post
        }
        store.removeRoom(roomId)
        sampleStore.delete(roomId)
        audit.log("room_close", roomId, "", mapOf("name" to room.name))
        call.respond(mapOf("ok" to true))
    }

    get("/api/teams/{roomId}/samples") {
        val roomId = call.parameters["roomId"].orEmpty()
        val code = call.parameters["code"].orEmpty()
        val room = store.findRoom(roomId)
        if (room == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "房间不存在"))
            return@get
        }
        if (!constantTimeEquals(room.joinCode.uppercase(), code.trim().uppercase())) {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "邀请码不正确"))
            return@get
        }
        val limit = call.parameters["limit"]?.toIntOrNull()?.coerceIn(1, 20_000) ?: 2000
        call.respond(sampleStore.load(roomId, limit))
    }

    /**
     * v1.9：管理员清理房间。
     * 仅当配置 WEILIE_ADMIN_TOKEN 且请求头 X-Admin-Token 匹配时可用。
     * 参数：days（默认 3，1–365）、emptyOnly（默认 true，只删没有成员/区域/标点的空房）
     */
    post("/api/admin/purge-rooms") {
        if (!call.adminConfigured()) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "未配置 WEILIE_ADMIN_TOKEN"))
            return@post
        }
        if (!call.adminOk()) {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "需要 X-Admin-Token"))
            return@post
        }
        val days = call.parameters["days"]?.toLongOrNull()?.coerceIn(1, 365) ?: 3L
        val emptyOnly = (call.parameters["emptyOnly"] ?: "true").toBooleanStrictOrNull() ?: true
        val cutoff = System.currentTimeMillis() - days * 24L * 3600_000L
        val targets = store.allRooms().filter { r ->
            r.lastActiveAt < cutoff && (!emptyOnly || (r.members.isEmpty() && r.zones.isEmpty() && r.markers.isEmpty()))
        }
        var removed = 0
        targets.forEach { r ->
            if (store.removeRoom(r.roomId)) {
                sampleStore.delete(r.roomId)
                audit.log("room_admin_purge", r.roomId, "", mapOf("days" to days.toString(), "emptyOnly" to emptyOnly.toString()))
                removed++
            }
        }
        call.respond(PurgeResult(ok = true, removed = removed, scanned = store.roomCount() + removed))
    }

    get("/api/teams/{roomId}/export") {
        val ip = call.request.origin.remoteHost
        if (!limiter.allow("export:$ip", 20)) {
            call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "请求过于频繁"))
            return@get
        }
        val roomId = call.parameters["roomId"].orEmpty()
        val code = call.parameters["code"].orEmpty()
        val format = (call.parameters["format"] ?: "geojson").lowercase()
        val room = store.findRoom(roomId)
        if (room == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "房间不存在"))
            return@get
        }
        if (!constantTimeEquals(room.joinCode.uppercase(), code.trim().uppercase())) {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "邀请码不正确"))
            return@get
        }
        val samples = sampleStore.load(roomId)
        Metrics.exports.incrementAndGet()
        audit.log("export", roomId, "", mapOf("format" to format, "samples" to samples.size.toString()))
        when (format) {
            "kml" -> call.respondText(
                ServerExports.kml(room, samples),
                ContentType("application", "vnd.google-earth.kml+xml"),
            )
            "csv" -> call.respondText(ServerExports.csv(samples), ContentType.Text.CSV)
            else -> call.respondText(
                ServerExports.geojson(room, samples),
                ContentType.Application.Json,
            )
        }
    }
}
