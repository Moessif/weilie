package com.gakj.weilie.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RateLimiterTest {

    @Test
    fun `每分钟额度用尽后拒绝`() {
        val rl = RateLimiter()
        assertTrue(rl.allow("k", 2))
        assertTrue(rl.allow("k", 2))
        assertFalse(rl.allow("k", 2))
        assertEquals(1, rl.deniedCount())
    }

    @Test
    fun `不同 key 互不影响`() {
        val rl = RateLimiter()
        assertTrue(rl.allow("a", 1))
        assertTrue(rl.allow("b", 1))
        assertFalse(rl.allow("a", 1))
    }

    @Test
    fun `额度为零表示不限流`() {
        val rl = RateLimiter()
        repeat(50) { assertTrue(rl.allow("x", 0)) }
    }
}
