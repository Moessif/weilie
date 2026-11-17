<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ArrowDown,
  Expand,
  Fold,
  Lock,
  OfficeBuilding,
  SwitchButton,
} from '@element-plus/icons-vue'
import ChangePasswordDialog from '@/components/ChangePasswordDialog.vue'
import { mockMode } from '@/api/client'
import { primaryNav, secondaryNav } from '@/router/nav'
import { useAppStore } from '@/stores/app'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const app = useAppStore()

const activePath = computed(() => route.path)
const pageTitle = computed(() => (route.meta.title as string) || '仪表盘')
const sectionLabel = computed(() =>
  primaryNav.some((item) => item.path === route.path) ? '运营管理' : '运维与设置',
)
const showOrgScope = computed(() => auth.isSuperAdmin)
const version = 'v1.12.0'

const pwdVisible = ref(false)
const pwdForced = ref(false)

const media = typeof window !== 'undefined' ? window.matchMedia('(max-width: 1199px)') : null

function handleMediaChange(event: MediaQueryListEvent | MediaQueryList): void {
  if (event.matches) app.setCollapsed(true)
}

watch(
  () => auth.user?.mustChangePassword,
  (must) => {
    if (must) {
      pwdForced.value = true
      pwdVisible.value = true
    }
  },
  { immediate: true },
)

function onScopeChange(value: number | undefined | ''): void {
  app.setScopeOrg(typeof value === 'number' ? value : undefined)
}

function openPasswordDialog(): void {
  pwdForced.value = false
  pwdVisible.value = true
}

async function handleLogout(): Promise<void> {
  try {
    await ElMessageBox.confirm('退出后需要重新登录才能继续访问管理站。', '退出登录', {
      confirmButtonText: '退出登录',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  await auth.logout()
  ElMessage.success('已安全退出')
  router.replace({ name: 'login' })
}

function onCommand(command: string): void {
  if (command === 'password') openPasswordDialog()
  else if (command === 'logout') void handleLogout()
}

onMounted(() => {
  void auth.fetchMe()
  void app.loadOrgs()
  if (media) {
    handleMediaChange(media)
    media.addEventListener('change', handleMediaChange)
  }
})

onBeforeUnmount(() => {
  media?.removeEventListener('change', handleMediaChange)
})
</script>

<template>
  <div class="shell" :class="{ 'is-collapsed': app.collapsed }">
    <aside class="rail">
      <router-link to="/dashboard" class="brand" aria-label="围猎管理中心首页">
        <span class="brand__mark">围</span>
        <span v-show="!app.collapsed" class="brand__text">
          <span class="brand__name">围猎 · 管理中心</span>
          <span class="brand__sub">WEILIE ADMIN</span>
        </span>
      </router-link>

      <el-scrollbar class="rail__scroll">
        <nav class="nav" aria-label="主导航">
          <template v-for="group in [
            { label: '运营管理', items: primaryNav },
            { label: '运维与设置', items: secondaryNav },
          ]" :key="group.label">
            <p v-show="!app.collapsed" class="nav__caption">{{ group.label }}</p>
            <router-link
              v-for="item in group.items"
              :key="item.path"
              :to="item.path"
              class="nav__item"
              :class="{ 'is-active': activePath === item.path }"
              :title="app.collapsed ? item.title : undefined"
            >
              <el-icon class="nav__icon" :size="16"><component :is="item.icon" /></el-icon>
              <span v-show="!app.collapsed" class="nav__label">{{ item.title }}</span>
            </router-link>
          </template>
        </nav>
      </el-scrollbar>

      <div class="rail__foot">
        <button class="rail__toggle" type="button" :aria-label="app.collapsed ? '展开导航' : '收起导航'" @click="app.toggleCollapsed()">
          <el-icon :size="15">
            <Expand v-if="app.collapsed" />
            <Fold v-else />
          </el-icon>
        </button>
        <div v-show="!app.collapsed" class="rail__status">
          <span class="pulse" aria-hidden="true" />
          <span class="rail__status-text">服务运行中</span>
          <span class="mono rail__ver">{{ version }}</span>
        </div>
        <el-tooltip v-if="mockMode === 'on'" placement="right" content="当前为本地 mock 演示模式（未连接服务端）">
          <span class="mock-chip">MOCK</span>
        </el-tooltip>
      </div>
    </aside>

    <div class="main">
      <header class="topbar">
        <div class="topbar__lead">
          <p class="topbar__crumb">
            <span class="topbar__group">{{ sectionLabel }}</span>
            <span class="topbar__sep">/</span>
            <span class="topbar__now">{{ pageTitle }}</span>
          </p>
        </div>

        <div class="topbar__tools">
          <el-select
            v-if="showOrgScope"
            :model-value="app.scopeOrgId"
            class="org-select"
            placeholder="全部组织"
            clearable
            filterable
            :loading="app.orgsLoading"
            @change="onScopeChange"
          >
            <template #prefix>
              <el-icon><OfficeBuilding /></el-icon>
            </template>
            <el-option v-for="org in app.orgs" :key="org.id" :label="org.name" :value="org.id" />
          </el-select>

          <span class="topbar__rule" />

          <el-dropdown trigger="click" @command="onCommand">
            <button class="user" type="button">
              <span class="user__avatar">{{ auth.avatarText }}</span>
              <span class="user__meta">
                <span class="user__name">{{ auth.displayName }}</span>
                <span class="user__role">{{ auth.roleLabel }}</span>
              </span>
              <el-icon class="user__caret" :size="11"><ArrowDown /></el-icon>
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="password">
                  <el-icon><Lock /></el-icon>修改密码
                </el-dropdown-item>
                <el-dropdown-item command="logout" divided>
                  <el-icon><SwitchButton /></el-icon>退出登录
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>

      <main class="content">
        <router-view v-slot="{ Component }">
          <transition name="page" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </main>
    </div>

    <ChangePasswordDialog v-model="pwdVisible" :forced="pwdForced" @success="pwdVisible = false" />
  </div>
</template>

<style scoped>
.shell {
  display: flex;
  min-height: 100vh;
  background: var(--wl-bg);
}

/* ---------------- 侧栏 ---------------- */
.rail {
  position: fixed;
  inset: 0 auto 0 0;
  z-index: 30;
  display: flex;
  flex-direction: column;
  width: 236px;
  background: var(--wl-bg-soft);
  border-right: 1px solid var(--wl-line);
  transition: width 0.18s cubic-bezier(0.33, 1, 0.68, 1);
}

.is-collapsed .rail {
  width: 64px;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 58px;
  padding: 0 18px;
  flex-shrink: 0;
  border-bottom: 1px solid var(--wl-line);
  color: inherit;
}
.brand:hover {
  color: inherit;
}

.brand__mark {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  flex-shrink: 0;
  border-radius: 8px;
  font-size: 15px;
  font-weight: 700;
  color: var(--wl-cyan);
  background: var(--wl-cyan-wash);
  box-shadow: inset 0 0 0 1px rgba(34, 211, 238, 0.28);
}

.brand__text {
  display: flex;
  flex-direction: column;
  gap: 2px;
  overflow: hidden;
  white-space: nowrap;
}

.brand__name {
  font-size: 13.5px;
  font-weight: 600;
  letter-spacing: -0.01em;
  color: var(--wl-text);
}

.brand__sub {
  font-family: var(--wl-mono);
  font-size: 9px;
  letter-spacing: 0.2em;
  color: var(--wl-text-4);
}

.is-collapsed .brand {
  padding: 0;
  justify-content: center;
}

.rail__scroll {
  flex: 1;
  padding: 14px 0 8px;
}

.nav {
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.nav__caption {
  margin: 12px 0 6px;
  padding: 0 20px;
  font-size: 10px;
  font-weight: 500;
  letter-spacing: 0.16em;
  text-transform: uppercase;
  color: var(--wl-text-4);
}
.nav__caption:first-child {
  margin-top: 0;
}

.nav__item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 10px;
  height: 34px;
  margin: 1px 10px;
  padding: 0 10px;
  border-radius: var(--wl-radius-sm);
  color: var(--wl-text-2);
  font-size: 13px;
  font-weight: 450;
  transition: background 0.14s ease, color 0.14s ease;
}

.nav__item::before {
  content: '';
  position: absolute;
  left: -10px;
  top: 50%;
  width: 2px;
  height: 0;
  border-radius: 0 2px 2px 0;
  background: var(--wl-cyan);
  transform: translateY(-50%);
  transition: height 0.16s ease;
}

.nav__item:hover {
  background: rgba(255, 255, 255, 0.045);
  color: var(--wl-text);
}

.nav__item.is-active {
  background: var(--wl-cyan-wash);
  color: var(--wl-text);
  font-weight: 500;
}

.nav__item.is-active::before {
  height: 16px;
}

.nav__item.is-active .nav__icon {
  color: var(--wl-cyan);
}

.nav__icon {
  color: var(--wl-text-3);
  flex-shrink: 0;
  transition: color 0.14s ease;
}

.nav__item:hover .nav__icon {
  color: var(--wl-text-2);
}

.nav__label {
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.is-collapsed .nav__item {
  justify-content: center;
  padding: 0;
  margin: 1px 8px;
  height: 36px;
}
.is-collapsed .nav__item::before {
  display: none;
}

.rail__foot {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border-top: 1px solid var(--wl-line);
  flex-shrink: 0;
}

.is-collapsed .rail__foot {
  flex-direction: column;
  padding: 10px 8px;
}

.rail__toggle {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  flex-shrink: 0;
  border-radius: var(--wl-radius-sm);
  border: 1px solid transparent;
  background: transparent;
  color: var(--wl-text-3);
  cursor: pointer;
  transition: color 0.14s ease, background 0.14s ease, border-color 0.14s ease;
}
.rail__toggle:hover {
  color: var(--wl-text);
  background: rgba(255, 255, 255, 0.05);
  border-color: var(--wl-line-2);
}

.rail__status {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  font-size: 10.5px;
  color: var(--wl-text-4);
  white-space: nowrap;
}

.rail__status-text {
  overflow: hidden;
  text-overflow: ellipsis;
}

.rail__ver {
  margin-left: auto;
  color: var(--wl-text-4);
  font-size: 10px;
}

.pulse {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--wl-success);
  flex-shrink: 0;
  box-shadow: 0 0 0 0 rgba(62, 207, 142, 0.5);
  animation: pulse-ring 2.6s ease-out infinite;
}

@keyframes pulse-ring {
  0% {
    box-shadow: 0 0 0 0 rgba(62, 207, 142, 0.45);
  }
  70% {
    box-shadow: 0 0 0 5px rgba(62, 207, 142, 0);
  }
  100% {
    box-shadow: 0 0 0 0 rgba(62, 207, 142, 0);
  }
}

.mock-chip {
  padding: 1px 5px;
  font-family: var(--wl-mono);
  font-size: 9px;
  letter-spacing: 0.08em;
  color: var(--wl-warning);
  border: 1px solid rgba(240, 180, 41, 0.32);
  background: rgba(240, 180, 41, 0.08);
  border-radius: 4px;
  cursor: default;
  flex-shrink: 0;
}

/* ---------------- 主区域 ---------------- */
.main {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-width: 0;
  margin-left: 236px;
  min-height: 100vh;
  transition: margin-left 0.18s cubic-bezier(0.33, 1, 0.68, 1);
}

.is-collapsed .main {
  margin-left: 64px;
}

.topbar {
  position: sticky;
  top: 0;
  z-index: 20;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  height: 58px;
  padding: 0 var(--wl-gutter);
  background: rgba(10, 12, 17, 0.86);
  border-bottom: 1px solid var(--wl-line);
  backdrop-filter: blur(12px);
}

.topbar__lead {
  display: flex;
  align-items: baseline;
  gap: 10px;
  min-width: 0;
}

.topbar__crumb {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  min-width: 0;
  white-space: nowrap;
}

.topbar__group {
  font-size: 10.5px;
  font-weight: 500;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  color: var(--wl-text-4);
}

.topbar__sep {
  font-size: 11px;
  color: var(--wl-text-4);
  opacity: 0.55;
}

.topbar__now {
  font-size: 12.5px;
  font-weight: 500;
  color: var(--wl-text-2);
  overflow: hidden;
  text-overflow: ellipsis;
}

.topbar__tools {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-shrink: 0;
}

.org-select {
  width: 186px;
}
.org-select :deep(.el-select__wrapper) {
  background: transparent;
  box-shadow: 0 0 0 1px var(--wl-line-2) inset;
}

.topbar__rule {
  width: 1px;
  height: 18px;
  background: var(--wl-line-2);
}

.user {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 4px 8px 4px 4px;
  border: 1px solid transparent;
  border-radius: var(--wl-radius-sm);
  background: transparent;
  color: inherit;
  cursor: pointer;
  outline: none;
  transition: background 0.14s ease, border-color 0.14s ease;
}
.user:hover {
  background: rgba(255, 255, 255, 0.04);
  border-color: var(--wl-line);
}

.user__avatar {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 8px;
  background: var(--wl-cyan-wash);
  box-shadow: inset 0 0 0 1px rgba(34, 211, 238, 0.24);
  color: var(--wl-cyan);
  font-size: 12.5px;
  font-weight: 600;
  flex-shrink: 0;
}

.user__meta {
  display: flex;
  flex-direction: column;
  line-height: 1.2;
  text-align: left;
}

.user__name {
  font-size: 12.5px;
  font-weight: 500;
  color: var(--wl-text);
}

.user__role {
  font-size: 10px;
  color: var(--wl-text-4);
}

.user__caret {
  color: var(--wl-text-4);
}

.content {
  flex: 1;
  width: 100%;
  max-width: 1560px;
  margin: 0 auto;
  padding: 26px var(--wl-gutter) 56px;
  min-width: 0;
}

/* 路由切换 */
.page-enter-active,
.page-leave-active {
  transition: opacity 0.14s ease, transform 0.14s ease;
}
.page-enter-from {
  opacity: 0;
  transform: translateY(5px);
}
.page-leave-to {
  opacity: 0;
}

@media (max-width: 1180px) {
  .content {
    padding: 20px 18px 40px;
  }
  .topbar {
    padding: 0 18px;
  }
}

@media (max-width: 860px) {
  .topbar__crumb {
    display: none;
  }
  .user__meta {
    display: none;
  }
  .org-select {
    width: 140px;
  }
}
</style>
