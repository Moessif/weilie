<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Cpu, DataLine, Delete, FolderOpened, Refresh, Setting } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import { api } from '@/api'
import type { AppSettings, OpsStatus } from '@/types'
import { formatBytes, formatDateTime, formatDuration } from '@/utils/format'

const loading = ref(false)
const status = ref<OpsStatus | null>(null)
const settings = ref<AppSettings | null>(null)
const autoRefresh = ref(true)
let timer: number | null = null

const heapPercent = computed(() => {
  if (!status.value) return 0
  const { heapUsedBytes, heapMaxBytes } = status.value.server
  return heapMaxBytes > 0 ? Math.min(100, Math.round((heapUsedBytes / heapMaxBytes) * 100)) : 0
})

const metricItems = computed(() => {
  const metrics = status.value?.metrics
  if (!metrics) return []
  return [
    { label: 'WS 连接', value: metrics.wsConnections.toLocaleString('zh-CN') },
    { label: '消息累计', value: metrics.messages.toLocaleString('zh-CN') },
    { label: '采样点', value: metrics.samples.toLocaleString('zh-CN') },
    { label: '照片上传', value: metrics.photosUploaded.toLocaleString('zh-CN') },
    { label: '限流触发', value: metrics.rateLimited.toLocaleString('zh-CN') },
    { label: '错误数', value: metrics.errors.toLocaleString('zh-CN') },
    { label: '导出次数', value: metrics.exports.toLocaleString('zh-CN') },
  ]
})

const storageBreakdown = computed(() => {
  const server = status.value?.server
  const rows: Array<{ key: string; label: string; bytes: number; percent: number }> = []
  let total = 0
  if (server) {
    total = server.dbBytes + server.photosDirBytes + server.roomsDirBytes
    rows.push(
      { key: 'db', label: 'admin.db', bytes: server.dbBytes, percent: total > 0 ? (server.dbBytes / total) * 100 : 0 },
      {
        key: 'photos',
        label: 'photos 目录',
        bytes: server.photosDirBytes,
        percent: total > 0 ? (server.photosDirBytes / total) * 100 : 0,
      },
      {
        key: 'rooms',
        label: 'rooms 目录',
        bytes: server.roomsDirBytes,
        percent: total > 0 ? (server.roomsDirBytes / total) * 100 : 0,
      },
    )
  }
  return { total, rows }
})

async function loadOps(): Promise<void> {
  loading.value = true
  try {
    const [ops, cfg] = await Promise.allSettled([api.ops.status(), api.settings.get()])
    if (ops.status === 'fulfilled') status.value = ops.value
    if (cfg.status === 'fulfilled') {
      settings.value = cfg.value
      ttl.value = cfg.value.roomTtlDays
      requireToken.value = cfg.value.requireToken
    }
  } finally {
    loading.value = false
  }
}

/* ------------------------------ 自动刷新 ------------------------------ */

function startTimer(): void {
  stopTimer()
  timer = window.setInterval(() => {
    if (autoRefresh.value && !loading.value) void loadOps()
  }, 15_000)
}

function stopTimer(): void {
  if (timer !== null) {
    window.clearInterval(timer)
    timer = null
  }
}

/* ------------------------------ 清理空房 ------------------------------ */

const purgeVisible = ref(false)
const purgeDays = ref(3)
const purging = ref(false)

async function submitPurge(): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `将清理 ${purgeDays.value} 天前的空闲房间，操作不可恢复。确认继续？`,
      '清理空房',
      { confirmButtonText: '确认清理', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  purging.value = true
  try {
    const res = await api.rooms.purge(purgeDays.value, true)
    ElMessage.success(`清理完成，移除 ${res.removed} 个空闲房间`)
    purgeVisible.value = false
    await loadOps()
  } finally {
    purging.value = false
  }
}

/* ------------------------------ 运行时设置 ---------------------------- */

const ttl = ref(3)
const requireToken = ref(false)
const saving = ref(false)

const dirty = computed(() => {
  if (!settings.value) return false
  return ttl.value !== settings.value.roomTtlDays || requireToken.value !== settings.value.requireToken
})

async function saveSettings(): Promise<void> {
  saving.value = true
  try {
    const updated = await api.settings.update({
      roomTtlDays: ttl.value,
      requireToken: requireToken.value,
    })
    settings.value = updated
    ElMessage.success('运行时设置已保存')
    await loadOps()
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  await loadOps()
  startTimer()
})

onBeforeUnmount(stopTimer)
</script>

<template>
  <div class="wl-page">
    <PageHeader
      title="系统监控"
      eyebrow="运行状态 · OPS"
      description="服务端运行状态、内存与存储占用、房间与在线规模、业务指标；每 15 秒自动刷新。"
    >
      <template #actions>
        <span class="auto-label">自动刷新</span>
        <el-switch v-model="autoRefresh" />
        <el-button :icon="Refresh" plain @click="loadOps">刷新</el-button>
      </template>
    </PageHeader>

    <div v-loading="loading" class="ops-stack">
      <div class="ops-grid">
        <!-- 服务信息 -->
        <section class="wl-panel">
          <header class="wl-panel__head">
            <h3 class="wl-panel__title">服务信息</h3>
            <span class="wl-panel__hint">版本与运行环境</span>
          </header>
          <div v-if="status" class="card-body">
            <div class="version-line">
              <span class="version-value mono">{{ status.server.version }}</span>
              <span class="version-meta">已运行 {{ formatDuration(status.server.uptimeSec) }}</span>
            </div>
            <div class="kv-grid">
              <div class="kv-item">
                <span class="kv-key">Schema 版本</span>
                <span class="kv-value mono">{{ settings?.schemaVersion ?? '—' }}</span>
              </div>
              <div class="kv-item">
                <span class="kv-key">启动时间</span>
                <span class="kv-value mono">{{ formatDateTime(status.server.startedAt) }}</span>
              </div>
              <div class="kv-item">
                <span class="kv-key">Java 运行时</span>
                <span class="kv-value mono">{{ status.server.javaVersion }}</span>
              </div>
              <div class="kv-item">
                <span class="kv-key">操作系统</span>
                <span class="kv-value">{{ status.server.osName }}</span>
              </div>
              <div class="kv-item">
                <span class="kv-key">CPU 核心</span>
                <span class="kv-value num">{{ status.server.cpuCores }}</span>
              </div>
              <div class="kv-item">
                <span class="kv-key">线程数</span>
                <span class="kv-value num">{{ status.server.threads }}</span>
              </div>
            </div>
          </div>
        </section>

        <!-- 内存 -->
        <section class="wl-panel">
          <header class="wl-panel__head">
            <h3 class="wl-panel__title">内存</h3>
            <span class="wl-panel__hint">JVM 堆占用</span>
          </header>
          <div v-if="status" class="card-body">
            <div class="metric-line">
              <span class="metric-hero num">{{ heapPercent }}<span class="metric-unit">%</span></span>
              <span class="metric-note">已用 / 上限</span>
            </div>
            <el-progress
              :percentage="heapPercent"
              :stroke-width="6"
              :show-text="false"
              :color="heapPercent > 85 ? 'var(--wl-danger)' : heapPercent > 65 ? 'var(--wl-warning)' : 'var(--wl-cyan)'"
            />
            <div class="kv-grid">
              <div class="kv-item">
                <span class="kv-key">已使用</span>
                <span class="kv-value mono">{{ formatBytes(status.server.heapUsedBytes) }}</span>
              </div>
              <div class="kv-item">
                <span class="kv-key">上限</span>
                <span class="kv-value mono">{{ formatBytes(status.server.heapMaxBytes) }}</span>
              </div>
            </div>
          </div>
        </section>

        <!-- 房间与在线 -->
        <section class="wl-panel">
          <header class="wl-panel__head">
            <h3 class="wl-panel__title">房间与在线</h3>
            <span class="wl-panel__hint">内存中房间与连接规模</span>
          </header>
          <div v-if="status" class="card-body">
            <div class="dual-stats">
              <div class="dual-stat">
                <span class="dual-value num">{{ status.rooms.total }}</span>
                <span class="dual-label">房间总数</span>
                <span class="dual-sub">开放 {{ status.rooms.open }}</span>
              </div>
              <div class="dual-stat">
                <span class="dual-value num">{{ status.rooms.online }}</span>
                <span class="dual-label">在线成员</span>
                <span class="dual-sub">实时连接</span>
              </div>
            </div>
            <div class="limit-row">
              <span class="limit-item">
                <span class="wl-dot" :class="status.limits.requireToken ? 'wl-dot--warning' : 'wl-dot--success'" />
                上传{{ status.limits.requireToken ? '需 Token' : '免 Token' }}
              </span>
              <span class="limit-item">
                <span class="wl-dot" :class="status.limits.adminTokenConfigured ? 'wl-dot--success' : 'wl-dot--danger'" />
                管理 Token {{ status.limits.adminTokenConfigured ? '已配置' : '未配置' }}
              </span>
              <span class="limit-item">房间 TTL <span class="mono">{{ settings?.roomTtlDays ?? status.limits.roomTtlDays }} 天</span></span>
            </div>
          </div>
        </section>

        <!-- 业务指标 -->
        <section class="wl-panel">
          <header class="wl-panel__head">
            <h3 class="wl-panel__title">业务指标</h3>
            <span class="wl-panel__hint">累计处理量</span>
          </header>
          <div v-if="status" class="card-body">
            <div class="metric-grid">
              <div v-for="item in metricItems" :key="item.label" class="metric-cell">
                <span class="metric-cell__value num">{{ item.value }}</span>
                <span class="metric-cell__label">{{ item.label }}</span>
              </div>
            </div>
          </div>
        </section>
      </div>

      <div class="ops-lower">
        <!-- 存储 -->
        <section class="wl-panel">
          <header class="wl-panel__head">
            <h3 class="wl-panel__title">存储</h3>
            <span class="wl-panel__hint">数据目录与磁盘空间</span>
          </header>
          <div v-if="status" class="card-body">
            <div class="dir-row">
              <span class="dir-label">数据目录</span>
              <span class="dir-path mono" :title="status.server.dataDir">{{ status.server.dataDir }}</span>
            </div>

            <template v-if="storageBreakdown.total > 0">
              <div class="usage-bar">
                <span
                  v-for="seg in storageBreakdown.rows"
                  :key="seg.key"
                  class="usage-seg"
                  :class="'usage-seg--' + seg.key"
                  :style="{ width: seg.percent + '%' }"
                />
              </div>
              <div class="usage-legend">
                <div v-for="seg in storageBreakdown.rows" :key="seg.key" class="usage-item">
                  <span class="usage-dot" :class="'usage-dot--' + seg.key" />
                  <span class="usage-label">{{ seg.label }}</span>
                  <span class="usage-value mono">{{ formatBytes(seg.bytes) }}</span>
                  <span class="usage-pct num">{{ seg.percent.toFixed(1) }}%</span>
                </div>
              </div>
            </template>
            <p v-else class="empty-line">数据目录为空。</p>

            <div class="disk-row">
              <span class="disk-label">磁盘可用</span>
              <span class="disk-value mono">{{ formatBytes(status.server.diskFreeBytes) }}</span>
              <span class="disk-hint">数据目录合计 {{ formatBytes(storageBreakdown.total) }}</span>
            </div>
          </div>
        </section>

        <!-- 运行时设置 -->
        <section class="wl-panel">
          <header class="wl-panel__head">
            <h3 class="wl-panel__title">运行时设置</h3>
            <span class="wl-panel__hint">修改后立即生效</span>
          </header>
          <div class="card-body">
            <div class="setting-row">
              <div class="setting-copy">
                <span class="setting-label">房间 TTL（天）</span>
                <span class="setting-desc">超过该天数未活跃的房间会被自动清理</span>
              </div>
              <el-input-number v-model="ttl" :min="1" :max="30" :step="1" size="small" controls-position="right" />
            </div>
            <div class="setting-row">
              <div class="setting-copy">
                <span class="setting-label">上传接口要求 Token</span>
                <span class="setting-desc">开启后上传必须携带有效 Token</span>
              </div>
              <el-switch v-model="requireToken" />
            </div>

            <div class="ro-row">
              <span class="ro-label">只读参数</span>
              <div class="ro-chips">
                <span class="ro-chip">管理 Token {{ settings?.adminTokenConfigured ? '已配置' : '未配置' }}</span>
                <span class="ro-chip">服务版本 <span class="mono">{{ settings?.version ?? '—' }}</span></span>
                <span class="ro-chip">Schema 版本 <span class="mono">{{ settings?.schemaVersion ?? '—' }}</span></span>
              </div>
            </div>

            <div class="settings-foot">
              <el-button type="danger" plain :icon="Delete" @click="purgeVisible = true">清理空房</el-button>
              <el-button type="primary" :loading="saving" :disabled="!dirty" @click="saveSettings">保存设置</el-button>
            </div>
          </div>
        </section>
      </div>
    </div>

    <el-dialog v-model="purgeVisible" title="清理空房" width="420px">
      <p class="dialog-hint">将清理指定天数前不再活跃的空闲房间，操作不可恢复。</p>
      <div class="purge-row">
        <span>不活跃超过</span>
        <el-input-number v-model="purgeDays" :min="1" :max="30" :step="1" />
        <span>天</span>
      </div>
      <template #footer>
        <el-button @click="purgeVisible = false">取消</el-button>
        <el-button type="danger" :loading="purging" @click="submitPurge">清理</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.auto-label {
  font-size: 11.5px;
  color: var(--wl-text-3);
}

.ops-stack {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.ops-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
  align-items: start;
}

.ops-lower {
  display: grid;
  grid-template-columns: minmax(0, 1.1fr) minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}

.card-body {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 16px 18px 18px;
}

.version-line {
  display: flex;
  align-items: baseline;
  gap: 10px;
}

.version-value {
  font-size: 22px;
  font-weight: 600;
  color: var(--wl-text);
  letter-spacing: -0.01em;
}

.version-meta {
  font-size: 11.5px;
  color: var(--wl-text-3);
}

.kv-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px 20px;
}

.kv-item {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}

.kv-key {
  font-size: 10.5px;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  color: var(--wl-text-3);
}

.kv-value {
  font-size: 12.5px;
  color: var(--wl-text);
  overflow-wrap: anywhere;
}

.metric-line {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
}

.metric-hero {
  font-size: 26px;
  font-weight: 600;
  line-height: 1;
  color: var(--wl-text);
}

.metric-unit {
  margin-left: 2px;
  font-size: 14px;
  color: var(--wl-text-3);
}

.metric-note {
  font-size: 11.5px;
  color: var(--wl-text-3);
}

.dual-stats {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

.dual-stat {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.dual-value {
  font-size: 26px;
  font-weight: 600;
  line-height: 1.1;
  color: var(--wl-text);
}

.dual-label {
  font-size: 11.5px;
  color: var(--wl-text-3);
}

.dual-sub {
  font-size: 11px;
  color: var(--wl-text-4);
}

.limit-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 16px;
  padding-top: 12px;
  border-top: 1px solid var(--wl-line);
}

.limit-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 11.5px;
  color: var(--wl-text-3);
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px 12px;
}

.metric-cell {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.metric-cell__value {
  overflow: hidden;
  font-size: 18px;
  font-weight: 600;
  line-height: 1.2;
  color: var(--wl-text);
  white-space: nowrap;
  text-overflow: ellipsis;
}

.metric-cell__label {
  font-size: 11px;
  color: var(--wl-text-3);
}

.dir-row {
  display: flex;
  align-items: baseline;
  gap: 12px;
  padding: 10px 12px;
  border: 1px solid var(--wl-line);
  border-radius: var(--wl-radius);
  background: var(--wl-sunken);
}

.dir-label {
  flex: none;
  font-size: 10.5px;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  color: var(--wl-text-3);
}

.dir-path {
  min-width: 0;
  overflow: hidden;
  font-size: 11.5px;
  color: var(--wl-text-2);
  white-space: nowrap;
  text-overflow: ellipsis;
}

.usage-bar {
  display: flex;
  height: 6px;
  overflow: hidden;
  border-radius: 999px;
  background: var(--wl-sunken);
}

.usage-seg {
  height: 100%;
}

.usage-seg--db {
  background: var(--wl-cyan);
}

.usage-seg--photos {
  background: var(--wl-teal);
}

.usage-seg--rooms {
  background: var(--wl-warning);
}

.usage-legend {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.usage-item {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 11.5px;
}

.usage-dot {
  flex: none;
  width: 6px;
  height: 6px;
  border-radius: 50%;
}

.usage-dot--db {
  background: var(--wl-cyan);
}

.usage-dot--photos {
  background: var(--wl-teal);
}

.usage-dot--rooms {
  background: var(--wl-warning);
}

.usage-label {
  color: var(--wl-text-3);
}

.usage-value {
  margin-left: auto;
  color: var(--wl-text-2);
}

.usage-pct {
  width: 52px;
  text-align: right;
  color: var(--wl-text-3);
}

.empty-line {
  margin: 0;
  font-size: 11.5px;
  color: var(--wl-text-4);
}

.disk-row {
  display: flex;
  align-items: baseline;
  gap: 10px;
  padding-top: 12px;
  border-top: 1px solid var(--wl-line);
}

.disk-label {
  font-size: 11.5px;
  color: var(--wl-text-3);
}

.disk-value {
  font-size: 15px;
  font-weight: 600;
  color: var(--wl-text);
}

.disk-hint {
  margin-left: auto;
  font-size: 11px;
  color: var(--wl-text-4);
}

.setting-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.setting-copy {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}

.setting-label {
  font-size: 12px;
  font-weight: 500;
  color: var(--wl-text);
}

.setting-desc {
  font-size: 11px;
  line-height: 1.5;
  color: var(--wl-text-3);
}

.ro-row {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding-top: 12px;
  border-top: 1px solid var(--wl-line);
}

.ro-label {
  font-size: 10.5px;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  color: var(--wl-text-3);
}

.ro-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.ro-chip {
  padding: 3px 8px;
  border: 1px solid var(--wl-line);
  border-radius: 999px;
  background: var(--wl-panel-2);
  font-size: 11px;
  color: var(--wl-text-2);
}

.settings-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-top: 4px;
}

.dialog-hint {
  margin: 0 0 12px;
  font-size: 12px;
  color: var(--wl-text-2);
}

.purge-row {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12px;
  color: var(--wl-text-3);
}

@media (max-width: 1100px) {
  .ops-lower {
    grid-template-columns: minmax(0, 1fr);
  }
}

@media (max-width: 900px) {
  .ops-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .metric-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
