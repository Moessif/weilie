package com.gakj.weilie.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TokenServiceTest {

    private val secret = ByteArray(32) { it.toByte() }

    @Test
    fun `签发的令牌可校验且身份一致`() {
        val svc = TokenService(secret)
        val token = svc.issue("room1", "u1", "张三", "CAPTAIN")
        val claims = svc.verify(token)
        assertNotNull(claims)
        assertEquals("room1", claims.roomId)
        assertEquals("u1", claims.userId)
        assertEquals("张三", claims.name)
        assertEquals("CAPTAIN", claims.role)
    }

    @Test
    fun `篡改载荷验签失败`() {
        val svc = TokenService(secret)
        val token = svc.issue("room1", "u1", "张三", "MEMBER")
        val parts = token.split(".")
        val tampered = parts[0].dropLast(2) + "AA" + "." + parts[1]
        assertNull(svc.verify(tampered))
    }

    @Test
    fun `不同密钥无法互验`() {
        val a = TokenService(secret)
        val b = TokenService(ByteArray(32) { (it + 1).toByte() })
        assertNull(b.verify(a.issue("room1", "u1", "张三", "MEMBER")))
    }

    @Test
    fun `过期令牌被拒绝`() {
        val svc = TokenService(secret, ttlHours = -1)
        assertNull(svc.verify(svc.issue("room1", "u1", "张三", "MEMBER")))
    }
}
