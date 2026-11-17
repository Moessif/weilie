package com.gakj.weilie.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

private val log = LoggerFactory.getLogger("weilie.photo.files")

/** 内容哈希（大写十六进制，用于与 PhotoRegistry 登记的 sha256 比对） */
private fun sha256Hex(bytes: ByteArray): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { b -> "%02x".format(b) }
}

/** 单张照片上限：手机端按最长边 1600、质量 88 压缩后一般 < 1MB，8MB 足够兜底 */
private const val MAX_BYTES = 8 * 1024 * 1024

/** 验证码形态：8 位大写字母数字（与 PhotoRegistry 生成规则一致，放宽到 6~12 位兼容历史） */
private val CODE_RE = Regex("^[A-Z0-9]{6,12}$")

@Serializable
data class FileAck(val ok: Boolean, val bytes: Int = 0)

/**
 * 取证照片文件库：把照片字节按验证码落盘（data/photos/<code>.jpg）。
 *
 * 与 [PhotoRegistry] 的分工：
 * - Registry 只保存「哈希 + 拍摄元数据 + 签名」，用于验真；
 * - 本类保存原图字节，用于「多人房间里其他队员能打开同一张照片」。
 *
 * 安全考虑：验证码是 8 位随机串，不可枚举；下载接口只按验证码取，不列目录。
 * 若某张照片不希望被队友看到，拍照时不要上传即可（客户端按需上传）。
 */
class PhotoFiles(dataDir: Path) {

    private val dir: Path = dataDir.resolve("photos").also {
        runCatching { Files.createDirectories(it) }
            .onFailure { e -> log.warn("照片目录创建失败: {}", e.message) }
    }

    fun save(code: String, bytes: ByteArray): Boolean {
        val clean = normalize(code) ?: return false
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return false
        val target = dir.resolve("$clean.jpg")
        val tmp = dir.resolve("$clean.jpg.tmp")
        return runCatching {
            Files.write(tmp, bytes)
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            true
        }.onFailure { log.warn("照片写入失败 {}: {}", clean, it.message) }.getOrDefault(false)
    }

    fun load(code: String): ByteArray? {
        val clean = normalize(code) ?: return null
        val file = dir.resolve("$clean.jpg")
        if (!Files.isRegularFile(file)) return null
        return runCatching { Files.readAllBytes(file) }.getOrNull()
    }

    /** 是否存在原图（只做 stat，不读全文——列表接口避免内存放大） */
    fun exists(code: String): Boolean {
        val clean = normalize(code) ?: return false
        return Files.isRegularFile(dir.resolve("$clean.jpg"))
    }

    /** 原图字节数（只做 stat） */
    fun sizeOf(code: String): Long {
        val clean = normalize(code) ?: return 0L
        return runCatching { Files.size(dir.resolve("$clean.jpg")) }.getOrDefault(0L)
    }

    fun count(): Int = runCatching {
        Files.list(dir).use { stream -> stream.filter { it.fileName.toString().endsWith(".jpg") }.count().toInt() }
    }.getOrDefault(0)

    private fun normalize(code: String): String? {
        val clean = code.trim().uppercase()
        return if (CODE_RE.matches(clean)) clean else null
    }
}

/**
 * 照片文件接口：
 * - `POST /api/photo/upload?code=XXXXXXXX`：请求体为 JPEG 原始字节（Content-Type 任意，按字节存）；
 * - `GET  /api/photo/file?code=XXXXXXXX`：返回 image/jpeg，供房间内其他队员查看。
 */
fun Route.photoFileRoutes(
    files: PhotoFiles,
    limiter: RateLimiter = RateLimiter(),
    registry: PhotoRegistry? = null,
) {
    post("/api/photo/upload") {
        val ip = call.request.origin.remoteHost
        if (!limiter.allow("photo_up:$ip", 30)) {
            call.respond(HttpStatusCode.TooManyRequests, FileAck(ok = false))
            return@post
        }
        val code = call.request.queryParameters["code"].orEmpty()
        val bytes = runCatching {
            val channel = call.receiveChannel()
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = channel.readAvailable(buf, 0, buf.size)
                if (n <= 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_BYTES) return@runCatching null
            }
            out.toByteArray()
        }.getOrNull()
        if (bytes == null || bytes.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, FileAck(ok = false))
            return@post
        }
        // v1.12（审计 V09/M7）：只接受 JPEG/PNG 魔数，避免把任意二进制/脚本写进照片目录
        if (!looksLikeImage(bytes)) {
            log.warn("照片上传内容不是 JPEG/PNG code={}", code)
            call.respond(HttpStatusCode.BadRequest, FileAck(ok = false))
            return@post
        }
        // 安全加固：只允许把已登记验证码的字节写一次，且内容哈希必须与登记时一致。
        // 否则任何人可用已知 code 上传任意图片覆盖已有取证照片（证据篡改）。
        if (registry != null) {
            val entry = registry.byCode(code.trim().uppercase())
            if (entry == null) {
                call.respond(HttpStatusCode.NotFound, FileAck(ok = false))
                return@post
            }
            if (!MessageDigest.isEqual(sha256Hex(bytes).toByteArray(), entry.sha256.toByteArray())) {
                log.warn("照片上传哈希与登记不符 code={}", code)
                call.respond(HttpStatusCode.Conflict, FileAck(ok = false))
                return@post
            }
            val existing = files.load(code)
            if (existing != null && MessageDigest.isEqual(existing, bytes)) {
                // 幂等重传（客户端重试）直接成功，避免重复覆盖
                call.respond(HttpStatusCode.OK, FileAck(ok = true, bytes = bytes.size))
                return@post
            }
        }
        val ok = files.save(code, bytes)
        if (ok) Metrics.photosUploaded.incrementAndGet()
        call.respond(if (ok) HttpStatusCode.OK else HttpStatusCode.BadRequest, FileAck(ok = ok, bytes = bytes.size))
    }

    get("/api/photo/file") {
        val ip = call.request.origin.remoteHost
        if (!limiter.allow("photo_get:$ip", 120)) {
            call.respond(HttpStatusCode.TooManyRequests, FileAck(ok = false))
            return@get
        }
        val code = call.request.queryParameters["code"].orEmpty()
        val bytes = files.load(code)
        if (bytes == null) {
            call.respond(HttpStatusCode.NotFound, FileAck(ok = false))
        } else {
            call.respondBytes(bytes = bytes, contentType = ContentType.Image.JPEG)
        }
    }

    get("/api/photo/file/count") {
        call.respond(FileAck(ok = true, bytes = files.count()))
    }
}
/**
 * v1.12（审计 V09/M7）：照片魔数白名单。
 * 上传路径原本任意字节都能落盘成 `.jpg`，既可写入畸形文件污染取证目录，也便于后续误用。
 */
private fun looksLikeImage(bytes: ByteArray): Boolean {
    if (bytes.size < 8) return false
    val jpeg = bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
    val png = bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
        bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
    return jpeg || png
}