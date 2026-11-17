package com.gakj.weilie.server.admin

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * 密码散列：PBKDF2WithHmacSHA256，16 字节随机盐，210000 次迭代（纯 JDK，无额外依赖）。
 * 存储格式：`pbkdf2$<iterations>$<saltBase64>$<hashBase64>`
 */
object Password {
    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val ITERATIONS = 210_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16

    private val rnd = SecureRandom()

    fun hash(raw: String): String {
        val salt = ByteArray(SALT_BYTES).also { rnd.nextBytes(it) }
        val digest = derive(raw, salt, ITERATIONS)
        return "pbkdf2$$ITERATIONS$${b64(salt)}$${b64(digest)}"
    }

    fun verify(raw: String, stored: String): Boolean {
        val parts = stored.split('$')
        if (parts.size != 4 || parts[0] != "pbkdf2") return false
        val iterations = parts[1].toIntOrNull() ?: return false
        val salt = runCatching { Base64.getDecoder().decode(parts[2]) }.getOrNull() ?: return false
        val expected = runCatching { Base64.getDecoder().decode(parts[3]) }.getOrNull() ?: return false
        val actual = derive(raw, salt, iterations)
        return MessageDigest.isEqual(expected, actual)
    }

    /** 供初始化时生成随机密码（如未提供环境变量密码）。 */
    fun randomPassword(length: Int = 16): String = Tokens.randomToken(length)

    private fun derive(raw: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(raw.toCharArray(), salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
}

/** 令牌、激活码与摘要工具。 */
object Tokens {
    private const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private val rnd = SecureRandom()

    /** 32 字节随机令牌（base64url，无填充）。 */
    fun randomToken(byteLength: Int = 32): String {
        val bytes = ByteArray(byteLength).also { rnd.nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** 8 位一次性激活码（去掉易混字符 0/O/1/I）。 */
    fun activationCode(length: Int = 8): String =
        (1..length).map { CODE_ALPHABET[rnd.nextInt(CODE_ALPHABET.length)] }.joinToString("")

    /** 会话/令牌只存 sha256 摘要，防止数据库泄露后直接复用。 */
    fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** 常量时间比较两个十六进制摘要。 */
    fun sha256Equals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    /** 生成组织/用户可见的短编号（如房间归属标记）。 */
    fun shortId(prefix: String): String =
        prefix + (1..6).map { CODE_ALPHABET[rnd.nextInt(CODE_ALPHABET.length)] }.joinToString("").lowercase()
}
