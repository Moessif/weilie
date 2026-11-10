package com.gakj.weilie.server

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.nio.file.Path


private val log = LoggerFactory.getLogger("weilie.app")

/** 快照落盘间隔（毫秒） */
private const val SNAPSHOT_INTERVAL_MS = 30_000L

/** GET / 状态页响应 */
@Serializable
data class StatusResponse(
    val app: String,
    val version: String,
    val ok: Boolean = true,
    val rooms: Int,
    val onlineMembers: Int,
    val uptimeSec: Long,
)

fun main() {
    // 数据目录：环境变量 WEILIE_DATA_DIR 可覆盖，默认进程工作目录下的 data/
    val dataDir = Path.of(System.getenv("WEILIE_DATA_DIR") ?: "data")
    val store = RoomStore(dataDir)
    // 照片登记库：与房间快照同目录，photo-registry.jsonl 追加落盘
    val photoRegistry = PhotoRegistry(dataDir)
    // 基站库：OpenCelliD 代理 + 瓦片缓存（API Key 只从环境变量读，不下发到 App）
    val cellLibrary = CellLibrary(dataDir)

    // 启动时从 data/snapshot.json 恢复房间（邀请码/zone/markers）
    val restored = store.loadSnapshot()
    if (restored > 0) log.info("已从快照恢复 {} 个房间", restored)

    embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
        module(store, photoRegistry, cellLibrary)
    }.start(wait = true)
}

/** Ktor 应用装配：WebSocket + JSON 内容协商 + 照片验真 REST + 路由 + 30s 快照调度 */
fun Application.module(store: RoomStore, photoRegistry: PhotoRegistry, cellLibrary: CellLibrary = CellLibrary(Path.of("data"))) {
    install(WebSockets) {
        // 服务端协议层保活：快速发现移动网络半开连接。
        // 死连接（App 被强杀/断网未挥手）最多约 15s(下一个 ping) + 30s(超时) 内被协议层断开，
        // 触发既有 finally 清理并广播 state——无需应用层心跳即可做到 ~30s 级剔除
        pingPeriodMillis = 15_000L
        timeoutMillis = 30_000L
        maxFrameSize = Long.MAX_VALUE
    }
    install(ContentNegotiation) {
        json(wsJson)
    }

    val startedAt = System.currentTimeMillis()
    routing {
        // 简单 JSON 状态页
        get("/") {
            call.respond(
                StatusResponse(
                    app = "weilie-server",
                    version = "0.1.0",
                    ok = true,
                    rooms = store.roomCount(),
                    onlineMembers = store.onlineMemberCount(),
                    uptimeSec = (System.currentTimeMillis() - startedAt) / 1000,
                ),
            )
        }
        teamRoutes(store)
        // 取证照片在线验真：/api/photo/register、/api/photo/verify、/api/photo/count
        photoVerifyRoutes(photoRegistry)
        // 第三方网页验真：/v/{code} 验真页、/api/photo/verify/file 文件哈希比对
        photoWebVerifyRoutes(photoRegistry)
        // 基站库：/api/cells/near、/api/cells/cell、/api/cells/stats
        cellLibraryRoutes(cellLibrary)
        roomWsRoutes(store)
    }

    // 每 30 秒快照一次到 data/snapshot.json
    val snapshotScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    snapshotScope.launch {
        while (isActive) {
            delay(SNAPSHOT_INTERVAL_MS)
            runCatching { store.saveSnapshot() }
                .onFailure { log.warn("快照写入失败: {}", it.message) }
        }
    }

    // 优雅停机：停调度、补一次最终快照（Ktor 2.x 的生命周期事件挂在 environment.monitor 上）
    environment.monitor.subscribe(ApplicationStopped) {
        snapshotScope.cancel()
        runCatching { runBlocking { store.saveSnapshot() } }
        log.info("服务已停止，最终快照已落盘")
    }
}
