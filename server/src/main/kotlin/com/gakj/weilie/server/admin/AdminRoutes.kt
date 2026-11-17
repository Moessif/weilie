package com.gakj.weilie.server.admin
import com.gakj.weilie.server.trustedClientIp

import com.gakj.weilie.server.PhotoRegistry
import com.gakj.weilie.server.RoomStore
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.ZoneId

private val log = LoggerFactory.getLogger("WeilieAdminRoutes")

/**
 * 管理站 HTTP API（M1）：登录 / 组织 / 组织申请 / 用户 / 激活码 / 仪表盘。
 * 约定的错误都以 [AdminError] 抛出，由 StatusPages 统一转成 ErrorResponse。
 */
fun Route.adminRoutes(
    store: AdminStore,
    security: AdminSecurity,
    roomStore: RoomStore,
    photoRegistry: PhotoRegistry,
    serverVersion: String,
    startedAt: Long,
) {
    route("/api/admin") {

        // ---------------- 公开：组织申请 ----------------

        post("/organizations/apply") {
            // v1.12：匿名接口按 IP 限流，防批量灌库
            val ip = call.trustedClientIp()
            if (!security.allowAnonymous("org_apply", ip, 5)) {
                throw AdminError("提交过于频繁，请稍后再试", 429, "TOO_MANY_ATTEMPTS")
            }
            val req = call.body<CreateOrgApplicationRequest>()
            store.createApplication(req)
            call.respond(OkResponse())
        }

        // ---------------- 管理站登录 ----------------

        post("/auth/login") {
            val req = call.body<LoginRequest>()
            // v1.12：默认只信 socket 对端 IP（不再无条件采信 X-Forwarded-For）
            val ip = call.trustedClientIp()
            val username = req.username.trim().take(64)
            val key = "${username.lowercase()}@$ip"
            // 双桶限流：用户名+IP（5 次/5 分钟）与来源 IP 全局（30 次/5 分钟，防换用户名撞库）
            if (!security.loginAllowed(key) || !security.ipLoginAllowed(ip)) {
                throw AdminError("失败次数过多，请 5 分钟后再试", 429, "TOO_MANY_ATTEMPTS")
            }
            val user = store.findUserByUsername(username)
                ?: run {
                    security.loginFailed(key)
                    security.ipLoginFailed(ip)
                    throw AdminError("用户名或密码不正确", 401, "BAD_CREDENTIALS")
                }
            if (user.status == UserStatus.DELETED || user.status == UserStatus.SUSPENDED) {
                throw AdminError("账号已停用，请联系组织管理员", 403, "ACCOUNT_DISABLED")
            }
            if (user.status == UserStatus.LOCKED && user.lockedUntil > System.currentTimeMillis()) {
                throw AdminError("账号已锁定，请稍后再试或联系管理员", 423, "ACCOUNT_LOCKED")
            }
            if (!Password.verify(req.password, user.passwordHash)) {
                security.loginFailed(key)
                security.ipLoginFailed(ip)
                store.recordAdminFailure(user)
                throw AdminError("用户名或密码不正确", 401, "BAD_CREDENTIALS")
            }
            // v1.12：管理站只允许管理类角色登录。手机端普通账号（Role.USER）即使密码正确
            // 也不能进后台——否则任何拿到 App 账号的人都能读取全站房间/照片/审计数据（BFLA）。
            if (!AuthUser.from(user).canAccessAdminConsole) {
                security.loginFailed(key)
                security.ipLoginFailed(ip)
                throw AdminError("用户名或密码不正确", 401, "BAD_CREDENTIALS")
            }
            security.loginSucceeded(key)
            security.ipLoginSucceeded(ip)
            store.recordAdminLogin(user)
            val (token, csrf) = store.createSession(user.id, ip, call.request.headers["User-Agent"].orEmpty())
            security.appendSessionCookie(call, token)
            // 登录是敏感操作：写一条审计，管理站「审计日志」与仪表盘 24h 计数都依赖它
            store.audit(AuthUser.from(user), user.orgId, "auth.login", "user:${user.id}", "ip=$ip")
            call.respond(LoginResponse(user = user.toView(store.orgName(user.orgId)), csrfToken = csrf))
        }

        get("/auth/me") {
            // v1.12：改密前也要能读到 mustChangePassword 状态，因此这里放行
            val (auth, session) = security.requireForPasswordChange(call)
            val user = store.findUserById(auth.id) ?: throw AdminError("账号不存在", 401)
            val csrf = security.sessionToken(call)?.let { store.rotateCsrf(it) } ?: ""
            call.respond(MeResponse(user = user.toView(store.orgName(user.orgId)), csrfToken = csrf))
        }

        post("/auth/logout") {
            val (_, session) = security.requireForPasswordChange(call)
            security.sessionToken(call)?.let { store.revokeSession(it) }
            security.clearSessionCookie(call)
            log.info("admin logout userId={} sessionId={}", session.userId, session.id)
            call.respond(OkResponse())
        }

        post("/auth/change-password") {
            val (auth, session) = security.requireForPasswordChange(call)
            security.checkCsrf(call, session)
            val req = call.body<ChangePasswordRequest>()
            val user = store.findUserById(auth.id) ?: throw AdminError("账号不存在", 401)
            store.changeAdminPassword(user, req.oldPassword, req.newPassword)
            store.audit(auth, user.orgId, "admin.change-password", "user:${user.id}", "")
            security.clearSessionCookie(call)
            call.respond(OkResponse())
        }

        // ---------------- 组织 ----------------

        get("/orgs") {
            val (auth, _) = security.require(call)
            val (page, size) = call.paging()
            if (auth.isSuper) {
                val (total, items) = store.listOrgs(
                    call.request.queryParameters["q"],
                    call.request.queryParameters["status"],
                    page,
                    size,
                )
                call.respond(OrgListResponse(total = total, items = items, page = page, size = size))
            } else {
                val org = auth.orgId?.let { store.getOrg(it) }
                val stats = org?.let { store.orgStats(it.id) }
                call.respond(
                    OrgListResponse(
                        total = if (org == null) 0 else 1,
                        items = if (org == null) emptyList() else listOf(org.toView(stats?.users ?: 0, stats?.rooms ?: 0)),
                    ),
                )
            }
        }

        post("/orgs") {
            val (auth, _) = security.requireWrite(call)
            if (!auth.isSuper) throw AdminError("仅超级管理员可创建组织", 403, "FORBIDDEN")
            val req = call.body<CreateOrgRequest>()
            val created = store.createOrg(req, auth)
            call.respond(created)
        }

        get("/orgs/{id}") {
            val (auth, _) = security.require(call)
            val id = call.idParam()
            if (!auth.isSuper && auth.orgId != id) throw AdminError("组织不存在", 404)
            val org = store.getOrg(id) ?: throw AdminError("组织不存在", 404)
            val stats = store.orgStats(id)
            call.respond(
                OrgDetailResponse(
                    org = org.toView(stats.users, stats.rooms),
                    stats = stats,
                ),
            )
        }

        patch("/orgs/{id}") {
            val (auth, _) = security.requireWrite(call)
            if (!auth.isSuper) throw AdminError("仅超级管理员可修改组织", 403, "FORBIDDEN")
            val id = call.idParam()
            val req = call.body<UpdateOrgRequest>()
            store.updateOrg(id, req)
            store.audit(auth, id, "org.update", "org:$id", "")
            call.respond(OkResponse())
        }

        delete("/orgs/{id}") {
            val (auth, _) = security.requireWrite(call)
            if (!auth.isSuper) throw AdminError("仅超级管理员可停用组织", 403, "FORBIDDEN")
            val id = call.idParam()
            store.deleteOrg(id)
            store.audit(auth, id, "org.delete", "org:$id", "")
            call.respond(OkResponse())
        }

        // ---------------- 组织申请（管理端） ----------------

        get("/applications") {
            val (auth, _) = security.require(call)
            if (!auth.isSuper) throw AdminError("仅超级管理员可查看组织申请", 403, "FORBIDDEN")
            val (page, size) = call.paging()
            val (total, items) = store.listApplications(call.request.queryParameters["status"], page, size)
            call.respond(OrgApplicationListResponse(total = total, items = items))
        }

        post("/applications/{id}/approve") {
            val (auth, _) = security.requireWrite(call)
            if (!auth.isSuper) throw AdminError("仅超级管理员可审核组织申请", 403, "FORBIDDEN")
            val id = call.idParam()
            val req = call.body<ApproveApplicationRequest>()
            call.respond(store.approveApplication(id, req, auth))
        }

        post("/applications/{id}/reject") {
            val (auth, _) = security.requireWrite(call)
            if (!auth.isSuper) throw AdminError("仅超级管理员可审核组织申请", 403, "FORBIDDEN")
            val id = call.idParam()
            val req = call.body<RejectApplicationRequest>()
            store.rejectApplication(id, req.reason, auth)
            call.respond(OkResponse())
        }

        // ---------------- 用户与账号 ----------------

        get("/users") {
            val (auth, _) = security.require(call)
            val (page, size) = call.paging()
            val (total, items) = store.listUsers(
                auth,
                call.request.queryParameters["orgId"]?.toLongOrNull(),
                call.request.queryParameters["q"],
                call.request.queryParameters["status"],
                page,
                size,
            )
            call.respond(UserListResponse(total = total, items = items, page = page, size = size))
        }

        post("/users") {
            val (auth, _) = security.requireWrite(call)
            val req = call.body<CreateUserRequest>()
            assertCanSetRole(auth, req.role)
            call.respond(CreateUserResponse(user = store.createUser(req, auth)))
        }

        patch("/users/{id}") {
            val (auth, _) = security.requireWrite(call)
            val id = call.idParam()
            val req = call.body<UpdateUserRequest>()
            assertCanManageUser(store, auth, id)
            req.role?.let { assertCanSetRole(auth, it) }
            call.respond(store.updateUser(id, req, auth))
        }

        delete("/users/{id}") {
            val (auth, _) = security.requireWrite(call)
            val id = call.idParam()
            assertCanManageUser(store, auth, id)
            store.deleteUser(id, auth)
            call.respond(OkResponse())
        }

        post("/users/import") {
            val (auth, _) = security.requireWrite(call)
            val req = call.body<ImportUsersRequest>()
            call.respond(store.importUsers(req.orgId, req.rows, auth))
        }

        post("/users/{id}/unbind-device") {
            val (auth, _) = security.requireWrite(call)
            val id = call.idParam()
            assertCanManageUser(store, auth, id)
            store.unbindDevice(id, auth)
            call.respond(OkResponse())
        }

        post("/users/{id}/codes") {
            val (auth, _) = security.requireWrite(call)
            val id = call.idParam()
            assertCanManageUser(store, auth, id)
            val req = call.body<IssueCodeRequest>()
            val row = store.issueCode(id, req.purpose, auth.id)
            store.audit(auth, row.orgId, "code.issue", "user:$id", req.purpose.name)
            call.respond(row.toIssued())
        }

        post("/users/{id}/reset-password") {
            val (auth, _) = security.requireWrite(call)
            val id = call.idParam()
            // 安全加固：重置密码等于接管账号，必须先通过归属/等级校验（原先任意 ORG_ADMIN 可重置超管）
            assertCanManageUser(store, auth, id)
            val row = store.issueCode(id, CodePurpose.RESET_PASSWORD, auth.id)
            store.revokeClientTokens(id)
            store.revokeUserSessions(id)
            store.audit(auth, row.orgId, "user.reset-password", "user:$id", "")
            call.respond(row.toIssued())
        }

        // ---------------- 激活码 ----------------

        get("/codes") {
            val (auth, _) = security.require(call)
            val (page, size) = call.paging()
            val (total, items) = store.codeViews(
                auth,
                call.request.queryParameters["orgId"]?.toLongOrNull(),
                call.request.queryParameters["status"],
                page,
                size,
            )
            call.respond(CodeListResponse(total = total, items = items, page = page, size = size))
        }

        post("/codes/{id}/revoke") {
            val (auth, _) = security.requireWrite(call)
            store.revokeCode(call.idParam(), auth)
            call.respond(OkResponse())
        }

        // ---------------- 仪表盘 ----------------

        get("/dashboard/summary") {
            val (auth, _) = security.require(call)
            val orgScope = if (auth.isSuper) {
                call.request.queryParameters["orgId"]?.toLongOrNull()
            } else {
                auth.orgId
            }
            val counts = store.kpiCounts(orgScope)
            val rooms = roomStore.allRooms()
            val online = roomStore.onlineMemberCount()
            val todayStart = LocalDate.now(ZoneId.systemDefault()).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val todaySamples = rooms.sumOf { room -> room.samples.count { it.ts >= todayStart } }
            val activeRooms = rooms.count { it.sessions.isNotEmpty() }
            val audits24h = store.auditCountSince(System.currentTimeMillis() - 24 * 3600_000L, orgScope)
            val kpi = DashboardKpi(
                orgs = counts["orgs"] ?: 0,
                users = counts["users"] ?: 0,
                activeUsers = counts["activeUsers"] ?: 0,
                pendingUsers = counts["pendingUsers"] ?: 0,
                onlineMembers = online,
                onlinePeak = online,
                rooms = rooms.size,
                activeRooms = activeRooms,
                todaySamples = todaySamples,
                photos = photoRegistry.count(),
                audits24h = audits24h,
            )
            call.respond(
                DashboardSummaryResponse(
                    kpi = kpi,
                    server = DashboardServerInfo(
                        version = serverVersion,
                        uptimeSec = (System.currentTimeMillis() - startedAt) / 1000,
                        roomsInMemory = roomStore.roomCount(),
                        onlineMembers = online,
                    ),
                ),
            )
        }

        // ---------------- 前端契约别名（M1 页面使用的路径） ----------------

        get("/org-applications") {
            val (auth, _) = security.require(call)
            if (!auth.isSuper) throw AdminError("仅超级管理员可查看组织申请", 403, "FORBIDDEN")
            val (page, size) = call.paging()
            val (total, items) = store.listApplications(call.request.queryParameters["status"], page, size)
            call.respond(OrgApplicationListResponse(total = total, items = items, page = page, size = size))
        }

        post("/org-applications/{id}/approve") {
            val (auth, _) = security.requireWrite(call)
            if (!auth.isSuper) throw AdminError("仅超级管理员可审核组织申请", 403, "FORBIDDEN")
            val id = call.idParam()
            val req = call.body<ApproveApplicationRequest>()
            call.respond(store.approveApplication(id, req, auth))
        }

        post("/org-applications/{id}/reject") {
            val (auth, _) = security.requireWrite(call)
            if (!auth.isSuper) throw AdminError("仅超级管理员可审核组织申请", 403, "FORBIDDEN")
            val id = call.idParam()
            val req = call.body<RejectApplicationRequest>()
            store.rejectApplication(id, req.reason, auth)
            call.respond(OkResponse())
        }

        get("/users/{id}/codes") {
            val (auth, _) = security.require(call)
            call.respond(ActivationCodesResponse(items = store.codesForUser(auth, call.idParam())))
        }

        post("/users/{id}/activation-code") {
            val (auth, _) = security.requireWrite(call)
            val id = call.idParam()
            val req = call.body<IssueCodeRequest>()
            val row = store.issueCode(id, req.purpose, auth.id)
            store.audit(auth, row.orgId, "code.issue", "user:$id", req.purpose.name)
            call.respond(row.toIssued())
        }

        get("/activation-codes") {
            val (auth, _) = security.require(call)
            val (page, size) = call.paging()
            val (total, items) = store.codeViews(
                auth,
                call.request.queryParameters["orgId"]?.toLongOrNull(),
                call.request.queryParameters["status"],
                page,
                size,
            )
            call.respond(CodeListResponse(total = total, items = items, page = page, size = size))
        }

        post("/activation-codes/{id}/revoke") {
            val (auth, _) = security.requireWrite(call)
            store.revokeCode(call.idParam(), auth)
            call.respond(OkResponse())
        }

        get("/audit") {
            val (auth, _) = security.require(call)
            val (page, size) = call.paging()
            val (total, items) = store.auditPage(
                auth,
                call.request.queryParameters["action"],
                call.request.queryParameters["orgId"]?.toLongOrNull(),
                page,
                size,
                call.request.queryParameters["level"],
                call.request.queryParameters["q"],
            )
            call.respond(AuditListResponse(total = total, items = items, page = page, size = size))
        }

        get("/dashboard/timeseries") {
            val (auth, _) = security.require(call)
            val range = call.request.queryParameters["range"] ?: "24h"
            val metric = call.request.queryParameters["metric"] ?: "samples"
            val now = System.currentTimeMillis()
            val (from, bucket) = when (range) {
                "30d" -> (now - 30L * 24 * 3600_000L) to (24L * 3600_000L)
                "7d" -> (now - 7L * 24 * 3600_000L) to (6L * 3600_000L)
                else -> (now - 24L * 3600_000L) to (3600_000L)
            }
            call.respond(TimeseriesResult(points = store.metricSeries(metric, from, bucket)))
        }

        get("/dashboard/top-orgs") {
            security.require(call)
            // 房间尚未与组织强绑定（客户端接入后按 room_meta 归属统计），M1 返回空集合由前端降级展示
            call.respond(TopOrgsResult(items = emptyList()))
        }

        get("/dashboard/heatmap") {
            val (auth, _) = security.require(call)
            val days = call.request.queryParameters["days"]?.toIntOrNull()?.coerceIn(1, 30) ?: 7
            val orgScope = if (auth.isSuper) call.request.queryParameters["orgId"]?.toLongOrNull() else auth.orgId
            call.respond(HeatmapResult(cells = store.heatmapCells(days, orgScope)))
        }

        get("/dashboard/room-status") {
            security.require(call)
            // 前端按“房间里有什么”分档（与 mock 数据口径一致）：
            // WITH_ZONE=有任务区、POINT_ONLY=仅有标点、EMPTY=空房、CLOSED=已关闭（内存里已移除）。
            val rooms = roomStore.allRooms()
            val withZone = rooms.count { it.zones.isNotEmpty() }
            val pointOnly = rooms.count { it.zones.isEmpty() && it.markers.isNotEmpty() }
            val empty = rooms.count { it.zones.isEmpty() && it.markers.isEmpty() }
            call.respond(
                RoomStatusResult(
                    items = listOf(
                        RoomStatusCount("WITH_ZONE", withZone),
                        RoomStatusCount("POINT_ONLY", pointOnly),
                        RoomStatusCount("EMPTY", empty),
                        RoomStatusCount("CLOSED", 0),
                    ),
                ),
            )
        }

        get("/dashboard/recent-rooms") {
            security.require(call)
            val now = System.currentTimeMillis()
            val items = roomStore.allRooms()
                .sortedByDescending { it.lastActiveAt }
                .take(8)
                .map { room ->
                    RecentRoom(
                        roomId = room.roomId,
                        orgId = 0,
                        orgName = room.name,
                        members = room.members.size,
                        samples = room.samples.size,
                        lastActiveAt = room.lastActiveAt,
                        status = when {
                            room.members.isNotEmpty() && now - room.lastActiveAt < 30L * 60 * 1000 -> "OPEN"
                            now - room.lastActiveAt < 3L * 24 * 3600 * 1000 -> "IDLE"
                            else -> "CLOSED"
                        },
                    )
                }
            call.respond(RecentRoomsResult(items = items))
        }

        get("/dashboard/user-status") {
            val (auth, _) = security.require(call)
            val orgScope = if (auth.isSuper) {
                call.request.queryParameters["orgId"]?.toLongOrNull()
            } else {
                auth.orgId
            }
            call.respond(CountListResponse(items = store.userStatusCounts(orgScope)))
        }
    }
}

// ---------------- 小工具 ----------------

internal suspend inline fun <reified T : Any> ApplicationCall.body(): T =
    runCatching { receive<T>() }.getOrElse {
        throw AdminError("请求体格式不正确", 400, "BAD_BODY")
    }

private fun ApplicationCall.paging(): Pair<Int, Int> {
    val page = request.queryParameters["page"]?.toIntOrNull()?.coerceIn(1, 100_000) ?: 1
    val size = request.queryParameters["size"]?.toIntOrNull()?.coerceIn(1, 200) ?: 20
    return page to size
}

private fun ApplicationCall.idParam(): Long =
    parameters["id"]?.toLongOrNull() ?: throw AdminError("无效的 id", 400, "BAD_ID")

/**
 * 安全加固（水平/垂直越权）：非超管只能管理本组织用户，且不得触碰超管。
 * 目标越权时统一返回 404，避免通过状态码探测其他组织是否存在该用户。
 */
private fun assertCanManageUser(store: AdminStore, auth: AuthUser, targetId: Long): UserRow {
    val target = store.findUserById(targetId) ?: throw AdminError("用户不存在", 404, "NOT_FOUND")
    if (auth.isSuper) return target
    if (target.role == Role.SUPER_ADMIN) throw AdminError("无权操作超级管理员", 404, "NOT_FOUND")
    if (target.orgId == null || target.orgId != auth.orgId) throw AdminError("用户不存在", 404, "NOT_FOUND")
    return target
}

/**
 * 安全加固（垂直提权）：角色白名单。只有超管能授予 SUPER_ADMIN / ORG_ADMIN，
 * 组织管理员最多在本组织内授予 USER / AUDITOR。
 */
private fun assertCanSetRole(auth: AuthUser, role: Role) {
    if (auth.isSuper) return
    if (role == Role.SUPER_ADMIN || role == Role.ORG_ADMIN) {
        throw AdminError("无权授予该角色", 403, "ROLE_FORBIDDEN")
    }
}

