import { ref } from 'vue'
import { defineStore } from 'pinia'
import { api } from '@/api'
import type { Org } from '@/types'

const SIDEBAR_KEY = 'weilie.admin.sidebar.collapsed'

function readCollapsed(): boolean {
  try {
    return localStorage.getItem(SIDEBAR_KEY) === '1'
  } catch {
    return false
  }
}

export const useAppStore = defineStore('app', () => {
  const collapsed = ref(readCollapsed())
  /** 顶部条“组织筛选”，供列表页作为默认筛选条件 */
  const scopeOrgId = ref<number | undefined>(undefined)
  const orgs = ref<Org[]>([])
  const orgsLoading = ref(false)
  let orgsPromise: Promise<void> | null = null

  function setCollapsed(value: boolean): void {
    collapsed.value = value
    try {
      localStorage.setItem(SIDEBAR_KEY, value ? '1' : '0')
    } catch {
      /* ignore */
    }
  }

  function toggleCollapsed(): void {
    setCollapsed(!collapsed.value)
  }

  async function loadOrgs(force = false): Promise<void> {
    if (!force && orgs.value.length) return
    if (orgsPromise) return orgsPromise
    orgsLoading.value = true
    orgsPromise = api.orgs
      .list({ page: 1, size: 200 })
      .then((res) => {
        orgs.value = res.items
        if (scopeOrgId.value && !orgs.value.some((org) => org.id === scopeOrgId.value)) {
          scopeOrgId.value = undefined
        }
      })
      .catch(() => {
        /* 组织列表加载失败不阻断页面 */
      })
      .finally(() => {
        orgsLoading.value = false
        orgsPromise = null
      })
    return orgsPromise
  }

  function setScopeOrg(orgId: number | undefined): void {
    scopeOrgId.value = orgId
  }

  return {
    collapsed,
    scopeOrgId,
    orgs,
    orgsLoading,
    setCollapsed,
    toggleCollapsed,
    loadOrgs,
    setScopeOrg,
  }
})
