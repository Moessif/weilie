package com.gakj.weilie.server.admin

import com.gakj.weilie.server.AuditLog
import com.gakj.weilie.server.CellLibrary
import com.gakj.weilie.server.PhotoFiles
import com.gakj.weilie.server.PhotoRegistry
import com.gakj.weilie.server.RateLimiter
import com.gakj.weilie.server.RoomSampleStore
import com.gakj.weilie.server.RoomStore
import com.gakj.weilie.server.module
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 管理平台 M1 端到端路由测试：超管登录 → 建组织（带组织管理员）→ 建用户 → 发激活码 →
 * 手机端激活/登录 → 组织隔离 → CSRF 拦截 → 仪表盘计数。
 */
class AdminApiTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun adminPlatformFlow() = testApplication {
        val dataDir = Files.createTempDirectory("weilie-admin-test")
        val roomStore = RoomStore(dataDir)
        val photoRegistry = PhotoRegistry(dataDir)
        val adminStore = AdminStore(Db(dataDir))
        adminStore.ensureSuperAdmin("admin", "admin12345", mustChange = false)

        application {
            module(
                store = roomStore,
                photoRegistry = photoRegistry,
                cellLibrary = CellLibrary(dataDir),
                photoFiles = PhotoFiles(dataDir),
                audit = AuditLog(dataDir),
                limiter = RateLimiter(),
                sampleStore = RoomSampleStore(dataDir),
                tokens = null,
                adminStore = adminStore,
            )
        }

        val admin = createClient {
            install(ContentNegotiation) { json() }
            install(HttpCookies)
        }

        // 1) 登录：返回 csrf，且 Cookie 生效
        val login = admin.post("/api/admin/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"admin12345"}""")
        }
        assertEquals(HttpStatusCode.OK, login.status)
        val csrf = json.parseToJsonElement(login.bodyAsText()).jsonObject.str("csrfToken")

        val me = admin.get("/api/admin/auth/me")
        assertEquals(HttpStatusCode.OK, me.status)
        val meJson = json.parseToJsonElement(me.bodyAsText()).jsonObject
        assertEquals("SUPER_ADMIN", meJson.obj("user").str("role"))
        // me 会轮换 CSRF，后续写操作使用最新令牌
        val csrf2 = meJson.str("csrfToken")

        // 2) 缺 CSRF 的写操作必须 403
        val noCsrf = admin.post("/api/admin/orgs") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"X"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, noCsrf.status)

        // 3) 建组织（同时建组织管理员）
        val orgResp = admin.post("/api/admin/orgs") {
            contentType(ContentType.Application.Json)
            header("X-CSRF-Token", csrf2)
            setBody(
                """{"name":"公安大学测试支队","code":"CUPL01","quotaUsers":20,"quotaRooms":3,""" +
                    """"adminUsername":"orgadmin","adminPassword":"OrgAdmin123","adminDisplayName":"组织管理员"}""",
            )
        }
        assertEquals(HttpStatusCode.OK, orgResp.status)
        val orgJson = json.parseToJsonElement(orgResp.bodyAsText()).jsonObject
        val orgId = orgJson.obj("org").get("id")!!.jsonPrimitive.content.toLong()
        assertEquals("ORG_ADMIN", orgJson.obj("adminUser").str("role"))

        // 4) 建普通用户 + 发激活码
        val userResp = admin.post("/api/admin/users") {
            contentType(ContentType.Application.Json)
            header("X-CSRF-Token", csrf2)
            setBody("""{"orgId":$orgId,"username":"policeman01","displayName":"一号队员"}""")
        }
        assertEquals(HttpStatusCode.OK, userResp.status)
        val userId = json.parseToJsonElement(userResp.bodyAsText()).jsonObject.obj("user").get("id")!!.jsonPrimitive.content.toLong()

        val codeResp = admin.post("/api/admin/users/$userId/codes") {
            contentType(ContentType.Application.Json)
            header("X-CSRF-Token", csrf2)
            setBody("""{"purpose":"ACTIVATE"}""")
        }
        assertEquals(HttpStatusCode.OK, codeResp.status)
        val activationCode = json.parseToJsonElement(codeResp.bodyAsText()).jsonObject.str("code")
        assertTrue(activationCode.length >= 8)

        // 5) 手机端激活 → 拿 Bearer 令牌 → /me
        val phone = createClient { install(ContentNegotiation) { json() } }
        val activate = phone.post("/api/client/activate") {
            contentType(ContentType.Application.Json)
            setBody("""{"code":"$activationCode","password":"Policeman123","deviceId":"dev-001","deviceName":"K80 Pro"}""")
        }
        assertEquals(HttpStatusCode.OK, activate.status)
        val token = json.parseToJsonElement(activate.bodyAsText()).jsonObject.str("token")
        assertTrue(token.length > 20)

        val clientMe = phone.get("/api/client/me") { header("Authorization", "Bearer $token") }
        assertEquals(HttpStatusCode.OK, clientMe.status)
        assertEquals("policeman01", json.parseToJsonElement(clientMe.bodyAsText()).jsonObject.obj("user").str("username"))

        // 复用同一激活码应失败
        val reuse = phone.post("/api/client/activate") {
            contentType(ContentType.Application.Json)
            setBody("""{"code":"$activationCode","password":"Policeman123","deviceId":"dev-001"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, reuse.status)

        // 6) 组织管理员只看到本组织用户，且不能建组织
        val orgAdmin = createClient {
            install(ContentNegotiation) { json() }
            install(HttpCookies)
        }
        val orgLogin = orgAdmin.post("/api/admin/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"orgadmin","password":"OrgAdmin123"}""")
        }
        assertEquals(HttpStatusCode.OK, orgLogin.status)
        var orgCsrf = json.parseToJsonElement(orgLogin.bodyAsText()).jsonObject.str("csrfToken")

        // 5.5) v1.12（审计 S3）：新开通的管理员必须先修改初始密码，
        // 否则管理站其余接口一律 403 MUST_CHANGE_PASSWORD。
        val mustChange = orgAdmin.get("/api/admin/users")
        assertEquals(HttpStatusCode.Forbidden, mustChange.status)
        assertTrue(mustChange.bodyAsText().contains("MUST_CHANGE_PASSWORD"))
        val changed = orgAdmin.post("/api/admin/auth/change-password") {
            contentType(ContentType.Application.Json)
            header("X-CSRF-Token", orgCsrf)
            setBody("""{"oldPassword":"OrgAdmin123","newPassword":"OrgAdmin456"}""")
        }
        assertEquals(HttpStatusCode.OK, changed.status)
        // 改密会吊销该用户全部会话（安全实践），后续请求必须重新登录。
        val orgRelogin = orgAdmin.post("/api/admin/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"orgadmin","password":"OrgAdmin456"}""")
        }
        assertEquals(HttpStatusCode.OK, orgRelogin.status)
        orgCsrf = json.parseToJsonElement(orgRelogin.bodyAsText()).jsonObject.str("csrfToken")

        val orgUsers = orgAdmin.get("/api/admin/users")
        assertEquals(HttpStatusCode.OK, orgUsers.status)
        val total = json.parseToJsonElement(orgUsers.bodyAsText()).jsonObject.get("total")!!.jsonPrimitive.content.toInt()
        assertEquals(2, total) // 组织管理员 + 普通用户

        val forbidden = orgAdmin.post("/api/admin/orgs") {
            contentType(ContentType.Application.Json)
            header("X-CSRF-Token", orgCsrf)
            setBody("""{"name":"另一个组织"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, forbidden.status)

        // 7) 仪表盘：组织 1 个，用户激活后为 ACTIVE
        val summary = admin.get("/api/admin/dashboard/summary")
        assertEquals(HttpStatusCode.OK, summary.status)
        val kpi = json.parseToJsonElement(summary.bodyAsText()).jsonObject.obj("kpi")
        assertEquals(1, kpi.get("orgs")!!.jsonPrimitive.content.toInt())
        assertEquals(3, kpi.get("users")!!.jsonPrimitive.content.toInt())

        val userStatus = admin.get("/api/admin/dashboard/user-status")
        assertEquals(HttpStatusCode.OK, userStatus.status)
        assertTrue(userStatus.bodyAsText().contains("ACTIVE"))
    }

    private fun JsonObject.str(key: String): String = get(key)!!.jsonPrimitive.content

    private fun JsonObject.obj(key: String): JsonObject = get(key)!!.jsonObject
}
