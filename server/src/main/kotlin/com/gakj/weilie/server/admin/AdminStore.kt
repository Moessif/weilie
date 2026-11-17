package com.gakj.weilie.server.admin

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement
import java.sql.Types

// ---------------- 行模型 ----------------

data class OrgRow(
    val id: Long,
    val name: String,
    val code: String,
    val contactName: String,
    val contactPhone: String,
    val status: OrgStatus,
    val quotaUsers: Int,
    val quotaRooms: Int,
    val createdAt: Long,
    val updatedAt: Long,
) {
    fun toView(userCount: Int = 0, roomCount: Int = 0) = OrgView(
        id = id,
        name = name,
        code = code,
        contactName = contactName,
        contactPhone = contactPhone,
        status = status,
        quotaUsers = quotaUsers,
        quotaRooms = quotaRooms,
        createdAt = createdAt,
        updatedAt = updatedAt,
        userCount = userCount,
        roomCount = roomCount,
    )
}

data class UserRow(
    val id: Long,
    val orgId: Long?,
    val username: String,
    val displayName: String,
    val phone: String,
    val role: Role,
    val status: UserStatus,
    val passwordHash: String,
    val deviceId: String,
    val deviceName: String,
    val mustChangePassword: Boolean,
    val failCount: Int,
    val lockedUntil: Long,
    val activatedAt: Long,
    val lastLoginAt: Long,
    val createdAt: Long,
    val deletedAt: Long,
) {
    fun toView(orgName: String? = null) = UserView(
        id = id,
        username = username,
        displayName = displayName,
        phone = phone,
        role = role,
        status = status,
        orgId = orgId,
        orgName = orgName,
        deviceId = deviceId,
        deviceName = deviceName,
        mustChangePassword = mustChangePassword,
        activatedAt = activatedAt,
        lastLoginAt = lastLoginAt,
        createdAt = createdAt,
    )

    fun toClientView(orgName: String? = null) = ClientUserView(
        id = id,
        username = username,
        displayName = displayName,
        orgId = orgId,
        orgName = orgName,
        role = role,
    )
}

data class CodeRow(
    val id: Long,
    val userId: Long,
    val orgId: Long?,
    val code: String,
    val purpose: CodePurpose,
    val expiresAt: Long,
    val usedAt: Long,
    val revokedAt: Long,
    val createdAt: Long,
    val username: String = "",
    val displayName: String = "",
) {
    fun toIssued() = IssuedCode(
        id = id,
        code = code,
        purpose = purpose,
        expiresAt = expiresAt,
        userId = userId,
        username = username,
        displayName = displayName,
    )

    fun status(now: Long): CodeStatus = when {
        revokedAt > 0 -> CodeStatus.REVOKED
        usedAt > 0 -> CodeStatus.USED
        now > expiresAt -> CodeStatus.EXPIRED
        else -> CodeStatus.UNUSED
    }
}

data class SessionRow(
    val id: Long,
    val userId: Long,
    val lastSeenAt: Long,
    val expiresAt: Long,
    val csrfHash: String,
    /** v1.12（审计 L2）：会话创建时间，用于绝对寿命上限（滑动续期不能无限续命）。 */
    val createdAt: Long = 0L,
)

data class AuditRow(
    val id: Long,
    val ts: Long,
    val actorId: Long?,
    val actorName: String,
    val orgId: Long?,
    val action: String,
    val target: String,
    val detail: String,
    val ip: String,
)

data class ApplicationRow(
    val id: Long,
    val orgName: String,
    val contactName: String,
    val contactPhone: String,
    val email: String,
    val reason: String,
    val status: String,
    val reviewNote: String,
    val createdAt: Long,
    val reviewedAt: Long,
) {
    fun toView() = OrgApplicationView(
        id = id,
        orgName = orgName,
        contactName = contactName,
        contactPhone = contactPhone,
        email = email,
        reason = reason,
        status = status,
        reviewNote = reviewNote,
        createdAt = createdAt,
        reviewedAt = reviewedAt,
    )
}

/** 管理平台数据访问层：纯 JDBC + 手写 SQL，不引 ORM。 */
class AdminStore(val db: Db) {

    // ---------------- 通用 JDBC 辅助 ----------------

    private fun PreparedStatement.bind(args: Array<out Any?>): PreparedStatement {
        args.forEachIndexed { index, value ->
            val i = index + 1
            when (value) {
                null -> setNull(i, Types.VARCHAR)
                is String -> setString(i, value)
                is Long -> setLong(i, value)
                is Int -> setInt(i, value)
                is Boolean -> setInt(i, if (value) 1 else 0)
                is Double -> setDouble(i, value)
                else -> setString(i, value.toString())
            }
        }
        return this
    }

    private fun <T> Connection.query(sql: String, vararg args: Any?, map: (ResultSet) -> T): List<T> =
        prepareStatement(sql).use { ps ->
            ps.bind(args).use { st ->
                st.executeQuery().use { rs ->
                    val out = ArrayList<T>()
                    while (rs.next()) out.add(map(rs))
                    out
                }
            }
        }

    private fun <T> Connection.queryOne(sql: String, vararg args: Any?, map: (ResultSet) -> T): T? =
        prepareStatement(sql).use { ps ->
            ps.bind(args).use { st ->
                st.executeQuery().use { rs -> if (rs.next()) map(rs) else null }
            }
        }

    private fun Connection.execute(sql: String, vararg args: Any?): Int =
        prepareStatement(sql).use { ps -> ps.bind(args).use { it.executeUpdate() } }

    private fun Connection.insert(sql: String, vararg args: Any?): Long =
        prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { ps ->
            ps.bind(args).use { st ->
                st.executeUpdate()
                st.generatedKeys.use { rs -> if (rs.next()) rs.getLong(1) else -1L }
            }
        }

    private fun ResultSet.str(name: String): String = getString(name) ?: ""
    private fun ResultSet.lng(name: String): Long = getLong(name)
    private fun ResultSet.int(name: String): Int = getInt(name)
    private fun ResultSet.bool(name: String): Boolean = getInt(name) != 0
    private fun ResultSet.lngOrNull(name: String): Long? {
        val v = getLong(name)
        return if (wasNull()) null else v
    }

    private fun mapOrg(rs: ResultSet) = OrgRow(
        id = rs.lng("id"),
        name = rs.str("name"),
        code = rs.str("code"),
        contactName = rs.str("contact_name"),
        contactPhone = rs.str("contact_phone"),
        status = enumOf(rs.str("status"), OrgStatus.ACTIVE),
        quotaUsers = rs.int("quota_users"),
        quotaRooms = rs.int("quota_rooms"),
        createdAt = rs.lng("created_at"),
        updatedAt = rs.lng("updated_at"),
    )

    private fun mapUser(rs: ResultSet) = UserRow(
        id = rs.lng("id"),
        orgId = rs.lngOrNull("org_id"),
        username = rs.str("username"),
        displayName = rs.str("display_name"),
        phone = rs.str("phone"),
        role = enumOf(rs.str("role"), Role.USER),
        status = enumOf(rs.str("status"), UserStatus.PENDING),
        passwordHash = rs.str("password_hash"),
        deviceId = rs.str("device_id"),
        deviceName = rs.str("device_name"),
        mustChangePassword = rs.bool("must_change_password"),
        failCount = rs.int("fail_count"),
        lockedUntil = rs.lng("locked_until"),
        activatedAt = rs.lng("activated_at"),
        lastLoginAt = rs.lng("last_login_at"),
        createdAt = rs.lng("created_at"),
        deletedAt = rs.lng("deleted_at"),
    )

    private fun mapCode(rs: ResultSet) = CodeRow(
        id = rs.lng("id"),
        userId = rs.lng("user_id"),
        orgId = rs.lngOrNull("org_id"),
        code = rs.str("code"),
        purpose = enumOf(rs.str("purpose"), CodePurpose.ACTIVATE),
        expiresAt = rs.lng("expires_at"),
        usedAt = rs.lng("used_at"),
        revokedAt = rs.lng("revoked_at"),
        createdAt = rs.lng("created_at"),
        username = runCatching { rs.str("username") }.getOrDefault(""),
        displayName = runCatching { rs.str("display_name") }.getOrDefault(""),
    )

    private fun mapApplication(rs: ResultSet) = ApplicationRow(
        id = rs.lng("id"),
        orgName = rs.str("org_name"),
        contactName = rs.str("contact_name"),
        contactPhone = rs.str("contact_phone"),
        email = rs.str("email"),
        reason = rs.str("reason"),
        status = rs.str("status"),
        reviewNote = rs.str("review_note"),
        createdAt = rs.lng("created_at"),
        reviewedAt = rs.lng("reviewed_at"),
    )

    private inline fun <reified E : Enum<E>> enumOf(name: String, fallback: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: fallback

    // ---------------- 初始化 ----------------

    /** 首次启动时创建超级管理员；已存在则不动。 */
    fun ensureSuperAdmin(username: String, password: String, mustChange: Boolean = true) {
        val existing = db.withConn { c ->
            c.queryOne("SELECT id FROM users WHERE role='SUPER_ADMIN' AND deleted_at=0 LIMIT 1") { it.getLong(1) }
        }
        if (existing != null) return
        val now = now()
        db.transaction { c ->
            c.insert(
                """
                INSERT INTO users(org_id, username, display_name, role, status, password_hash,
                                  must_change_password, created_at, updated_at, activated_at)
                VALUES(NULL, ?, ?, 'SUPER_ADMIN', 'ACTIVE', ?, ?, ?, ?, ?)
                """.trimIndent(),
                username, "超级管理员", Password.hash(password), if (mustChange) 1 else 0, now, now, now,
            )
        }
    }

    fun findUserByUsername(username: String): UserRow? = db.withConn { c ->
        c.queryOne("SELECT * FROM users WHERE username=? AND deleted_at=0", username.trim()) { mapUser(it) }
    }

    fun findUserById(id: Long): UserRow? = db.withConn { c ->
        c.queryOne("SELECT * FROM users WHERE id=? AND deleted_at=0", id) { mapUser(it) }
    }

    fun orgName(orgId: Long?): String? {
        if (orgId == null) return null
        return db.withConn { c -> c.queryOne("SELECT name FROM orgs WHERE id=?", orgId) { it.getString(1) } }
    }

    fun isUsernameTaken(username: String): Boolean = findUserByUsername(username) != null

    // ---------------- 组织 ----------------

    fun listOrgs(q: String?, status: String?, page: Int, size: Int): Pair<Int, List<OrgView>> = db.withConn { c ->
        val where = StringBuilder(" WHERE 1=1")
        val args = ArrayList<Any?>()
        if (!q.isNullOrBlank()) {
            where.append(" AND (name LIKE ? OR code LIKE ? OR contact_name LIKE ?)")
            val like = "%${q.trim()}%"
            args.add(like); args.add(like); args.add(like)
        }
        if (!status.isNullOrBlank() && status != "ALL") {
            where.append(" AND status=?")
            args.add(status)
        }
        val total = c.queryOne("SELECT COUNT(*) FROM orgs$where", *args.toTypedArray()) { it.getInt(1) } ?: 0
        args.add(size.coerceIn(1, 200))
        args.add(((page - 1).coerceAtLeast(0)) * size)
        val items = c.query(
            "SELECT * FROM orgs$where ORDER BY id DESC LIMIT ? OFFSET ?",
            *args.toTypedArray(),
        ) { rs ->
            val org = mapOrg(rs)
            val users = c.queryOne("SELECT COUNT(*) FROM users WHERE org_id=? AND deleted_at=0", org.id) { it.getInt(1) } ?: 0
            val rooms = c.queryOne("SELECT COUNT(*) FROM room_meta WHERE org_id=?", org.id) { it.getInt(1) } ?: 0
            org.toView(users, rooms)
        }
        total to items
    }

    fun getOrg(id: Long): OrgRow? = db.withConn { c ->
        c.queryOne("SELECT * FROM orgs WHERE id=?", id) { mapOrg(it) }
    }

    fun orgStats(orgId: Long): OrgStats = db.withConn { c ->
        val users = c.queryOne("SELECT COUNT(*) FROM users WHERE org_id=? AND deleted_at=0", orgId) { it.getInt(1) } ?: 0
        val active = c.queryOne("SELECT COUNT(*) FROM users WHERE org_id=? AND deleted_at=0 AND status='ACTIVE'", orgId) { it.getInt(1) } ?: 0
        val pending = c.queryOne("SELECT COUNT(*) FROM users WHERE org_id=? AND deleted_at=0 AND status='PENDING'", orgId) { it.getInt(1) } ?: 0
        val rooms = c.queryOne("SELECT COUNT(*) FROM room_meta WHERE org_id=?", orgId) { it.getInt(1) } ?: 0
        OrgStats(users = users, activeUsers = active, pendingUsers = pending, rooms = rooms)
    }

    fun createOrg(req: CreateOrgRequest, actor: AuthUser): CreateOrgResponse = db.transaction { c ->
        val now = now()
        val code = req.code.trim().ifBlank { Tokens.shortId("org") }
        if (c.queryOne("SELECT id FROM orgs WHERE code=?", code) { it.getLong(1) } != null) {
            throw AdminError("组织编码已存在：$code")
        }
        val orgId = c.insert(
            "INSERT INTO orgs(name, code, contact_name, contact_phone, status, quota_users, quota_rooms, created_at, updated_at) " +
                "VALUES(?,?,?,?,'ACTIVE',?,?,?,?)",
            req.name.trim(), code, req.contactName.trim(), req.contactPhone.trim(),
            req.quotaUsers, req.quotaRooms, now, now,
        )
        var adminUser: UserView? = null
        var initialPassword: String? = null
        if (req.adminUsername.isNotBlank()) {
            val password = req.adminPassword.ifBlank {
                Tokens.randomToken(10)
            }
            if (req.adminPassword.isBlank()) initialPassword = password
            val err = validateNewUser(req.adminUsername, orgId)
            if (err != null) throw AdminError(err)
            val userId = c.insert(
                "INSERT INTO users(org_id, username, display_name, role, status, password_hash, must_change_password, " +
                    "created_at, updated_at, activated_at) VALUES(?,?,?,'ORG_ADMIN','ACTIVE',?,?,?,?,?)",
                orgId, req.adminUsername.trim(), req.adminDisplayName.ifBlank { "组织管理员" },
                Password.hash(password), 1, now, now, now,
            )
            adminUser = UserRow(
                id = userId, orgId = orgId, username = req.adminUsername.trim(),
                displayName = req.adminDisplayName.ifBlank { "组织管理员" }, phone = "",
                role = Role.ORG_ADMIN, status = UserStatus.ACTIVE, passwordHash = "",
                deviceId = "", deviceName = "", mustChangePassword = true, failCount = 0, lockedUntil = 0,
                activatedAt = now, lastLoginAt = 0, createdAt = now, deletedAt = 0,
            ).toView(req.name.trim())
        }
        audit(c, actor, orgId, "org.create", "org:$orgId", "name=${req.name}")
        CreateOrgResponse(org = getOrgIn(c, orgId)!!.toView(), adminUser = adminUser, initialPassword = initialPassword)
    }

    private fun getOrgIn(c: Connection, id: Long): OrgRow? =
        c.queryOne("SELECT * FROM orgs WHERE id=?", id) { mapOrg(it) }

    fun updateOrg(id: Long, req: UpdateOrgRequest): OrgView {
        val now = now()
        db.transaction { c ->
            val org = getOrgIn(c, id) ?: throw AdminError("组织不存在", 404)
            c.execute(
                "UPDATE orgs SET name=?, contact_name=?, contact_phone=?, status=?, quota_users=?, quota_rooms=?, updated_at=? WHERE id=?",
                req.name?.trim() ?: org.name,
                req.contactName?.trim() ?: org.contactName,
                req.contactPhone?.trim() ?: org.contactPhone,
                (req.status ?: org.status).name,
                req.quotaUsers ?: org.quotaUsers,
                req.quotaRooms ?: org.quotaRooms,
                now, id,
            )
        }
        return getOrg(id)!!.toView()
    }

    fun deleteOrg(id: Long) {
        db.transaction { c ->
            val org = getOrgIn(c, id) ?: throw AdminError("组织不存在", 404)
            c.execute("UPDATE orgs SET status='SUSPENDED', updated_at=? WHERE id=?", now(), org.id)
        }
    }

    // ---------------- 组织申请 ----------------

    fun createApplication(req: CreateOrgApplicationRequest) {
        if (req.orgName.isBlank()) throw AdminError("组织名称不能为空")
        db.withConn { c ->
            c.insert(
                "INSERT INTO org_applications(org_name, contact_name, contact_phone, email, reason, status, created_at) " +
                    "VALUES(?,?,?,?,?,'PENDING',?)",
                req.orgName.trim(), req.contactName.trim(), req.contactPhone.trim(), req.email.trim(), req.reason.trim(), now(),
            )
        }
    }

    fun listApplications(status: String?, page: Int, size: Int): Pair<Int, List<OrgApplicationView>> = db.withConn { c ->
        val where = StringBuilder(" WHERE 1=1")
        val args = ArrayList<Any?>()
        if (!status.isNullOrBlank() && status != "ALL") {
            where.append(" AND status=?"); args.add(status)
        }
        val total = c.queryOne("SELECT COUNT(*) FROM org_applications$where", *args.toTypedArray()) { it.getInt(1) } ?: 0
        args.add(size.coerceIn(1, 200)); args.add((page - 1).coerceAtLeast(0) * size)
        val items = c.query(
            "SELECT * FROM org_applications$where ORDER BY id DESC LIMIT ? OFFSET ?",
            *args.toTypedArray(),
        ) { mapApplication(it).toView() }
        total to items
    }

    fun approveApplication(id: Long, req: ApproveApplicationRequest, actor: AuthUser): CreateOrgResponse = db.transaction { c ->
        val app = c.queryOne("SELECT * FROM org_applications WHERE id=?", id) { mapApplication(it) }
            ?: throw AdminError("申请不存在", 404)
        if (app.status != "PENDING") throw AdminError("该申请已处理")
        val code = req.code.trim().ifBlank { Tokens.shortId("org") }
        if (c.queryOne("SELECT id FROM orgs WHERE code=?", code) { it.getLong(1) } != null) {
            throw AdminError("组织编码已存在：$code")
        }
        val now = now()
        val orgId = c.insert(
            "INSERT INTO orgs(name, code, contact_name, contact_phone, status, created_at, updated_at) VALUES(?,?,?,?,'ACTIVE',?,?)",
            app.orgName, code, app.contactName, app.contactPhone, now, now,
        )
        c.execute(
            "UPDATE org_applications SET status='APPROVED', org_id=?, reviewed_at=?, review_note=? WHERE id=?",
            orgId, now, "已开通 $code", id,
        )
        var adminUser: UserView? = null
        var initialPassword: String? = null
        if (req.adminUsername.isNotBlank()) {
            val password = req.adminPassword.ifBlank { Tokens.randomToken(10) }
            if (req.adminPassword.isBlank()) initialPassword = password
            if (isUsernameTakenIn(c, req.adminUsername)) throw AdminError("管理员用户名已被占用")
            val userId = c.insert(
                "INSERT INTO users(org_id, username, display_name, role, status, password_hash, must_change_password, " +
                    "created_at, updated_at, activated_at) VALUES(?,?,?,'ORG_ADMIN','ACTIVE',?,?,?,?,?)",
                orgId, req.adminUsername.trim(), req.adminDisplayName.ifBlank { "组织管理员" },
                Password.hash(password), 1, now, now, now,
            )
            adminUser = UserRow(
                userId, orgId, req.adminUsername.trim(), req.adminDisplayName.ifBlank { "组织管理员" }, "",
                Role.ORG_ADMIN, UserStatus.ACTIVE, "", "", "", true, 0, 0, now, 0, now, 0,
            ).toView(app.orgName)
        }
        audit(c, actor, orgId, "org.approve", "application:$id", "org=${app.orgName}")
        CreateOrgResponse(org = getOrgIn(c, orgId)!!.toView(), adminUser = adminUser, initialPassword = initialPassword)
    }

    fun rejectApplication(id: Long, reason: String, actor: AuthUser) {
        db.transaction { c ->
            val app = c.queryOne("SELECT * FROM org_applications WHERE id=?", id) { mapApplication(it) }
                ?: throw AdminError("申请不存在", 404)
            if (app.status != "PENDING") throw AdminError("该申请已处理")
            c.execute(
                "UPDATE org_applications SET status='REJECTED', review_note=?, reviewed_at=? WHERE id=?",
                reason.trim(), now(), id,
            )
            audit(c, actor, null, "org.reject", "application:$id", reason)
        }
    }

    private fun isUsernameTakenIn(c: Connection, username: String): Boolean =
        c.queryOne("SELECT id FROM users WHERE username=? AND deleted_at=0", username.trim()) { it.getLong(1) } != null

    // ---------------- 用户 ----------------

    private fun validateNewUser(username: String, orgId: Long?): String? {
        val u = username.trim()
        if (u.length < 3 || u.length > 32) return "用户名长度需 3–32 位"
        if (!u.matches(Regex("^[a-zA-Z0-9_.-]+$"))) return "用户名只能包含字母、数字、下划线、点、短横线"
        if (isUsernameTaken(u)) return "用户名已存在：$u"
        return null
    }

    fun listUsers(
        actor: AuthUser,
        orgId: Long?,
        q: String?,
        status: String?,
        page: Int,
        size: Int,
    ): Pair<Int, List<UserView>> = db.withConn { c ->
        val where = StringBuilder(" WHERE deleted_at=0")
        val args = ArrayList<Any?>()
        val scopeOrg = if (actor.role == Role.SUPER_ADMIN) orgId else actor.orgId
        if (scopeOrg != null) {
            where.append(" AND org_id=?"); args.add(scopeOrg)
        }
        if (actor.role != Role.SUPER_ADMIN && actor.role != Role.ORG_ADMIN && actor.role != Role.AUDITOR) {
            where.append(" AND id=?"); args.add(actor.id)
        }
        if (!q.isNullOrBlank()) {
            where.append(" AND (username LIKE ? OR display_name LIKE ? OR phone LIKE ?)")
            val like = "%${q.trim()}%"
            args.add(like); args.add(like); args.add(like)
        }
        if (!status.isNullOrBlank() && status != "ALL") {
            where.append(" AND status=?"); args.add(status)
        }
        val total = c.queryOne("SELECT COUNT(*) FROM users$where", *args.toTypedArray()) { it.getInt(1) } ?: 0
        args.add(size.coerceIn(1, 200)); args.add((page - 1).coerceAtLeast(0) * size)
        val rows = c.query("SELECT * FROM users$where ORDER BY id DESC LIMIT ? OFFSET ?", *args.toTypedArray()) { mapUser(it) }
        val names = mutableMapOf<Long, String>()
        rows.mapNotNull { it.orgId }.distinct().forEach { id ->
            names[id] = c.queryOne("SELECT name FROM orgs WHERE id=?", id) { it.getString(1) } ?: ""
        }
        total to rows.map { it.toView(names[it.orgId]) }
    }

    fun createUser(req: CreateUserRequest, actor: AuthUser): UserView {
        val orgId = if (actor.role == Role.SUPER_ADMIN) req.orgId else actor.orgId
        if (orgId == null) throw AdminError("请选择组织")
        validateNewUser(req.username, orgId)?.let { throw AdminError(it) }
        // v1.12（审计 S1）：建号同样受角色白名单约束，避免 ORG_ADMIN 直接建出 SUPER_ADMIN
        assertRoleAssignable(actor, req.role)
        val now = now()
        val id = db.transaction { c ->
            val org = getOrgIn(c, orgId) ?: throw AdminError("组织不存在", 404)
            val count = c.queryOne("SELECT COUNT(*) FROM users WHERE org_id=? AND deleted_at=0", orgId) { it.getInt(1) } ?: 0
            if (count >= org.quotaUsers) throw AdminError("该组织用户数已达上限（${org.quotaUsers}）")
            val uid = c.insert(
                "INSERT INTO users(org_id, username, display_name, phone, role, status, created_at, updated_at) " +
                    "VALUES(?,?,?,?,?,'PENDING',?,?)",
                orgId, req.username.trim(), req.displayName.trim(), req.phone.trim(), req.role.name, now, now,
            )
            audit(c, actor, req.orgId, "user.create", "user:$uid", "username=${req.username}")
            uid
        }
        return findUserById(id)!!.toView(orgName(orgId))
    }

    fun updateUser(id: Long, req: UpdateUserRequest, actor: AuthUser): UserView {
        db.transaction { c ->
            val u = c.queryOne("SELECT * FROM users WHERE id=? AND deleted_at=0", id) { mapUser(it) }
                ?: throw AdminError("用户不存在", 404)
            requireScope(actor, u)
            // v1.12（审计 S1）：组织管理员不得改动超管账号，也不得修改自己的角色（防自提权）
            if (u.role == Role.SUPER_ADMIN && !actor.isSuper) {
                throw AdminError("无权修改超级管理员", 404, "NOT_FOUND")
            }
            if (req.role != null) {
                if (id == actor.id) throw AdminError("不能修改自己的角色", 403, "ROLE_FORBIDDEN")
                assertRoleAssignable(actor, req.role)
            }
            c.execute(
                "UPDATE users SET display_name=?, phone=?, role=?, status=?, updated_at=? WHERE id=?",
                req.displayName?.trim() ?: u.displayName,
                req.phone?.trim() ?: u.phone,
                (req.role ?: u.role).name,
                (req.status ?: u.status).name,
                now(), id,
            )
            audit(c, actor, u.orgId, "user.update", "user:$id", "status=${(req.status ?: u.status)}")
        }
        val row = findUserById(id)!!
        return row.toView(orgName(row.orgId))
    }

    fun deleteUser(id: Long, actor: AuthUser) {
        db.transaction { c ->
            val u = c.queryOne("SELECT * FROM users WHERE id=? AND deleted_at=0", id) { mapUser(it) }
                ?: throw AdminError("用户不存在", 404)
            requireScope(actor, u)
            if (u.role == Role.SUPER_ADMIN) throw AdminError("不能删除超级管理员")
            c.execute("UPDATE users SET deleted_at=?, status='DELETED', updated_at=? WHERE id=?", now(), now(), id)
            audit(c, actor, u.orgId, "user.delete", "user:$id", "username=${u.username}")
        }
    }

    private fun requireScope(actor: AuthUser, target: UserRow) {
        if (actor.role == Role.SUPER_ADMIN) return
        if (target.orgId != actor.orgId) throw AdminError("无权操作其他组织的账号", 404)
    }

    /**
     * v1.12（审计 S1）：角色白名单，垂直提权的服务层最后防线。
     * 超管可授予任意角色；组织管理员只能在本组织内授予 USER / AUDITOR。
     */
    private fun assertRoleAssignable(actor: AuthUser, role: Role) {
        val allowed = when (actor.role) {
            Role.SUPER_ADMIN -> setOf(Role.SUPER_ADMIN, Role.ORG_ADMIN, Role.AUDITOR, Role.USER)
            Role.ORG_ADMIN -> setOf(Role.USER, Role.AUDITOR)
            else -> emptySet()
        }
        if (role !in allowed) throw AdminError("无权分配该角色", 403, "ROLE_FORBIDDEN")
    }

    fun importUsers(orgId: Long?, rows: List<ImportRowRequest>, actor: AuthUser): ImportUsersResponse {
        val targetOrg = if (actor.role == Role.SUPER_ADMIN) orgId else actor.orgId
        if (targetOrg == null) throw AdminError("请选择组织")
        val failed = ArrayList<ImportFailure>()
        val created = ArrayList<UserRow>()
        rows.forEachIndexed { index, row ->
            val username = row.username.trim()
            if (username.isBlank()) {
                failed.add(ImportFailure(index + 1, username, "用户名为空")); return@forEachIndexed
            }
            runCatching { createUser(CreateUserRequest(targetOrg, username, row.displayName, row.phone), actor) }
                .onSuccess { created.add(findUserById(it.id)!!) }
                .onFailure { failed.add(ImportFailure(index + 1, username, it.message ?: "创建失败")) }
        }
        val codes = created.map { u ->
            val issued = issueCode(u.id, CodePurpose.ACTIVATE, actor.id)
            IssuedCode(issued.id, issued.code, issued.purpose, issued.expiresAt, u.id, u.username, u.displayName)
        }
        return ImportUsersResponse(created = created.size, failed = failed, codes = codes)
    }

    fun unbindDevice(id: Long, actor: AuthUser) {
        db.transaction { c ->
            val u = c.queryOne("SELECT * FROM users WHERE id=? AND deleted_at=0", id) { mapUser(it) }
                ?: throw AdminError("用户不存在", 404)
            requireScope(actor, u)
            c.execute("UPDATE users SET device_id='', device_name='', status=?, updated_at=? WHERE id=?", UserStatus.PENDING.name, now(), id)
            c.execute("UPDATE client_tokens SET revoked_at=? WHERE user_id=? AND revoked_at=0", now(), id)
            audit(c, actor, u.orgId, "user.unbind", "user:$id", u.username)
        }
    }

    // ---------------- 激活码 ----------------

    /** 生成新激活码：同一用户同一用途的未使用码全部作废，避免一码多用。 */
    fun issueCode(userId: Long, purpose: CodePurpose, actorId: Long, ttlDays: Long = 7): CodeRow {
        val now = now()
        return db.transaction { c ->
            val u = c.queryOne("SELECT * FROM users WHERE id=? AND deleted_at=0", userId) { mapUser(it) }
                ?: throw AdminError("用户不存在", 404)
            c.execute(
                "UPDATE activation_codes SET revoked_at=? WHERE user_id=? AND purpose=? AND used_at=0 AND revoked_at=0",
                now, userId, purpose.name,
            )
            var code: String
            do {
                code = Tokens.activationCode()
            } while (c.queryOne("SELECT id FROM activation_codes WHERE code=?", code) { it.getLong(1) } != null)
            val expiresAt = now + ttlDays * 24 * 3600 * 1000
            val id = c.insert(
                "INSERT INTO activation_codes(user_id, org_id, code, purpose, expires_at, created_by, created_at) " +
                    "VALUES(?,?,?,?,?,?,?)",
                userId, u.orgId, code, purpose.name, expiresAt, actorId, now,
            )
            CodeRow(id, userId, u.orgId, code, purpose, expiresAt, 0, 0, now, u.username, u.displayName)
        }
    }

    fun listCodes(actor: AuthUser, orgId: Long?, status: String?, page: Int, size: Int): Pair<Int, List<IssuedCode>> =
        db.withConn { c ->
            val where = StringBuilder(" WHERE 1=1")
            val args = ArrayList<Any?>()
            val scopeOrg = if (actor.role == Role.SUPER_ADMIN) orgId else actor.orgId
            if (scopeOrg != null) { where.append(" AND ac.org_id=?"); args.add(scopeOrg) }
            when (status) {
                "UNUSED" -> { where.append(" AND ac.used_at=0 AND ac.revoked_at=0 AND ac.expires_at>?"); args.add(now()) }
                "USED" -> { where.append(" AND ac.used_at>0") }
                "REVOKED" -> { where.append(" AND ac.revoked_at>0") }
                "EXPIRED" -> { where.append(" AND ac.used_at=0 AND ac.revoked_at=0 AND ac.expires_at<=?"); args.add(now()) }
            }
            val total = c.queryOne(
                "SELECT COUNT(*) FROM activation_codes ac$where", *args.toTypedArray(),
            ) { it.getInt(1) } ?: 0
            args.add(size.coerceIn(1, 200)); args.add((page - 1).coerceAtLeast(0) * size)
            val items = c.query(
                "SELECT ac.*, u.username AS username, u.display_name AS display_name FROM activation_codes ac " +
                    "LEFT JOIN users u ON u.id = ac.user_id$where ORDER BY ac.id DESC LIMIT ? OFFSET ?",
                *args.toTypedArray(),
            ) { mapCode(it).toIssued() }
            total to items
        }

    fun revokeCode(id: Long, actor: AuthUser) {
        db.transaction { c ->
            val row = c.queryOne(
                "SELECT ac.*, u.username AS username, u.display_name AS display_name FROM activation_codes ac " +
                    "LEFT JOIN users u ON u.id = ac.user_id WHERE ac.id=?",
                id,
            ) { mapCode(it) } ?: throw AdminError("激活码不存在", 404)
            if (row.usedAt > 0) throw AdminError("已使用的激活码不能作废")
            c.execute("UPDATE activation_codes SET revoked_at=? WHERE id=?", now(), id)
            audit(c, actor, row.orgId, "code.revoke", "code:${row.code}")
        }
    }

    fun findCode(code: String): CodeRow? = db.withConn { c ->
        c.queryOne(
            "SELECT ac.*, u.username AS username, u.display_name AS display_name FROM activation_codes ac " +
                "LEFT JOIN users u ON u.id = ac.user_id WHERE ac.code=?",
            code.trim().uppercase(),
        ) { mapCode(it) }
    }

    fun markCodeUsed(codeId: Long) {
        db.withConn { c -> c.execute("UPDATE activation_codes SET used_at=? WHERE id=?", now(), codeId) }
    }

    // ---------------- 会话 ----------------

    fun createSession(userId: Long, ip: String, userAgent: String): Pair<String, String> {
        val token = Tokens.randomToken(32)
        val csrf = Tokens.randomToken(24)
        val now = now()
        db.withConn { c ->
            c.execute(
                "INSERT INTO admin_sessions(user_id, token_hash, csrf_hash, created_at, last_seen_at, expires_at, ip, user_agent) " +
                    "VALUES(?,?,?,?,?,?,?,?)",
                userId, Tokens.sha256Hex(token), Tokens.sha256Hex(csrf), now, now, now + SESSION_TTL_MS, ip, userAgent,
            )
        }
        return token to csrf
    }

    fun findSession(token: String): SessionRow? {
        val hash = Tokens.sha256Hex(token)
        val now = now()
        return db.transaction { c ->
            val row = c.queryOne(
                "SELECT id, user_id, token_hash, csrf_hash, created_at, last_seen_at, expires_at, revoked_at " +
                    "FROM admin_sessions WHERE token_hash=?",
                hash,
            ) { rs ->
                SessionRow(
                    id = rs.lng("id"),
                    userId = rs.lng("user_id"),
                    lastSeenAt = rs.lng("last_seen_at"),
                    expiresAt = rs.lng("expires_at"),
                    csrfHash = rs.str("csrf_hash"),
                    createdAt = rs.lng("created_at"),
                ) to rs.lng("revoked_at")
            } ?: return@transaction null
            val (session, revokedAt) = row
            if (revokedAt > 0) return@transaction null
            if (now > session.expiresAt) return@transaction null
            // v1.12（审计 L2）：滑动续期必须有绝对上限，否则一次会话泄露即可被无限续命
            if (session.createdAt > 0 && now - session.createdAt > SESSION_ABSOLUTE_MS) {
                c.execute("UPDATE admin_sessions SET revoked_at=? WHERE id=?", now, session.id)
                return@transaction null
            }
            if (now - session.lastSeenAt > SESSION_IDLE_MS) {
                c.execute("UPDATE admin_sessions SET revoked_at=? WHERE id=?", now, session.id)
                return@transaction null
            }
            c.execute(
                "UPDATE admin_sessions SET last_seen_at=?, expires_at=? WHERE id=?",
                now, now + SESSION_TTL_MS, session.id,
            )
            session
        }
    }

    /** 重新签发 CSRF（页面刷新后由 /auth/me 调用），返回明文。 */
    fun rotateCsrf(token: String): String {
        val csrf = Tokens.randomToken(24)
        db.withConn { c ->
            c.execute(
                "UPDATE admin_sessions SET csrf_hash=? WHERE token_hash=?",
                Tokens.sha256Hex(csrf), Tokens.sha256Hex(token),
            )
        }
        return csrf
    }

    fun revokeSession(token: String) {
        db.withConn { c ->
            c.execute("UPDATE admin_sessions SET revoked_at=? WHERE token_hash=?", now(), Tokens.sha256Hex(token))
        }
    }

    fun revokeUserSessions(userId: Long) {
        db.withConn { c -> c.execute("UPDATE admin_sessions SET revoked_at=? WHERE user_id=? AND revoked_at=0", now(), userId) }
    }

    // ---------------- 手机端令牌 ----------------

    fun createClientToken(userId: Long, deviceId: String): String {
        val token = Tokens.randomToken(32)
        val now = now()
        db.withConn { c ->
            c.execute(
                "INSERT INTO client_tokens(user_id, token_hash, device_id, created_at, last_seen_at, expires_at) " +
                    "VALUES(?,?,?,?,?,?)",
                userId, Tokens.sha256Hex(token), deviceId, now, now, now + CLIENT_TOKEN_TTL_MS,
            )
        }
        return token
    }

    fun findClientToken(token: String): UserRow? {
        val hash = Tokens.sha256Hex(token)
        return db.transaction { c ->
            val row = c.queryOne(
                "SELECT user_id, expires_at, revoked_at FROM client_tokens WHERE token_hash=?",
                hash,
            ) { Triple(it.getLong("user_id"), it.getLong("expires_at"), it.getLong("revoked_at")) }
                ?: return@transaction null
            val (userId, expiresAt, revokedAt) = row
            if (revokedAt > 0 || now() > expiresAt) return@transaction null
            c.execute("UPDATE client_tokens SET last_seen_at=? WHERE token_hash=?", now(), hash)
            val user = c.queryOne("SELECT * FROM users WHERE id=? AND deleted_at=0", userId) { mapUser(it) }
                ?: return@transaction null
            // v1.12（审计 M4）：账号被停用/锁定/删除后，旧令牌立即失效，不必等 30 天过期
            if (user.status == UserStatus.SUSPENDED || user.status == UserStatus.DELETED || user.status == UserStatus.LOCKED) {
                c.execute("UPDATE client_tokens SET revoked_at=? WHERE token_hash=?", now(), hash)
                return@transaction null
            }
            user
        }
    }

    fun revokeClientToken(token: String) {
        db.withConn { c ->
            c.execute("UPDATE client_tokens SET revoked_at=? WHERE token_hash=?", now(), Tokens.sha256Hex(token))
        }
    }

    fun revokeClientTokens(userId: Long) {
        db.withConn { c -> c.execute("UPDATE client_tokens SET revoked_at=? WHERE user_id=? AND revoked_at=0", now(), userId) }
    }

    // ---------------- 手机端激活 / 登录 ----------------

    fun activate(code: String, password: String, deviceId: String, deviceName: String): UserRow {
        if (password.length < 8) throw AdminError("密码至少 8 位")
        val row = findCode(code) ?: throw AdminError("激活码无效", 400, "INVALID_CODE")
        val now = now()
        when (row.status(now)) {
            CodeStatus.USED -> throw AdminError("激活码已被使用", 400, "CODE_USED")
            CodeStatus.EXPIRED -> throw AdminError("激活码已过期，请联系管理员重新生成", 400, "CODE_EXPIRED")
            CodeStatus.REVOKED -> throw AdminError("激活码已被作废", 400, "CODE_REVOKED")
            CodeStatus.UNUSED -> Unit
        }
        val user = findUserById(row.userId) ?: throw AdminError("账号不存在", 404, "USER_NOT_FOUND")
        db.transaction { c ->
            if (row.purpose == CodePurpose.RESET_PASSWORD) {
                c.execute(
                    "UPDATE users SET password_hash=?, device_id='', device_name='', status='ACTIVE', fail_count=0, " +
                        "locked_until=0, must_change_password=0, updated_at=? WHERE id=?",
                    Password.hash(password), now, user.id,
                )
                c.execute("UPDATE client_tokens SET revoked_at=? WHERE user_id=? AND revoked_at=0", now, user.id)
            } else {
                c.execute(
                    "UPDATE users SET password_hash=?, device_id=?, device_name=?, status='ACTIVE', activated_at=?, " +
                        "fail_count=0, locked_until=0, must_change_password=0, updated_at=? WHERE id=?",
                    Password.hash(password), deviceId.trim(), deviceName.trim(), now, now, user.id,
                )
            }
            c.execute("UPDATE activation_codes SET used_at=? WHERE id=?", now, row.id)
            audit(c, null, user.orgId, "client.activate", "user:${user.id}", row.code)
        }
        return findUserById(user.id)!!
    }

    fun clientLogin(username: String, password: String, deviceId: String): UserRow {
        val user = findUserByUsername(username) ?: throw AdminError("用户名或密码错误", 401, "BAD_CREDENTIALS")
        val now = now()
        if (user.status == UserStatus.SUSPENDED) throw AdminError("账号已停用", 403, "USER_SUSPENDED")
        if (user.status == UserStatus.DELETED) throw AdminError("账号不存在", 404, "USER_NOT_FOUND")
        if (user.lockedUntil > now) {
            val mins = ((user.lockedUntil - now) / 60000 + 1)
            throw AdminError("登录失败次数过多，请 $mins 分钟后重试", 423, "USER_LOCKED")
        }
        if (user.status == UserStatus.PENDING) throw AdminError("账号尚未激活，请使用激活码激活", 403, "USER_PENDING")
        if (!Password.verify(password, user.passwordHash)) {
            db.transaction { c ->
                val fails = user.failCount + 1
                val lock = if (fails >= 5) now + 15 * 60 * 1000 else 0
                c.execute(
                    "UPDATE users SET fail_count=?, locked_until=?${if (lock > 0) ", status='LOCKED'" else ""} WHERE id=?",
                    fails, lock, user.id,
                )
            }
            throw AdminError("用户名或密码错误", 401, "BAD_CREDENTIALS")
        }
        // v1.12（审计 M4）：设备绑定不能因为「客户端不传 deviceId」被绕过——
        // 已绑定账号必须携带匹配的 deviceId，缺省直接拒绝。
        val bound = user.deviceId.trim()
        val provided = deviceId.trim()
        if (bound.isNotEmpty() && provided != bound) {
            throw AdminError("此账号已绑定其他设备，请联系管理员解绑", 409, "DEVICE_MISMATCH")
        }
        db.transaction { c ->
            val newDevice = if (bound.isEmpty() && deviceId.isNotBlank()) deviceId.trim() else bound
            c.execute(
                "UPDATE users SET fail_count=0, locked_until=0, status='ACTIVE', device_id=?, last_login_at=?, updated_at=? WHERE id=?",
                newDevice, now, now, user.id,
            )
        }
        return findUserById(user.id)!!
    }

    fun changeClientPassword(user: UserRow, oldPassword: String, newPassword: String) {
        if (newPassword.length < 8) throw AdminError("新密码至少 8 位")
        if (!Password.verify(oldPassword, user.passwordHash)) throw AdminError("原密码不正确", 400, "BAD_OLD_PASSWORD")
        db.transaction { c ->
            c.execute("UPDATE users SET password_hash=?, updated_at=? WHERE id=?", Password.hash(newPassword), now(), user.id)
            // v1.12（审计 M4）：改密后吊销全部手机端令牌，攻击者不能再靠旧令牌继续使用
            c.execute("UPDATE client_tokens SET revoked_at=? WHERE user_id=? AND revoked_at=0", now(), user.id)
        }
    }

    fun changeAdminPassword(user: UserRow, oldPassword: String, newPassword: String) {
        if (newPassword.length < 8) throw AdminError("新密码至少 8 位")
        if (!Password.verify(oldPassword, user.passwordHash)) throw AdminError("原密码不正确", 400, "BAD_OLD_PASSWORD")
        db.transaction { c ->
            c.execute(
                "UPDATE users SET password_hash=?, must_change_password=0, updated_at=? WHERE id=?",
                Password.hash(newPassword), now(), user.id,
            )
            c.execute("UPDATE admin_sessions SET revoked_at=? WHERE user_id=? AND revoked_at=0", now(), user.id)
        }
    }

    fun recordAdminLogin(user: UserRow) {
        db.withConn { c ->
            c.execute("UPDATE users SET fail_count=0, locked_until=0, last_login_at=?, status='ACTIVE', updated_at=? WHERE id=?", now(), now(), user.id)
        }
    }

    fun recordAdminFailure(user: UserRow) {
        db.withConn { c ->
            val fails = user.failCount + 1
            val lock = if (fails >= 5) now() + 15 * 60 * 1000 else 0
            c.execute("UPDATE users SET fail_count=?, locked_until=? WHERE id=?", fails, lock, user.id)
        }
    }

    // ---------------- 审计 ----------------

    fun audit(
        actor: AuthUser?,
        orgId: Long?,
        action: String,
        target: String = "",
        detail: String = "",
        ip: String = "",
    ) = db.withConn { c -> audit(c, actor, orgId, action, target, detail, ip) }

    private fun audit(
        c: Connection,
        actor: AuthUser?,
        orgId: Long?,
        action: String,
        target: String = "",
        detail: String = "",
        ip: String = "",
    ) {
        val name = actor?.let { it.displayName.ifBlank { it.username } } ?: "system"
        c.insert(
            "INSERT INTO admin_audit(ts, actor_id, actor_name, org_id, action, target, detail, ip) VALUES(?,?,?,?,?,?,?,?)",
            now(), actor?.id, name, orgId, action, target, detail.take(500), ip,
        )
    }

    fun listAudit(
        actor: AuthUser,
        action: String?,
        orgId: Long?,
        from: Long?,
        to: Long?,
        page: Int,
        size: Int,
    ): Pair<Int, List<AuditRow>> = db.withConn { c ->
        val where = StringBuilder(" WHERE 1=1")
        val args = ArrayList<Any?>()
        val scopeOrg = if (actor.role == Role.SUPER_ADMIN) orgId else actor.orgId
        if (scopeOrg != null) { where.append(" AND org_id=?"); args.add(scopeOrg) }
        if (!action.isNullOrBlank()) { where.append(" AND action LIKE ?"); args.add("%${action.trim()}%") }
        if (from != null) { where.append(" AND ts>=?"); args.add(from) }
        if (to != null) { where.append(" AND ts<=?"); args.add(to) }
        val total = c.queryOne("SELECT COUNT(*) FROM admin_audit$where", *args.toTypedArray()) { it.getInt(1) } ?: 0
        args.add(size.coerceIn(1, 200)); args.add((page - 1).coerceAtLeast(0) * size)
        val items = c.query(
            "SELECT * FROM admin_audit$where ORDER BY id DESC LIMIT ? OFFSET ?",
            *args.toTypedArray(),
        ) { rs ->
            AuditRow(
                id = rs.lng("id"), ts = rs.lng("ts"), actorId = rs.lngOrNull("actor_id"),
                actorName = rs.str("actor_name"), orgId = rs.lngOrNull("org_id"),
                action = rs.str("action"), target = rs.str("target"), detail = rs.str("detail"), ip = rs.str("ip"),
            )
        }
        total to items
    }

    // ---------------- 仪表盘计数 ----------------

    /** 一次连接里取齐仪表盘 KPI 需要的标量计数。orgScope=null 表示全平台。 */
    fun kpiCounts(orgScope: Long?): Map<String, Int> = db.withConn { c ->
        val userScope = if (orgScope != null) " AND org_id=$orgScope" else ""
        val codeScope = if (orgScope != null) " AND org_id=$orgScope" else ""
        val n: (String, List<Any?>) -> Int = { sql, args ->
            val ps = c.prepareStatement(sql)
            try {
                args.forEachIndexed { i, v -> ps.setObject(i + 1, v) }
                ps.executeQuery().use { if (it.next()) it.getInt(1) else 0 }
            } finally {
                ps.close()
            }
        }
        val m = LinkedHashMap<String, Int>()
        m["orgs"] = n("SELECT COUNT(*) FROM orgs WHERE status!='SUSPENDED'", emptyList())
        m["users"] = n("SELECT COUNT(*) FROM users WHERE deleted_at=0$userScope", emptyList())
        m["activeUsers"] = n("SELECT COUNT(*) FROM users WHERE deleted_at=0 AND status='ACTIVE'$userScope", emptyList())
        m["pendingUsers"] = n("SELECT COUNT(*) FROM users WHERE deleted_at=0 AND status='PENDING'$userScope", emptyList())
        m["suspendedUsers"] = n("SELECT COUNT(*) FROM users WHERE deleted_at=0 AND status='SUSPENDED'$userScope", emptyList())
        m["lockedUsers"] = n("SELECT COUNT(*) FROM users WHERE deleted_at=0 AND status='LOCKED'$userScope", emptyList())
        m["auditorUsers"] = n("SELECT COUNT(*) FROM users WHERE deleted_at=0 AND role='AUDITOR'$userScope", emptyList())
        m["codesUnused"] = n(
            "SELECT COUNT(*) FROM activation_codes WHERE used_at=0 AND revoked_at=0 AND expires_at>?$codeScope",
            listOf(now()),
        )
        m
    }

    fun userStatusCounts(orgScope: Long?): List<CountItem> {
        val rows = kpiCounts(orgScope)
        return listOf(
            CountItem("ACTIVE", rows["activeUsers"] ?: 0),
            CountItem("PENDING", rows["pendingUsers"] ?: 0),
            CountItem("SUSPENDED", rows["suspendedUsers"] ?: 0),
            CountItem("LOCKED", rows["lockedUsers"] ?: 0),
        )
    }

    fun auditCountSince(since: Long, orgId: Long?): Int = db.withConn { c ->
        if (orgId == null) {
            c.queryOne("SELECT COUNT(*) FROM admin_audit WHERE ts>=?", since) { it.getInt(1) } ?: 0
        } else {
            c.queryOne("SELECT COUNT(*) FROM admin_audit WHERE ts>=? AND org_id=?", since, orgId) { it.getInt(1) } ?: 0
        }
    }

    // ---------------- 管理站扩展查询（M2：激活码视图 / 审计 / 指标时序） ----------------

    fun codeViews(actor: AuthUser, orgId: Long?, status: String?, page: Int, size: Int): Pair<Int, List<ActivationCodeView>> =
        db.withConn { c ->
            val where = StringBuilder(" WHERE 1=1")
            val args = ArrayList<Any?>()
            val scopeOrg = if (actor.role == Role.SUPER_ADMIN) orgId else actor.orgId
            if (scopeOrg != null) { where.append(" AND ac.org_id=?"); args.add(scopeOrg) }
            when (status) {
                "UNUSED" -> { where.append(" AND ac.used_at=0 AND ac.revoked_at=0 AND ac.expires_at>?"); args.add(now()) }
                "USED" -> where.append(" AND ac.used_at>0")
                "REVOKED" -> where.append(" AND ac.revoked_at>0")
                "EXPIRED" -> { where.append(" AND ac.used_at=0 AND ac.revoked_at=0 AND ac.expires_at<=?"); args.add(now()) }
            }
            val total = c.queryOne("SELECT COUNT(*) FROM activation_codes ac$where", *args.toTypedArray()) { it.getInt(1) } ?: 0
            args.add(size.coerceIn(1, 200)); args.add((page - 1).coerceAtLeast(0) * size)
            val items = c.query(
                "SELECT ac.*, u.username AS u_name, u.display_name AS u_display, o.name AS org_name " +
                    "FROM activation_codes ac LEFT JOIN users u ON u.id=ac.user_id LEFT JOIN orgs o ON o.id=ac.org_id" +
                    "$where ORDER BY ac.id DESC LIMIT ? OFFSET ?",
                *args.toTypedArray(),
            ) { mapCodeView(it) }
            total to items
        }

    fun codesForUser(actor: AuthUser, userId: Long): List<ActivationCodeView> = db.withConn { c ->
        val scope = if (actor.role == Role.SUPER_ADMIN) "" else " AND ac.org_id=${actor.orgId ?: -1}"
        c.query(
            "SELECT ac.*, u.username AS u_name, u.display_name AS u_display, o.name AS org_name " +
                "FROM activation_codes ac LEFT JOIN users u ON u.id=ac.user_id LEFT JOIN orgs o ON o.id=ac.org_id " +
                "WHERE ac.user_id=?$scope ORDER BY ac.id DESC",
            userId,
        ) { mapCodeView(it) }
    }

    fun auditPage(
        actor: AuthUser,
        action: String?,
        orgId: Long?,
        page: Int,
        size: Int,
        level: String? = null,
        q: String? = null,
    ): Pair<Int, List<AuditEventView>> =
        db.withConn { c ->
            val where = StringBuilder(" WHERE 1=1")
            val args = ArrayList<Any?>()
            val scopeOrg = if (actor.role == Role.SUPER_ADMIN) orgId else actor.orgId
            if (scopeOrg != null) { where.append(" AND a.org_id=?"); args.add(scopeOrg) }
            if (!action.isNullOrBlank()) { where.append(" AND a.action LIKE ?"); args.add("%${action.trim()}%") }
            if (!q.isNullOrBlank()) {
                where.append(" AND (a.actor_name LIKE ? OR a.action LIKE ? OR a.detail LIKE ?)")
                val like = "%${q.trim()}%"
                args.add(like); args.add(like); args.add(like)
            }
            val total = c.queryOne("SELECT COUNT(*) FROM admin_audit a$where", *args.toTypedArray()) { it.getInt(1) } ?: 0
            args.add(size.coerceIn(1, 200)); args.add((page - 1).coerceAtLeast(0) * size)
            val items = c.query(
                "SELECT a.*, o.name AS org_name FROM admin_audit a LEFT JOIN orgs o ON o.id=a.org_id" +
                    "$where ORDER BY a.id DESC LIMIT ? OFFSET ?",
                *args.toTypedArray(),
            ) { rs ->
                val act = rs.getString("action") ?: ""
                AuditEventView(
                    id = rs.getLong("id"),
                    ts = rs.getLong("ts"),
                    actorName = rs.getString("actor_name") ?: "",
                    orgName = rs.getString("org_name"),
                    action = act,
                    detail = rs.getString("detail") ?: "",
                    level = auditLevel(act),
                )
            }
            if (level.isNullOrBlank()) {
                total to items
            } else {
                // level 由动作关键字推导，SQL 里无法直接过滤：取近 2000 条内存筛选后重新分页
                val (_, pool) = auditPage(actor, action, orgId, 1, 2000, null, q)
                val filtered = pool.filter { it.level.equals(level, ignoreCase = true) }
                val from = ((page - 1).coerceAtLeast(0) * size).coerceAtMost(filtered.size)
                val to = (from + size).coerceAtMost(filtered.size)
                filtered.size to filtered.subList(from, to)
            }
        }

    // ---------------- 运行时设置（M2） ----------------

    /** 读设置项，不存在返回 null。 */
    fun setting(key: String): String? = db.withConn { c ->
        c.queryOne("SELECT value FROM settings WHERE key=?", key) { it.getString(1) }
    }

    fun putSetting(key: String, value: String) {
        db.withConn { c ->
            c.execute(
                "INSERT INTO settings(key, value, updated_at) VALUES(?,?,?) " +
                    "ON CONFLICT(key) DO UPDATE SET value=excluded.value, updated_at=excluded.updated_at",
                key, value, now(),
            )
        }
    }

    /** 房间 TTL（天）：管理站可改，未设置时回落到环境变量/默认 3 天。 */
    fun roomTtlDays(): Long =
        setting("room_ttl_days")?.toLongOrNull()?.coerceIn(1, 365)
            ?: (System.getenv("WEILIE_ROOM_TTL_DAYS")?.toLongOrNull()?.coerceIn(1, 365) ?: 3L)

    fun setRoomTtlDays(days: Long) = putSetting("room_ttl_days", days.coerceIn(1, 365).toString())

    /** 当前超管用户名（设置页展示用）。 */
    fun superAdminUsername(): String = db.withConn { c ->
        c.queryOne("SELECT username FROM users WHERE role='SUPER_ADMIN' AND deleted_at=0 ORDER BY id LIMIT 1") {
            it.getString(1)
        }
    } ?: "admin"

    fun insertMetricSnapshot(ts: Long, rooms: Int, online: Int, samples: Int, photos: Int) {
        db.withConn { c ->
            c.execute(
                "INSERT INTO metrics_snapshots(ts, rooms, online_members, ws_connections, samples, photos) VALUES(?,?,?,?,?,?)",
                ts, rooms, online, online, samples, photos,
            )
            // 只保留最近 40 天的快照，避免无限增长
            c.execute("DELETE FROM metrics_snapshots WHERE ts<?", ts - 40L * 24 * 3600 * 1000)
        }
    }

    fun metricSeries(metric: String, fromTs: Long, bucketMs: Long): List<TimePoint> = db.withConn { c ->
        val rows = c.query(
            "SELECT ts, samples, online_members, ws_connections FROM metrics_snapshots WHERE ts>=? ORDER BY ts ASC",
            fromTs,
        ) { rs -> longArrayOf(rs.getLong(1), rs.getLong(2).toLong(), rs.getLong(3).toLong(), rs.getLong(4).toLong()) }
        if (rows.isEmpty()) return@withConn emptyList()
        val buckets = LinkedHashMap<Long, Double>()
        var prevSamples = rows.first()[1]
        for ((i, r) in rows.withIndex()) {
            val bucket = r[0] / bucketMs * bucketMs
            when (metric) {
                "samples" -> {
                    val delta = if (i == 0) 0.0 else (r[1] - prevSamples).coerceAtLeast(0).toDouble()
                    buckets[bucket] = (buckets[bucket] ?: 0.0) + delta
                    prevSamples = r[1]
                }
                "ws" -> buckets[bucket] = maxOf(buckets[bucket] ?: 0.0, r[3].toDouble())
                else -> buckets[bucket] = maxOf(buckets[bucket] ?: 0.0, r[2].toDouble())
            }
        }
        buckets.entries.sortedBy { it.key }.map { TimePoint(it.key, it.value) }
    }

    fun heatmapCells(days: Int, orgId: Long?): List<HeatCell> = db.withConn { c ->
        val since = now() - days.toLong() * 24 * 3600 * 1000
        val tsList = if (orgId == null) {
            c.query("SELECT ts FROM admin_audit WHERE ts>=?", since) { it.getLong(1) }
        } else {
            c.query("SELECT ts FROM admin_audit WHERE ts>=? AND org_id=?", since, orgId) { it.getLong(1) }
        }
        val counts = HashMap<Int, Int>()
        for (ts in tsList) {
            val dt = java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(ts), java.time.ZoneId.systemDefault())
            val key = dt.dayOfWeek.value % 7 * 24 + dt.hour
            counts[key] = (counts[key] ?: 0) + 1
        }
        counts.entries.sortedBy { it.key }.map { HeatCell(weekday = it.key / 24, hour = it.key % 24, value = it.value) }
    }

    private fun mapCodeView(rs: java.sql.ResultSet): ActivationCodeView {
        val usedAt = rs.getLong("used_at")
        val revokedAt = rs.getLong("revoked_at")
        val expiresAt = rs.getLong("expires_at")
        val status = when {
            revokedAt > 0 -> CodeStatus.REVOKED
            usedAt > 0 -> CodeStatus.USED
            now() > expiresAt -> CodeStatus.EXPIRED
            else -> CodeStatus.UNUSED
        }
        return ActivationCodeView(
            id = rs.getLong("id"),
            code = rs.getString("code"),
            userId = rs.getLong("user_id"),
            username = rs.getString("u_name") ?: "",
            displayName = rs.getString("u_display") ?: "",
            orgId = rs.getObject("org_id")?.let { (it as Number).toLong() },
            orgName = rs.getString("org_name") ?: "",
            purpose = runCatching { CodePurpose.valueOf(rs.getString("purpose")) }.getOrDefault(CodePurpose.ACTIVATE),
            status = status,
            expiresAt = expiresAt,
            usedAt = if (usedAt > 0) usedAt else null,
            createdAt = rs.getLong("created_at"),
        )
    }

    private fun auditLevel(action: String): String = when {
        action.contains("delete") || action.contains("reject") || action.contains("revoke") ||
            action.contains("suspend") || action.contains("reset-password") -> "warning"
        action.contains("login") || action.contains("activate") || action.contains("create") -> "success"
        action.contains("fail") || action.contains("error") -> "danger"
        else -> "info"
    }

    companion object {
        private const val SESSION_TTL_MS = 8L * 3600 * 1000
        private const val SESSION_IDLE_MS = 30L * 60 * 1000
        private const val SESSION_ABSOLUTE_MS = 12L * 3600 * 1000
        private const val CLIENT_TOKEN_TTL_MS = 30L * 24 * 3600 * 1000

        fun now(): Long = System.currentTimeMillis()
    }
}


/** 可预期的业务错误（路由层转成对应 HTTP 状态与 JSON）。 */
class AdminError(
    override val message: String,
    val status: Int = 400,
    val code: String? = null,
) : RuntimeException(message)
