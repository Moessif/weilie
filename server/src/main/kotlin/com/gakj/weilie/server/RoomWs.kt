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
fun Route.roomWsRoutes(
    store: RoomStore,
    sampleStore: RoomSampleStore,
    audit: AuditLog,
    limiter: RateLimiter,
    tokens: TokenService? = null,
) {
    webSocket("/ws/room/{roomId}") {
        Metrics.wsConnections.incrementAndGet()
        val pathRoomId = call.parameters["roomId"].orEmpty()
        var qUserId = call.request.queryParameters["userId"].orEmpty()
        var qName = call.request.queryParameters["name"].orEmpty()
        var qRole = call.request.queryParameters["role"].orEmpty().ifBlank { "MEMBER" }

        // v1.4：强制令牌模式下，用令牌里的身份覆盖查询参数，防伪造 userId / 抢队长
        if (TokenService.requireToken()) {
            val claims = tokens?.verify(call.request.queryParameters["token"].orEmpty())
            if (claims == null || claims.roomId != pathRoomId) {
                log.warn("拒绝无令牌连接 room={} user={}", pathRoomId, qUserId)
                runCatching { close(CloseReason(CloseReason.Codes.NORMAL, "缺少或无效的入房令牌")) }
                return@webSocket
            }
            qUserId = claims.userId
            qName = claims.name
            qRole = claims.role
        }

        val room = store.findRoom(pathRoomId)
        if (room == null) {
            // 房间不存在：告知后立即关闭
            sendToSession(this, encodeMsg(WsMsg.Bye(qUserId.ifBlank { "?" }, "房间不存在")))
            close(CloseReason(CloseReason.Codes.NORMAL, "房间不存在"))
            return@webSocket
        }

        // v1.12：房间邀请码校验。客户端带了 code 就强校验；未带时看部署开关
        // WEILIE_REQUIRE_JOIN_CODE=1（旧版 App 不带 code，等客户端升级后再打开）。
        val providedCode = call.request.queryParameters["code"].orEmpty().trim()
        if (providedCode.isNotEmpty()) {
            if (!constantTimeEquals(providedCode.trim().uppercase(), room.joinCode.uppercase())) {
                log.warn("拒绝邀请码不匹配的连接 room={} user={} ip={}", pathRoomId, qUserId, call.trustedClientIp())
                sendToSession(this, encodeMsg(WsMsg.Bye(qUserId.ifBlank { "?" }, "邀请码不正确")))
                runCatching { close(CloseReason(CloseReason.Codes.NORMAL, "邀请码不正确")) }
                return@webSocket
            }
        } else if (TokenService.requireJoinCode()) {
            log.warn("拒绝未带邀请码的连接 room={} user={} ip={}", pathRoomId, qUserId, call.trustedClientIp())
            sendToSession(this, encodeMsg(WsMsg.Bye(qUserId.ifBlank { "?" }, "缺少房间邀请码")))
            runCatching { close(CloseReason(CloseReason.Codes.NORMAL, "缺少房间邀请码")) }
            return@webSocket
        } else if (!TokenService.requireToken()) {
            // 兼容模式：记录一次「未验证身份入房」，便于运维审计异常连接
            audit.log("join_unverified", pathRoomId, qUserId, mapOf("ip" to call.trustedClientIp()))
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

                // v1.3：活动时间与消息计数（TTL 清理、/metrics 用）
                room.lastActiveAt = System.currentTimeMillis()
                Metrics.messages.incrementAndGet()

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
                        audit.log(
                            "join", room.roomId, myUserId,
                            mapOf(
                                "name" to (room.members[myUserId]?.name ?: ""),
                                "role" to (room.members[myUserId]?.role ?: ""),
                            ),
                        )
                        // v1.3：首次进房时从磁盘恢复采样，重启后覆盖率不归零
                        if (room.samples.isEmpty()) {
                            val restored = sampleStore.load(room.roomId)
                            if (restored.isNotEmpty()) {
                                room.samples.addAll(restored)
                                refreshZoneStats(room)
                                log.info("恢复采样 room={} 条数={}", room.roomId, restored.size)
                            }
                        }
                        broadcastState(room)
                    }

                    is WsMsg.Loc -> {
                        if (!limiter.allow("loc:$myUserId", 180)) {
                            log.debug("限流 loc room={} user={}", room.roomId, myUserId)
                            continue
                        }
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
                        // v1.3：采样 10s 一次，限 20/min 足够，防止刷统计
                        if (!limiter.allow("sample:$myUserId", 20)) {
                            log.debug("限流 sample room={} user={}", room.roomId, myUserId)
                            continue
                        }
                        room.members.compute(msg.userId) { _, m ->
                            m?.copy(
                                lat = msg.lat,
                                lon = msg.lon,
                                onTarget = msg.onTarget,
                                lastTs = msg.ts,
                                sampleCount = m.sampleCount + 1,
                            )
                        }
                        // v1.2：采样点进环形缓存，供任务区完成度统计
                        // v1.3：同时追加落盘，服务重启后可恢复统计与导出
                        Metrics.samples.incrementAndGet()
                        sampleStore.append(room.roomId, msg)
                        room.samples.add(msg)
                        while (room.samples.size > RoomState.MAX_SAMPLES) {
                            runCatching { room.samples.removeAt(0) }
                        }
                        // 每攒 10 个点重算一次完成度并广播 state（不能每个点都广播，太费流量）
                        if (room.samples.size % 10 == 0 && room.zones.isNotEmpty()) {
                            refreshZoneStats(room)
                            broadcastState(room)
                        } else {
                            // 采样帧转发全员（含发送者）
                            broadcast(room, text)
                        }
                    }

                    is WsMsg.AudioStart -> {
                        // 语音只转发：不做历史、不落库，避免房间快照被音频撑爆
                        broadcast(room, text, exceptUserId = msg.userId)
                    }

                    is WsMsg.AudioChunk -> {
                        // v1.3：音频帧上限，防大帧打爆内存
                        if (msg.data.length > 96_000) {
                            log.warn("丢弃超大音频帧 room={} user={} len={}", room.roomId, msg.userId, msg.data.length)
                            continue
                        }
                        if (!limiter.allow("audio:$myUserId", 600)) continue
                        broadcast(room, text, exceptUserId = msg.userId)
                    }

                    is WsMsg.AudioStop -> {
                        broadcast(room, text, exceptUserId = msg.userId)
                    }

                    is WsMsg.Chat -> {
                        if (!limiter.allow("chat:$myUserId", 30)) continue
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
                                    // 照片标点由服务器分配稳定 id：客户端要能按 id 删除它
                                    id = "ph-" + msg.photoId,
                                    photoCode = msg.code,
                                )
                            )
                            log.info("照片落标 room={} userId={} photoId={}", room.roomId, msg.userId, msg.photoId)
                            broadcastState(room)
                        }
                        broadcast(room, text, exceptUserId = msg.userId)
                    }

                    is WsMsg.Marker -> {
                        if (!limiter.allow("marker:$myUserId", 60)) continue
                        // 安全加固：标点总量上限 + 字段长度上限，防止单连接刷爆内存/快照
                        if (room.markers.size >= RoomState.MAX_MARKERS) {
                            log.warn("标点已达上限，拒绝新增 room={} size={}", room.roomId, room.markers.size)
                            continue
                        }
                        // QA-06：标记入库后广播完整 state（客户端会丢弃 marker 帧，只有 state 能让各端地图刷新）
                        // 老客户端手动标点不带 id → 服务器补一个稳定 id，否则「删除标点」按 id 匹配永远失败
                        val stored = if (msg.id.isBlank()) {
                            msg.copy(id = "m-" + System.nanoTime().toString(36))
                        } else {
                            msg
                        }.copy(
                            name = msg.name.take(64),
                            note = msg.note.take(200),
                            byName = msg.byName.take(64),
                            visibleTo = msg.visibleTo.take(50),
                        )
                        room.markers.add(stored)
                        audit.log("marker", room.roomId, msg.by, mapOf("id" to stored.id, "kind" to stored.kind))
                        broadcastState(room)
                    }

                    is WsMsg.MarkerDelete -> {
                        // 标点只能由创建者本人或队长删除；删完广播 state
                        // 安全加固：原先「房间尚无队长」时任何人都能删除他人标点，改为只认已登记的队长
                        val isCaptain = room.captainUserId != null && room.captainUserId == msg.by
                        val removed = room.markers.removeAll {
                            it.id == msg.id && (it.by == msg.by || isCaptain)
                        }
                        if (removed) {
                            log.info("删除标点 room={} id={} by={}", room.roomId, msg.id, msg.by)
                            audit.log("marker_delete", room.roomId, msg.by, mapOf("id" to msg.id))
                            broadcastState(room)
                        }
                    }

                    is WsMsg.Zone -> {
                        // 仅队长可下发/重划任务范围；replace=true 表示清空旧任务区重新划分
                        // 安全加固：角色只认服务端 members 表里的登记值，不再回退到查询参数自报的 qRole
                        val role = room.members[myUserId]?.role
                        if (role == "CAPTAIN") {
                            if (!limiter.allow("zone:$myUserId", 30)) continue
                            val liveZones = room.zones.count { !it.deleted }
                            if (liveZones >= RoomState.MAX_ZONES) {
                                log.warn("任务区已达上限，拒绝新增 room={} size={}", room.roomId, liveZones)
                                continue
                            }
                            if (msg.replace) {
                                // v1.3：不再物理清空——旧区域标记 deleted 保留审计；统计只算未删除的
                                room.zones.replaceAll { z -> if (z.deleted) z else z.copy(deleted = true) }
                                room.zoneStats.clear()
                            }
                            // v1.2：服务端分配稳定 id，删除与统计都以它为准
                            val stored = if (msg.id.isBlank()) {
                                msg.copy(id = "z-" + System.nanoTime().toString(36))
                            } else {
                                msg
                            }
                            room.zones.add(stored)
                            @Suppress("DEPRECATION")
                            room.zone = stored
                            room.zoneStats[stored.id] = ZoneCoverage.stat(stored, room.samples.toList())
                            audit.log(
                                "zone", room.roomId, stored.by,
                                mapOf("id" to stored.id, "kind" to stored.kind, "replace" to msg.replace.toString()),
                            )
                            broadcastState(room)
                        } else {
                            log.warn(
                                "拒绝非队长下发 zone room={} userId={} role={}",
                                room.roomId, myUserId, role,
                            )
                        }
                    }

                    is WsMsg.ZoneDelete -> {
                        // v1.3：软删除——只标记 deleted，保留数据用于审计/导出；state 只下发未删除的
                        val senderRole = room.members[myUserId]?.role ?: qRole
                        val isCaptain = senderRole == "CAPTAIN"
                        var changed = false
                        room.zones.replaceAll { z ->
                            if (z.id == msg.id && (z.by == msg.by || isCaptain) && !z.deleted) {
                                changed = true
                                z.copy(deleted = true)
                            } else {
                                z
                            }
                        }
                        if (changed) {
                            room.zoneStats.remove(msg.id)
                            @Suppress("DEPRECATION")
                            room.zone = room.zones.lastOrNull { !it.deleted }
                            log.info("软删除任务区 room={} id={} by={}", room.roomId, msg.id, msg.by)
                            audit.log("zone_delete", room.roomId, msg.by, mapOf("id" to msg.id))
                            broadcastState(room)
                        } else {
                            log.warn("拒绝删除任务区 room={} id={} by={} role={}", room.roomId, msg.id, msg.by, senderRole)
                        }
                    }

                    is WsMsg.ZoneTransferRequest -> {
                        val senderRole = room.members[myUserId]?.role ?: qRole
                        val zone = room.zones.firstOrNull { it.id == msg.id && !it.deleted }
                        val target = room.members[msg.to]
                        // v1.11：只有现任队长可以转移任务（归属人降级后不再允许）
                        val canInitiate = zone != null && msg.by == myUserId && senderRole == "CAPTAIN"
                        if (canInitiate && target != null && msg.to != msg.by &&
                            target.role != "OBSERVER" && zone!!.pendingTransferTo.isBlank()
                        ) {
                            room.zones.replaceAll { z ->
                                if (z.id == msg.id) z.copy(pendingTransferTo = msg.to) else z
                            }
                            room.sessions[msg.to]?.let { sendToSession(it, encodeMsg(msg)) }
                            audit.log(
                                "zone_transfer_request", room.roomId, msg.by,
                                mapOf("id" to msg.id, "to" to msg.to),
                            )
                            broadcastState(room)
                        } else {
                            log.warn(
                                "拒绝转移请求 room={} zone={} by={} to={} canInitiate={} target={}",
                                room.roomId, msg.id, msg.by, msg.to, canInitiate, target?.role,
                            )
                        }
                    }

                    is WsMsg.ZoneTransferAccept -> {
                        val zone = room.zones.firstOrNull { it.id == msg.id && !it.deleted }
                        if (zone != null && zone.pendingTransferTo == msg.by && msg.by == myUserId) {
                            room.zones.replaceAll { z ->
                                if (z.id == msg.id) {
                                    z.copy(assignees = listOf(msg.by), pendingTransferTo = "")
                                } else {
                                    z
                                }
                            }
                            val name = room.members[msg.by]?.name ?: msg.by
                            systemChat(room, "任务区「${zone.note.ifBlank { zone.id }}」已转给 $name")
                            audit.log("zone_transfer_accept", room.roomId, msg.by, mapOf("id" to msg.id))
                            broadcastState(room)
                        }
                    }

                    is WsMsg.ZoneTransferDecline -> {
                        val zone = room.zones.firstOrNull { it.id == msg.id && !it.deleted }
                        if (zone != null && zone.pendingTransferTo == msg.by && msg.by == myUserId) {
                            room.zones.replaceAll { z ->
                                if (z.id == msg.id) z.copy(pendingTransferTo = "") else z
                            }
                            room.sessions[zone.by]?.let {
                                sendToSession(it, encodeMsg(msg))
                            }
                            audit.log("zone_transfer_decline", room.roomId, msg.by, mapOf("id" to msg.id))
                            broadcastState(room)
                        }
                    }

                    is WsMsg.ZoneClear -> {
                        val senderRole = room.members[myUserId]?.role ?: qRole
                        if (senderRole == "CAPTAIN" && msg.by == myUserId) {
                            room.zones.replaceAll { if (it.deleted) it else it.copy(deleted = true, pendingTransferTo = "") }
                            room.zoneStats.clear()
                            @Suppress("DEPRECATION")
                            room.zone = null
                            systemChat(room, "队长已清空全部任务区，可重新划区")
                            audit.log("zone_clear", room.roomId, myUserId)
                            broadcastState(room)
                        }
                    }
                    is WsMsg.PromoteRequest -> {
                        // v1.2：队员申请接任 → 只转发给现任队长；同一时刻只保留一条
                        val senderRole = room.members[myUserId]?.role ?: qRole
                        val captainId = room.captainUserId
                        val captainOnline = captainId != null && room.sessions.containsKey(captainId)
                        if (senderRole != "CAPTAIN" && captainId != null && captainOnline) {
                            room.pendingPromote = msg
                            room.sessions[captainId]?.let { sendToSession(it, encodeMsg(msg)) }
                            audit.log("promote_request", room.roomId, msg.from, mapOf("to" to captainId))
                            log.info("队长接任申请 room={} from={} to={}", room.roomId, msg.from, msg.to)
                        } else {
                            log.warn(
                                "拒绝接任申请 room={} from={} role={} 队长在线={}",
                                room.roomId, msg.from, senderRole, captainOnline,
                            )
                        }
                    }

                    is WsMsg.PromoteDecline -> {
                        // 队长拒绝：清掉待处理申请并转发给申请人
                        room.pendingPromote = null
                        room.sessions[msg.to]?.let { sendToSession(it, encodeMsg(msg)) }
                        audit.log("promote_decline", room.roomId, msg.from, mapOf("to" to msg.to))
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
                            room.pendingPromote = null
                            // v1.11：队长变更后，清空旧队长发起的待确认任务转移
                            room.zones.replaceAll { z ->
                                if (z.pendingTransferTo.isNotBlank()) z.copy(pendingTransferTo = "") else z
                            }
                            log.info("队长转让 room={} {} -> {}", room.roomId, myUserId, msg.to)
                            systemChat(room, "队长已转让给 ${target.name}（历史任务区保留）")
                            audit.log("promote", room.roomId, myUserId, mapOf("to" to msg.to, "toName" to target.name))
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
            withContext(NonCancellable) { unregister(room, myUserId, session, audit) }
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
    audit: AuditLog,
) {
    if (userId.isBlank()) return
    if (room.sessions.remove(userId, session)) {
        room.members.remove(userId)
        // 目标离线：清掉指向他的待确认转移，避免任务永远卡在「待确认」
        room.zones.replaceAll { z ->
            if (z.pendingTransferTo == userId) z.copy(pendingTransferTo = "") else z
        }
        log.info("成员离线 room={} userId={}", room.roomId, userId)
        audit.log("leave", room.roomId, userId)
        broadcast(room, encodeMsg(WsMsg.Bye(userId, "disconnect")))
        broadcastState(room)
    }
}

/**
 * 构造并广播 state（成员列表 / zones / markers / 聊天历史），成员按 userId 排序保证输出稳定。
 *
 * 标点可见性（需求二）：visibleTo 为空 = 全队可见；非空时只发给列表里的人 + 创建者本人，
 * 所以这里按会话逐个构造 state，而不是一份 state 广播给所有人。
 */
private suspend fun broadcastState(room: RoomState) {
    val members = room.members.values.sortedBy { it.userId }
    room.sessions.forEach { (uid, session) ->
        val visibleMarkers = room.markers.filter { m ->
            m.visibleTo.isEmpty() || m.by == uid || m.visibleTo.contains(uid)
        }
        @Suppress("DEPRECATION")
        // v1.3：软删除的区域不再下发（数据仍保留在房间快照与磁盘，供导出/审计）
        val visibleZones = room.zones.filter { !it.deleted }
        val state = WsMsg.State(
            roomId = room.roomId,
            members = members,
            zones = visibleZones,
            zone = visibleZones.lastOrNull() ?: room.zone,
            markers = visibleMarkers,
            chats = room.chats.toList(),
            zoneStats = visibleZones.map { z -> room.zoneStats[z.id] ?: WsMsg.ZoneStat(z.id) },
            teamSamples = mergedSamples(room),
        )
        sendToSession(session, encodeMsg(state))
    }
}

/** v1.2：重算房间内全部任务区的完成度（采样攒批后调用） */
private fun refreshZoneStats(room: RoomState) {
    val snapshot = room.samples.toList()
    room.zones.filter { !it.deleted }.forEach { z ->
        room.zoneStats[z.id] = ZoneCoverage.stat(z, snapshot)
    }
}

/** 聊天入历史（QA-12）：超过上限时淘汰最旧的一条，保证列表有界 */
private fun appendChat(room: RoomState, chat: WsMsg.Chat) {
    while (room.chats.size >= RoomState.MAX_CHAT_HISTORY) {
        room.chats.removeAt(0)
    }
    // 安全加固：单条聊天长度上限，避免超长文本反复进入 state 广播与快照
    val capped = if (chat.text.length > 500) chat.copy(text = chat.text.take(500)) else chat
    room.chats.add(capped)
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

/** v1.8：把房间采样降采样到 ≤1500 点后下发（全队覆盖拟合用） */
private fun mergedSamples(room: RoomState, max: Int = 1500): List<SamplePoint> {
    val all = room.samples
    if (all.isEmpty()) return emptyList()
    val stride = (all.size + max - 1) / max
    return all.filterIndexed { i, _ -> i % stride == 0 }
        .map { SamplePoint(it.userId, it.lat, it.lon, it.rsrp, it.onTarget, it.ts) }
}