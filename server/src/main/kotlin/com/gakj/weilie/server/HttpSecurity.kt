package com.gakj.weilie.server

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.plugins.origin
import io.ktor.server.request.path

/**
 * 反向代理头信任开关（v1.12 安全加固）。
 *
 * 默认 **不信任** `X-Forwarded-For` / `X-Real-IP`：服务端直接监听 0.0.0.0:8080 且没有反向代理，
 * 先前的实现无条件采信这两个头，导致：
 * 1) 攻击者每次换一个伪造 IP 就能绕过登录失败限流（撞库）；
 * 2) 审计日志里的 IP 可被任意伪造，取证价值归零。
 *
 * 只有显式配置 `WEILIE_TRUST_PROXY=1`（前面确实有 nginx/网关时）才读代理头。
 */
fun trustProxyHeaders(): Boolean = (System.getenv("WEILIE_TRUST_PROXY") ?: "0").trim() == "1"

/** 仅接受合法 IP 形态（IPv4/IPv6），其余一律记 unknown，避免把任意字符串写进审计与限流 key。 */
private val IP_SHAPE = Regex("^[0-9a-fA-F:.]{3,45}$")

/**
 * 常量时间字符串比较（v1.12，审计 M3/L）：
 * 邀请码、令牌等秘密值不能用 `!=`/`==` 比较，否则逐字节提前返回的耗时差可被用于逐位爆破。
 */
fun constantTimeEquals(a: String, b: String): Boolean =
    java.security.MessageDigest.isEqual(
        a.toByteArray(Charsets.UTF_8),
        b.toByteArray(Charsets.UTF_8),
    )

/** 纯函数，便于单测：算出可信客户端 IP。 */
fun clientIpOf(remoteHost: String, forwarded: String?, realIp: String?, trustProxy: Boolean): String {
    val candidates = buildList {
        if (trustProxy) {
            // 采信代理头时优先取最左侧（真实客户端），但只接受形状合法的值；
            // 非法值逐个跳过，最后一定回落到 socket 对端地址，绝不让攻击者用一个
            // 乱填的头把限流 key 打成 "unknown"（那会让所有人共享同一个桶）。
            forwarded?.split(',')?.forEach { part ->
                part.trim().takeIf { it.isNotEmpty() }?.let { add(it) }
            }
            realIp?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
        }
        add(remoteHost)
    }
    for (candidate in candidates) {
        val clean = candidate.trim().take(45)
        if (IP_SHAPE.matches(clean)) return clean
    }
    return "unknown"
}

/** 当前请求的可信客户端 IP。 */
fun ApplicationCall.trustedClientIp(): String = clientIpOf(
    remoteHost = request.origin.remoteHost,
    forwarded = request.headers["X-Forwarded-For"],
    realIp = request.headers["X-Real-IP"],
    trustProxy = trustProxyHeaders(),
)

/**
 * 管理站静态资源路径白名单化（v1.12）：
 * 拒绝 `../`、反斜杠、绝对路径、控制字符，避免 `/admin/{path...}` 读到 classpath 其他资源。
 * 返回要读取的 classpath 资源名；非法返回 null。
 */
fun safeAdminAssetPath(raw: String): String? {
    val p = raw.replace('\\', '/').trim()
    // 先做安全判定再判断"是否像首页"：否则 `/etc/passwd` 这种无扩展名绝对路径
    // 会被当成 SPA 首页放行（虽然读不到，但语义上应当 400）。
    if (p.startsWith("/")) return null
    if (p.contains("..")) return null
    if (p.any { it.code < 0x20 || it.code == 0x7f }) return null
    if (p.isEmpty() || !p.contains('.')) return "admin/index.html"
    return "admin/$p"
}

/**
 * 请求体大小上限（v1.12，对应审计 V08）：
 * 原实现完全没有请求体限制，任何未认证请求都能用超大 JSON 把 `receive<T>()` 撑爆堆（OOM DoS）。
 * 这些路由全是小 JSON 或 8MB 以内的照片二进制，12MB 足够；超出直接 413 并中断管线。
 */
fun Application.requestSizeLimitPlugin(maxBytes: Long = 12L * 1024 * 1024) {
    intercept(ApplicationCallPipeline.Setup) {
        val declared = call.request.headers["Content-Length"]?.toLongOrNull()
        if (declared != null && declared > maxBytes) {
            call.respond(
                io.ktor.http.HttpStatusCode.PayloadTooLarge,
                mapOf("ok" to false, "error" to "请求体过大"),
            )
            finish()
        }
    }
}

/**
 * 通用安全响应头（v1.12）：
 * - `X-Content-Type-Options: nosniff` 防止 MIME 嗅探
 * - `X-Frame-Options: DENY` + CSP `frame-ancestors 'none'` 防点击劫持
 * - `Referrer-Policy` / `Permissions-Policy` 收敛泄露面
 * - 管理站与验真页额外下发 CSP（脚本仅同源，提升 XSS 门槛）
 *
 * 说明：本服务经 HTTP 暴露时无法下发 HSTS（无意义），待上 TLS 后再加。
 */
fun securityHeadersPlugin() = createApplicationPlugin("WeilieSecurityHeaders") {
    onCall { call ->
        val headers = call.response.headers
        headers.append("X-Content-Type-Options", "nosniff")
        headers.append("X-Frame-Options", "DENY")
        headers.append("Referrer-Policy", "no-referrer")
        headers.append(
            "Permissions-Policy",
            "geolocation=(), camera=(), microphone=(), payment=(), usb=()",
        )
        headers.append("Cross-Origin-Opener-Policy", "same-origin")
        val path = call.request.path()
        if (path == "/admin" || path.startsWith("/admin/") || path.startsWith("/v/")) {
            headers.append(
                "Content-Security-Policy",
                "default-src 'self'; img-src 'self' data: blob:; " +
                    "style-src 'self' 'unsafe-inline'; script-src 'self'; " +
                    "connect-src 'self'; font-src 'self' data:; " +
                    "frame-ancestors 'none'; base-uri 'self'; form-action 'self'",
            )
        }
    }
}
