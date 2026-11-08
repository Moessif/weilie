package com.gakj.weilie.server

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
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
fun Route.teamRoutes(store: RoomStore) {
    // 创建队伍
    post("/api/teams") {
        val req = call.receive<CreateTeamRequest>()
        val room = store.createRoom(req.name)
        call.respond(HttpStatusCode.OK, TeamInfo(roomId = room.roomId, joinCode = room.joinCode))
    }

    // 按邀请码查房间（客户端输入 6 位码加入时调用）
    get("/api/teams/by-code/{code}") {
        val room = store.findByCode(call.parameters["code"].orEmpty())
        if (room == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "邀请码不存在"))
        } else {
            call.respond(TeamInfo(roomId = room.roomId, joinCode = room.joinCode))
        }
    }

    // 健康检查
    get("/api/health") {
        call.respond(mapOf("ok" to true))
    }
}
