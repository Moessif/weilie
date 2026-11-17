<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Lock, Refresh, Setting, Tools } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import ChangePasswordDialog from '@/components/ChangePasswordDialog.vue'
import { api } from '@/api'
import type { AppSettings } from '@/types'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const loading = ref(false)
const saving = ref(false)
const settings = ref<AppSettings | null>(null)
const ttl = ref(3)
const passwordVisible = ref(false)

const dirty = computed(() => !!settings.value && ttl.value !== settings.value.roomTtlDays)

const readonlyItems = computed(() => {
  const cfg = settings.value
  if (!cfg) return []
  return [
    { label: '超级管理员账号', value: cfg.superadminUser },
    { label: '上传接口要求 Token', value: cfg.requireToken ? '已开启' : '未开启' },
    { label: '管理 Token', value: cfg.adminTokenConfigured ? '已配置' : '未配置' },
    { label: '服务版本', value: cfg.version },
    { label: '数据库 schema 版本', value: String(cfg.schemaVersion) },
    { label: '审计日志保留', value: `${cfg.auditRetentionDays} 天` },
    { label: '激活码有效期', value: `${cfg.activationCodeTtlDays} 天` },
  ]
})

async function loadSettings(): Promise<void> {
  loading.value = true
  try {
    const cfg = await api.settings.get()
    settings.value = cfg
    ttl.value = cfg.roomTtlDays
  } finally {
    loading.value = false
  }
}

async function saveTtl(): Promise<void> {
  saving.value = true
  try {
    const updated = await api.settings.update({ roomTtlDays: ttl.value })
    settings.value = updated
    ttl.value = updated.roomTtlDays
    ElMessage.success('房间 TTL 已更新')
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  void loadSettings()
})
</script>

<template>
  <div class="wl-page">
    <PageHeader
      title="系统设置"
      eyebrow="管理配置 · SETTINGS"
      description="运行时参数、安全策略与版本信息；安全相关配置由服务端启动参数控制，界面仅展示当前生效值。"
    >
      <template #actions>
        <el-button :icon="Refresh" plain @click="loadSettings">刷新</el-button>
      </template>
    </PageHeader>

    <div v-loading="loading" class="settings-grid">
      <div class="settings-main">
        <section class="wl-panel">
          <header class="wl-panel__head">
            <h3 class="wl-panel__title">系统设置</h3>
            <span class="wl-panel__hint">保存在服务端配置，修改后立即生效</span>
          </header>

          <div class="settings-body">
            <div class="form-field">
              <label class="form-label">房间 TTL（天）</label>
              <div class="form-row">
                <el-input-number v-model="ttl" :min="1" :max="30" :step="1" controls-position="right" />
                <span class="form-hint">超过该天数未活跃的房间会自动清理，可设置 1–30 天。</span>
              </div>
            </div>

            <div class="settings-actions">
              <el-button type="primary" :loading="saving" :disabled="!dirty" @click="saveTtl">保存 TTL 设置</el-button>
            </div>

            <div class="settings-sep" />

            <div class="section-head">
              <span class="wl-label">只读参数</span>
              <span class="section-note">由服务端启动配置决定</span>
            </div>
            <el-table :data="readonlyItems" class="readonly-table">
              <el-table-column prop="label" label="参数" min-width="150" />
              <el-table-column label="当前值" min-width="180">
                <template #default="{ row }">
                  <span class="mono readonly-value">{{ row.value }}</span>
                </template>
              </el-table-column>
            </el-table>
            <p v-if="!settings" class="empty-hint">设置加载失败，请稍后重试。</p>
          </div>
        </section>
      </div>

      <div class="settings-side">
        <section class="wl-panel">
          <header class="wl-panel__head">
            <h3 class="wl-panel__title">安全</h3>
            <span class="wl-panel__hint">账号与访问控制</span>
          </header>

          <div class="settings-body">
            <div class="account-row">
              <span class="account-avatar">{{ auth.avatarText }}</span>
              <div class="account-meta">
                <span class="account-name">{{ auth.displayName }}</span>
                <span class="account-sub">
                  {{ auth.roleLabel }}<template v-if="auth.user?.orgName"> · {{ auth.user.orgName }}</template>
                </span>
                <span class="account-sub mono">@{{ auth.user?.username }}</span>
              </div>
            </div>

            <div class="settings-actions settings-actions--start">
              <el-button type="primary" plain @click="passwordVisible = true">修改密码</el-button>
            </div>
            <p class="side-hint">修改密码成功后需要用新密码重新登录。</p>

            <div class="settings-sep" />

            <div class="section-head">
              <span class="wl-label">安全策略</span>
            </div>
            <ul class="policy-list">
              <li>
                <span class="policy-name">会话与 CSRF</span>
                <span class="policy-text">管理站使用 HttpOnly Cookie（WEILIE_ADMIN）维持会话，所有写操作校验 X-CSRF-Token。</span>
              </li>
              <li>
                <span class="policy-name">登录限流</span>
                <span class="policy-text">同一来源 15 分钟内允许 5 次登录尝试，超限后暂时拒绝。</span>
              </li>
              <li>
                <span class="policy-name">会话有效期</span>
                <span class="policy-text">8 小时滑动过期，持续操作会自动续期；退出登录立即失效。</span>
              </li>
              <li>
                <span class="policy-name">审计保留</span>
                <span class="policy-text">审计日志默认保留 {{ settings?.auditRetentionDays ?? 180 }} 天，支持 CSV 导出。</span>
              </li>
              <li>
                <span class="policy-name">设备绑定</span>
                <span class="policy-text">账号首次激活后绑定设备，管理员可解绑或重置激活码。</span>
              </li>
            </ul>
          </div>
        </section>

      </div>
    </div>

    <ChangePasswordDialog v-model="passwordVisible" />
  </div>
</template>

<style scoped>
.settings-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 340px;
  gap: 16px;
  align-items: start;
}

.settings-main,
.settings-side {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-width: 0;
}

.settings-body {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 16px 18px 18px;
}

.settings-body--tight {
  gap: 0;
  padding-top: 10px;
}

.form-field {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.form-label {
  font-size: 12px;
  font-weight: 500;
  color: var(--wl-text-2);
}

.form-row {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.form-hint {
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--wl-text-3);
}

.settings-actions {
  display: flex;
  justify-content: flex-end;
}

.settings-actions--start {
  justify-content: flex-start;
}

.settings-sep {
  height: 1px;
  background: var(--wl-line);
}

.section-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
}

.section-note {
  font-size: 11px;
  color: var(--wl-text-4);
}

.readonly-table {
  width: 100%;
  margin-top: -4px;
}

.readonly-value {
  font-size: 12px;
  color: var(--wl-text-2);
}

.account-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.account-avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: none;
  width: 38px;
  height: 38px;
  border: 1px solid var(--wl-line-2);
  border-radius: 50%;
  background: var(--wl-panel-2);
  color: var(--wl-cyan);
  font-size: 15px;
  font-weight: 600;
}

.account-meta {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.account-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--wl-text);
}

.account-sub {
  font-size: 11.5px;
  color: var(--wl-text-3);
}

.side-hint {
  margin: -4px 0 0;
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--wl-text-3);
}

.policy-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.policy-list li {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.policy-name {
  font-size: 12px;
  font-weight: 500;
  color: var(--wl-text);
}

.policy-text {
  font-size: 11.5px;
  line-height: 1.65;
  color: var(--wl-text-3);
}

.kv-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 0;
  border-bottom: 1px solid var(--wl-line);
}

.kv-row:last-child {
  border-bottom: 0;
}

.kv-key {
  flex: none;
  font-size: 11.5px;
  color: var(--wl-text-3);
}

.kv-value {
  font-size: 12px;
  color: var(--wl-text-2);
  text-align: right;
  overflow-wrap: anywhere;
}

.empty-hint {
  margin: 0;
  font-size: 11.5px;
  color: var(--wl-text-4);
}

@media (max-width: 1100px) {
  .settings-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
