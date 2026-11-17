// 围猎管理平台 · 与 server/docs/admin-api.md 对齐的类型定义（M1）

export type AdminRole = 'SUPER_ADMIN' | 'ORG_ADMIN' | 'USER' | 'AUDITOR'
export type UserStatus = 'PENDING' | 'ACTIVE' | 'SUSPENDED' | 'LOCKED' | 'DELETED'
export type OrgStatus = 'PENDING' | 'ACTIVE' | 'SUSPENDED'
export type ApplicationStatus = 'PENDING' | 'APPROVED' | 'REJECTED'
export type CodePurpose = 'ACTIVATE' | 'RESET_PASSWORD'
export type CodeStatus = 'UNUSED' | 'USED' | 'EXPIRED' | 'REVOKED'

export interface AdminUser {
  id: number
  username: string
  displayName: string
  role: AdminRole
  orgId: number | null
  orgName: string | null
  status: UserStatus
  mustChangePassword: boolean
}

export interface Org {
  id: number
  name: string
  code: string
  contactName: string
  contactPhone: string
  status: OrgStatus
  quotaUsers: number
  quotaRooms: number
  createdAt: number
  userCount?: number
  activeUsers?: number
  roomCount?: number
  sampleCount?: number
}

export interface OrgStats {
  users: number
  activeUsers: number
  rooms: number
  samples: number
}

export interface OrgApplication {
  id: number
  orgName: string
  contactName: string
  contactPhone: string
  email: string
  reason: string
  status: ApplicationStatus
  reviewNote: string | null
  createdAt: number
  reviewedAt: number | null
}

export interface UserItem {
  id: number
  orgId: number
  orgName: string
  username: string
  displayName: string
  phone: string
  role: AdminRole
  status: UserStatus
  deviceId: string | null
  deviceName: string | null
  lastLoginAt: number | null
  activatedAt: number | null
  createdAt: number
}

export interface ActivationCode {
  id: number
  code: string
  userId: number
  username: string
  displayName: string
  orgId: number
  orgName: string
  purpose: CodePurpose
  status: CodeStatus
  expiresAt: number
  usedAt: number | null
  createdAt: number
}

export interface PageResult<T> {
  ok: boolean
  total: number
  items: T[]
  page: number
  size: number
}

export interface LoginResult {
  ok: boolean
  user: AdminUser
  csrfToken: string
}

export interface Kpi {
  orgs: number
  orgsDelta: number
  users: number
  activeUsers: number
  pendingUsers: number
  onlineMembers: number
  onlinePeak: number
  rooms: number
  activeRooms: number
  todaySamples: number
  samplesDeltaPct: number
  photos: number
  audits24h: number
}

export interface DashboardSummary {
  ok: boolean
  kpi: Kpi
  server: {
    version: string
    uptimeSec: number
    roomsInMemory: number
    onlineMembers: number
  }
}

export interface TimePoint {
  ts: number
  value: number
}

export interface TimeseriesResult {
  ok: boolean
  points: TimePoint[]
}

export interface TopOrgItem {
  orgId: number
  orgName: string
  samples: number
}

export interface TopOrgsResult {
  ok: boolean
  items: TopOrgItem[]
}

export interface HeatCell {
  weekday: number
  hour: number
  value: number
}

export interface HeatmapResult {
  ok: boolean
  cells: HeatCell[]
}

export interface StatusCount {
  status: string
  count: number
}

export interface StatusCountResult {
  ok: boolean
  items: StatusCount[]
}

export interface RoomStatusCount {
  key: string
  count: number
}

export interface RoomStatusResult {
  ok: boolean
  items: RoomStatusCount[]
}

export type RoomStatusKey = 'OPEN' | 'IDLE' | 'CLOSED'

export interface RecentRoom {
  roomId: string
  orgId: number
  orgName: string
  members: number
  samples: number
  lastActiveAt: number
  status: RoomStatusKey
}

export interface AuditEvent {
  id: number
  ts: number
  actorName: string
  orgName: string | null
  action: string
  detail: string
  level: 'info' | 'success' | 'warning' | 'danger'
}

export interface ImportFailedRow {
  row: number
  reason: string
}

export interface ImportResult {
  ok: boolean
  created: number
  failed: ImportFailedRow[]
  codes: { username: string; code: string }[]
}

export type RangeKey = '24h' | '7d' | '30d'
export type TimeseriesMetric = 'samples' | 'online' | 'ws'

/* ------------------------------ M2 ------------------------------ */

export interface RoomItem {
  roomId: string
  name: string
  joinCode: string
  orgId: number
  orgName: string
  members: number
  samples: number
  markers: number
  zones: number
  photos: number
  createdAt: number
  lastActiveAt: number
  status: RoomStatusKey
}

export interface RoomMember {
  userId: string
  name: string
  role: string
  lat: number
  lon: number
  lastSeenAt: number
  online: boolean
}

export interface RoomZone {
  id: string
  kind: string
  note: string
  assignees: string[]
  centerLat: number
  centerLon: number
  radiusM: number
}

export interface RoomMarker {
  id: string
  name: string
  byName: string
  lat: number
  lon: number
  kind: string
  ts: number
}

export interface RoomSample {
  userId: string
  lat: number
  lon: number
  ts: number
  targetSeen: boolean
  rsrp?: number
}

export interface RoomDetailResult {
  ok: boolean
  room: RoomItem
  members: RoomMember[]
  zones: RoomZone[]
  markers: RoomMarker[]
  recentSamples: RoomSample[]
}

export interface RoomSamplesResult {
  ok: boolean
  items: RoomSample[]
}

export type RoomExportFormat = 'csv' | 'geojson' | 'kml'

export type PhotoKind = 'PHOTO' | 'SUSPICIOUS'

export interface PhotoItem {
  code: string
  sha256: string
  ts: number
  lat: number
  lon: number
  device: string
  kind: PhotoKind
  note: string
  serverTs: number
  hasFile: boolean
  sizeBytes: number
}

export interface OpsServerInfo {
  version: string
  uptimeSec: number
  startedAt: number
  javaVersion: string
  osName: string
  cpuCores: number
  heapUsedBytes: number
  heapMaxBytes: number
  threads: number
  dataDir: string
  dbBytes: number
  photosDirBytes: number
  roomsDirBytes: number
  diskFreeBytes: number
}

export interface OpsStatus {
  ok: boolean
  server: OpsServerInfo
  rooms: { total: number; open: number; online: number }
  metrics: {
    wsConnections: number
    messages: number
    samples: number
    photosUploaded: number
    rateLimited: number
    errors: number
    exports: number
  }
  limits: { roomTtlDays: number; requireToken: boolean; adminTokenConfigured: boolean }
}

export interface AppSettings {
  ok: boolean
  roomTtlDays: number
  requireToken: boolean
  adminTokenConfigured: boolean
  superadminUser: string
  version: string
  schemaVersion: number
  auditRetentionDays: number
  activationCodeTtlDays: number
}

export type SettingsPatch = Partial<Pick<AppSettings, 'roomTtlDays' | 'requireToken'>>
