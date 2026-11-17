package com.gakj.weilie.server.admin

import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.response.respond
import java.util.concurrent.ConcurrentHashMap

/** 登录态用户（管理站会话或手机端令牌解析后的统一视图）。 */
data class AuthUser(
    val id: Long,
    val username: String,
    val displayName: String = "",
    val role: Role = Role.USER,
    val orgId: Long? = null,
    val mustChangePassword: Boolean = false,
) {
    val isSuper: Boolean get() = role == Role.SUPER_ADMIN
    val canWrite: Boolean get() = role == Role.SUPER_ADMIN || role == Role.ORG_ADMIN

    /** v1.12：能否登录管理站。手机端普通账号（Role.USER）不具备后台权限。 */
    val canAccessAdminConsole: Boolean
        get() = role == Role.SUPER_ADMIN || role == Role.ORG_ADMIN || role == Role.AUDITOR

    companion object {
        fun from(row: UserRow) = AuthUser(
            id = row.id,
            username = row.username,
            displayName = row.displayName,
            role = row.role,
            orgId = row.orgId,
            mustChangePassword = row.mustChangePassword,
        )

        fun clientFrom(row: UserRow) = AuthUser(
            id = row.id,
            username = row.username,
            displayName = row.displayName,
            role = row.role,
            orgId = row.orgId,
        )
    }
}

/** 管理站会话：Cookie 名、CSRF 头、登录限流与鉴权辅助。 */
class AdminSecurity(
    private val store: AdminStore,
    private val loginThrottle: LoginThrottle = LoginThrottle(),
    /** v1.12：同一来源 IP 的全局失败上限（防换用户名撞库），默认 5 分钟内 30 次 */
    private val ipThrottle: LoginThrottle = LoginThrottle(maxFailures = 30),
    /** v1.12：组织申请等匿名接口的限流器 */
    private val anonLimiter: com.gakj.weilie.server.RateLimiter = com.gakj.weilie.server.RateLimiter(),
) {

    fun sessionToken(call: ApplicationCall): String? =
        call.request.cookies[COOKIE_NAME]?.takeIf { it.isNotBlank() }

    /** 解析当前管理站会话；失败返回 null（不抛异常，交给路由返回 401）。 */
    fun current(call: ApplicationCall): Pair<AuthUser, SessionRow>? {
        val token = sessionToken(call) ?: return null
        val session = store.findSession(token) ?: return null
        val user = store.findUserById(session.userId) ?: return null
        if (user.status == UserStatus.SUSPENDED || user.status == UserStatus.DELETED) return null
        return AuthUser.from(user) to session
    }

    /**
     * v1.12（对应审计 S3）：初始口令强制修改。
     * `mustChangePassword=true` 的账号在改密前只能访问 `/auth/me`、`/auth/change-password`、`/auth/logout`，
     * 其它接口一律 403 MUST_CHANGE_PASSWORD，避免默认/重置口令被长期沿用。
     */
    fun require(call: ApplicationCall, allowMustChange: Boolean = false): Pair<AuthUser, SessionRow> {
        val (user, session) = current(call)
            ?: throw AdminError("未登录或会话已过期", 401, "UNAUTHORIZED")
        if (user.mustChangePassword && !allowMustChange) {
            throw AdminError("请先修改初始密码", 403, "MUST_CHANGE_PASSWORD")
        }
        return user to session
    }

    fun requireWrite(call: ApplicationCall): Pair<AuthUser, SessionRow> {
        val (user, session) = require(call)
        if (!user.canWrite) throw AdminError("当前账号为只读权限", 403, "FORBIDDEN")
        checkCsrf(call, session)
        return user to session
    }

    /** 改密后必须让前端拿到「仍需改密」状态，因此 /auth/me 单独放行。 */
    fun requireForPasswordChange(call: ApplicationCall): Pair<AuthUser, SessionRow> =
        require(call, allowMustChange = true)

    /** 校验 X-CSRF-Token 与当前会话绑定的哈希是否一致。 */
    fun checkCsrf(call: ApplicationCall, session: SessionRow) {
        val header = call.request.headers[HEADER_CSRF]?.trim().orEmpty()
        if (header.isEmpty()) throw AdminError("缺少 CSRF 令牌", 403, "CSRF_MISSING")
        if (!Tokens.sha256Equals(Tokens.sha256Hex(header), session.csrfHash)) {
            throw AdminError("CSRF 令牌无效", 403, "CSRF_INVALID")
        }
    }

    /** 手机端 Bearer 令牌。 */
    fun requireClient(call: ApplicationCall): UserRow {
        val header = call.request.headers["Authorization"]?.trim().orEmpty()
        val token = when {
            header.startsWith("Bearer ", ignoreCase = true) -> header.substring(7).trim()
            else -> ""
        }
        if (token.isEmpty()) throw AdminError("缺少访问令牌", 401, "UNAUTHORIZED")
        return store.findClientToken(token) ?: throw AdminError("令牌无效或已过期，请重新登录", 401, "TOKEN_INVALID")
    }

    fun tryClient(call: ApplicationCall): UserRow? = runCatching { requireClient(call) }.getOrNull()

    fun loginAllowed(key: String): Boolean = loginThrottle.allow(key)

    fun loginFailed(key: String) = loginThrottle.record(key)

    fun loginSucceeded(key: String) = loginThrottle.reset(key)

    /** v1.12：同一来源 IP 的全局登录预算（跨用户名，防撞库）。 */
    fun ipLoginAllowed(ip: String): Boolean = ipThrottle.allow("ip:$ip")

    fun ipLoginFailed(ip: String) = ipThrottle.record("ip:$ip")

    fun ipLoginSucceeded(ip: String) = ipThrottle.reset("ip:$ip")

    /** v1.12：匿名接口（组织申请）按 IP 限流。 */
    fun allowAnonymous(scope: String, ip: String, perMinute: Int): Boolean =
        anonLimiter.allow("$scope:$ip", perMinute)

    /** 定期清理限流表，避免被随机用户名/伪造 IP 撑爆内存。 */
    fun purgeThrottles() {
        loginThrottle.purge()
        ipThrottle.purge()
        anonLimiter.purge()
    }

    fun appendSessionCookie(call: ApplicationCall, token: String) {
        call.response.cookies.append(
            Cookie(
                name = COOKIE_NAME,
                value = token,
                maxAge = SESSION_COOKIE_MAX_AGE,
                path = "/",
                httpOnly = true,
                // v1.12：HTTPS 部署时自动加 Secure；HTTP 部署可显式 WEILIE_COOKIE_SECURE=1 强制
                secure = cookieSecure(call),
                encoding = CookieEncoding.RAW,
                extensions = mapOf("SameSite" to "Lax"),
            ),
        )
    }

    /** Cookie 是否加 Secure：请求本身是 HTTPS，或部署方显式要求。 */
    fun cookieSecure(call: ApplicationCall): Boolean =
        cookieSecureFor(call.request.origin.scheme)

    fun clearSessionCookie(call: ApplicationCall) {
        call.response.cookies.append(
            Cookie(
                name = COOKIE_NAME,
                value = "",
                maxAge = 0,
                path = "/",
                httpOnly = true,
                // v1.12：清除时属性与下发保持一致，否则部分浏览器的 Secure/SameSite 处理会出现“清不掉”
                secure = cookieSecure(call),
                encoding = CookieEncoding.RAW,
                extensions = mapOf("SameSite" to "Lax"),
            ),
        )
    }

    companion object {
        const val COOKIE_NAME = "WEILIE_ADMIN"
        const val HEADER_CSRF = "X-CSRF-Token"
        const val SESSION_COOKIE_MAX_AGE = 8 * 3600
    }
}

/**
 * 会话 Cookie 是否加 `Secure`（v1.12）：
 * - 请求走 HTTPS 时必须加；
 * - 部署方也可以通过 `WEILIE_COOKIE_SECURE=1` 强制（例如 TLS 在反代终止且未透传 scheme）。
 * 纯函数便于单测。
 */
fun cookieSecureFor(scheme: String): Boolean =
    scheme.equals("https", ignoreCase = true) ||
        (System.getenv("WEILIE_COOKIE_SECURE") ?: "0").trim() == "1"

/** 登录失败限流：同一 key（用户名 + IP）5 分钟内最多 5 次失败。 */
class LoginThrottle(
    private val windowMs: Long = 5 * 60 * 1000,
    private val maxFailures: Int = 5,
) {
    private val failures = ConcurrentHashMap<String, MutableList<Long>>()

    fun allow(key: String): Boolean {
        val now = System.currentTimeMillis()
        val list = failures[key] ?: return true
        synchronized(list) {
            list.removeAll { now - it > windowMs }
            return list.size < maxFailures
        }
    }

    fun record(key: String) {
        val now = System.currentTimeMillis()
        val list = failures.getOrPut(key) { mutableListOf() }
        synchronized(list) {
            list.removeAll { now - it > windowMs }
            list.add(now)
        }
    }

    fun reset(key: String) {
        failures.remove(key)
    }

    /**
     * v1.12：清理过期记录并给表加上限，避免攻击者用随机用户名/随机 IP
     * 灌入无限 key 造成内存膨胀（进程内限流器的经典 DoS 面）。
     */
    fun purge(maxEntries: Int = 20_000) {
        val now = System.currentTimeMillis()
        failures.entries.removeIf { (_, list) ->
            synchronized(list) {
                list.removeAll { now - it > windowMs }
                list.isEmpty()
            }
        }
        if (failures.size <= maxEntries) return
        // 超限时按“最近一次失败时间”丢弃最旧的 key（粗略但足够）
        failures.entries
            .sortedBy { (_, list) -> synchronized(list) { list.maxOrNull() ?: 0L } }
            .take(failures.size - maxEntries)
            .forEach { (key, _) -> failures.remove(key) }
    }
}

/** 供响应层统一使用的错误辅助。 */
suspend fun ApplicationCall.respondOk(payload: Any) = respond(payload)

suspend fun ApplicationCall.respondAdminError(error: AdminError) {
    val status = when (error.status) {
        400, 401, 403, 404, 409, 423, 429, 500 -> io.ktor.http.HttpStatusCode.fromValue(error.status)
        else -> io.ktor.http.HttpStatusCode.BadRequest
    }
    respond(status, ErrorResponse(error = error.message, code = error.code))
}
