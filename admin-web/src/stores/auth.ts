import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { api } from '@/api'
import { clearCsrfToken, setCsrfToken } from '@/api/client'
import type { AdminUser, LoginResult } from '@/types'

const ROLE_LABELS: Record<string, string> = {
  SUPER_ADMIN: '超级管理员',
  ORG_ADMIN: '组织管理员',
  USER: '普通用户',
  AUDITOR: '审计员',
}

export const useAuthStore = defineStore('auth', () => {
  const user = ref<AdminUser | null>(null)
  const initialized = ref(false)
  let mePromise: Promise<AdminUser | null> | null = null

  const isAuthenticated = computed(() => !!user.value)
  const isSuperAdmin = computed(() => user.value?.role === 'SUPER_ADMIN')
  const roleLabel = computed(() => (user.value ? ROLE_LABELS[user.value.role] || user.value.role : ''))
  const displayName = computed(() => user.value?.displayName || '')
  const avatarText = computed(() => (user.value?.displayName || '管').slice(0, 1))

  function applySession(res: LoginResult): void {
    user.value = res.user
    setCsrfToken(res.csrfToken)
  }

  async function login(username: string, password: string): Promise<AdminUser> {
    const res = await api.auth.login(username, password)
    applySession(res)
    initialized.value = true
    return res.user
  }

  /** 恢复会话；401/失败时置空，不抛错（由路由守卫跳登录） */
  async function fetchMe(force = false): Promise<AdminUser | null> {
    if (!force && initialized.value) return user.value
    if (mePromise) return mePromise
    mePromise = api.auth
      .me()
      .then((res) => {
        applySession(res)
        return res.user
      })
      .catch(() => {
        user.value = null
        clearCsrfToken()
        return null
      })
      .finally(() => {
        initialized.value = true
        mePromise = null
      })
    return mePromise
  }

  async function logout(): Promise<void> {
    try {
      await api.auth.logout()
    } catch {
      /* 网络异常也清理本地会话 */
    } finally {
      reset()
    }
  }

  async function changePassword(oldPassword: string, newPassword: string): Promise<void> {
    await api.auth.changePassword(oldPassword, newPassword)
    if (user.value) user.value = { ...user.value, mustChangePassword: false }
  }

  function markPasswordChanged(): void {
    if (user.value) user.value = { ...user.value, mustChangePassword: false }
  }

  function reset(): void {
    user.value = null
    initialized.value = true
    clearCsrfToken()
  }

  return {
    user,
    initialized,
    isAuthenticated,
    isSuperAdmin,
    roleLabel,
    displayName,
    avatarText,
    login,
    fetchMe,
    logout,
    changePassword,
    markPasswordChanged,
    reset,
  }
})
