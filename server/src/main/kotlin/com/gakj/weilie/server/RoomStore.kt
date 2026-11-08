package com.gakj.weilie.server

import io.ktor.server.websocket.DefaultWebSocketServerSession
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

/**
 * 单个房间的全部内存态。线程模型：多个 WS 会话并发读写，
 * 统一使用并发容器；zone 用 @Volatile 保证可见性。
 */
class RoomState(
    val roomId: String,
    val name: String,
    val joinCode: String,
    val createdAt: Long,
) {
    /** userId -> 成员视图（位置/角色/采样计数等聚合信息） */
    val members = ConcurrentHashMap<String, MemberView>()

    /** userId -> 当前活跃 WS 会话（同一 userId 顶号时旧会话被关闭） */
    val sessions = ConcurrentHashMap<String, DefaultWebSocketServerSession>()

    /** 队长最近一次下发的任务范围（CIRCLE / POLYGON），仅队长可更新 */
    @Volatile
    @Deprecated("用 zones；v1.0 单块字段仅为快照兼容保留")
    var zone: WsMsg.Zone? = null

    /** 任务区全部分块（v1.1：多块任务划分；每块一条 Zone，replace=true 清空重划） */
    val zones = CopyOnWriteArrayList<WsMsg.Zone>()

    /** 房间全部地图标记，只追加 */
    val markers = CopyOnWriteArrayList<WsMsg.Marker>()

    /** 聊天历史（QA-12）：最多保留 MAX_CHAT_HISTORY 条，随 state 下发、随快照持久化 */
    val chats = CopyOnWriteArrayList<WsMsg.Chat>()

    /** 本房间的队长 userId（QA-08）：跨断线/重连保持，成员离线不清除，重进时据此恢复队长身份 */
    @Volatile
    var captainUserId: String? = null

    companion object {
        /** 聊天历史上限 */
        const val MAX_CHAT_HISTORY = 200
    }
}

/** 快照文件中的单房间持久化数据（成员与在线会话属瞬态，不持久化） */
@Serializable
data class RoomSnapshot(
    val roomId: String,
    val name: String,
    val joinCode: String,
    val createdAt: Long,
    /** @deprecated v1.0 单块字段，读取旧快照时迁移进 zones */
    @Deprecated("用 zones") val zone: WsMsg.Zone? = null,
    val zones: List<WsMsg.Zone> = emptyList(),
    val markers: List<WsMsg.Marker> = emptyList(),
    val chats: List<WsMsg.Chat> = emptyList(),
    /** 队长 userId（QA-08）：重启后仍能识别队长身份 */
    val captainUserId: String? = null,
)

/** 快照文件根结构：data/snapshot.json */
@Serializable
data class SnapshotFile(
    val savedAt: Long,
    val rooms: List<RoomSnapshot> = emptyList(),
)

/**
 * 内存房间仓库（无数据库）：
 * - rooms：roomId -> RoomState；codeIndex：joinCode -> roomId；
 * - 每 30 秒由 Application 层调度 saveSnapshot()，启动时 loadSnapshot() 恢复。
 */
class RoomStore(private val dataDir: Path) {

    private val rooms = ConcurrentHashMap<String, RoomState>()

    /** 邀请码 -> roomId 反查索引 */
    private val codeIndex = ConcurrentHashMap<String, String>()

    private val snapshotMutex = Mutex()

    /** 创建队伍：生成 roomId 与全局唯一的 6 位数字邀请码 */
    @Synchronized
    fun createRoom(name: String): RoomState {
        val code = randomJoinCode()
        val roomId = newRoomId()
        val room = RoomState(roomId, name.ifBlank { "未命名队伍" }, code, System.currentTimeMillis())
        rooms[roomId] = room
        codeIndex[code] = roomId
        return room
    }

    fun findRoom(roomId: String): RoomState? = rooms[roomId]

    fun findByCode(code: String): RoomState? = codeIndex[code]?.let { rooms[it] }

    fun roomCount(): Int = rooms.size

    /** 当前在线会话总数（未发 hello 的连接不计入） */
    fun onlineMemberCount(): Int = rooms.values.sumOf { it.sessions.size }

    /** 6 位数字邀请码，类锁保证不重复 */
    private fun randomJoinCode(): String {
        var code: String
        do {
            code = Random.nextInt(1_000_000).toString().padStart(6, '0')
        } while (codeIndex.containsKey(code))
        return code
    }

    /** 12 位十六进制短 roomId */
    private fun newRoomId(): String = UUID.randomUUID().toString().replace("-", "").take(12)

    // ---------------- 快照持久化 ----------------

    /**
     * 立即写一次快照：先写临时文件再原子替换，进程被杀也不会留下半个坏文件。
     * 目录可用环境变量 WEILIE_DATA_DIR 覆盖（Docker 内挂载 /app/data）。
     */
    suspend fun saveSnapshot() = snapshotMutex.withLock {
        val snapshot = SnapshotFile(
            savedAt = System.currentTimeMillis(),
            rooms = rooms.values.map { r ->
                @Suppress("DEPRECATION")
                RoomSnapshot(
                    roomId = r.roomId,
                    name = r.name,
                    joinCode = r.joinCode,
                    createdAt = r.createdAt,
                    zone = r.zone,
                    zones = r.zones.toList(),
                    markers = r.markers.toList(),
                    chats = r.chats.toList(),
                    captainUserId = r.captainUserId,
                )
            },
        )
        Files.createDirectories(dataDir)
        val target = dataDir.resolve("snapshot.json")
        val tmp = dataDir.resolve("snapshot.json.tmp")
        Files.writeString(tmp, wsJson.encodeToString(snapshot))
        Files.move(
            tmp, target,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    }

    /**
     * 启动时从快照恢复：只恢复房间元信息 / 邀请码 / zone / markers / 聊天历史；
     * 成员列表与 WS 会话是瞬态，由客户端重连后重新 hello 建立。
     * 快照缺失或损坏时返回 0 并空库启动，保证服务可用。
     */
    fun loadSnapshot(): Int {
        val file = dataDir.resolve("snapshot.json")
        if (!Files.exists(file)) return 0
        return try {
            val snap = wsJson.decodeFromString(SnapshotFile.serializer(), Files.readString(file))
            var restored = 0
            snap.rooms.forEach { rs ->
                if (!rooms.containsKey(rs.roomId) && !codeIndex.containsKey(rs.joinCode)) {
                    val room = RoomState(rs.roomId, rs.name, rs.joinCode, rs.createdAt)
                    @Suppress("DEPRECATION")
                    room.zone = rs.zone ?: rs.zones.lastOrNull()
                    room.zones.addAll(rs.zones.ifEmpty { listOfNotNull(rs.zone) })
                    room.markers.addAll(rs.markers)
                    room.chats.addAll(rs.chats)
                    room.captainUserId = rs.captainUserId
                    rooms[room.roomId] = room
                    codeIndex[room.joinCode] = room.roomId
                    restored++
                }
            }
            restored
        } catch (e: Exception) {
            println("快照恢复失败，空库启动：${e.message}")
            0
        }
    }
}
