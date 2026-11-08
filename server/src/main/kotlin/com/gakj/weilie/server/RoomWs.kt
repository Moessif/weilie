package com.gakj.weilie.server

import io.ktor.server.routing.Route
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("weilie.ws")

/** 单房间人数上限（QA-15）：超过后新成员被拒绝入房 */
private const val MAX_MEMBERS = 12

/**
 * 房间 WebSocket 路由：/ws/room/{roomId}?userId=&name=&role=
 *
 * 处理规则（与客户端 Protocol.kt 一一对应）：
 * - hello  -> 注册成员与会话（同 userId 顶号时关闭旧连接），向全员广播 state；
 *             曾是队长的 userId 强制恢复 CAPTAIN；房间无队长时首位 MEMBER 自动提升；
 *             房间满员（最多 12 人）时新成员被拒绝（bye + 关闭）
 * - loc    -> 更新成员视图(lat/lon/acc/onTarget/lastTs)后，原样转发给其他成员
 * - sample -> 成员 sampleCount+1 并更新位置，原样转发全员（含发送者）
 * - chat   -> 入聊天历史（最近 200 条）后包装为 chatEcho 广播全员（含发送者）
 * - photo  -> 先回 photoAck(ok=true) 给发送者，转发给其他成员，并把拍照点落成
 *             地图标记（按 photoId 去重）后广播 state
 * - marker -> 追加进房间标记列表并广播完整 state（客户端不消费 marker 帧）
 * - zone   -> 仅 CAPTAIN 角色可下发：记录并广播 state，非队长忽略并记日志
 * - ping   -> 回 pong（原样回传 ts）
 * - 断开   -> 移除成员，广播 bye 与最新 state
 * - 未知 type 或解析失败 -> 忽略该帧，保证前向兼容
 */
fun Route.roomWsRoutes(store: RoomStore) {
    webSocket("/ws/room/{roomId}") {
        val pathRoomId = call.parameters["roomId"].orEmpty()
        val qUserId = call.request.queryParameters["userId"].orEmpty()
        val qName = call.request.queryParameters["name"].orEmpty()
        val qRole = call.request.queryParameters["role"].orEmpty().ifBlank { "MEMBER" }

        val room = store.findRoom(pathRoomId)
        if (room == null) {
            // 房间不存在：告知后立即关闭
            sendToSession(this, encodeMsg(WsMsg.Bye(qUserId.ifBlank { "?" }, "房间不存在")))
            close(CloseReason(CloseReason.Codes.NORMAL, "房间不存在"))
            return@webSocket
        }
        log.info("WS 连接建立 room={} userId={} name={} role={}", pathRoomId, qUserId, qName, qRole)

        // 捕获当前会话引用（finally 清理时不能依赖 this，防止被内层接收者覆盖）
        val session: DefaultWebSocketServerSession = this

        // 发 hello 前若直接发其他消息，用查询参数身份兜底注册
        var registered = false
        var myUserId = qUserId

        try {
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val text = frame.readText()
                // 优先按多态 WsMsg 解析（带 "type" 判别字段的标准帧）；
                // 失败时兜底按具体 Hello 解析——旧版客户端静态类型为 Hello 时
                // 发出的 hello 帧不带 "type"，此处保证其仍能完成注册。
                val msg: WsMsg? = runCatching { wsJson.decodeFromString<WsMsg>(text) }.getOrNull()
                    ?: runCatching { wsJson.decodeFromString<WsMsg.Hello>(text) }.getOrNull()?.also {
                        log.info("hello 帧缺少 type 判别字段，已按兼容模式解析 room={} userId={}", pathRoomId, it.userId)
                    }
                if (msg == null) continue

                // 未注册且不是 hello：能从查询参数拿到 userId 就兜底注册
                if (msg !is WsMsg.Hello && !registered) {
                    if (myUserId.isBlank()) continue
                    if (isRoomFull(room, myUserId)) {
                        rejectRoomFull(this, myUserId)
                        break
                    }
                    register(room, myUserId, qName, qRole, this)
                    registered = true
                }

                when (msg) {
                    is WsMsg.Hello -> {
                        myUserId = msg.userId.ifBlank { qUserId }
                        if (myUserId.isBlank()) continue
                        // QA-15：房间满员且该 userId 是新面孔时拒绝入房
                        if (isRoomFull(room, myUserId)) {
                            rejectRoomFull(this, myUserId)
                            break
                        }
                        val prevRole = register(room, myUserId, msg.name.ifBlank { qName }, msg.role.ifBlank { qRole }, this)
                        registered = true
                        // QA-08：首次以 CAPTAIN 身份出现时登记队长 userId——
                        // 成员离线会被从 members 移除，恢复队长身份必须靠这个跨断线字段
                        if (room.captainUserId == null && room.members[myUserId]?.role == "CAPTAIN") {
                            room.captainUserId = myUserId
                        }
                        val wasCaptain = prevRole == "CAPTAIN" || room.captainUserId == myUserId
                        // 已有其他队长在线时不再恢复/提升，保证全程单队长
                        val otherCaptainExists = room.members.values.any { it.role == "CAPTAIN" && it.userId != myUserId }
                        if (wasCaptain && !otherCaptainExists && room.members[myUserId]?.role != "CAPTAIN") {
                            // 队长掉线重进（即使是 force-stop 后以 MEMBER 身份回来）：恢复 CAPTAIN
                            room.members.compute(myUserId) { _, m -> m?.copy(role = "CAPTAIN") }
                            systemChat(room, "队长已恢复")
                            log.info("队长重连恢复角色 room={} userId={}", room.roomId, myUserId)
                        } else if (otherCaptainExists && room.captainUserId != myUserId &&
                            room.members[myUserId]?.role == "CAPTAIN"
                        ) {
                            // 已有队长在线时，后来者即使请求 CAPTAIN 也只能当队员（保证全程单队长）
                            room.members.compute(myUserId) { _, m -> m?.copy(role = "MEMBER") }
                            log.info("已有队长，请求 CAPTAIN 降为 MEMBER room={} userId={}", room.roomId, myUserId)
                        } else if (room.members.values.none { it.role == "CAPTAIN" }) {
                            // 房间没有任何队长（队长彻底失联）：首个加入者自动提升（幂等：有队长就不动）
                            room.members.compute(myUserId) { _, m -> m?.copy(role = "CAPTAIN") }
                            room.captainUserId = myUserId
                            room.members[myUserId]?.let { systemChat(room, "${it.name} 已自动成为队长") }
                            log.info("自动提升队长 room={} userId={}", room.roomId, myUserId)
                        }
                        // 向房间全员（含新成员）广播最新 state
                        broadcastState(room)
                    }

                    is WsMsg.Loc -> {
                        room.members.compute(msg.userId) { _, m ->
                            m?.copy(
                                lat = msg.lat,
                                lon = msg.lon,
                                acc = msg.acc,
                                onTarget = msg.onTarget,
                                lastTs = msg.ts,
                            )
                        }
                        // 原样转发给其他成员
                        broadcast(room, text, exceptUserId = msg.userId)
                    }

                    is WsMsg.SamplePing -> {
                        room.members.compute(msg.userId) { _, m ->
                            m?.copy(
                                lat = msg.lat,
                                lon = msg.lon,
                                onTarget = msg.onTarget,
                                lastTs = msg.ts,
                                sampleCount = m.sampleCount + 1,
                            )
                        }
                        // 采样帧转发全员（含发送者）
                        broadcast(room, text)
                    }

                    is WsMsg.Chat -> {
                        // QA-12：聊天入历史（保留最近 200 条），再包装 chatEcho 广播全员（含发送者）
                        appendChat(room, msg)
                        broadcast(room, encodeMsg(WsMsg.ChatEcho(msg)))
                    }

                    is WsMsg.PhotoRef -> {
                        // 照片仅传引用：先回执，再转发给其他成员
                        sendToSession(this, encodeMsg(WsMsg.PhotoAck(msg.photoId, ok = true, url = null)))
                        // QA-06②：拍照点同时落成地图标记并广播 state，让全员地图出现可验真的照片标点
                        if (msg.photoId.isNotBlank() && room.markers.none { it.photoId == msg.photoId }) {
                            val byName = room.members[msg.userId]?.name ?: msg.userId
                            room.markers.add(
                                WsMsg.Marker(
                                    by = msg.userId,
                                    byName = byName,
                                    lat = msg.lat,
                                    lon = msg.lon,
                                    kind = msg.kind,
                                    note = msg.note,
                                    ts = msg.ts,
                                    photoId = msg.photoId,
                                )
                            )
                            log.info("照片落标 room={} userId={} photoId={}", room.roomId, msg.userId, msg.photoId)
                            broadcastState(room)
                        }
                        broadcast(room, text, exceptUserId = msg.userId)
                    }

                    is WsMsg.Marker -> {
                        // QA-06：标记入库后广播完整 state（客户端会丢弃 marker 帧，只有 state 能让各端地图刷新）
                        room.markers.add(msg)
                        broadcastState(room)
                    }

                    is WsMsg.Zone -> {
                        // 仅队长可下发/重划任务范围；replace=true 表示清空旧任务区重新划分
                        val role = room.members[myUserId]?.role ?: qRole
                        if (role == "CAPTAIN") {
                            if (msg.replace) room.zones.clear()
                            room.zones.add(msg)
                            @Suppress("DEPRECATION")
                            room.zone = msg
                            broadcastState(room)
                        } else {
                            log.warn(
                                "拒绝非队长下发 zone room={} userId={} role={}",
                                room.roomId, myUserId, role,
                            )
                        }
                    }

                    is WsMsg.Promote -> {
                        // 队长转让：仅现任队长可发起，目标必须在房间内
                        val senderRole = room.members[myUserId]?.role ?: qRole
                        val target = room.members[msg.to]
                        if (senderRole == "CAPTAIN" && target != null && msg.to != myUserId) {
                            room.members.compute(msg.to) { _, m -> m?.copy(role = "CAPTAIN") }
                            room.members.compute(myUserId) { _, m -> m?.copy(role = "MEMBER") }
                            // QA-08：队长身份载体同步转移，保证新队长重连后也能恢复
                            room.captainUserId = msg.to
                            log.info("队长转让 room={} {} -> {}", room.roomId, myUserId, msg.to)
                            systemChat(room, "队长已转让给 ${target.name}")
                            broadcastState(room)
                        } else {
                            log.warn(
                                "拒绝队长转让 room={} userId={} role={} to={} target在线={}",
                                room.roomId, myUserId, senderRole, msg.to, target != null,
                            )
                        }
                    }

                    is WsMsg.Ping -> sendToSession(this, encodeMsg(WsMsg.Pong(msg.ts)))

                    // 以下为服务端下发类型，客户端发来时忽略
                    is WsMsg.State, is WsMsg.ChatEcho, is WsMsg.PhotoAck, is WsMsg.Bye, is WsMsg.Pong -> Unit                }
            }
        } catch (e: Exception) {
            log.info("WS 连接断开 room={} userId={} err={}", room.roomId, myUserId, e.message)
        } finally {
            // 服务关闭等协程取消场景下也保证成员清理完成
            withContext(NonCancellable) { unregister(room, myUserId, session) }
        }
    }
}

/**
 * 注册成员与会话。同一 userId 顶号时关闭旧会话；
 * 重连场景保留该成员此前累计的位置与 sampleCount。
 * QA-08：若该 userId 之前在本房间内是 CAPTAIN，则忽略查询参数强制保持 CAPTAIN。
 * 返回该成员注册前的角色（新成员返回 null）。
 */
private suspend fun register(
    room: RoomState,
    userId: String,
    name: String,
    role: String,
    session: DefaultWebSocketServerSession,
): String? {
    val old = room.sessions.put(userId, session)
    if (old != null && old !== session) {
        runCatching { old.close(CloseReason(CloseReason.Codes.NORMAL, "该账号在其他设备上线")) }
    }
    val prev = room.members[userId]
    room.members[userId] = MemberView(
        userId = userId,
        name = name.ifBlank { prev?.name ?: userId },
        role = if (prev?.role == "CAPTAIN") "CAPTAIN" else role.ifBlank { prev?.role ?: "MEMBER" },
        lat = prev?.lat,
        lon = prev?.lon,
        acc = prev?.acc,
        onTarget = prev?.onTarget ?: false,
        lastTs = prev?.lastTs ?: 0,
        sampleCount = prev?.sampleCount ?: 0,
    )
    return prev?.role
}

/**
 * 会话关闭清理：仅当当前会话仍是登记会话时移除成员（顶号后旧连接断开不触发），
 * 然后向剩余成员广播 bye 与最新 state。
 * QA-08：队长身份（captainUserId）不随离线清除，重连时据此恢复。
 */
private suspend fun unregister(
    room: RoomState,
    userId: String,
    session: DefaultWebSocketServerSession,
) {
    if (userId.isBlank()) return
    if (room.sessions.remove(userId, session)) {
        room.members.remove(userId)
        log.info("成员离线 room={} userId={}", room.roomId, userId)
        broadcast(room, encodeMsg(WsMsg.Bye(userId, "disconnect")))
        broadcastState(room)
    }
}

/** 构造并广播 state（成员列表 / zones / markers / 聊天历史），成员按 userId 排序保证输出稳定 */
private suspend fun broadcastState(room: RoomState) {
    @Suppress("DEPRECATION")
    val state = WsMsg.State(
        roomId = room.roomId,
        members = room.members.values.sortedBy { it.userId },
        zones = room.zones.toList(),
        zone = room.zones.lastOrNull() ?: room.zone,
        markers = room.markers.toList(),
        chats = room.chats.toList(),
    )
    broadcast(room, encodeMsg(state))
}

/** 聊天入历史（QA-12）：超过上限时淘汰最旧的一条，保证列表有界 */
private fun appendChat(room: RoomState, chat: WsMsg.Chat) {
    while (room.chats.size >= RoomState.MAX_CHAT_HISTORY) {
        room.chats.removeAt(0)
    }
    room.chats.add(chat)
}

/** 系统聊天（QA-08/QA-12）：以「系统」名义广播给全员，并写入聊天历史供重连后补看 */
private suspend fun systemChat(room: RoomState, text: String) {
    val chat = WsMsg.Chat(
        from = "system",
        fromName = "系统",
        text = text,
        ts = System.currentTimeMillis(),
    )
    appendChat(room, chat)
    broadcast(room, encodeMsg(WsMsg.ChatEcho(chat)))
}

/** QA-15：房间满员且该 userId 不是房间内已有成员（重连不计入容量） */
private fun isRoomFull(room: RoomState, userId: String): Boolean =
    room.members.size >= MAX_MEMBERS && !room.members.containsKey(userId)

/** QA-15：满员拒绝——回 bye 说明原因后关闭连接 */
private suspend fun rejectRoomFull(session: DefaultWebSocketServerSession, userId: String) {
    log.warn("拒绝入房：房间已满 userId={}", userId)
    sendToSession(session, encodeMsg(WsMsg.Bye(userId, "房间已满（最多 12 人）")))
    session.close(CloseReason(CloseReason.Codes.NORMAL, "房间已满"))
}

/** 向房间内全部会话发送文本帧；exceptUserId 非空时跳过该成员 */
private suspend fun broadcast(room: RoomState, text: String, exceptUserId: String? = null) {
    room.sessions.forEach { (uid, session) ->
        if (exceptUserId != null && uid == exceptUserId) return@forEach
        sendToSession(session, text)
    }
}

/** 单会话发送，吞掉连接已关闭等异常（发送失败不影响其他成员） */
private suspend fun sendToSession(session: DefaultWebSocketServerSession, text: String) {
    runCatching { session.send(Frame.Text(text)) }
}
