import type { Component } from 'vue'
import type { RouteRecordRaw } from 'vue-router'
import {
  DataBoard,
  OfficeBuilding,
  User,
  Key,
  VideoCamera,
  Picture,
  Document,
  Monitor,
  Setting,
} from '@element-plus/icons-vue'

export interface NavItem {
  path: string
  title: string
  icon: Component
}

/** 一级导航：运营管理 */
export const primaryNav: NavItem[] = [
  { path: '/dashboard', title: '仪表盘', icon: DataBoard },
  { path: '/orgs', title: '组织管理', icon: OfficeBuilding },
  { path: '/users', title: '用户与账号', icon: User },
  { path: '/codes', title: '激活码', icon: Key },
]

/** 二级导航：运维与设置 */
export const secondaryNav: NavItem[] = [
  { path: '/rooms', title: '房间与任务', icon: VideoCamera },
  { path: '/photos', title: '取证照片', icon: Picture },
  { path: '/audit', title: '审计日志', icon: Document },
  { path: '/ops', title: '系统监控', icon: Monitor },
  { path: '/settings', title: '系统设置', icon: Setting },
]

export const navRoutes: RouteRecordRaw[] = [
  {
    path: 'dashboard',
    name: 'dashboard',
    component: () => import('@/pages/DashboardPage.vue'),
    meta: { title: '仪表盘', icon: DataBoard },
  },
  {
    path: 'orgs',
    name: 'orgs',
    component: () => import('@/pages/OrgsPage.vue'),
    meta: { title: '组织管理', icon: OfficeBuilding },
  },
  {
    path: 'users',
    name: 'users',
    component: () => import('@/pages/UsersPage.vue'),
    meta: { title: '用户与账号', icon: User },
  },
  {
    path: 'codes',
    name: 'codes',
    component: () => import('@/pages/CodesPage.vue'),
    meta: { title: '激活码', icon: Key },
  },
  {
    path: 'rooms',
    name: 'rooms',
    component: () => import('@/pages/RoomsPage.vue'),
    meta: { title: '房间与任务', icon: VideoCamera },
  },
  {
    path: 'photos',
    name: 'photos',
    component: () => import('@/pages/PhotosPage.vue'),
    meta: { title: '取证照片', icon: Picture },
  },
  {
    path: 'audit',
    name: 'audit',
    component: () => import('@/pages/AuditPage.vue'),
    meta: { title: '审计日志', icon: Document },
  },
  {
    path: 'ops',
    name: 'ops',
    component: () => import('@/pages/OpsPage.vue'),
    meta: { title: '系统监控', icon: Monitor },
  },
  {
    path: 'settings',
    name: 'settings',
    component: () => import('@/pages/SettingsPage.vue'),
    meta: { title: '系统设置', icon: Setting },
  },
]
