package com.gakj.weilie.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.io.ByteArrayOutputStream
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

private val log = LoggerFactory.getLogger("weilie.photo.files")

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
fun Route.photoFileRoutes(files: PhotoFiles) {
    post("/api/photo/upload") {
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
        val ok = files.save(code, bytes)
        call.respond(if (ok) HttpStatusCode.OK else HttpStatusCode.BadRequest, FileAck(ok = ok, bytes = bytes.size))
    }

    get("/api/photo/file") {
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
