package com.gakj.weilie.server

import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 照片登记/验真核心逻辑的纯 JVM 测试：
 * - HMAC 复算（固定密钥 + 固定输入，锁定 code:sha256:serverTs:ts:lat:lon 消息格式）；
 * - 哈希比对（大小写归一、非法哈希拒绝）；
 * - 验证码大小写归一。
 * 不依赖网络与数据库：用临时目录 + 固定 hmac.secret 文件。
 */
class PhotoRegistryTest {

    private lateinit var dir: java.nio.file.Path
    private lateinit var registry: PhotoRegistry

    /** 与 PhotoRegistry 约定一致的固定测试密钥（写入临时目录的 hmac.secret） */
    private val secret = "weilie-unit-test-secret".toByteArray(Charsets.UTF_8)

    /** 固定输入：照片内容字节与登记元数据 */
    private val photoBytes = "THE-EVIDENCE-PHOTO-BYTES".toByteArray(Charsets.UTF_8)

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("weilie-photo-test")
        // 先落固定密钥再构造 registry，确保 sign 用的是已知密钥
        Files.write(dir.resolve("hmac.secret"), secret)
        registry = PhotoRegistry(dir)
    }

    @AfterTest
    fun tearDown() {
        // 清理临时目录
        Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    /** 用与 PhotoRegistry 相同的拼接方式独立复算 HMAC */
    private fun expectedSig(entry: PhotoEntry): String {
        val message = "${entry.code}:${entry.sha256}:${entry.serverTs}:${entry.ts}:${entry.lat}:${entry.lon}"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `HMAC 复算与登记签名一致`() {
        val resp = registry.register(
            PhotoRegisterRequest(
                sha256 = MessageDigest.getInstance("SHA-256").digest(photoBytes).joinToString("") { "%02x".format(it) },
                ts = 1_700_000_000_000L,
                lat = 39.9042,
                lon = 116.4074,
                device = "Pixel-Test",
                kind = "NORMAL",
                note = "单元测试",
            ),
        )
        val entry = registry.find(resp.code)
        assertTrue(entry != null, "登记后应能按验证码查到记录")
        // 锁定消息格式与密钥来源：独立复算的 HMAC 必须与登记返回的 sig 完全一致
        assertEquals(expectedSig(entry), entry.sig, "签名应等于 HMAC(secret, code:sha256:serverTs:ts:lat:lon)")
        assertTrue(registry.sigValid(entry), "登记记录的签名应校验通过")
    }

    @Test
    fun `签名被篡改后校验失败`() {
        val resp = registry.register(
            PhotoRegisterRequest(sha256 = "ab".repeat(32), ts = 1L, lat = 1.0, lon = 2.0),
        )
        val entry = registry.find(resp.code)!!
        val tampered = entry.copy(sig = "deadbeef".repeat(8))
        assertFalse(registry.sigValid(tampered), "篡改后的签名必须校验失败")
        assertNotEquals(expectedSig(entry), tampered.sig)
    }

    @Test
    fun `哈希比对大小写归一且拒绝非法哈希`() {
        val sha = MessageDigest.getInstance("SHA-256").digest(photoBytes).joinToString("") { "%02x".format(it) }
        val resp = registry.register(PhotoRegisterRequest(sha256 = sha, ts = 1L, lat = 0.0, lon = 0.0))
        val entry = registry.find(resp.code)!!
        assertTrue(registry.hashMatches(entry, sha), "相同哈希应一致")
        assertTrue(registry.hashMatches(entry, sha.uppercase()), "大写哈希应归一后一致")
        assertFalse(registry.hashMatches(entry, "ff".repeat(32)), "不同哈希应不一致")
        assertFalse(registry.hashMatches(entry, "not-a-hash"), "非法哈希按不一致处理")
    }

    @Test
    fun `验证码大小写归一与未登记`() {
        val resp = registry.register(PhotoRegisterRequest(sha256 = "cd".repeat(32), ts = 1L, lat = 0.0, lon = 0.0))
        assertEquals(resp.code, registry.find(resp.code.lowercase())?.code, "小写验证码应归一命中")
        assertNull(registry.find("ZZZZ9999"), "未登记验证码应返回 null")
    }

    @Test
    fun `登记记录可序列化回环`() {
        val resp = registry.register(
            PhotoRegisterRequest(sha256 = "ef".repeat(32), ts = 42L, lat = 30.5, lon = 120.5, device = "d", kind = "SUSPICIOUS", note = "n"),
        )
        val entry = registry.find(resp.code)!!
        // 与 PhotoRegistry 内部一致的 JSON 配置做回环校验
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
        val roundTrip = json.decodeFromString(PhotoEntry.serializer(), json.encodeToString(PhotoEntry.serializer(), entry))
        assertEquals(entry, roundTrip)
    }
}
