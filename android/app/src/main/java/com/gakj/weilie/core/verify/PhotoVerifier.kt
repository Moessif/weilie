package com.gakj.weilie.core.verify

import com.gakj.weilie.core.photo.PhotoRecord
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.math.BigInteger
import java.security.MessageDigest

/**
 * 取证照片在线验真（客户端）。
 *
 * 流程：
 * 1. 拍照落库后把照片 SHA-256 + 拍摄元数据 POST 到 /api/photo/register，
 *    服务端回 8 位验证码 + HMAC 签名（密钥只在服务端），客户端把回执存 verify.jsonl；
 * 2. 以后任何人凭验证码/二维码 + 照片调 /api/photo/verify，服务端一次比对
 *    哈希（照片是否被改）与签名（登记记录是否伪造）。
 *
 * 本文件三块：哈希工具 [PhotoHasher]、回执持久化 [VerifyStore]、网络客户端 [PhotoVerifier]。
 */

/** 照片 SHA-256（流式读，8KB 缓冲，大图也不吃内存） */
object PhotoHasher {

    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(8 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (n == 0) continue
                digest.update(buf, 0, n)
            }
        }
        return BigInteger(1, digest.digest()).toString(16).padStart(64, '0')
    }
}

/**
 * 单张照片的验证结果。
 * [error] 非空表示这次验证没做成（网络/服务器异常），不代表照片有问题。
 */
/** 远程照片登记信息（本机没有照片原件时，按验证码从服务器查到的元数据） */
data class RemotePhoto(
    val code: String,
    val sha256: String,
    val ts: Long,
    val lat: Double,
    val lon: Double,
    val device: String = "",
    val kind: String = "",
    val note: String = "",
    val serverTs: Long = 0L,
    val sigValid: Boolean = false,
) {
    val hashShort: String get() = if (sha256.length >= 16) sha256.take(16) + "…" else sha256
}
data class VerifyResult(
    val found: Boolean,
    val hashMatch: Boolean,
    val sigValid: Boolean,
    val serverTs: Long?,
    val code: String?,
    val error: String? = null,
) {
    /** 三项都通过才算「验真通过」 */
    val passed: Boolean
        get() = error == null && found && hashMatch && sigValid
}

/** 登记回执（单独一个 jsonl，不碰 PhotoRecord，避免污染旧索引） */
@Serializable
data class VerifyEntry(
    val photoId: String,
    val code: String,
    val sig: String,
    val sha256: String,
    val serverTs: Long = 0L,
)

/**
 * 验证回执库：filesDir/photos/verify.jsonl，一行一条 [VerifyEntry]。
 * 写法与 [com.gakj.weilie.core.photo.PhotoStore] 一致：追加写、坏行跳过，不引入 Room/DataStore。
 */
class VerifyStore(private val dir: File) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val index: File get() = File(dir, "verify.jsonl")

    fun ensure() {
        if (!dir.exists()) dir.mkdirs()
    }

    fun add(entry: VerifyEntry) {
        ensure()
        runCatching { index.appendText(json.encodeToString(VerifyEntry.serializer(), entry) + "\n") }
    }

    fun all(): List<VerifyEntry> = runCatching {
        if (!index.exists()) emptyList()
        else index.readLines()
            .filter { it.isNotBlank() }
            .mapNotNull { line -> runCatching { json.decodeFromString(VerifyEntry.serializer(), line) }.getOrNull() }
    }.getOrElse { emptyList() }

    /** 同一张照片重复登记时以最后一次为准（返回最后写入的回执） */
    fun byPhotoId(photoId: String): VerifyEntry? = all().lastOrNull { it.photoId == photoId }

    fun contains(photoId: String): Boolean = byPhotoId(photoId) != null

    /** 待补登记队列：有照片记录但还没有回执的那些（典型是离线拍摄） */
    fun pending(records: List<PhotoRecord>): List<PhotoRecord> {
        val done = all().mapTo(HashSet()) { it.photoId }
        return records.filter { it.id !in done }
    }
}

/** 登记请求体（字段名与服务端 PhotoRegisterRequest 对齐） */
@Serializable
private data class RegisterBody(
    val sha256: String,
    val ts: Long,
    val lat: Double,
    val lon: Double,
    val device: String,
    val kind: String,
    val note: String,
)

@Serializable
private data class RegisterResp(
    val ok: Boolean = false,
    val code: String = "",
    val sig: String = "",
    val serverTs: Long = 0L,
)

@Serializable
private data class VerifyBody(val code: String, val sha256: String)

/** POST /api/photo/upload 响应（只需要 ok） */
@Serializable
private data class UploadAck(val ok: Boolean = false, val bytes: Int = 0)

@Serializable
private data class VerifyRecordDto(
    val code: String = "",
    val sha256: String = "",
    val ts: Long = 0L,
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val device: String = "",
    val kind: String = "",
    val note: String = "",
    val serverTs: Long = 0L,
    val sig: String = "",
)

/** 注意：服务端未命中时会把 hashMatch/sigValid 显式输出成 null，这里必须可空接 */
@Serializable
private data class VerifyResp(
    val ok: Boolean = false,
    val found: Boolean = false,
    val hashMatch: Boolean? = null,
    val sigValid: Boolean? = null,
    val record: VerifyRecordDto? = null,
)

/**
 * 照片验真 HTTP 客户端（Ktor CIO，与 RoomClient 同引擎）。
 *
 * 约定：网络异常一律归一成 [VerifyResult.error] / register 返回 null，绝不向上抛，
 * 保证 UI 层不需要 try/catch 也不会因为断网崩。
 *
 * @param baseUrl 服务器地址（http:// 或 ws:// 都可，构造时归一成 http）
 * @param store   回执库；传入后 register 成功会自动落盘，重复登记直接复用已有回执
 */
class PhotoVerifier(baseUrl: String, private val store: VerifyStore? = null) {

    private val base = normalizeBase(baseUrl)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val client by lazy { HttpClient(CIO) }

    /** 串行化登记：拍照后自动登记与详情页补登记可能并发，避免同一张照片登记出两个码 */
    private val registerLock = Mutex()

    /**
     * 登记一张照片；成功返回 (code, sig)，失败（离线/服务器异常/文件缺失）返回 null。
     * 已有回执时直接复用（幂等），不会重复申请验证码。
     */
    suspend fun register(file: File, rec: PhotoRecord): Pair<String, String>? {
        store?.byPhotoId(rec.id)?.let { return it.code to it.sig }
        return registerLock.withLock {
            // 等锁期间可能已被另一次调用登记成功
            store?.byPhotoId(rec.id)?.let { return@withLock it.code to it.sig }
            if (!file.isFile || file.length() <= 0L) return@withLock null
            val sha256 = withContext(Dispatchers.IO) {
                runCatching { PhotoHasher.sha256Hex(file) }.getOrNull()
            } ?: return@withLock null
            val body = RegisterBody(
                sha256 = sha256,
                ts = rec.ts,
                lat = rec.lat,
                lon = rec.lon,
                device = rec.deviceType,
                kind = rec.kind,
                note = rec.note,
            )
            val resp = runCatching {
                withTimeout(TIMEOUT_MS) {
                    client.post("$base/api/photo/register") {
                        contentType(ContentType.Application.Json)
                        setBody(json.encodeToString(RegisterBody.serializer(), body))
                    }.bodyAsText()
                }
            }.getOrNull() ?: return@withLock null
            val dto = runCatching { json.decodeFromString(RegisterResp.serializer(), resp) }.getOrNull()
                ?: return@withLock null
            if (!dto.ok || dto.code.isBlank()) return@withLock null
            store?.add(
                VerifyEntry(
                    photoId = rec.id,
                    code = dto.code,
                    sig = dto.sig,
                    sha256 = sha256,
                    serverTs = dto.serverTs,
                ),
            )
            dto.code to dto.sig
        }
    }

    /**
     * 把照片原图上传到服务端（按验证码存），供同一房间的其他队员打开查看。
     *
     * 失败不抛异常：调用方多数是「拍照后尽力上传」，失败时队友只能看登记信息。
     */
    suspend fun uploadFile(code: String, file: File): Boolean {
        val clean = code.trim().uppercase()
        if (clean.isBlank() || !file.isFile || file.length() <= 0L) return false
        return runCatching {
            withTimeout(20_000L) {
                val resp = client.post("$base/api/photo/upload?code=$clean") {
                    contentType(ContentType.Application.OctetStream)
                    setBody(file.readBytes())
                }.bodyAsText()
                json.decodeFromString(UploadAck.serializer(), resp).ok
            }
        }.getOrDefault(false)
    }

    /** 下载队友上传的照片原图；没有上传/离线/异常都返回 null */
    suspend fun fetchRemoteImage(code: String): ByteArray? {
        val clean = code.trim().uppercase()
        if (clean.isBlank()) return null
        return runCatching {
            withTimeout(15_000L) {
                val resp = client.get("$base/api/photo/file?code=$clean")
                if (resp.status.value in 200..299) resp.body<ByteArray>() else null
            }
        }.getOrNull()
    }

    /**
     * 按验证码 + 照片哈希校验；任何异常都折算成带回中文 error 的 [VerifyResult]。
     */
    suspend fun verify(code: String, sha256: String): VerifyResult {
        val cleanCode = code.trim().uppercase()
        val cleanSha = sha256.trim().lowercase()
        if (cleanCode.isBlank()) return fail(cleanCode, "验证码为空")
        if (cleanSha.length != 64) return fail(cleanCode, "照片哈希异常，请重新拍照")

        val body = VerifyBody(code = cleanCode, sha256 = cleanSha)
        val text = try {
            withTimeout(TIMEOUT_MS) {
                client.post("$base/api/photo/verify") {
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(VerifyBody.serializer(), body))
                }.bodyAsText()
            }
        } catch (_: TimeoutCancellationException) {
            return fail(cleanCode, "验证请求超时（8 秒），请检查网络")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // 协程被取消要原样抛出，不能吞
        } catch (e: Exception) {
            return fail(cleanCode, "无法连接验证服务器：${e.message ?: "网络异常"}")
        }

        val dto = runCatching { json.decodeFromString(VerifyResp.serializer(), text) }.getOrNull()
            ?: return fail(cleanCode, "服务器返回格式异常")
        if (!dto.ok) return fail(cleanCode, "服务器处理失败")
        if (!dto.found) {
            return VerifyResult(
                found = false,
                hashMatch = false,
                sigValid = false,
                serverTs = null,
                code = cleanCode,
            )
        }
        return VerifyResult(
            found = true,
            hashMatch = dto.hashMatch ?: false,
            sigValid = dto.sigValid ?: false,
            serverTs = dto.record?.serverTs,
            code = dto.record?.code?.ifBlank { cleanCode } ?: cleanCode,
        )
    }

    /**
     * 按验证码查登记信息（不需要照片原件）。
     *
     * 跨设备场景：队友只收到 photoId + code，本机没有照片文件，用这个接口把溯源信息（时间/坐标/哈希/签名）
     * 显示出来；任何异常都返回 null，不打断 UI。
     */
    suspend fun lookup(code: String): RemotePhoto? {
        val cleanCode = code.trim().uppercase()
        if (cleanCode.isBlank()) return null
        val text = runCatching {
            withTimeout(TIMEOUT_MS) {
                client.get("$base/api/photo/verify?code=$cleanCode").bodyAsText()
            }
        }.getOrNull() ?: return null
        val dto = runCatching { json.decodeFromString(VerifyResp.serializer(), text) }.getOrNull() ?: return null
        if (!dto.ok || !dto.found) return null
        val rec = dto.record ?: return null
        return RemotePhoto(
            code = rec.code,
            sha256 = rec.sha256,
            ts = rec.ts,
            lat = rec.lat,
            lon = rec.lon,
            device = rec.device,
            kind = rec.kind,
            note = rec.note,
            serverTs = rec.serverTs,
            sigValid = dto.sigValid ?: false,
        )
    }

    /** 直接按文件校验（内部现算 SHA-256；文件缺失给中文错误） */
    suspend fun verifyFile(file: File, code: String): VerifyResult {
        if (!file.isFile || file.length() <= 0L) {
            return fail(code.trim().uppercase(), "照片文件缺失，无法计算哈希")
        }
        val sha = withContext(Dispatchers.IO) {
            runCatching { PhotoHasher.sha256Hex(file) }.getOrNull()
        } ?: return fail(code.trim().uppercase(), "照片读取失败，无法计算哈希")
        return verify(code, sha)
    }

    private fun fail(code: String, message: String): VerifyResult = VerifyResult(
        found = false,
        hashMatch = false,
        sigValid = false,
        serverTs = null,
        code = code.ifBlank { null },
        error = message,
    )

    private companion object {
        const val TIMEOUT_MS = 8_000L

        /** 设置页可能填 ws://，验真走 REST 要换成 http:// */
        fun normalizeBase(raw: String): String {
            val cleaned = raw.trim().trimEnd('/')
            return when {
                cleaned.startsWith("ws://") -> "http://" + cleaned.removePrefix("ws://")
                cleaned.startsWith("wss://") -> "https://" + cleaned.removePrefix("wss://")
                cleaned.isBlank() -> ""
                else -> cleaned
            }
        }
    }
}
