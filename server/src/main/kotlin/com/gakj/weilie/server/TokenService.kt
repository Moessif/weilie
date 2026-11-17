package com.gakj.weilie.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** 入房令牌载荷（v1.4）：只放必要的身份与房间信息，短时效 */
@Serializable
data class RoomTokenClaims(
    val roomId: String,
    val userId: String,
    val name: String,
    val role: String,
    val exp: Long,
)

/**
 * 入房令牌（v1.4）：HMAC-SHA256 签名的无状态令牌，服务端不落库。
 *
 * 令牌格式：base64url(payloadJson) + "." + base64url(hmac)
 * 密钥来源：环境变量 WEILIE_HMAC_SECRET，其次 data/hmac.secret（与照片验签共用）。
 *
 * 兼容策略：`WEILIE_REQUIRE_TOKEN=1` 时才强制校验；默认关闭，
 * 保证旧客户端（不带 token）仍可连接，待客户端全量升级后再打开开关。
 */
class TokenService(secret: ByteArray, private val ttlHours: Long = 12) {

    private val key = SecretKeySpec(secret, "HmacSHA256")

    fun issue(roomId: String, userId: String, name: String, role: String): String {
        val exp = System.currentTimeMillis() + ttlHours * 3600_000
        val claims = RoomTokenClaims(roomId, userId, name, role, exp)
        val payload = b64(wsJson.encodeToString(claims).toByteArray())
        return "$payload.${sign(payload)}"
    }

    /** 校验签名与时效；成功返回 claims */
    fun verify(token: String, now: Long = System.currentTimeMillis()): RoomTokenClaims? {
        val parts = token.split('.')
        if (parts.size != 2) return null
        val payload = parts[0]
        val sig = parts[1]
        val expect = sign(payload)
        if (!constantTimeEquals(sig, expect)) return null
        val json = runCatching { String(Base64.getUrlDecoder().decode(payload)) }.getOrNull() ?: return null
        val claims = runCatching { wsJson.decodeFromString<RoomTokenClaims>(json) }.getOrNull() ?: return null
        if (claims.exp < now) return null
        return claims
    }

    private fun sign(payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(key)
        return b64(mac.doFinal(payload.toByteArray()))
    }

    private fun b64(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    companion object {
        /** 与照片验签共用密钥：环境变量优先，其次 data/hmac.secret，最后随机生成（重启后令牌失效） */
        fun loadSecret(dataDir: Path): ByteArray {
            System.getenv("WEILIE_HMAC_SECRET")?.trim()?.takeIf { it.isNotEmpty() }?.let {
                return it.toByteArray()
            }
            val f = dataDir.resolve("hmac.secret")
            runCatching {
                if (Files.exists(f)) return Files.readAllBytes(f)
            }
            val random = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
            runCatching {
                Files.createDirectories(dataDir)
                Files.write(f, random)
            }
            return random
        }

        /** 是否强制校验令牌（部署开关） */
        fun requireToken(): Boolean =
            (System.getenv("WEILIE_REQUIRE_TOKEN") ?: "0").trim() == "1"

        /**
         * v1.12：是否强制 WebSocket 连接携带房间邀请码（`?code=`）。
         *
         * 背景：默认模式下 WS 身份完全取自查询参数，任何知道 roomId 的人都能
         * 自称队长/他人 userId，读取全员实时定位。令牌模式（[requireToken]）能根治，
         * 但需要客户端升级；在客户端升级前，可先用本开关要求「知道邀请码才能入房」。
         * 旧版 App 不带 code，因此默认关闭，升级客户端后再打开。
         */
        fun requireJoinCode(): Boolean =
            (System.getenv("WEILIE_REQUIRE_JOIN_CODE") ?: "0").trim() == "1"
    }
}
