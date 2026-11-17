import type { Component } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import AdminLayout from '@/layouts/AdminLayout.vue'
import { navRoutes } from './nav'
import { useAuthStore } from '@/stores/auth'

declare module 'vue-router' {
  interface RouteMeta {
    title?: string
    icon?: Component
    public?: boolean
    milestone?: string
    description?: string
    features?: string[]
  }
}

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/pages/LoginPage.vue'),
    meta: { title: '登录', public: true },
  },
  {
    path: '/',
    component: AdminLayout,
    redirect: '/dashboard',
    children: navRoutes,
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    redirect: '/dashboard',
  },
]

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes,
  scrollBehavior: () => ({ top: 0 }),
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()

  if (to.meta.public) {
    if (to.name === 'login' && auth.isAuthenticated) return { name: 'dashboard' }
    return true
  }

  if (!auth.initialized) await auth.fetchMe()
  if (!auth.user) {
    return { name: 'login', query: to.fullPath !== '/' ? { redirect: to.fullPath } : {} }
  }
  return true
})

router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} · 围猎管理中心` : '围猎 · 管理中心'
})

export default router
