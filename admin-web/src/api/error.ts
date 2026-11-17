// 统一 API 错误类型（client / mock 共用，避免循环依赖）

export class ApiError extends Error {
  /** HTTP 状态码；网络错误为 0 */
  status: number
  /** 服务端返回的业务错误码（如 DEVICE_MISMATCH） */
  code?: string
  /** 该错误是否代表“真实接口不可用”，可自动回退到 mock */
  fallbackable: boolean

  constructor(message: string, status = 0, code?: string, fallbackable = false) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.fallbackable = fallbackable
  }
}

export function isApiError(err: unknown): err is ApiError {
  return err instanceof ApiError
}

export function errorMessage(err: unknown): string {
  if (isApiError(err)) return err.message
  if (err instanceof Error) return err.message || '请求失败'
  return '请求失败，请稍后重试'
}
