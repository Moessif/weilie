package com.gakj.weilie.server

import com.gakj.weilie.server.admin.AdminError
import com.gakj.weilie.server.admin.AdminSecurity
import com.gakj.weilie.server.admin.adminM2Routes
import com.gakj.weilie.server.admin.AdminStore
import com.gakj.weilie.server.admin.Db
import com.gakj.weilie.server.admin.adminRoutes
import com.gakj.weilie.server.admin.clientRoutes
import com.gakj.weilie.server.admin.respondAdminError
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
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

/** 服务端版本（v1.9：运维加固） */
const val SERVER_VERSION = "1.12.0"

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
    // 取证照片原图：data/photos/<code>.jpg，供同一房间的其他队员查看
    val photoFiles = PhotoFiles(dataDir)
    // v1.3：审计日志 / 限流 / 采样落盘
    val audit = AuditLog(dataDir)
    val limiter = RateLimiter()
    val sampleStore = RoomSampleStore(dataDir)
    // v1.4：入房令牌（默认只签发不强制，WEILIE_REQUIRE_TOKEN=1 时强制）
    val tokens = TokenService(TokenService.loadSecret(dataDir))
    // v1.11：管理站账号库（SQLite data/admin.db）+ 超管引导
    val adminStore = AdminStore(Db(dataDir))
    val superUser = System.getenv("WEILIE_SUPERADMIN_USER")?.trim()?.takeIf { it.isNotEmpty() } ?: "admin"
    // v1.12：不再使用固定默认口令 admin12345。未配置环境变量时生成随机密码，
    // 写入 data/superadmin-initial-password.txt（仅首次引导用，管理员登录后应立即改密）。
    val superPassword = System.getenv("WEILIE_SUPERADMIN_PASSWORD")?.trim()?.takeIf { it.isNotEmpty() }
        ?: bootstrapSuperPassword(dataDir)
    adminStore.ensureSuperAdmin(superUser, superPassword)
    log.info("管理站账号库就绪（超管 {}）", superUser)

    // 启动时从 data/snapshot.json 恢复房间（邀请码/zone/markers）
    val restored = store.loadSnapshot()
    if (restored > 0) log.info("已从快照恢复 {} 个房间", restored)

    embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
        module(store, photoRegistry, cellLibrary, photoFiles, audit, limiter, sampleStore, tokens, adminStore)
    }.start(wait = true)
}

/** Ktor 应用装配：WebSocket + JSON 内容协商 + 照片验真 REST + 路由 + 30s 快照调度 */
fun Application.module(
    store: RoomStore,
    photoRegistry: PhotoRegistry,
    cellLibrary: CellLibrary = CellLibrary(Path.of("data")),
    photoFiles: PhotoFiles = PhotoFiles(Path.of("data")),
    audit: AuditLog = AuditLog(Path.of("data")),
    limiter: RateLimiter = RateLimiter(),
    sampleStore: RoomSampleStore = RoomSampleStore(Path.of("data")),
    tokens: TokenService? = null,
    adminStore: AdminStore? = null,
    dataDir: Path = Path.of(System.getenv("WEILIE_DATA_DIR") ?: "data"),
) {
    install(WebSockets) {
        // 服务端协议层保活：快速发现移动网络半开连接。
        // 死连接（App 被强杀/断网未挥手）最多约 15s(下一个 ping) + 30s(超时) 内被协议层断开，
        // 触发既有 finally 清理并广播 state——无需应用层心跳即可做到 ~30s 级剔除
        pingPeriodMillis = 15_000L
        timeoutMillis = 30_000L
        // v1.12：单帧上限 1MB（原为 Long.MAX_VALUE，攻击者可发巨型帧打爆堆内存）。
        // 语音分片约 96KB base64、state 广播最大几十 KB，1MB 有余量。
        maxFrameSize = 1024L * 1024L
    }
    // v1.12：通用安全响应头（nosniff / 防点击劫持 / 管理站 CSP）
    install(securityHeadersPlugin())
    requestSizeLimitPlugin()
    install(ContentNegotiation) {
        json(wsJson)
    }

    install(StatusPages) {
        exception<AdminError> { call, cause -> call.respondAdminError(cause) }
        exception<Throwable> { call, cause ->
            log.error("未处理异常 uri={}", call.request.uri, cause)
            call.respond(HttpStatusCode.InternalServerError, com.gakj.weilie.server.admin.ErrorResponse(error = "服务器内部错误"))
        }
    }

    val startedAt = System.currentTimeMillis()
    val adminSecurity = adminStore?.let { AdminSecurity(it) }
    routing {
        // 简单 JSON 状态页
        get("/") {
            call.respond(
                StatusResponse(
                    app = "weilie-server",
                    version = SERVER_VERSION,
                    ok = true,
                    rooms = store.roomCount(),
                    onlineMembers = store.onlineMemberCount(),
                    uptimeSec = (System.currentTimeMillis() - startedAt) / 1000,
                ),
            )
        }
        teamRoutes(store, limiter, audit, tokens)
        // 取证照片在线验真：/api/photo/register、/api/photo/verify、/api/photo/count
        photoVerifyRoutes(photoRegistry)
        // 第三方网页验真：/v/{code} 验真页、/api/photo/verify/file 文件哈希比对
        photoWebVerifyRoutes(photoRegistry, limiter)
        // 取证照片原图：/api/photo/upload（上传）、/api/photo/file（下载）
        photoFileRoutes(photoFiles, limiter, photoRegistry)
        // 基站库：/api/cells/near、/api/cells/cell、/api/cells/stats
        cellLibraryRoutes(cellLibrary)
        roomWsRoutes(store, sampleStore, audit, limiter, tokens)
        if (adminStore != null && adminSecurity != null) {
            // v1.11 管理站：/api/admin/*（组织/用户/激活码/仪表盘）与手机端账号 /api/client/*
            adminRoutes(adminStore, adminSecurity, store, photoRegistry, SERVER_VERSION, startedAt)
            // v1.11 M2：房间与任务 / 取证照片 / 审计导出 / 运维监控 / 运行时设置
            adminM2Routes(
                store = adminStore,
                security = adminSecurity,
                roomStore = store,
                photoRegistry = photoRegistry,
                photoFiles = photoFiles,
                sampleStore = sampleStore,
                dataDir = dataDir,
                serverVersion = SERVER_VERSION,
                startedAt = startedAt,
            )
            clientRoutes(adminStore, adminSecurity)
            // 管理站前端（Vue SPA，资源打进 jar 的 admin/ 目录；未构建时返回提示）
            get("/admin") { call.respondRedirect("/admin/") }
            get("/admin/{path...}") {
                val rawPath = call.parameters.getAll("path")?.joinToString("/").orEmpty()
                // v1.12：路径白名单化，拒绝 ../ 等穿越尝试
                val target = safeAdminAssetPath(rawPath)
                    ?: run {
                        call.respondText("Bad Request", status = HttpStatusCode.BadRequest)
                        return@get
                    }
                val loader = Thread.currentThread().contextClassLoader
                val bytes = loader.getResourceAsStream(target)?.use { it.readBytes() }
                when {
                    bytes != null -> call.respondBytes(bytes, contentTypeFor(target))
                    target == "admin/index.html" -> call.respondText(
                        "管理站前端尚未构建（把 web 构建产物放到 server/src/main/resources/admin 后重新打包）",
                        status = HttpStatusCode.OK,
                    )
                    else -> call.respondText("Not Found", status = HttpStatusCode.NotFound)
                }
            }
        }
        // v1.3 运维：/metrics、/api/rooms、房间关闭、KML/GeoJSON/CSV 导出
        opsRoutes(
            store = store,
            sampleStore = sampleStore,
            audit = audit,
            limiter = limiter,
            startedAt = startedAt,
            adminToken = System.getenv("WEILIE_ADMIN_TOKEN")?.trim().orEmpty(),
        )
    }

    // 每 30 秒快照一次到 data/snapshot.json
    val snapshotScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // v1.3：房间 TTL 清理（默认 7 天无活动回收）
    // TTL 优先取管理站设置（管理员可在线调整），否则回落环境变量/默认 3 天
    fun currentTtlDays(): Long = adminStore?.roomTtlDays()
        ?: ((System.getenv("WEILIE_ROOM_TTL_DAYS") ?: "3").toLongOrNull()?.coerceIn(1, 365) ?: 3L)
    snapshotScope.launch {
        while (isActive) {
            delay(10 * 60 * 1000L)
            runCatching {
                val ttlDays = currentTtlDays()
                val ttlMs = ttlDays * 24 * 60 * 60 * 1000
                val purged = store.purgeIdle(ttlMs)
                purged.forEach { r ->
                    sampleStore.delete(r.roomId)
                    audit.log("room_ttl", r.roomId, "", mapOf("idleDays" to ttlDays.toString()))
                }
                if (purged.isNotEmpty()) log.info("TTL 清理房间 {} 个", purged.size)
                limiter.purge()
                // v1.12：同时清理登录/匿名限流表，避免 key 无限增长
                adminSecurity?.purgeThrottles()
            }.onFailure { log.warn("TTL 清理失败: {}", it.message) }
        }
    }
    snapshotScope.launch {
        while (isActive) {
            delay(SNAPSHOT_INTERVAL_MS)
            runCatching { store.saveSnapshot() }
                .onFailure { log.warn("快照写入失败: {}", it.message) }
        }
    }

    // 优雅停机：停调度、补一次最终快照（Ktor 2.x 的生命周期事件挂在 environment.monitor 上）
    // 管理站指标快照：每 60 秒落一条，供仪表盘时序图使用
    if (adminStore != null) {
        val metricsScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        metricsScope.launch {
            while (isActive) {
                runCatching {
                    val rooms = store.allRooms()
                    adminStore.insertMetricSnapshot(
                        ts = System.currentTimeMillis(),
                        rooms = store.roomCount(),
                        online = store.onlineMemberCount(),
                        samples = rooms.sumOf { it.samples.size },
                        photos = photoRegistry.count(),
                    )
                }
                delay(60_000L)
            }
        }
        environment.monitor.subscribe(ApplicationStopped) { metricsScope.cancel() }
    }
    environment.monitor.subscribe(ApplicationStopped) {
        snapshotScope.cancel()
        runCatching { runBlocking { store.saveSnapshot() } }
        log.info("服务已停止，最终快照已落盘")
    }
}

/**
 * v1.12：超管引导口令。
 *
 * 历史实现里未配置 `WEILIE_SUPERADMIN_PASSWORD` 时会退回固定口令 `admin12345`，
 * 一旦部署遗漏环境变量就是默认口令漏洞。现在改为随机生成，并写入
 * `data/superadmin-initial-password.txt`（仅首次引导，登录后应立即改密）。
 */
private fun bootstrapSuperPassword(dataDir: Path): String {
    val password = com.gakj.weilie.server.admin.Tokens.randomToken(18)
    runCatching {
        java.nio.file.Files.createDirectories(dataDir)
        val file = dataDir.resolve("superadmin-initial-password.txt")
        if (!java.nio.file.Files.exists(file)) {
            java.nio.file.Files.writeString(
                file,
                "首次启动随机生成的超管口令（登录后请立即修改）：\n$password\n",
            )
            runCatching {
                java.nio.file.Files.setPosixFilePermissions(
                    file,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),
                )
            }
        }
    }
    log.warn("未配置 WEILIE_SUPERADMIN_PASSWORD，已生成随机超管口令并写入 {}/superadmin-initial-password.txt", dataDir)
    return password
}

/** 按扩展名推断响应类型（管理站静态资源用）。 */
private fun contentTypeFor(path: String): ContentType = when {
    path.endsWith(".html") -> ContentType.Text.Html
    path.endsWith(".js") || path.endsWith(".mjs") -> ContentType.Application.JavaScript
    path.endsWith(".css") -> ContentType.Text.CSS
    path.endsWith(".json") -> ContentType.Application.Json
    path.endsWith(".svg") -> ContentType.Image.SVG
    path.endsWith(".png") -> ContentType.Image.PNG
    path.endsWith(".jpg") || path.endsWith(".jpeg") -> ContentType.Image.JPEG
    path.endsWith(".woff2") -> ContentType("font", "woff2")
    path.endsWith(".woff") -> ContentType("font", "woff")
    path.endsWith(".ico") -> ContentType("image", "x-icon")
    else -> ContentType.Application.OctetStream
}