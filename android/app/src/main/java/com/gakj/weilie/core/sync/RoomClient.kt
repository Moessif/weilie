package com.gakj.weilie.core.sync

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.url
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicBoolean

/** 房间连接状态 */
enum class ConnState { DISCONNECTED, CONNECTING, CONNECTED, RETRYING }

/**
 * 多人房间客户端：连接、自动重连（指数退避 2s→30s 封顶）、状态聚合。
 * 位置上报节流 2s/次；sample 心跳 10s/次。
 */
class RoomClient(
    private val scope: CoroutineScope,
    private val baseUrl: String, // ws://host:port
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val client by lazy {
        HttpClient(CIO) { install(io.ktor.client.plugins.websocket.WebSockets) }
    }

    private val _state = MutableStateFlow(ConnState.DISCONNECTED)
    val state: StateFlow<ConnState> = _state

    private val _room = MutableStateFlow(WsMsg.State(roomId = ""))
    val room: StateFlow<WsMsg.State> = _room

    private val _chats = MutableStateFlow<List<WsMsg.Chat>>(emptyList())
    val chats: StateFlow<List<WsMsg.Chat>> = _chats

    /** 地图标记（可疑设备/到达点等）；State 帧为权威来源，Marker 帧增量合并 */
    private val _markers = MutableStateFlow<List<WsMsg.Marker>>(emptyList())
    val markers: StateFlow<List<WsMsg.Marker>> = _markers

    @Volatile
    private var session: DefaultClientWebSocketSession? = null
    private var rxJob: Job? = null
    private var hello: WsMsg.Hello? = null
    private val running = AtomicBoolean(false)
    private var lastLocSent = 0L
    private var lastSampleSent = 0L

    fun connect(helloMsg: WsMsg.Hello) {
        if (!running.compareAndSet(false, true)) return
        hello = helloMsg
        scope.launch { loop() }
    }

    fun disconnect() {
        running.set(false)
        rxJob?.cancel()
        scope.launch { runCatching { session?.close() } }
        session = null
        _state.value = ConnState.DISCONNECTED
    }

    private suspend fun loop() {
        var backoff = 2000L
        while (running.get()) {
            try {
                _state.value = ConnState.CONNECTING
                val h = hello ?: return
                val s = client.webSocketSession {
                    url("$baseUrl/ws/room/${h.roomId}?userId=${h.userId}&name=${h.name}&role=${h.role}")
                }
                session = s
                _state.value = ConnState.CONNECTED
                backoff = 2000L
                s.send(Frame.Text(json.encodeToString<WsMsg>(h)))
                rxJob = scope.launch { receiveLoop(s) }
                // 等 rx 结束（连接断开）或 running=false
                while (running.get() && s.isActive) delay(500)
                rxJob?.cancel()
            } catch (_: Exception) {
                // 网络异常进入重试
            }
            if (running.get()) {
                _state.value = ConnState.RETRYING
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
        }
    }

    private suspend fun receiveLoop(s: DefaultClientWebSocketSession) {
        for (frame in s.incoming) {
            if (frame !is Frame.Text) continue
            val msg = runCatching { json.decodeFromString<WsMsg>(frame.readText()) }.getOrNull() ?: continue
            when (msg) {
                is WsMsg.State -> {
                    _room.value = msg
                    // 服务端 state 是权威来源：markers 整体覆盖
                    _markers.value = msg.markers.takeLast(200)
                    // 聊天历史随 state 下发：按 ts 合并去重，断线重连不清空
                    mergeChats(msg.chats)
                }
                is WsMsg.ChatEcho -> mergeChats(listOf(msg.chat))
                is WsMsg.Marker -> appendMarker(msg)
                // QA-07：服务端对 Loc/SamplePing 是「改写成员后原样转发」，
                // 客户端必须就地更新成员状态，否则队友位置/采样数只等下一条 state 广播（可能很久）
                is WsMsg.Loc -> patchMember(msg.userId) {
                    it.copy(lat = msg.lat, lon = msg.lon, acc = msg.acc, onTarget = msg.onTarget, lastTs = msg.ts)
                }
                is WsMsg.SamplePing -> patchMember(msg.userId) {
                    it.copy(
                        lat = msg.lat,
                        lon = msg.lon,
                        onTarget = msg.onTarget,
                        lastTs = msg.ts,
                        sampleCount = it.sampleCount + 1,
                    )
                }
                is WsMsg.Pong -> Unit
                is WsMsg.Bye -> Unit
                is WsMsg.PhotoAck -> Unit
                else -> Unit
            }
        }
    }

    /** 就地更新某个成员的状态（Loc/SamplePing 增量帧用）；找不到成员时忽略 */
    private fun patchMember(userId: String, f: (MemberView) -> MemberView) {
        val cur = _room.value ?: return
        val idx = cur.members.indexOfFirst { it.userId == userId }
        if (idx < 0) return
        val updated = cur.members.toMutableList()
        updated[idx] = f(updated[idx])
        _room.value = cur.copy(members = updated)
    }

    /** 增量合并一个 Marker：按 (by, ts) 去重，最多保留 200 条 */
    private fun appendMarker(m: WsMsg.Marker) {
        val cur = _markers.value
        if (cur.any { it.by == m.by && it.ts == m.ts }) return
        _markers.value = (cur + m).takeLast(200)
    }

    /** 聊天合并去重（按 ts，服务端消息优先），断线重连后本地历史不丢 */
    private fun mergeChats(remote: List<WsMsg.Chat>) {
        if (remote.isEmpty()) return
        _chats.value = (_chats.value + remote)
            .associateBy { it.ts }
            .values
            .sortedBy { it.ts }
            .takeLast(200)
    }

    suspend fun send(msg: WsMsg) {
        val s = session ?: return
        if (!s.isActive) return
        runCatching { s.send(Frame.Text(json.encodeToString(msg))) }
    }

    /** 当前是否已建立会话（协同地图/轮询侧判断是否需要上报；可跨协程安全读取） */
    fun hasSession(): Boolean = session?.isActive == true

    /** 位置/采样节流上报（SamplingController 每秒调用，内部按间隔过滤） */
    suspend fun maybeReportLoc(lat: Double, lon: Double, acc: Double?, onTarget: Boolean, userId: String) =
        report(userId, lat, lon, acc, onTarget, rsrp = null)

    /**
     * 位置 + 采样上报（协同地图直接调用）：位置 2s 节流、采样 10s 节流；
     * [rsrp] 随 SamplePing 上报，供服务端统计目标小区覆盖。
     */
    suspend fun reportSample(
        userId: String,
        lat: Double,
        lon: Double,
        rsrp: Int?,
        onTarget: Boolean,
        acc: Double?,
    ) = report(userId, lat, lon, acc, onTarget, rsrp)

    private suspend fun report(
        userId: String,
        lat: Double,
        lon: Double,
        acc: Double?,
        onTarget: Boolean,
        rsrp: Int?,
    ) {
        val now = System.currentTimeMillis()
        if (now - lastLocSent < 2000) return
        lastLocSent = now
        send(WsMsg.Loc(userId, lat, lon, acc, now, onTarget))
        if (now - lastSampleSent >= 10_000) {
            lastSampleSent = now
            send(WsMsg.SamplePing(userId, lat, lon, rsrp, onTarget, now))
        }
    }

    suspend fun chat(text: String, userId: String, name: String) =
        send(WsMsg.Chat(userId, name, text, System.currentTimeMillis()))

    suspend fun marker(
        lat: Double,
        lon: Double,
        kind: String,
        note: String,
        userId: String,
        name: String,
        photoId: String = "",
        deviceType: String = "",
    ) = send(
        WsMsg.Marker(userId, name, lat, lon, kind, note, System.currentTimeMillis(), photoId, deviceType),
    )

    /** 拍照打点：把取证照片（photoId 来自 PhotoRecord.id）登记到房间，供其他人点标点翻图 */
    suspend fun photo(marker: WsMsg.Marker) {
        if (marker.photoId.isBlank()) return
        send(
            WsMsg.PhotoRef(
                userId = marker.by,
                photoId = marker.photoId,
                lat = marker.lat,
                lon = marker.lon,
                note = marker.note,
                kind = marker.kind,
                ts = marker.ts,
            ),
        )
    }

    /** 队长转让：请求服务端把 to 提升为 CAPTAIN（服务端校验发送者现任队长） */
    suspend fun promote(from: String, to: String) =
        send(WsMsg.Promote(from, to, System.currentTimeMillis()))
}
