import { ElMessage } from 'element-plus'
import { ApiError, errorMessage } from './error'
import { mockDownload, mockRequest } from './mock'
import { downloadText } from '@/utils/csv'

export type HttpMethod = 'GET' | 'POST' | 'PATCH' | 'PUT' | 'DELETE'

export type QueryValue = string | number | boolean | null | undefined

export interface RequestOptions {
  method?: HttpMethod
  body?: unknown
  query?: Record<string, QueryValue>
  /** 为 true 时不自动弹错误提示（如登录页需要在表单内展示） */
  silent?: boolean
}

/** 'on' | 'off' | 'auto'，来自 VITE_USE_MOCK（兼容 true/false/1/0） */
const MOCK_MODE = String(import.meta.env.VITE_USE_MOCK ?? 'auto')
  .trim()
  .toLowerCase()

export const mockMode: 'on' | 'off' | 'auto' = import.meta.env.PROD
  ? // 生产构建绝不启用 mock：否则后端 404/5xx 时管理站会静默展示编造的演示数据，
    // 且演示口令（admin12345）会随静态资源一起发布出去。
    'off'
  : MOCK_MODE === 'on' || MOCK_MODE === 'true' || MOCK_MODE === '1'
    ? 'on'
    : MOCK_MODE === 'off' || MOCK_MODE === 'false' || MOCK_MODE === '0'
      ? 'off'
      : 'auto'

const API_BASE = '/api/admin'
const CSRF_STORAGE_KEY = 'weilie.admin.csrf'

let csrfToken = (() => {
  try {
    return localStorage.getItem(CSRF_STORAGE_KEY) || ''
  } catch {
    return ''
  }
})()

let unauthorizedHandler: (() => void) | null = null
let unauthorizedFired = false

export function getCsrfToken(): string {
  return csrfToken
}

export function setCsrfToken(token: string): void {
  csrfToken = token || ''
  unauthorizedFired = false
  try {
    if (csrfToken) localStorage.setItem(CSRF_STORAGE_KEY, csrfToken)
    else localStorage.removeItem(CSRF_STORAGE_KEY)
  } catch {
    /* private mode */
  }
}

export function clearCsrfToken(): void {
  setCsrfToken('')
}

/** 由 auth store 注册：401 时清会话并跳转登录 */
export function setUnauthorizedHandler(handler: (() => void) | null): void {
  unauthorizedHandler = handler
}

export interface DownloadOptions {
  query?: Record<string, QueryValue>
  fallbackName?: string
  silent?: boolean
}

function parseFilename(disposition: string | null): string {
  if (!disposition) return ''
  const utf8 = disposition.match(/filename\*=UTF-8''([^;]+)/i)
  if (utf8?.[1]) {
    try {
      return decodeURIComponent(utf8[1].trim())
    } catch {
      /* ignore */
    }
  }
  const plain = disposition.match(/filename="?([^";]+)"?/i)
  return plain?.[1]?.trim() || ''
}

function triggerBlobDownload(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

function buildQuery(query?: Record<string, QueryValue>): string {
  if (!query) return ''
  const params = new URLSearchParams()
  Object.entries(query).forEach(([key, value]) => {
    if (value === undefined || value === null || value === '') return
    params.set(key, String(value))
  })
  const qs = params.toString()
  return qs ? `?${qs}` : ''
}

function pickErrorMessage(payload: unknown, status: number): { message: string; code?: string } {
  if (payload && typeof payload === 'object') {
    const obj = payload as Record<string, unknown>
    const rawError = obj.error
    if (obj.message && typeof obj.message === 'string') {
      return { message: obj.message, code: typeof obj.code === 'string' ? obj.code : undefined }
    }
    if (typeof rawError === 'string' && rawError) {
      return { message: rawError, code: typeof obj.code === 'string' ? obj.code : undefined }
    }
    if (rawError && typeof rawError === 'object') {
      const nested = rawError as Record<string, unknown>
      const message = String(nested.message || nested.error || '') || `请求失败（HTTP ${status}）`
      return { message, code: typeof nested.code === 'string' ? nested.code : undefined }
    }
  }
  return { message: `请求失败（HTTP ${status}）` }
}

/** 真实接口请求；网络/接口缺失异常带上 fallbackable 标记，供 auto 模式回退 mock */
async function rawRequest<T>(path: string, options: RequestOptions): Promise<T> {
  const method = options.method || 'GET'
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (options.body !== undefined) headers['Content-Type'] = 'application/json'
  if (method !== 'GET' && csrfToken) headers['X-CSRF-Token'] = csrfToken

  let res: Response
  try {
    res = await fetch(`${API_BASE}${path}${buildQuery(options.query)}`, {
      method,
      headers,
      credentials: 'include',
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
    })
  } catch {
    throw new ApiError('无法连接服务器', 0, 'NETWORK_ERROR', true)
  }

  const contentType = res.headers.get('content-type') || ''
  const isJson = contentType.includes('application/json')
  let payload: unknown = undefined

  if (isJson) {
    try {
      payload = await res.json()
    } catch {
      throw new ApiError('服务器响应解析失败', res.status, 'BAD_RESPONSE', res.ok)
    }
  } else {
    const text = await res.text().catch(() => '')
    // 静态页/代理返回 HTML，或后端未启动时代理返回 5xx：都视为“真实接口不可用”，可回退 mock
    if (!res.ok || text.trimStart().startsWith('<')) {
      throw new ApiError(
        res.ok ? '接口返回了非 JSON 内容' : `服务暂不可用（HTTP ${res.status}）`,
        res.status,
        'BAD_RESPONSE',
        true,
      )
    }
    payload = text
  }

  if (!res.ok) {
    const { message, code } = pickErrorMessage(payload, res.status)
    const fallbackable = [404, 405, 501, 502, 503, 504].includes(res.status)
    throw new ApiError(message, res.status, code, fallbackable)
  }

  // 契约中成功响应含 ok:true，失败可能仍是 200
  if (payload && typeof payload === 'object' && (payload as { ok?: boolean }).ok === false) {
    const { message, code } = pickErrorMessage(payload, res.status)
    throw new ApiError(message, res.status, code, false)
  }

  return payload as T
}

function notifyUnauthorized(path: string): void {
  if (path === '/auth/login' || path === '/auth/me') return
  if (unauthorizedFired) return
  unauthorizedFired = true
  unauthorizedHandler?.()
}

/**
 * 统一 API 调用入口。
 * auto 模式：优先真实接口；接口不存在/网络不可用时自动回退 mock，保证离线演示可用。
 */
export async function call<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const invoke = async (): Promise<T> => {
    if (mockMode === 'on') {
      return (await mockRequest(options.method || 'GET', path, options.body, options.query)) as T
    }
    if (mockMode === 'off') return rawRequest<T>(path, options)
    try {
      return await rawRequest<T>(path, options)
    } catch (err) {
      if (err instanceof ApiError && err.fallbackable) {
        console.info(`[api] ${options.method || 'GET'} ${path} 使用 mock 回退：${err.message}`)
        return (await mockRequest(options.method || 'GET', path, options.body, options.query)) as T
      }
      throw err
    }
  }

  try {
    const result = await invoke()
    unauthorizedFired = false
    return result
  } catch (err) {
    if (err instanceof ApiError && err.status === 401) notifyUnauthorized(path)
    if (!options.silent) ElMessage.error(errorMessage(err))
    throw err
  }
}

function fallbackDownload(path: string, options: DownloadOptions): void {
  const file = mockDownload(path, options.query)
  downloadText(options.fallbackName || file.filename, file.content, file.mime)
}

/**
 * 文件下载（房间导出 / 审计导出等）。
 * mock 模式直接生成同格式文件；auto 模式真实接口不可用时回退 mock。
 */
export async function download(path: string, options: DownloadOptions = {}): Promise<void> {
  if (mockMode === 'on') {
    fallbackDownload(path, options)
    return
  }

  try {
    const res = await fetch(`${API_BASE}${path}${buildQuery(options.query)}`, {
      credentials: 'include',
      headers: { Accept: '*/*' },
    })
    if (!res.ok) {
      let message = `下载失败（HTTP ${res.status}）`
      try {
        const payload = (await res.json()) as { error?: string }
        if (payload?.error) message = payload.error
      } catch {
        /* ignore */
      }
      const fallbackable = [404, 405, 501, 502, 503, 504].includes(res.status)
      throw new ApiError(message, res.status, 'DOWNLOAD_FAILED', fallbackable)
    }
    const blob = await res.blob()
    const filename =
      parseFilename(res.headers.get('content-disposition')) ||
      options.fallbackName ||
      `download-${Date.now()}`
    triggerBlobDownload(blob, filename)
  } catch (err) {
    if (err instanceof ApiError && err.fallbackable && mockMode === 'auto') {
      console.info(`[api] GET ${path} 下载使用 mock 回退：${err.message}`)
      fallbackDownload(path, options)
      return
    }
    if (!options.silent) ElMessage.error(errorMessage(err))
    throw err
  }
}


