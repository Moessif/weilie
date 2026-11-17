import { call, download, mockMode } from './client'
import type { QueryValue } from './client'
import { mockPhotoThumb } from './mock'
import type {
  ActivationCode,
  AdminUser,
  AppSettings,
  AuditEvent,
  CodePurpose,
  DashboardSummary,
  HeatmapResult,
  ImportResult,
  LoginResult,
  OpsStatus,
  Org,
  OrgApplication,
  OrgStats,
  PageResult,
  PhotoItem,
  PhotoKind,
  RangeKey,
  RecentRoom,
  RoomDetailResult,
  RoomExportFormat,
  RoomItem,
  RoomSamplesResult,
  RoomStatusResult,
  SettingsPatch,
  StatusCountResult,
  TimeseriesMetric,
  TimeseriesResult,
  TopOrgsResult,
  UserItem,
} from '@/types'

/* ------------------------------ 参数类型 ------------------------------ */

function toQuery<T extends object>(obj: T): Record<string, QueryValue> {
  return obj as unknown as Record<string, QueryValue>
}

export interface OrgQuery {
  q?: string
  status?: string
  page?: number
  size?: number
}

export interface CreateOrgPayload {
  name: string
  code: string
  contactName: string
  contactPhone: string
  quotaUsers: number
  quotaRooms: number
  adminUsername: string
  adminDisplayName: string
  adminPassword: string
}

export type UpdateOrgPayload = Partial<Pick<Org, 'name' | 'contactName' | 'contactPhone' | 'quotaUsers' | 'quotaRooms' | 'status'>>

export interface UserQuery {
  orgId?: number
  q?: string
  status?: string
  page?: number
  size?: number
}

export interface CreateUserPayload {
  orgId: number
  username: string
  displayName: string
  phone: string
  role?: string
}

export type UpdateUserPayload = Partial<Pick<UserItem, 'displayName' | 'phone' | 'status' | 'role'>>

export interface ImportUserRow {
  username: string
  displayName: string
  phone: string
}

export interface CodeQuery {
  orgId?: number
  status?: string
  q?: string
  page?: number
  size?: number
}

export interface CodeIssueResult {
  ok: boolean
  id: number
  code: string
  expiresAt: number
  purpose: CodePurpose
}

export interface ApplicationQuery {
  status?: string
  page?: number
  size?: number
}

export interface ApplicationApprovePayload {
  code: string
  adminUsername: string
  adminPassword: string
}

/* ------------------------------ M2 参数 ------------------------------ */

export interface RoomQuery {
  status?: string
  orgId?: number
  q?: string
  page?: number
  size?: number
}

export interface PhotoQuery {
  page?: number
  size?: number
  kind?: PhotoKind | ''
  q?: string
  onlyUploaded?: boolean
}

export interface AuditQuery {
  page?: number
  size?: number
  level?: string
  action?: string
  q?: string
  orgId?: number
}

export interface PurgeResult {
  ok: boolean
  removed: number
}

/* ------------------------------ API 定义 ------------------------------ */

/**
 * 页面只依赖这个对象；真实接口与 mock 同签名。
 * auto 模式下，真实接口不可用时自动回退 mock（见 client.ts）。
 */
export const api = {
  auth: {
    login(username: string, password: string) {
      return call<LoginResult>('/auth/login', {
        method: 'POST',
        body: { username, password },
        silent: true,
      })
    },
    me() {
      return call<LoginResult>('/auth/me', { silent: true })
    },
    logout() {
      return call<{ ok: boolean }>('/auth/logout', { method: 'POST' })
    },
    changePassword(oldPassword: string, newPassword: string) {
      return call<{ ok: boolean }>('/auth/change-password', {
        method: 'POST',
        body: { oldPassword, newPassword },
      })
    },
  },

  dashboard: {
    summary() {
      return call<DashboardSummary>('/dashboard/summary')
    },
    timeseries(metric: TimeseriesMetric, range: RangeKey) {
      return call<TimeseriesResult>('/dashboard/timeseries', { query: toQuery({ metric, range }) })
    },
    topOrgs(hours = 24) {
      return call<TopOrgsResult>('/dashboard/top-orgs', { query: toQuery({ hours }) })
    },
    heatmap(days = 7) {
      return call<HeatmapResult>('/dashboard/heatmap', { query: toQuery({ days }) })
    },
    userStatus() {
      return call<StatusCountResult>('/dashboard/user-status')
    },
    roomStatus() {
      return call<RoomStatusResult>('/dashboard/room-status')
    },
    recentRooms() {
      return call<{ ok: boolean; items: RecentRoom[] }>('/dashboard/recent-rooms')
    },
  },

  orgs: {
    list(query: OrgQuery = {}) {
      return call<PageResult<Org>>('/orgs', { query: toQuery(query) })
    },
    get(id: number) {
      return call<{ ok: boolean; org: Org; stats: OrgStats }>(`/orgs/${id}`)
    },
    create(payload: CreateOrgPayload) {
      return call<{ ok: boolean; org: Org; adminUser: UserItem }>('/orgs', { method: 'POST', body: payload })
    },
    update(id: number, payload: UpdateOrgPayload) {
      return call<{ ok: boolean; org: Org }>(`/orgs/${id}`, { method: 'PATCH', body: payload })
    },
    remove(id: number) {
      return call<{ ok: boolean }>(`/orgs/${id}`, { method: 'DELETE' })
    },
  },

  applications: {
    list(query: ApplicationQuery = {}) {
      return call<PageResult<OrgApplication>>('/org-applications', { query: toQuery(query) })
    },
    approve(id: number, payload: ApplicationApprovePayload) {
      return call<{ ok: boolean; org: Org; adminUser: UserItem }>(`/org-applications/${id}/approve`, {
        method: 'POST',
        body: payload,
      })
    },
    reject(id: number, reason: string) {
      return call<{ ok: boolean }>(`/org-applications/${id}/reject`, { method: 'POST', body: { reason } })
    },
  },

  users: {
    list(query: UserQuery = {}) {
      return call<PageResult<UserItem>>('/users', { query: toQuery(query) })
    },
    create(payload: CreateUserPayload) {
      return call<{ ok: boolean; user: UserItem }>('/users', { method: 'POST', body: payload })
    },
    update(id: number, payload: UpdateUserPayload) {
      return call<{ ok: boolean; user: UserItem }>(`/users/${id}`, { method: 'PATCH', body: payload })
    },
    remove(id: number) {
      return call<{ ok: boolean }>(`/users/${id}`, { method: 'DELETE' })
    },
    import(orgId: number, rows: ImportUserRow[]) {
      return call<ImportResult>('/users/import', { method: 'POST', body: { orgId, rows } })
    },
    activationCode(id: number, purpose: CodePurpose = 'ACTIVATE') {
      return call<CodeIssueResult>(`/users/${id}/activation-code`, { method: 'POST', body: { purpose } })
    },
    resetPassword(id: number) {
      return call<CodeIssueResult>(`/users/${id}/reset-password`, { method: 'POST' })
    },
    unbindDevice(id: number) {
      return call<{ ok: boolean }>(`/users/${id}/unbind-device`, { method: 'POST' })
    },
    codes(id: number) {
      return call<{ ok: boolean; items: ActivationCode[] }>(`/users/${id}/codes`)
    },
  },

  codes: {
    list(query: CodeQuery = {}) {
      return call<PageResult<ActivationCode>>('/activation-codes', { query: toQuery(query) })
    },
    revoke(id: number) {
      return call<{ ok: boolean }>(`/activation-codes/${id}/revoke`, { method: 'POST' })
    },
  },

  rooms: {
    list(query: RoomQuery = {}) {
      return call<PageResult<RoomItem>>('/rooms', { query: toQuery(query) })
    },
    get(roomId: string) {
      return call<RoomDetailResult>(`/rooms/${roomId}`)
    },
    samples(roomId: string, limit = 500) {
      return call<RoomSamplesResult>(`/rooms/${roomId}/samples`, { query: toQuery({ limit }) })
    },
    exportFile(roomId: string, format: RoomExportFormat) {
      return download(`/rooms/${roomId}/export`, {
        query: toQuery({ format }),
        fallbackName: `room-${roomId}.${format === 'geojson' ? 'geojson' : format}`,
      })
    },
    close(roomId: string) {
      return call<{ ok: boolean }>(`/rooms/${roomId}/close`, { method: 'POST' })
    },
    purge(days: number, emptyOnly = true) {
      return call<PurgeResult>('/rooms/purge', {
        method: 'POST',
        query: toQuery({ days, emptyOnly }),
      })
    },
  },

  photos: {
    list(query: PhotoQuery = {}) {
      return call<PageResult<PhotoItem>>('/photos', { query: toQuery(query) })
    },
    /** 原图地址：mock 模式返回内置缩略图，真实模式由服务端返回二进制 */
    fileUrl(code: string, kind: PhotoKind = 'PHOTO') {
      return mockMode === 'on' ? mockPhotoThumb(code, kind) : `/api/admin/photos/${code}/file`
    },
  },

  audit: {
    list(query: AuditQuery = {}) {
      return call<PageResult<AuditEvent>>('/audit', { query: toQuery(query) })
    },
    exportCsv() {
      return download('/audit/export', {
        query: toQuery({ format: 'csv' }),
        fallbackName: `audit-${new Date().toISOString().slice(0, 10)}.csv`,
      })
    },
  },

  ops: {
    status() {
      return call<OpsStatus>('/ops/status')
    },
  },

  settings: {
    get() {
      return call<AppSettings>('/settings')
    },
    update(patch: SettingsPatch) {
      return call<AppSettings>('/settings', { method: 'PATCH', body: patch })
    },
  },
}

export type Api = typeof api
export type { AdminUser }
