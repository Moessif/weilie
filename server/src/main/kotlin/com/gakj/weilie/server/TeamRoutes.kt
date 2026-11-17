package com.gakj.weilie.server

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

/** POST /api/teams 请求体 */
@Serializable
data class CreateTeamRequest(val name: String = "未命名队伍")

/** 队伍信息：roomId + 6 位数字邀请码（创建与按码查询共用） */
@Serializable
data class TeamInfo(val roomId: String, val joinCode: String)

/**
 * REST 路由：
 * - POST /api/teams                创建队伍 -> {roomId, joinCode}
 * - GET  /api/teams/by-code/{code} 按邀请码查房间 -> {roomId, joinCode} 或 404
 * - GET  /api/health               健康检查 -> {"ok":true}
 */
@Serializable
data class TokenRequest(
    val code: String = "",
    val userId: String = "",
    val name: String = "",
    val role: String = "",
)

/** 令牌响应：必须是强类型，Ktor 无法序列化 String+Int 混合 Map */
@Serializable
data class TokenResponse(val token: String, val expiresInSec: Int)

fun Route.teamRoutes(
    store: RoomStore,
    limiter: RateLimiter = RateLimiter(),
    audit: AuditLog? = null,
    tokens: TokenService? = null,
) {
    // 创建队伍（v1.3：按 IP 限流 10 次/分钟，防建房刷爆内存与快照）
    post("/api/teams") {
        val ip = call.request.origin.remoteHost
        if (!limiter.allow("team_create:$ip", 10)) {
            call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "创建过于频繁，请稍后再试"))
            return@post
        }
        val req = call.receive<CreateTeamRequest>()
        val room = store.createRoom(req.name.take(40))
        audit?.log("room_create", room.roomId, "", mapOf("name" to room.name, "ip" to ip))
        call.respond(HttpStatusCode.OK, TeamInfo(roomId = room.roomId, joinCode = room.joinCode))
    }

    // 按邀请码查房间（客户端输入邀请码加入时调用）
    get("/api/teams/by-code/{code}") {
        val ip = call.request.origin.remoteHost
        // v1.12：邀请码枚举防护——单 IP 10 次/分钟（原 60 次），外加全局 600 次/分钟熔断，
        // 阻断分布式扫描 6 位纯数字码（旧房间仍是 10^6 空间）。
        if (!limiter.allow("team_lookup:$ip", 10) || !limiter.allow("team_lookup_global", 600)) {
            call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "查询过于频繁"))
            return@get
        }
        val rawCode = call.parameters["code"].orEmpty().trim()
        if (rawCode.length !in 4..16 || rawCode.any { !it.isLetterOrDigit() }) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "邀请码格式不正确"))
            return@get
        }
        val room = store.findByCode(rawCode)
        if (room == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "邀请码不存在"))
        } else {
            call.respond(TeamInfo(roomId = room.roomId, joinCode = room.joinCode))
        }
    }

    // v1.4：凭队伍码换取短时效入房令牌（无状态 HMAC，服务端不落库）
    post("/api/teams/{roomId}/token") {
        val ip = call.request.origin.remoteHost
        if (!limiter.allow("token:$ip", 20)) {
            call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "请求过于频繁"))
            return@post
        }
        val svc = tokens
        if (svc == null) {
            call.respond(HttpStatusCode.NotImplemented, mapOf("error" to "令牌服务未启用"))
            return@post
        }
        val roomId = call.parameters["roomId"].orEmpty()
        val room = store.findRoom(roomId)
        if (room == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "房间不存在"))
            return@post
        }
        val req = runCatching { call.receive<TokenRequest>() }.getOrNull()
        if (req == null || !constantTimeEquals(req.code.trim().uppercase(), room.joinCode.uppercase())) {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "队伍码不正确"))
            return@post
        }
        if (req.userId.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "userId 不能为空"))
            return@post
        }
        // v1.12（审计 M2）：令牌接口不能自封队长——角色只允许 MEMBER/OBSERVER，
        // 队长只能由房间内已有的队长通过 Promote 帧授予，否则拿到邀请码的人就能直接夺权。
        val role = when (req.role.uppercase()) {
            "OBSERVER" -> "OBSERVER"
            else -> "MEMBER"
        }
        val token = svc.issue(roomId, req.userId, req.name.take(24), role)
        // 审计：谁在何时换了令牌（客户端是否已升级，可直接看这条）
        audit?.log("token", roomId, req.userId, mapOf("role" to role))
        call.respond(TokenResponse(token = token, expiresInSec = 12 * 3600))
    }

    // 健康检查
    get("/api/health") {
        call.respond(mapOf("ok" to true))
    }
}
