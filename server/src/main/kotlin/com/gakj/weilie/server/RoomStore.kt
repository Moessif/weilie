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

    /** v1.3：最近一次活动时间，供 TTL 清理 */
    @Volatile
    var lastActiveAt: Long = createdAt

    /** 采样点环形缓存（v1.2）：用于统计任务区完成度；超出上限丢弃最旧的 */
    val samples = CopyOnWriteArrayList<WsMsg.SamplePing>()

    /** 待处理的队长接任申请（v1.2）：同一时刻只保留一条 */
    @Volatile
    var pendingPromote: WsMsg.PromoteRequest? = null

    /** zoneId -> 最近一次算出的完成度（v1.2） */
    val zoneStats = ConcurrentHashMap<String, WsMsg.ZoneStat>()

    companion object {
        /** 聊天历史上限 */
        const val MAX_CHAT_HISTORY = 200

        /** 采样缓存上限（约 8 小时 × 1s 的心跳，足够统计用） */
        const val MAX_SAMPLES = 20_000

        /** 标点数量上限（安全加固：防止单连接无限刷标点把 state 快照撑爆） */
        const val MAX_MARKERS = 2_000

        /** 未删除任务区上限 */
        const val MAX_ZONES = 200
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

    /** v1.3：运维接口用的房间快照列表 */
    fun allRooms(): List<RoomState> = rooms.values.toList()

    /** v1.3：关闭房间（连同邀请码索引一起移除） */
    fun removeRoom(roomId: String): Boolean {
        val room = rooms.remove(roomId) ?: return false
        codeIndex.remove(room.joinCode)
        return true
    }

    /** v1.3：清理长时间无活动的房间，返回清理数量 */
    fun purgeIdle(ttlMs: Long): List<RoomState> {
        val now = System.currentTimeMillis()
        val idle = rooms.values.filter { now - it.lastActiveAt > ttlMs }
        return idle.filter { removeRoom(it.roomId) }
    }

    /** 当前在线会话总数（未发 hello 的连接不计入） */
    fun onlineMemberCount(): Int = rooms.values.sumOf { it.sessions.size }

    /**
     * 邀请码（v1.12）：由 6 位纯数字改为 8 位字母数字（去易混字符），
     * 熵从 10^6 提到约 32^8 ≈ 1.1e12，显著抬高枚举难度。
     * 旧房间的 6 位数字码仍可被 [findByCode] 命中，兼容未升级客户端。
     */
    private fun randomJoinCode(): String {
        var code: String
        do {
            code = buildString(JOIN_CODE_LENGTH) {
                repeat(JOIN_CODE_LENGTH) { append(JOIN_CODE_ALPHABET[joinCodeRandom.nextInt(JOIN_CODE_ALPHABET.length)]) }
            }
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

// v1.12（审计 M3）：邀请码使用 8 位字母数字（去掉易混的 0/O/1/I），并由 SecureRandom 生成。
private const val JOIN_CODE_LENGTH = 8
private const val JOIN_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
private val joinCodeRandom = java.security.SecureRandom()
