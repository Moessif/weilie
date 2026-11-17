// 通用格式化工具

export function formatNumber(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '—'
  return value.toLocaleString('zh-CN')
}

export function formatCompact(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '—'
  if (value >= 100000) return `${(value / 10000).toFixed(1)} 万`
  return value.toLocaleString('zh-CN')
}

function pad2(n: number): string {
  return n < 10 ? `0${n}` : String(n)
}

export function formatDateTime(ts: number | null | undefined): string {
  if (!ts) return '—'
  const d = new Date(ts)
  if (Number.isNaN(d.getTime())) return '—'
  return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())} ${pad2(d.getHours())}:${pad2(
    d.getMinutes(),
  )}`
}

export function formatTime(ts: number | null | undefined): string {
  if (!ts) return '—'
  const d = new Date(ts)
  if (Number.isNaN(d.getTime())) return '—'
  return `${pad2(d.getHours())}:${pad2(d.getMinutes())}`
}

export function formatDate(ts: number | null | undefined): string {
  if (!ts) return '—'
  const d = new Date(ts)
  if (Number.isNaN(d.getTime())) return '—'
  return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`
}

export function formatRelative(ts: number | null | undefined): string {
  if (!ts) return '—'
  const diff = Date.now() - ts
  if (diff < 0) return formatDateTime(ts)
  const sec = Math.floor(diff / 1000)
  if (sec < 60) return '刚刚'
  const min = Math.floor(sec / 60)
  if (min < 60) return `${min} 分钟前`
  const hour = Math.floor(min / 60)
  if (hour < 24) return `${hour} 小时前`
  const day = Math.floor(hour / 24)
  if (day < 30) return `${day} 天前`
  return formatDate(ts)
}

export function formatDuration(sec: number | null | undefined): string {
  if (!sec && sec !== 0) return '—'
  const days = Math.floor(sec / 86400)
  const hours = Math.floor((sec % 86400) / 3600)
  const minutes = Math.floor((sec % 3600) / 60)
  if (days > 0) return `${days} 天 ${hours} 小时`
  if (hours > 0) return `${hours} 小时 ${minutes} 分`
  return `${minutes} 分钟`
}

export function maskPhone(phone: string | null | undefined): string {
  if (!phone) return '—'
  if (phone.length < 7) return phone
  return `${phone.slice(0, 3)}****${phone.slice(-4)}`
}

const STATUS_TEXT: Record<string, string> = {
  ACTIVE: '已激活',
  PENDING: '待激活',
  SUSPENDED: '已停用',
  LOCKED: '已锁定',
  DELETED: '已删除',
  APPROVED: '已通过',
  REJECTED: '已驳回',
  UNUSED: '未使用',
  USED: '已使用',
  EXPIRED: '已过期',
  REVOKED: '已作废',
  ACTIVATE: '激活',
  RESET_PASSWORD: '重置密码',
  SUPER_ADMIN: '超级管理员',
  ORG_ADMIN: '组织管理员',
  USER: '普通用户',
  AUDITOR: '审计员',
  WITH_ZONE: '有任务区',
  POINT_ONLY: '仅有标点',
  EMPTY: '空房',
  CLOSED: '已关闭',
  OPEN: '进行中',
  IDLE: '空闲',
}

export function statusText(status: string | null | undefined): string {
  if (!status) return '—'
  return STATUS_TEXT[status] || status
}

export type TagType = 'success' | 'warning' | 'danger' | 'info' | 'primary'

export function statusTagType(status: string | null | undefined): TagType {
  switch (status) {
    case 'ACTIVE':
    case 'APPROVED':
    case 'USED':
    case 'OPEN':
      return 'success'
    case 'PENDING':
    case 'IDLE':
      return 'warning'
    case 'UNUSED':
      return 'primary'
    case 'SUSPENDED':
    case 'LOCKED':
    case 'REJECTED':
    case 'REVOKED':
      return 'danger'
    case 'EXPIRED':
    case 'CLOSED':
    case 'DELETED':
      return 'info'
    default:
      return 'info'
  }
}

export function formatBytes(bytes: number | null | undefined, digits = 1): string {
  if (bytes === null || bytes === undefined || Number.isNaN(bytes)) return '—'
  if (bytes < 1024) return `${bytes} B`
  const units = ['KB', 'MB', 'GB', 'TB']
  let value = bytes / 1024
  let unit = 0
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024
    unit++
  }
  return `${value.toFixed(digits)} ${units[unit]}`
}

export function shortId(id: string): string {
  return id.length > 10 ? `${id.slice(0, 8)}…` : id
}

export function greeting(): string {
  const h = new Date().getHours()
  if (h < 6) return '凌晨好'
  if (h < 12) return '上午好'
  if (h < 14) return '中午好'
  if (h < 18) return '下午好'
  return '晚上好'
}
