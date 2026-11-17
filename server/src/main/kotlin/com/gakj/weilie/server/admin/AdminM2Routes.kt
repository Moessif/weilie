package com.gakj.weilie.server.admin

import com.gakj.weilie.server.Metrics
import com.gakj.weilie.server.PhotoEntry
import com.gakj.weilie.server.PhotoFiles
import com.gakj.weilie.server.PhotoRegistry
import com.gakj.weilie.server.RoomSampleStore
import com.gakj.weilie.server.RoomState
import com.gakj.weilie.server.RoomStore
import com.gakj.weilie.server.ServerExports
import com.gakj.weilie.server.WsMsg
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path

private val log = LoggerFactory.getLogger("WeilieAdminM2")

// ---------------- DTO（与 server/docs/admin-api-m2.md 一一对应） ----------------

@Serializable
data class RoomListItem(
    val roomId: String,
    val name: String,
    val joinCode: String = "",
    val orgId: Long = 0,
    val orgName: String = "",
    val members: Int = 0,
    val samples: Int = 0,
    val markers: Int = 0,
    val zones: Int = 0,
    val photos: Int = 0,
    val createdAt: Long = 0,
    val lastActiveAt: Long = 0,
    val status: String = "IDLE",
)

@Serializable
data class RoomListResponse(
    val ok: Boolean = true,
    val total: Int = 0,
    val page: Int = 1,
    val size: Int = 20,
    val items: List<RoomListItem> = emptyList(),
)

@Serializable
data class RoomMemberItem(
    val userId: String,
    val name: String = "",
    val role: String = "",
    val lat: Double? = null,
    val lon: Double? = null,
    val lastSeenAt: Long = 0,
    val online: Boolean = false,
)

@Serializable
data class RoomZoneItem(
    val id: String = "",
    val kind: String = "",
    val note: String = "",
    val assignees: List<String> = emptyList(),
    val centerLat: Double? = null,
    val centerLon: Double? = null,
    val radiusM: Double? = null,
)

@Serializable
data class RoomMarkerItem(
    val id: String = "",
    val name: String = "",
    val byName: String = "",
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val kind: String = "",
    val ts: Long = 0,
)

@Serializable
data class RoomSampleItem(
    val userId: String = "",
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val ts: Long = 0,
    val targetSeen: Boolean = false,
    val rsrp: Int? = null,
)

@Serializable
data class RoomDetailResponse(
    val ok: Boolean = true,
    val room: RoomListItem,
    val members: List<RoomMemberItem> = emptyList(),
    val zones: List<RoomZoneItem> = emptyList(),
    val markers: List<RoomMarkerItem> = emptyList(),
    val recentSamples: List<RoomSampleItem> = emptyList(),
)

@Serializable
data class SampleListResponse(
    val ok: Boolean = true,
    val items: List<RoomSampleItem> = emptyList(),
)

@Serializable
data class PurgeResponse(val ok: Boolean = true, val removed: Int = 0)

@Serializable
data class PhotoItem(
    val code: String,
    val sha256: String = "",
    val ts: Long = 0,
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val device: String = "",
    val kind: String = "",
    val note: String = "",
    val serverTs: Long = 0,
    val hasFile: Boolean = false,
    val sizeBytes: Long = 0,
)

@Serializable
data class PhotoListResponse(
    val ok: Boolean = true,
    val total: Int = 0,
    val page: Int = 1,
    val size: Int = 24,
    val items: List<PhotoItem> = emptyList(),
)

@Serializable
data class OpsServerInfo(
    val version: String = "",
    val uptimeSec: Long = 0,
    val startedAt: Long = 0,
    val javaVersion: String = "",
    val osName: String = "",
    val cpuCores: Int = 0,
    val heapUsedBytes: Long = 0,
    val heapMaxBytes: Long = 0,
    val threads: Int = 0,
    val dataDir: String = "",
    val dbBytes: Long = 0,
    val photosDirBytes: Long = 0,
    val roomsDirBytes: Long = 0,
    val diskFreeBytes: Long = 0,
)

@Serializable
data class OpsRoomInfo(val total: Int = 0, val open: Int = 0, val online: Int = 0)

@Serializable
data class OpsMetrics(
    val wsConnections: Long = 0,
    val messages: Long = 0,
    val samples: Long = 0,
    val photosUploaded: Long = 0,
    val rateLimited: Long = 0,
    val errors: Long = 0,
    val exports: Long = 0,
)

@Serializable
data class OpsLimits(
    val roomTtlDays: Long = 3,
    val requireToken: Boolean = false,
    val adminTokenConfigured: Boolean = false,
)

@Serializable
data class OpsStatusResponse(
    val ok: Boolean = true,
    val server: OpsServerInfo = OpsServerInfo(),
    val rooms: OpsRoomInfo = OpsRoomInfo(),
    val metrics: OpsMetrics = OpsMetrics(),
    val limits: OpsLimits = OpsLimits(),
)

@Serializable
data class SettingsView(
    val ok: Boolean = true,
    val roomTtlDays: Long = 3,
    val requireToken: Boolean = false,
    val adminTokenConfigured: Boolean = false,
    val superadminUser: String = "admin",
    val version: String = "",
    val schemaVersion: Int = 1,
    val auditRetentionDays: Int = 180,
    val activationCodeTtlDays: Int = 7,
)

@Serializable
data class SettingsPatch(
    val roomTtlDays: Long? = null,
    val requireToken: Boolean? = null,
)

// ---------------- 路由 ----------------

/**
 * 管理站 M2：房间与任务、取证照片、审计导出、运维监控、运行时设置。
 * 契约见 server/docs/admin-api-m2.md；错误统一抛 [AdminError] 交由 StatusPages 处理。
 */
fun Route.adminM2Routes(
    store: AdminStore,
    security: AdminSecurity,
    roomStore: RoomStore,
    photoRegistry: PhotoRegistry,
    photoFiles: PhotoFiles,
    sampleStore: RoomSampleStore,
    dataDir: Path,
    serverVersion: String,
    startedAt: Long,
) {
    val adminTokenConfigured = !System.getenv("WEILIE_ADMIN_TOKEN").isNullOrBlank()
    val requireTokenEnv = System.getenv("WEILIE_REQUIRE_TOKEN")?.trim() == "1"

    route("/api/admin") {

        // ---------------- 房间 ----------------

        get("/rooms") {
            security.require(call)
            val status = call.request.queryParameters["status"]?.uppercase()?.takeIf { it.isNotBlank() }
            val q = call.request.queryParameters["q"]?.trim()?.lowercase().orEmpty()
            val (page, size) = call.m2Paging(20)
            val now = System.currentTimeMillis()
            val all = roomStore.allRooms().map { it.toListItem(now) }
            val filtered = all.filter { item ->
                (status == null || item.status == status) &&
                    (q.isEmpty() || item.roomId.lowercase().contains(q) ||
                        item.name.lowercase().contains(q) || item.joinCode.contains(q))
            }.sortedByDescending { it.lastActiveAt }
            val from = ((page - 1).coerceAtLeast(0) * size).coerceAtMost(filtered.size)
            val to = (from + size).coerceAtMost(filtered.size)
            call.respond(
                RoomListResponse(total = filtered.size, page = page, size = size, items = filtered.subList(from, to)),
            )
        }

        get("/rooms/{roomId}") {
            security.require(call)
            val room = roomStore.findRoom(call.parameters["roomId"].orEmpty())
                ?: throw AdminError("房间不存在", 404, "ROOM_NOT_FOUND")
            val now = System.currentTimeMillis()
            val members = room.members.values.map { m ->
                RoomMemberItem(
                    userId = m.userId,
                    name = m.name,
                    role = m.role,
                    lat = m.lat,
                    lon = m.lon,
                    lastSeenAt = m.lastTs,
                    online = room.sessions.containsKey(m.userId),
                )
            }.sortedByDescending { it.online }
            val zones = room.zones.filterNot { it.deleted }.map { z ->
                RoomZoneItem(
                    id = z.id,
                    kind = z.kind,
                    note = z.note,
                    assignees = z.assignees,
                    centerLat = z.centerLat,
                    centerLon = z.centerLon,
                    radiusM = z.radiusM,
                )
            }
            val markers = room.markers.takeLast(200).map { mk ->
                RoomMarkerItem(
                    id = mk.id,
                    name = mk.name.ifBlank { mk.note },
                    byName = mk.byName,
                    lat = mk.lat,
                    lon = mk.lon,
                    kind = mk.kind,
                    ts = mk.ts,
                )
            }
            val recent = room.samples.takeLast(200).map { it.toSampleItem() }
            call.respond(
                RoomDetailResponse(
                    room = room.toListItem(now),
                    members = members,
                    zones = zones,
                    markers = markers,
                    recentSamples = recent,
                ),
            )
        }

        get("/rooms/{roomId}/samples") {
            security.require(call)
            val roomId = call.parameters["roomId"].orEmpty()
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 5000) ?: 500
            val room = roomStore.findRoom(roomId)
            // 内存里的采样随房间保留；房间被清掉时回落到落盘文件
            val items = when {
                room != null && room.samples.isNotEmpty() -> room.samples.takeLast(limit).map { it.toSampleItem() }
                else -> runCatching { sampleStore.load(roomId, limit) }.getOrDefault(emptyList()).map { it.toSampleItem() }
            }
            call.respond(SampleListResponse(items = items))
        }

        get("/rooms/{roomId}/export") {
            security.require(call)
            val roomId = call.parameters["roomId"].orEmpty()
            val room = roomStore.findRoom(roomId)
                ?: throw AdminError("房间不存在", 404, "ROOM_NOT_FOUND")
            val format = (call.request.queryParameters["format"] ?: "csv").lowercase()
            val samples = if (room.samples.isNotEmpty()) {
                room.samples.toList()
            } else {
                runCatching { sampleStore.load(roomId, 20_000) }.getOrDefault(emptyList())
            }
            val (body, contentType, ext) = when (format) {
                "geojson" -> Triple(ServerExports.geojson(room, samples), ContentType.Application.Json, "geojson")
                "kml" -> Triple(ServerExports.kml(room, samples), ContentType.parse("application/vnd.google-earth.kml+xml"), "kml")
                else -> Triple(ServerExports.csv(samples), ContentType.parse("text/csv; charset=utf-8"), "csv")
            }
            Metrics.exports.incrementAndGet()
            call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"room-$roomId.$ext\"")
            call.respondText(body, contentType)
        }

        post("/rooms/purge") {
            val (auth, _) = security.requireWrite(call)
            val days = call.request.queryParameters["days"]?.toLongOrNull()?.coerceIn(1, 365) ?: store.roomTtlDays()
            val emptyOnly = call.request.queryParameters["emptyOnly"]?.toBooleanStrictOrNull() ?: false
            val cutoff = System.currentTimeMillis() - days * 24 * 60 * 60 * 1000
            val victims = roomStore.allRooms().filter { r ->
                r.lastActiveAt < cutoff && r.sessions.isEmpty() && (!emptyOnly || r.members.isEmpty())
            }
            victims.forEach { r ->
                roomStore.removeRoom(r.roomId)
                runCatching { sampleStore.delete(r.roomId) }
                store.audit(auth, null, "room.purge", r.roomId, "idleDays=$days emptyOnly=$emptyOnly")
            }
            log.info("管理站清理房间 {} 个（days={} emptyOnly={}）", victims.size, days, emptyOnly)
            call.respond(PurgeResponse(removed = victims.size))
        }

        post("/rooms/{roomId}/close") {
            val (auth, _) = security.requireWrite(call)
            val roomId = call.parameters["roomId"].orEmpty()
            if (!roomStore.removeRoom(roomId)) throw AdminError("房间不存在", 404, "ROOM_NOT_FOUND")
            runCatching { sampleStore.delete(roomId) }
            store.audit(auth, null, "room.close", roomId)
            call.respond(OkResponse())
        }

        // ---------------- 取证照片 ----------------

        get("/photos") {
            security.require(call)
            val kind = call.request.queryParameters["kind"]?.trim()?.takeIf { it.isNotBlank() }
            val q = call.request.queryParameters["q"]?.trim()?.lowercase().orEmpty()
            val onlyUploaded = call.request.queryParameters["onlyUploaded"]?.toBooleanStrictOrNull() ?: false
            val (page, size) = call.m2Paging(24)
            val all = photoRegistry.all().map { it.toPhotoItem(photoFiles) }
                .sortedByDescending { it.serverTs }
                .filter { item ->
                    (kind == null || item.kind.equals(kind, ignoreCase = true)) &&
                        (!onlyUploaded || item.hasFile) &&
                        (q.isEmpty() || item.code.lowercase().contains(q) ||
                            item.sha256.lowercase().startsWith(q) ||
                            item.device.lowercase().contains(q) ||
                            item.note.lowercase().contains(q))
                }
            val from = ((page - 1).coerceAtLeast(0) * size).coerceAtMost(all.size)
            val to = (from + size).coerceAtMost(all.size)
            call.respond(PhotoListResponse(total = all.size, page = page, size = size, items = all.subList(from, to)))
        }

        get("/photos/{code}/file") {
            security.require(call)
            val code = call.parameters["code"].orEmpty().uppercase()
            val bytes = photoFiles.load(code)
                ?: throw AdminError("该验证码没有上传原图", 404, "PHOTO_FILE_MISSING")
            call.respondBytes(bytes, ContentType.Image.JPEG)
        }

        // ---------------- 审计导出 ----------------

        get("/audit/export") {
            security.require(call)
            val format = (call.request.queryParameters["format"] ?: "csv").lowercase()
            val (_, items) = store.auditPage(
                security.require(call).first,
                call.request.queryParameters["action"],
                call.request.queryParameters["orgId"]?.toLongOrNull(),
                1,
                5000,
                call.request.queryParameters["level"],
                call.request.queryParameters["q"],
            )
            if (format == "json") {
                call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"audit.json\"")
                call.respond(AuditListResponse(total = items.size, items = items, page = 1, size = items.size))
                return@get
            }
            val sb = StringBuilder("\uFEFF")
            sb.append("时间,操作人,组织,动作,等级,详情\r\n")
            items.forEach { e ->
                sb.append(csvCell(isoTime(e.ts))).append(',')
                    .append(csvCell(e.actorName)).append(',')
                    .append(csvCell(e.orgName ?: "")).append(',')
                    .append(csvCell(e.action)).append(',')
                    .append(csvCell(e.level)).append(',')
                    .append(csvCell(e.detail)).append("\r\n")
            }
            call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"audit.csv\"")
            call.respondText(sb.toString(), ContentType.parse("text/csv; charset=utf-8"))
        }

        // ---------------- 运维 ----------------

        get("/ops/status") {
            val (auth, _) = security.require(call)
            // 安全加固：运维状态含数据目录绝对路径、磁盘容量、JVM/OS 信息，仅超管可见
            if (!auth.isSuper) throw AdminError("仅超级管理员可查看运维状态", 403, "FORBIDDEN")
            val rt = Runtime.getRuntime()
            val heap = ManagementFactory.getMemoryMXBean().heapMemoryUsage
            val now = System.currentTimeMillis()
            val rooms = roomStore.allRooms()
            val open = rooms.count { it.members.isNotEmpty() && now - it.lastActiveAt < 30L * 60 * 1000 }
            val dataRoot = dataDir.toAbsolutePath()
            call.respond(
                OpsStatusResponse(
                    server = OpsServerInfo(
                        version = serverVersion,
                        uptimeSec = (now - startedAt) / 1000,
                        startedAt = startedAt,
                        javaVersion = System.getProperty("java.version").orEmpty(),
                        osName = System.getProperty("os.name").orEmpty(),
                        cpuCores = rt.availableProcessors(),
                        heapUsedBytes = heap.used,
                        heapMaxBytes = heap.max.takeIf { it > 0 } ?: rt.maxMemory(),
                        threads = Thread.activeCount(),
                        dataDir = dataRoot.toString(),
                        dbBytes = dirSize(dataRoot.resolve("admin.db")),
                        photosDirBytes = dirSize(dataRoot.resolve("photos")),
                        roomsDirBytes = dirSize(dataRoot.resolve("rooms")),
                        diskFreeBytes = runCatching { Files.getFileStore(dataRoot).usableSpace }.getOrDefault(0L),
                    ),
                    rooms = OpsRoomInfo(total = rooms.size, open = open, online = roomStore.onlineMemberCount()),
                    metrics = OpsMetrics(
                        wsConnections = Metrics.wsConnections.get(),
                        messages = Metrics.messages.get(),
                        samples = Metrics.samples.get(),
                        photosUploaded = Metrics.photosUploaded.get(),
                        rateLimited = Metrics.rateLimited.get(),
                        errors = Metrics.errors.get(),
                        exports = Metrics.exports.get(),
                    ),
                    limits = OpsLimits(
                        roomTtlDays = store.roomTtlDays(),
                        requireToken = requireTokenEnv,
                        adminTokenConfigured = adminTokenConfigured,
                    ),
                ),
            )
        }

        get("/settings") {
            val (auth, _) = security.require(call)
            // 超级管理员账号名只对超管可见，组织管理员不应拿到全局运维账号信息
            val superName = if (auth.role == Role.SUPER_ADMIN) store.superAdminUsername() else "—"
            call.respond(
                SettingsView(
                    roomTtlDays = store.roomTtlDays(),
                    requireToken = requireTokenEnv,
                    adminTokenConfigured = adminTokenConfigured,
                    superadminUser = superName,
                    version = serverVersion,
                    schemaVersion = Db.SCHEMA_VERSION,
                ),
            )
        }

        patch("/settings") {
            val (auth, _) = security.requireWrite(call)
            // 安全加固：房间 TTL 是全局配置，只有超管可改（组织管理员原先可改全平台）
            if (!auth.isSuper) throw AdminError("仅超级管理员可修改全局设置", 403, "FORBIDDEN")
            val req = call.body<SettingsPatch>()
            req.roomTtlDays?.let { days ->
                if (days !in 1..30) throw AdminError("房间 TTL 需在 1~30 天之间", 400, "BAD_TTL")
                store.setRoomTtlDays(days)
                store.audit(auth, null, "settings.update", "room_ttl_days", "value=$days")
            }
            if (req.requireToken != null) {
                store.audit(auth, null, "settings.update", "require_token", "value=${req.requireToken}")
            }
            call.respond(
                SettingsView(
                    roomTtlDays = store.roomTtlDays(),
                    requireToken = requireTokenEnv,
                    adminTokenConfigured = adminTokenConfigured,
                    superadminUser = store.superAdminUsername(),
                    version = serverVersion,
                    schemaVersion = Db.SCHEMA_VERSION,
                ),
            )
        }
    }
}

// ---------------- 辅助 ----------------

/** 房间状态口径与仪表盘一致：OPEN 有成员且 30 分钟内活跃；IDLE 3 天内活跃；其余 CLOSED。 */
private fun roomStatusOf(room: RoomState, now: Long): String = when {
    room.members.isNotEmpty() && now - room.lastActiveAt < 30L * 60 * 1000 -> "OPEN"
    now - room.lastActiveAt < 3L * 24 * 3600 * 1000 -> "IDLE"
    else -> "CLOSED"
}

private fun RoomState.toListItem(now: Long) = RoomListItem(
    roomId = roomId,
    name = name,
    joinCode = joinCode,
    members = members.size,
    samples = samples.size,
    markers = markers.size,
    zones = zones.count { !it.deleted },
    photos = markers.count { it.photoId.isNotBlank() },
    createdAt = createdAt,
    lastActiveAt = lastActiveAt,
    status = roomStatusOf(this, now),
)

private fun WsMsg.SamplePing.toSampleItem() = RoomSampleItem(
    userId = userId,
    lat = lat,
    lon = lon,
    ts = ts,
    targetSeen = onTarget,
    rsrp = rsrp,
)

private fun PhotoEntry.toPhotoItem(files: PhotoFiles): PhotoItem {
    // 安全加固：列表只做 stat，不读整份 JPEG（原先每条 readAllBytes，N 条即 N×8MB 内存放大）
    val has = files.exists(code)
    return PhotoItem(
        code = code,
        sha256 = sha256,
        ts = ts,
        lat = lat,
        lon = lon,
        device = device,
        kind = kind,
        note = note,
        serverTs = serverTs,
        hasFile = has,
        sizeBytes = if (has) files.sizeOf(code) else 0L,
    )
}

private fun ApplicationCall.m2Paging(defaultSize: Int): Pair<Int, Int> {
    val page = request.queryParameters["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
    val size = request.queryParameters["size"]?.toIntOrNull()?.coerceIn(1, 200) ?: defaultSize
    return page to size
}

private fun dirSize(path: Path): Long = runCatching {
    if (!Files.exists(path)) return@runCatching 0L
    if (Files.isRegularFile(path)) return@runCatching Files.size(path)
    Files.walk(path).use { stream ->
        stream.filter { Files.isRegularFile(it) }.mapToLong { runCatching { Files.size(it) }.getOrDefault(0L) }.sum()
    }
}.getOrDefault(0L)

private fun isoTime(ms: Long): String =
    runCatching {
        java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.of("Asia/Shanghai"))
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
    }.getOrDefault(ms.toString())

private fun csvCell(raw: String): String {
    val s = if (raw.length > 500) raw.take(500) else raw
    // 安全加固：中和 CSV 公式注入（= + - @ 开头），避免审计导出被 Excel 当公式执行
    val guarded = if (s.isNotEmpty() && s[0] in "=+-@\t\r") "'$s" else s
    return if (guarded.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
        "\"" + guarded.replace("\"", "\"\"") + "\""
    } else {
        guarded
    }
}
