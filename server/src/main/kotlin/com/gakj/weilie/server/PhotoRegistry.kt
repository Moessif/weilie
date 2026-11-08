package com.gakj.weilie.server

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private val log = LoggerFactory.getLogger("weilie.photo")

/**
 * 取证照片在线验真：拍照时把照片 SHA-256 与拍摄元数据登记到本服务，
 * 服务端回一个 8 位验证码 + HMAC 签名；之后任何人凭验证码 + 照片即可核验
 * 「这张照片确实是某时某地这台设备拍的、且内容未被改动」。
 *
 * 关键设计：
 * - 秘密密钥只存在服务端（WEILIE_HMAC_SECRET 或 data/hmac.secret），客户端拿不到，
 *   所以签名无法伪造；客户端只保存 code / sig / sha256 回执；
 * - 记录追加写 photo-registry.jsonl（与现有数据同目录），坏行跳过，进程被杀不毁库；
 * - 签名比较、哈希比较全部走 MessageDigest.isEqual（常量时间），避免计时侧信道。
 */

/** POST /api/photo/register 请求体 */
@Serializable
data class PhotoRegisterRequest(
    val sha256: String,
    val ts: Long = 0L,
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val device: String = "",
    val kind: String = "",
    val note: String = "",
)

/** 登记记录（落盘与对外返回共用；sig 也在 JSONL 里，便于重启后继续校验） */
@Serializable
data class PhotoEntry(
    val code: String,
    val sha256: String,
    val ts: Long,
    val lat: Double,
    val lon: Double,
    val device: String = "",
    val kind: String = "",
    val note: String = "",
    val serverTs: Long,
    val sig: String,
)

/** POST /api/photo/register 响应 */
@Serializable
data class PhotoRegisterResponse(
    val ok: Boolean = true,
    val code: String,
    val sig: String,
    val serverTs: Long,
)

/** GET / POST /api/photo/verify 响应（未找到时只有 ok/found） */
@Serializable
data class PhotoVerifyResponse(
    val ok: Boolean = true,
    val found: Boolean,
    val hashMatch: Boolean? = null,
    val sigValid: Boolean? = null,
    val record: PhotoEntry? = null,
)

/** 简单错误响应（mapOf 混类型在 kotlinx 下不可序列化，单独建模） */
@Serializable
data class PhotoErrorResponse(val ok: Boolean = false, val error: String)

/** GET /api/photo/count 响应 */
@Serializable
data class PhotoCountResponse(val ok: Boolean = true, val count: Int)

/** POST /api/photo/verify 请求体 */
@Serializable
data class PhotoVerifyRequest(val code: String = "", val sha256: String = "")

/** 64 位十六进制（大小写都收，内部统一小写） */
private val SHA256_HEX = Regex("^[0-9a-fA-F]{64}$")

/** 验证码字符集：去掉容易读错的 I/O/0/1 */
private const val CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
private const val CODE_LEN = 8

/**
 * 照片登记仓库：内存索引 + JSONL 追加落盘。
 * 所有写操作串行化（Ktor 多线程），单机数据量小，不做分片。
 */
class PhotoRegistry(dataDir: Path) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
    private val file = dataDir.resolve("photo-registry.jsonl").toFile()
    private val lock = Any()
    private val byCode = LinkedHashMap<String, PhotoEntry>()
    private val random = SecureRandom()
    private val secret: ByteArray = loadSecret(dataDir)

    init {
        load()
    }

    private fun load() {
        runCatching {
            if (!file.isFile) return
            file.readLines()
                .filter { it.isNotBlank() }
                .forEach { line ->
                    val e = runCatching { json.decodeFromString(PhotoEntry.serializer(), line) }.getOrNull()
                    if (e != null) byCode[e.code] = e
                }
            if (byCode.isNotEmpty()) log.info("已恢复 {} 条照片登记记录", byCode.size)
        }.onFailure { log.warn("照片登记库读取失败: {}", it.message) }
    }

    fun count(): Int = synchronized(lock) { byCode.size }

    fun byCode(code: String): PhotoEntry? = synchronized(lock) { byCode[code] }

    /** 生成签名（服务端与校验共用同一段消息拼接，顺序不可改） */
    private fun sign(code: String, sha256: String, serverTs: Long, ts: Long, lat: Double, lon: Double): String {
        val message = "$code:$sha256:$serverTs:$ts:$lat:$lon"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8)).toHex()
    }

    /** 登记一张照片：生成唯一验证码 + 签名，追加落盘后返回回执 */
    fun register(req: PhotoRegisterRequest): PhotoRegisterResponse {
        val sha256 = req.sha256.trim().lowercase()
        val serverTs = System.currentTimeMillis()
        synchronized(lock) {
            var code = randomCode()
            var guard = 0
            while (byCode.containsKey(code) && guard++ < 1000) code = randomCode()
            val sig = sign(code, sha256, serverTs, req.ts, req.lat, req.lon)
            val entry = PhotoEntry(
                code = code,
                sha256 = sha256,
                ts = req.ts,
                lat = req.lat,
                lon = req.lon,
                device = req.device,
                kind = req.kind,
                note = req.note,
                serverTs = serverTs,
                sig = sig,
            )
            byCode[code] = entry
            runCatching {
                file.parentFile?.mkdirs()
                file.appendText(json.encodeToString(PhotoEntry.serializer(), entry) + "\n")
            }.onFailure { log.warn("照片登记落盘失败: {}", it.message) }
            return PhotoRegisterResponse(ok = true, code = code, sig = sig, serverTs = serverTs)
        }
    }

    /** 验证码是否命中 */
    fun find(codeRaw: String): PhotoEntry? {
        val code = codeRaw.trim().uppercase()
        if (code.length != CODE_LEN) return null
        return synchronized(lock) { byCode[code] }
    }

    /** 常量时间比较签名是否有效 */
    fun sigValid(entry: PhotoEntry): Boolean {
        val expected = sign(entry.code, entry.sha256, entry.serverTs, entry.ts, entry.lat, entry.lon)
        return MessageDigest.isEqual(
            expected.lowercase().toByteArray(Charsets.UTF_8),
            entry.sig.trim().lowercase().toByteArray(Charsets.UTF_8),
        )
    }

    /** 常量时间比较照片哈希是否一致（sha256 非法按不一致处理） */
    fun hashMatches(entry: PhotoEntry, sha256Raw: String): Boolean {
        val sha256 = sha256Raw.trim().lowercase()
        if (!SHA256_HEX.matches(sha256)) return false
        return MessageDigest.isEqual(
            entry.sha256.lowercase().toByteArray(Charsets.UTF_8),
            sha256.toByteArray(Charsets.UTF_8),
        )
    }

    private fun randomCode(): String = buildString(CODE_LEN) {
        repeat(CODE_LEN) { append(CODE_CHARS[random.nextInt(CODE_CHARS.length)]) }
    }

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private companion object {
        val HEX = "0123456789abcdef".toCharArray()

        /**
         * 密钥来源优先级：环境变量 WEILIE_HMAC_SECRET > dataDir/hmac.secret > 随机生成并 600 落盘。
         * 首次启动生成的密钥必须保留，丢了旧签名全部作废。
         */
        fun loadSecret(dataDir: Path): ByteArray {
            System.getenv("WEILIE_HMAC_SECRET")?.trim()?.takeIf { it.isNotBlank() }?.let {
                return it.toByteArray(Charsets.UTF_8)
            }
            val f = dataDir.resolve("hmac.secret").toFile()
            runCatching {
                if (f.isFile && f.length() > 0L) return f.readBytes()
            }.onFailure { log.warn("HMAC 密钥读取失败: {}", it.message) }

            val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            runCatching {
                dataDir.toFile().mkdirs()
                f.writeBytes(bytes)
                // Linux 上收窄到 600；Windows 不支持 POSIX 权限，忽略即可
                Files.setPosixFilePermissions(
                    f.toPath(),
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                )
            }.onFailure { log.warn("HMAC 密钥落盘失败（不影响内存运行）: {}", it.message) }
            return bytes
        }
    }
}

/**
 * 照片验真 REST 路由：
 * - POST /api/photo/register  登记（sha256/ts/lat/lon/device/kind/note）-> code/sig/serverTs
 * - GET  /api/photo/verify?code=CODE   按验证码查记录
 * - POST /api/photo/verify    {code, sha256} 一次校验哈希 + 签名
 * - GET  /api/photo/count     已登记条数
 */
fun Route.photoVerifyRoutes(registry: PhotoRegistry) {
    // 登记：sha256 必须 64 位十六进制，否则 400
    post("/api/photo/register") {
        val req = runCatching { call.receive<PhotoRegisterRequest>() }.getOrNull()
        if (req == null || !SHA256_HEX.matches(req.sha256.trim())) {
            call.respond(
                HttpStatusCode.BadRequest,
                PhotoErrorResponse(error = "sha256 必须为 64 位十六进制"),
            )
            return@post
        }
        call.respond(HttpStatusCode.OK, registry.register(req))
    }

    // 按验证码查登记记录（不校验内容，只看存在性与签名）
    get("/api/photo/verify") {
        val entry = registry.find(call.parameters["code"].orEmpty())
        if (entry == null) {
            call.respond(PhotoVerifyResponse(found = false))
        } else {
            call.respond(
                PhotoVerifyResponse(
                    found = true,
                    sigValid = registry.sigValid(entry),
                    record = entry,
                ),
            )
        }
    }

    // 一次校验：验证码 + 当前照片哈希（客户端把本地 SHA-256 传上来）
    post("/api/photo/verify") {
        val req = runCatching { call.receive<PhotoVerifyRequest>() }.getOrNull()
        if (req == null) {
            call.respond(HttpStatusCode.BadRequest, PhotoErrorResponse(error = "请求体格式错误"))
            return@post
        }
        val entry = registry.find(req.code)
        if (entry == null) {
            call.respond(PhotoVerifyResponse(found = false))
        } else {
            call.respond(
                PhotoVerifyResponse(
                    found = true,
                    hashMatch = registry.hashMatches(entry, req.sha256),
                    sigValid = registry.sigValid(entry),
                    record = entry,
                ),
            )
        }
    }

    // 已登记条数（状态页 / 运维小接口）
    get("/api/photo/count") {
        call.respond(PhotoCountResponse(count = registry.count()))
    }
}
