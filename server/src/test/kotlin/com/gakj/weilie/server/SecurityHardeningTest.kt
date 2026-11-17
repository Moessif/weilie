package com.gakj.weilie.server

import com.gakj.weilie.server.admin.AdminStore
import com.gakj.weilie.server.admin.AuthUser
import com.gakj.weilie.server.admin.Db
import com.gakj.weilie.server.admin.LoginThrottle
import com.gakj.weilie.server.admin.Role
import com.gakj.weilie.server.admin.cookieSecureFor
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * v1.12 安全加固回归测试（对应 2026-11-17 src-hunter 白盒审计的高/中危条目）。
 *
 * 覆盖：
 * - 代理头信任开关（V13/H4）：默认忽略 `X-Forwarded-For`，只有显式开启才采信；
 * - 管理站静态路径归一（V15）：拒绝 `..` / 反斜杠 / 绝对路径；
 * - 会话 Cookie 的 Secure 属性（V14/M1）：仅 HTTPS 或显式开关下开启；
 * - 邀请码强度（V19/M3）：8 位、去混淆字符、SecureRandom；
 * - 登录限流表上限（V21）：purge 只清过期项、不误伤活跃窗口；
 * - 安全响应头与 CSP（V14/F9）；
 * - 取证照片上传防覆盖（V09/M7）：哈希不一致 409；
 * - 导出 CSV 公式注入中和（V06/L1）；
 * - 管理站角色门（S3/H1）：手机端 USER 角色不得进入后台。
 */
class SecurityHardeningTest {

    // ---------- 代理头 / 真实 IP ----------

    @Test
    fun forwardedHeadersAreIgnoredByDefault() {
        // 未开启 WEILIE_TRUST_PROXY 时，XFF / X-Real-IP 一律不采信
        assertEquals(
            "1.2.3.4",
            clientIpOf("1.2.3.4", "9.9.9.9, 8.8.8.8", "7.7.7.7", trustProxy = false),
        )
        // 即使只有伪造头、没有 socket 地址，也不能被拉去当限流 key
        assertEquals("unknown", clientIpOf("", "9.9.9.9", "7.7.7.7", trustProxy = false))
    }

    @Test
    fun forwardedHeadersHonoredOnlyWhenTrusted() {
        assertEquals("9.9.9.9", clientIpOf("10.0.0.1", "9.9.9.9, 10.0.0.1", null, trustProxy = true))
        assertEquals("7.7.7.7", clientIpOf("10.0.0.1", null, "7.7.7.7", trustProxy = true))
        // 非法值要回落，不能把 "not-an-ip" 当 key
        assertEquals("10.0.0.1", clientIpOf("10.0.0.1", "not-an-ip", null, trustProxy = true))
    }

    // ---------- 管理站静态资源路径 ----------

    @Test
    fun adminAssetPathRejectsTraversal() {
        assertNull(safeAdminAssetPath("../etc/passwd"))
        assertNull(safeAdminAssetPath("a/../../b.js"))
        assertNull(safeAdminAssetPath("..\\..\\windows\\win.ini"))
        assertNull(safeAdminAssetPath("/etc/passwd"))
        assertNull(safeAdminAssetPath("asset\u0000.js"))
        assertEquals("admin/index.html", safeAdminAssetPath(""))
        assertEquals("admin/index.html", safeAdminAssetPath("index.html"))
        assertEquals("admin/assets/index-a1b2.js", safeAdminAssetPath("assets/index-a1b2.js"))
    }

    // ---------- Cookie / 角色门 ----------

    @Test
    fun sessionCookieIsSecureOnlyOnHttps() {
        assertTrue(cookieSecureFor("https"))
        assertTrue(cookieSecureFor("HTTPS"))
        assertFalse(cookieSecureFor("http"))
        // 反向代理到 HTTP 的场景可用 WEILIE_COOKIE_SECURE=1 强制，测试环境不设即 false
        assertEquals(System.getenv("WEILIE_COOKIE_SECURE")?.trim() == "1", cookieSecureFor("http"))
    }

    @Test
    fun mobileUserRoleCannotEnterAdminConsole() {
        assertFalse(AuthUser(id = 1, username = "u", role = Role.USER).canAccessAdminConsole)
        assertTrue(AuthUser(id = 1, username = "a", role = Role.AUDITOR).canAccessAdminConsole)
        assertTrue(AuthUser(id = 1, username = "o", role = Role.ORG_ADMIN).canAccessAdminConsole)
        assertTrue(AuthUser(id = 1, username = "s", role = Role.SUPER_ADMIN).canAccessAdminConsole)
    }

    // ---------- 邀请码 ----------

    @Test
    fun joinCodeIsLongEnoughAndUnambiguous() {
        val store = RoomStore(Files.createTempDirectory("weilie-join-code"))
        val codes = (1..20).map { store.createRoom("队$it").joinCode }
        assertEquals(20, codes.toSet().size, "邀请码不应重复")
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        codes.forEach { code ->
            assertEquals(8, code.length, "邀请码应为 8 位：$code")
            assertTrue(code.all { it in alphabet }, "邀请码含易混淆或非法字符：$code")
        }
    }

    // ---------- 限流表 ----------

    @Test
    fun loginThrottlePurgeKeepsActiveWindow() {
        val throttle = LoginThrottle(maxFailures = 5)
        repeat(5) { throttle.record("admin@1.2.3.4") }
        assertFalse(throttle.allow("admin@1.2.3.4"), "5 次失败后应被限流")
        // purge 只能清过期项，活跃窗口不能被清掉（否则攻击者可用 purge 重置计数）
        throttle.purge(maxEntries = 10_000)
        assertFalse(throttle.allow("admin@1.2.3.4"), "purge 不应清掉活跃窗口")
        throttle.reset("admin@1.2.3.4")
        assertTrue(throttle.allow("admin@1.2.3.4"))
    }

    // ---------- 路由级：安全头 + 照片上传防覆盖 ----------

    @Test
    fun securityHeadersAndPhotoUploadIntegrity() = testApplication {
        val dataDir = Files.createTempDirectory("weilie-hardening")
        val roomStore = RoomStore(dataDir)
        val photoRegistry = PhotoRegistry(dataDir)
        val photoFiles = PhotoFiles(dataDir)
        val adminStore = AdminStore(Db(dataDir))
        adminStore.ensureSuperAdmin("admin", "admin12345")
        application {
            module(
                store = roomStore,
                photoRegistry = photoRegistry,
                cellLibrary = CellLibrary(dataDir),
                photoFiles = photoFiles,
                audit = AuditLog(dataDir),
                limiter = RateLimiter(),
                sampleStore = RoomSampleStore(dataDir),
                tokens = null,
                adminStore = adminStore,
                dataDir = dataDir,
            )
        }

        // 1) 通用安全头
        val health = client.get("/api/health")
        assertEquals("nosniff", health.headers["X-Content-Type-Options"])
        assertEquals("DENY", health.headers["X-Frame-Options"])

        // 2) 管理站额外 CSP
        val admin = client.get("/admin/")
        val csp = admin.headers["Content-Security-Policy"]
        assertNotNull(csp, "管理站必须下发 CSP")
        assertTrue(csp!!.contains("default-src 'self'"), "CSP 应限制同源：$csp")
        assertTrue(csp.contains("frame-ancestors 'none'"), "CSP 应禁止被嵌套：$csp")

        // 3) 取证照片上传：哈希与登记不一致必须 409（防止覆盖他人取证照片）
        val jpegA = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(64) { 1 }
        val jpegB = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(64) { 2 }
        val registered = photoRegistry.register(
            PhotoRegisterRequest(sha256 = sha256Hex(jpegA), ts = 1_700_000_000_000L, device = "test"),
        )
        val code = registered.code

        val ok = client.post("/api/photo/upload?code=$code") { setBody(jpegA) }
        assertEquals(HttpStatusCode.OK, ok.status, "首次上传（哈希一致）应成功：${ok.bodyAsText()}")

        val overwrite = client.post("/api/photo/upload?code=$code") { setBody(jpegB) }
        assertEquals(HttpStatusCode.Conflict, overwrite.status, "哈希不一致必须拒绝覆盖")

        // 4) 非图片内容（无 JPEG/PNG 魔数）直接 400
        val notImage = client.post("/api/photo/upload?code=$code") {
            setBody("<script>alert(1)</script>".toByteArray())
        }
        assertEquals(HttpStatusCode.BadRequest, notImage.status)
    }

    // ---------- 导出 CSV 公式注入 ----------

    @Test
    fun csvExportNeutralizesFormulaInjection() {
        val csv = ServerExports.csv(
            listOf(
                com.gakj.weilie.server.WsMsg.SamplePing(
                    userId = "=1+1",
                    lat = 39.9,
                    lon = 116.4,
                    rsrp = -80,
                    onTarget = true,
                    ts = 1_700_000_000_000L,
                ),
            ),
        )
        val body = csv.lineSequence().drop(1).first()
        assertTrue(body.startsWith("'="), "公式前缀必须被中和：$body")
        assertFalse(body.contains(",=1+1"), "不得出现未中和的公式单元：$body")
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
