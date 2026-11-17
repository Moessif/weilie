package com.gakj.weilie.server.admin

import com.gakj.weilie.server.AuditLog
import com.gakj.weilie.server.CellLibrary
import com.gakj.weilie.server.PhotoFiles
import com.gakj.weilie.server.PhotoRegisterRequest
import com.gakj.weilie.server.PhotoRegistry
import com.gakj.weilie.server.RateLimiter
import com.gakj.weilie.server.RoomSampleStore
import com.gakj.weilie.server.RoomStore
import com.gakj.weilie.server.WsMsg
import com.gakj.weilie.server.module
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 管理平台 M2 路由测试：房间列表/详情/采样/导出/关闭/清理、照片列表与原图、
 * 审计筛选与导出、运维状态、运行时设置读写。
 */
class AdminM2ApiTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun m2RoomPhotoAuditOpsFlow() = testApplication {
        val dataDir = Files.createTempDirectory("weilie-admin-m2")
        val roomStore = RoomStore(dataDir)
        val photoRegistry = PhotoRegistry(dataDir)
        val photoFiles = PhotoFiles(dataDir)
        val sampleStore = RoomSampleStore(dataDir)
        val adminStore = AdminStore(Db(dataDir))
        adminStore.ensureSuperAdmin("admin", "admin12345", mustChange = false)

        // 造数据：一个房间 + 采样 + 标记 + 一张已登记且已上传原图的照片
        val room = roomStore.createRoom("测试小队")
        room.members["u-captain"] = com.gakj.weilie.server.MemberView(
            userId = "u-captain", name = "王警官", role = "CAPTAIN", lat = 39.9, lon = 116.4, lastTs = System.currentTimeMillis(),
        )
        repeat(3) { i ->
            room.samples.add(WsMsg.SamplePing(userId = "u-captain", lat = 39.9 + i * 0.0001, lon = 116.4, rsrp = -90 + i, onTarget = i > 0, ts = 1_700_000_000_000 + i))
        }
        room.markers.add(
            WsMsg.Marker(
                by = "u-captain", byName = "王警官", lat = 39.9, lon = 116.4,
                kind = "SUSPICIOUS", note = "可疑摄像头", ts = System.currentTimeMillis(),
                id = "m-1", name = "3 号楼", photoId = "photo-1",
            ),
        )
        val code = photoRegistry.register(
            PhotoRegisterRequest(sha256 = "a".repeat(64), ts = 1_700_000_000_000, lat = 39.9, lon = 116.4, device = "REDMI K80 Pro", kind = "PHOTO", note = "现场取证"),
        ).code
        photoFiles.save(code, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x01, 0x02))

        application {
            module(
                store = roomStore,
                photoRegistry = photoRegistry,
                cellLibrary = CellLibrary(dataDir),
                photoFiles = photoFiles,
                audit = AuditLog(dataDir),
                limiter = RateLimiter(),
                sampleStore = sampleStore,
                tokens = null,
                adminStore = adminStore,
                dataDir = dataDir,
            )
        }

        val admin = createClient {
            install(ContentNegotiation) { json() }
            install(HttpCookies)
        }
        val login = admin.post("/api/admin/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"admin12345"}""")
        }
        assertEquals(HttpStatusCode.OK, login.status)
        val meJson = json.parseToJsonElement(admin.get("/api/admin/auth/me").bodyAsText()).jsonObject
        val csrf = meJson.str("csrfToken")

        // 1) 房间列表
        val roomsResp = admin.get("/api/admin/rooms")
        assertEquals(HttpStatusCode.OK, roomsResp.status)
        val roomsJson = json.parseToJsonElement(roomsResp.bodyAsText()).jsonObject
        assertEquals(1, roomsJson["total"]!!.jsonPrimitive.content.toInt())
        val roomItem = roomsJson["items"]!!.jsonArray.first().jsonObject
        assertEquals(room.roomId, roomItem.str("roomId"))
        assertEquals(3, roomItem["samples"]!!.jsonPrimitive.content.toInt())
        assertEquals(1, roomItem["markers"]!!.jsonPrimitive.content.toInt())

        // 2) 房间详情：成员/任务区/标点/最近采样
        val detail = json.parseToJsonElement(admin.get("/api/admin/rooms/${room.roomId}").bodyAsText()).jsonObject
        assertEquals(1, detail["members"]!!.jsonArray.size)
        assertEquals("王警官", detail["members"]!!.jsonArray.first().jsonObject.str("name"))
        assertEquals(1, detail["markers"]!!.jsonArray.size)
        assertEquals(3, detail["recentSamples"]!!.jsonArray.size)

        // 3) 采样列表与导出
        val samples = json.parseToJsonElement(admin.get("/api/admin/rooms/${room.roomId}/samples?limit=10").bodyAsText()).jsonObject
        assertEquals(3, samples["items"]!!.jsonArray.size)
        val csv = admin.get("/api/admin/rooms/${room.roomId}/export?format=csv")
        assertEquals(HttpStatusCode.OK, csv.status)
        assertTrue(csv.bodyAsText().contains("lat"), "CSV 应包含表头 lat")

        // 4) 照片列表与原图
        val photos = json.parseToJsonElement(admin.get("/api/admin/photos").bodyAsText()).jsonObject
        assertEquals(1, photos["total"]!!.jsonPrimitive.content.toInt())
        val photoItem = photos["items"]!!.jsonArray.first().jsonObject
        assertEquals(code, photoItem.str("code"))
        assertTrue(photoItem["hasFile"]!!.jsonPrimitive.content == "true")
        val file = admin.get("/api/admin/photos/$code/file")
        assertEquals(HttpStatusCode.OK, file.status)

        // 5) 审计筛选与导出
        val audit = json.parseToJsonElement(admin.get("/api/admin/audit?level=success").bodyAsText()).jsonObject
        assertTrue(audit["total"]!!.jsonPrimitive.content.toInt() >= 1, "登录应产生 success 级审计")
        val auditCsv = admin.get("/api/admin/audit/export?format=csv")
        assertEquals(HttpStatusCode.OK, auditCsv.status)
        assertTrue(auditCsv.bodyAsText().contains("操作人"))

        // 6) 运维状态
        val ops = json.parseToJsonElement(admin.get("/api/admin/ops/status").bodyAsText()).jsonObject
        assertEquals(1, ops.obj("rooms")["total"]!!.jsonPrimitive.content.toInt())
        assertTrue(ops.obj("server")["uptimeSec"]!!.jsonPrimitive.content.toLong() >= 0)
        assertEquals(3, ops.obj("limits")["roomTtlDays"]!!.jsonPrimitive.content.toLong())

        // 7) 设置读写
        val patched = admin.patch("/api/admin/settings") {
            contentType(ContentType.Application.Json)
            header("X-CSRF-Token", csrf)
            setBody("""{"roomTtlDays":7}""")
        }
        assertEquals(HttpStatusCode.OK, patched.status)
        assertEquals(7, json.parseToJsonElement(patched.bodyAsText()).jsonObject["roomTtlDays"]!!.jsonPrimitive.content.toLong())

        // 8) 关闭房间
        val closed = admin.post("/api/admin/rooms/${room.roomId}/close") { header("X-CSRF-Token", csrf) }
        assertEquals(HttpStatusCode.OK, closed.status)
        assertEquals(0, json.parseToJsonElement(admin.get("/api/admin/rooms").bodyAsText()).jsonObject["total"]!!.jsonPrimitive.content.toInt())
    }

    private fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content
    private fun JsonObject.obj(key: String) = this[key]!!.jsonObject
}


