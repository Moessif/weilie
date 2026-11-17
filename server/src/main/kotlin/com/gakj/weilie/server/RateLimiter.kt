package com.gakj.weilie.server

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 轻量令牌桶限流（v1.3）：按 key（IP / userId / 接口名）限速，进程内实现。
 * 单机部署够用；多实例部署时应换成 Redis 或网关限流。
 */
class RateLimiter {

    private class Bucket(var tokens: Double, var lastRefillMs: Long)

    private val buckets = ConcurrentHashMap<String, Bucket>()
    private val denied = AtomicLong()

    /** 返回 true 表示放行 */
    fun allow(key: String, perMinute: Int): Boolean {
        if (perMinute <= 0) return true
        val now = System.currentTimeMillis()
        val capacity = perMinute.toDouble()
        val bucket = buckets.computeIfAbsent(key) { Bucket(capacity, now) }
        synchronized(bucket) {
            val elapsed = (now - bucket.lastRefillMs).coerceAtLeast(0)
            if (elapsed > 0) {
                bucket.tokens = (bucket.tokens + elapsed * capacity / 60_000.0).coerceAtMost(capacity)
                bucket.lastRefillMs = now
            }
            return if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0
                true
            } else {
                denied.incrementAndGet()
                false
            }
        }
    }

    /** 清理长时间不用的桶，避免内存无上限 */
    fun purge(idleMs: Long = 10 * 60_000L) {
        val now = System.currentTimeMillis()
        buckets.entries.removeIf { (_, b) -> now - b.lastRefillMs > idleMs }
    }

    fun deniedCount(): Long = denied.get()
}
