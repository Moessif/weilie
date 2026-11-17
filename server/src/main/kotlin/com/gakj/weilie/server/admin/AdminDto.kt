package com.gakj.weilie.server.admin

import kotlinx.serialization.Serializable

// ---------- 枚举 ----------

@Serializable
enum class Role { SUPER_ADMIN, ORG_ADMIN, USER, AUDITOR }

@Serializable
enum class UserStatus { PENDING, ACTIVE, SUSPENDED, LOCKED, DELETED }

@Serializable
enum class OrgStatus { PENDING, ACTIVE, SUSPENDED }

@Serializable
enum class CodePurpose { ACTIVATE, RESET_PASSWORD }

@Serializable
enum class CodeStatus { UNUSED, USED, EXPIRED, REVOKED }

// ---------- 通用 ----------

@Serializable
data class OkResponse(val ok: Boolean = true)

@Serializable
data class ErrorResponse(val ok: Boolean = false, val error: String, val code: String? = null)

// ---------- 账号 ----------

@Serializable
data class UserView(
    val id: Long,
    val username: String,
    val displayName: String = "",
    val phone: String = "",
    val role: Role = Role.USER,
    val status: UserStatus = UserStatus.PENDING,
    val orgId: Long? = null,
    val orgName: String? = null,
    val deviceId: String = "",
    val deviceName: String = "",
    val mustChangePassword: Boolean = false,
    val activatedAt: Long = 0,
    val lastLoginAt: Long = 0,
    val createdAt: Long = 0,
)

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class LoginResponse(
    val ok: Boolean = true,
    val user: UserView,
    val csrfToken: String,
)

@Serializable
data class MeResponse(val ok: Boolean = true, val user: UserView, val csrfToken: String)

@Serializable
data class ChangePasswordRequest(val oldPassword: String = "", val newPassword: String)

// ---------- 组织 ----------

@Serializable
data class OrgView(
    val id: Long,
    val name: String,
    val code: String,
    val contactName: String = "",
    val contactPhone: String = "",
    val status: OrgStatus = OrgStatus.ACTIVE,
    val quotaUsers: Int = 50,
    val quotaRooms: Int = 5,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val userCount: Int = 0,
    val roomCount: Int = 0,
)

@Serializable
data class OrgListResponse(
    val ok: Boolean = true,
    val total: Int,
    val items: List<OrgView>,
    val page: Int = 1,
    val size: Int = 20,
)

@Serializable
data class CreateOrgRequest(
    val name: String,
    val code: String = "",
    val contactName: String = "",
    val contactPhone: String = "",
    val quotaUsers: Int = 50,
    val quotaRooms: Int = 5,
    val adminUsername: String = "",
    val adminDisplayName: String = "",
    val adminPassword: String = "",
)

@Serializable
data class CreateOrgResponse(
    val ok: Boolean = true,
    val org: OrgView,
    val adminUser: UserView? = null,
    val initialPassword: String? = null,
)

@Serializable
data class UpdateOrgRequest(
    val name: String? = null,
    val contactName: String? = null,
    val contactPhone: String? = null,
    val status: OrgStatus? = null,
    val quotaUsers: Int? = null,
    val quotaRooms: Int? = null,
)

@Serializable
data class OrgDetailResponse(
    val ok: Boolean = true,
    val org: OrgView,
    val stats: OrgStats = OrgStats(),
)

@Serializable
data class OrgStats(
    val users: Int = 0,
    val activeUsers: Int = 0,
    val pendingUsers: Int = 0,
    val rooms: Int = 0,
    val samples: Long = 0,
)

@Serializable
data class CreateOrgApplicationRequest(
    val orgName: String,
    val contactName: String = "",
    val contactPhone: String = "",
    val email: String = "",
    val reason: String = "",
)

@Serializable
data class OrgApplicationView(
    val id: Long,
    val orgName: String,
    val contactName: String = "",
    val contactPhone: String = "",
    val email: String = "",
    val reason: String = "",
    val status: String = "PENDING",
    val reviewNote: String = "",
    val createdAt: Long = 0,
    val reviewedAt: Long = 0,
)

@Serializable
data class OrgApplicationListResponse(
    val ok: Boolean = true,
    val total: Int,
    val items: List<OrgApplicationView>,
    val page: Int = 1,
    val size: Int = 20,
)

@Serializable
data class ApproveApplicationRequest(
    val code: String = "",
    val adminUsername: String,
    val adminPassword: String = "",
    val adminDisplayName: String = "",
)

@Serializable
data class RejectApplicationRequest(val reason: String = "")

// ---------- 用户 ----------

@Serializable
data class UserListResponse(
    val ok: Boolean = true,
    val total: Int,
    val items: List<UserView>,
    val page: Int = 1,
    val size: Int = 20,
)

@Serializable
data class CreateUserRequest(
    val orgId: Long? = null,
    val username: String,
    val displayName: String = "",
    val phone: String = "",
    val role: Role = Role.USER,
)

@Serializable
data class CreateUserResponse(val ok: Boolean = true, val user: UserView)

@Serializable
data class UpdateUserRequest(
    val displayName: String? = null,
    val phone: String? = null,
    val role: Role? = null,
    val status: UserStatus? = null,
)

@Serializable
data class ImportRowRequest(val username: String, val displayName: String = "", val phone: String = "")

@Serializable
data class ImportUsersRequest(val orgId: Long? = null, val rows: List<ImportRowRequest>)

@Serializable
data class ImportFailure(val row: Int, val username: String, val reason: String)

@Serializable
data class ImportUsersResponse(
    val ok: Boolean = true,
    val created: Int,
    val failed: List<ImportFailure> = emptyList(),
    val codes: List<IssuedCode> = emptyList(),
)

// ---------- 激活码 ----------

@Serializable
data class IssueCodeRequest(val purpose: CodePurpose = CodePurpose.ACTIVATE)

@Serializable
data class IssuedCode(
    val id: Long,
    val code: String,
    val purpose: CodePurpose = CodePurpose.ACTIVATE,
    val expiresAt: Long,
    val userId: Long,
    val username: String = "",
    val displayName: String = "",
)

@Serializable
data class CodeListResponse(
    val ok: Boolean = true,
    val total: Int,
    val items: List<ActivationCodeView>,
    val page: Int = 1,
    val size: Int = 20,
)

/** 管理站激活码视图（含组织名与状态，对齐前端 ActivationCode）。 */
@Serializable
data class ActivationCodeView(
    val id: Long,
    val code: String,
    val userId: Long,
    val username: String = "",
    val displayName: String = "",
    val orgId: Long? = null,
    val orgName: String = "",
    val purpose: CodePurpose = CodePurpose.ACTIVATE,
    val status: CodeStatus = CodeStatus.UNUSED,
    val expiresAt: Long,
    val usedAt: Long? = null,
    val createdAt: Long = 0,
)

@Serializable
data class ActivationCodesResponse(val ok: Boolean = true, val items: List<ActivationCodeView>)

@Serializable
data class AuditEventView(
    val id: Long,
    val ts: Long,
    val actorName: String = "",
    val orgName: String? = null,
    val action: String,
    val detail: String = "",
    val level: String = "info",
)

@Serializable
data class AuditListResponse(
    val ok: Boolean = true,
    val total: Int,
    val items: List<AuditEventView>,
    val page: Int = 1,
    val size: Int = 20,
)

@Serializable
data class TimePoint(val ts: Long, val value: Double)

@Serializable
data class TimeseriesResult(val ok: Boolean = true, val points: List<TimePoint>)

@Serializable
data class TopOrgItem(val orgId: Long, val orgName: String, val samples: Int)

@Serializable
data class TopOrgsResult(val ok: Boolean = true, val items: List<TopOrgItem>)

@Serializable
data class HeatCell(val weekday: Int, val hour: Int, val value: Int)

@Serializable
data class HeatmapResult(val ok: Boolean = true, val cells: List<HeatCell>)

@Serializable
data class RoomStatusCount(val key: String, val count: Int)

@Serializable
data class RoomStatusResult(val ok: Boolean = true, val items: List<RoomStatusCount>)

@Serializable
data class RecentRoom(
    val roomId: String,
    val orgId: Long = 0,
    val orgName: String = "",
    val members: Int = 0,
    val samples: Int = 0,
    val lastActiveAt: Long = 0,
    val status: String = "IDLE",
)

@Serializable
data class RecentRoomsResult(val ok: Boolean = true, val items: List<RecentRoom>)

// ---------- 仪表盘 ----------

@Serializable
data class DashboardKpi(
    val orgs: Int = 0,
    val orgsDelta: Int = 0,
    val users: Int = 0,
    val activeUsers: Int = 0,
    val pendingUsers: Int = 0,
    val onlineMembers: Int = 0,
    val onlinePeak: Int = 0,
    val rooms: Int = 0,
    val activeRooms: Int = 0,
    val todaySamples: Int = 0,
    val samplesDeltaPct: Double = 0.0,
    val photos: Int = 0,
    val audits24h: Int = 0,
)

@Serializable
data class DashboardServerInfo(
    val version: String = "",
    val uptimeSec: Long = 0,
    val roomsInMemory: Int = 0,
    val onlineMembers: Int = 0,
)

@Serializable
data class DashboardSummaryResponse(
    val ok: Boolean = true,
    val kpi: DashboardKpi,
    val server: DashboardServerInfo,
)

@Serializable
data class CountItem(val key: String, val count: Int)

@Serializable
data class CountListResponse(val ok: Boolean = true, val items: List<CountItem>)

// ---------- 手机端 ----------

@Serializable
data class ActivateRequest(
    val code: String,
    val password: String,
    val deviceId: String = "",
    val deviceName: String = "",
)

@Serializable
data class ClientLoginRequest(
    val username: String,
    val password: String,
    val deviceId: String = "",
)

@Serializable
data class ClientUserView(
    val id: Long,
    val username: String,
    val displayName: String = "",
    val orgId: Long? = null,
    val orgName: String? = null,
    val role: Role = Role.USER,
)

@Serializable
data class ClientAuthResponse(
    val ok: Boolean = true,
    val token: String,
    val user: ClientUserView,
)

@Serializable
data class ClientMeResponse(val ok: Boolean = true, val user: ClientUserView)
