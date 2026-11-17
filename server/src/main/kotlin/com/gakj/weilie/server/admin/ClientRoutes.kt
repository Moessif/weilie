package com.gakj.weilie.server.admin
import com.gakj.weilie.server.trustedClientIp

import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * 手机客户端账号 API：激活 / 登录 / 当前用户 / 登出 / 改密码。
 * 令牌为 Bearer，30 天滑动有效；/api/client 下的接口与房间 WebSocket 可用同一令牌。
 */
fun Route.clientRoutes(store: AdminStore, security: AdminSecurity) {
    route("/api/client") {

        post("/activate") {
            // v1.12（审计 L6）：激活码接口是匿名可用入口，必须限流，否则可被用来无限试探码状态
            val ip = call.trustedClientIp()
            if (!security.allowAnonymous("client_activate", ip, 10) ||
                !security.allowAnonymous("client_activate_global", "all", 300)
            ) {
                throw AdminError("激活请求过于频繁，请稍后再试", 429, "TOO_MANY_ATTEMPTS")
            }
            val req = call.body<ActivateRequest>()
            val code = req.code.trim().uppercase()
            if (code.isBlank()) throw AdminError("请输入激活码")
            if (req.password.length < 8) throw AdminError("密码至少 8 位")
            val deviceId = req.deviceId.trim()
            val user = store.activate(code, req.password, deviceId, req.deviceName.trim())
            security.loginSucceeded("client:${user.id}")
            val token = store.createClientToken(user.id, deviceId)
            store.audit(null, user.orgId, "client.activate", "user:${user.id}", "device=$deviceId")
            call.respond(
                ClientAuthResponse(
                    token = token,
                    user = user.toClientView(store.orgName(user.orgId)),
                ),
            )
        }

        post("/login") {
            val req = call.body<ClientLoginRequest>()
            val username = req.username.trim()
            // v1.12：只信 socket 对端 IP；用户名+IP 与来源 IP 双桶限流
            val ip = call.trustedClientIp()
            val key = "${username.lowercase()}@$ip"
            if (!security.loginAllowed(key) || !security.ipLoginAllowed(ip)) {
                throw AdminError("失败次数过多，请 5 分钟后再试", 429, "TOO_MANY_ATTEMPTS")
            }
            val user = runCatching { store.clientLogin(username, req.password, req.deviceId.trim()) }
                .getOrElse { error ->
                    security.loginFailed(key)
                    security.ipLoginFailed(ip)
                    throw error
                }
            security.loginSucceeded(key)
            security.ipLoginSucceeded(ip)
            val token = store.createClientToken(user.id, req.deviceId.trim())
            store.audit(null, user.orgId, "client.login", "user:${user.id}", "device=${req.deviceId.trim()}")
            call.respond(
                ClientAuthResponse(
                    token = token,
                    user = user.toClientView(store.orgName(user.orgId)),
                ),
            )
        }

        get("/me") {
            val user = security.requireClient(call)
            call.respond(ClientMeResponse(user = user.toClientView(store.orgName(user.orgId))))
        }

        post("/logout") {
            security.requireClient(call)
            call.bearerToken()?.let { store.revokeClientToken(it) }
            call.respond(OkResponse())
        }

        post("/change-password") {
            val user = security.requireClient(call)
            val req = call.body<ChangePasswordRequest>()
            store.changeClientPassword(user, req.oldPassword, req.newPassword)
            store.audit(null, user.orgId, "client.change-password", "user:${user.id}", "")
            call.respond(OkResponse())
        }
    }
}

private fun ApplicationCall.bearerToken(): String? {
    val header = request.headers["Authorization"]?.trim().orEmpty()
    return if (header.startsWith("Bearer ", ignoreCase = true)) header.substring(7).trim().takeIf { it.isNotEmpty() } else null
}
