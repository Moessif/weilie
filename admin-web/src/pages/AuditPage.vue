<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Download, Refresh, Search } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import { api } from '@/api'
import type { AuditEvent } from '@/types'
import { formatDateTime } from '@/utils/format'
import { useAppStore } from '@/stores/app'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const appStore = useAppStore()
const isSuper = computed(() => auth.isSuperAdmin)

const levels = [
  { label: '全部等级', value: '' },
  { label: '一般', value: 'info' },
  { label: '成功', value: 'success' },
  { label: '警告', value: 'warning' },
  { label: '危险', value: 'danger' },
]

function levelDotClass(level: AuditEvent['level']): string {
  return `wl-dot--${level}`
}

function auditDate(ts: number): string {
  return formatDateTime(ts).slice(0, 10)
}

function auditTime(ts: number): string {
  const d = new Date(ts)
  if (Number.isNaN(d.getTime())) return '—'
  const pad = (n: number): string => (n < 10 ? `0${n}` : String(n))
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

const levelText: Record<string, string> = {
  info: '一般',
  success: '成功',
  warning: '警告',
  danger: '危险',
}

const loading = ref(false)
const events = ref<AuditEvent[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(30)
const actionOptions = ref<string[]>([])

const query = reactive({
  level: '',
  action: '',
  q: '',
  orgId: undefined as number | undefined,
})

const orgOptions = computed(() => appStore.orgs)

const autoRefresh = ref(true)
let timer: number | null = null

async function loadEvents(): Promise<void> {
  loading.value = true
  try {
    const res = await api.audit.list({
      level: query.level || undefined,
      action: query.action || undefined,
      q: query.q.trim() || undefined,
      orgId: isSuper.value ? query.orgId : auth.user?.orgId ?? undefined,
      page: page.value,
      size: size.value,
    })
    events.value = res.items
    total.value = res.total
    rememberActions(res.items)
  } catch {
    events.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function rememberActions(items: AuditEvent[]): void {
  const merged = new Set(actionOptions.value)
  items.forEach((event) => merged.add(event.action))
  actionOptions.value = [...merged].sort((a, b) => a.localeCompare(b, 'zh-CN'))
}

async function loadActionOptions(): Promise<void> {
  try {
    const res = await api.audit.list({ page: 1, size: 200 })
    actionOptions.value = [...new Set(res.items.map((event) => event.action))].sort((a, b) =>
      a.localeCompare(b, 'zh-CN'),
    )
  } catch {
    actionOptions.value = []
  }
}

function searchEvents(): void {
  page.value = 1
  void loadEvents()
}

function resetQuery(): void {
  query.level = ''
  query.action = ''
  query.q = ''
  query.orgId = undefined
  page.value = 1
  void loadEvents()
}

function onPageChange(): void {
  void loadEvents()
}

function startTimer(): void {
  stopTimer()
  timer = window.setInterval(() => {
    if (autoRefresh.value && !loading.value) void loadEvents()
  }, 15_000)
}

function stopTimer(): void {
  if (timer !== null) {
    window.clearInterval(timer)
    timer = null
  }
}

async function exportCsv(): Promise<void> {
  try {
    await api.audit.exportCsv()
    ElMessage.success('审计日志已开始导出')
  } catch {
    /* client 已统一提示 */
  }
}

watch(autoRefresh, (enabled) => {
  if (enabled) ElMessage.success('已开启自动刷新（15 秒）')
})

onMounted(async () => {
  if (isSuper.value) await appStore.loadOrgs()
  await Promise.all([loadEvents(), loadActionOptions()])
  startTimer()
})

onBeforeUnmount(stopTimer)
</script>

<template>
  <div class="wl-page">
    <PageHeader
      title="审计日志"
      eyebrow="审计日志 · AUDIT"
      description="记录管理站与实测系统的关键操作，用于安全审计与问题追溯；支持按等级、动作与关键词筛选。"
    >
      <template #actions>
        <span class="auto-label">自动刷新</span>
        <el-switch v-model="autoRefresh" />
        <el-button :icon="Download" plain @click="exportCsv">导出 CSV</el-button>
        <el-button :icon="Refresh" plain @click="loadEvents">刷新</el-button>
      </template>
    </PageHeader>

    <div class="wl-panel">
      <div class="wl-toolbar audit-toolbar">
        <el-radio-group v-model="query.level" class="wl-segmented" @change="searchEvents">
          <el-radio-button v-for="level in levels" :key="level.value" :value="level.value">
            {{ level.label }}
          </el-radio-button>
        </el-radio-group>

        <el-select
          v-model="query.action"
          class="action-select"
          placeholder="全部动作"
          clearable
          filterable
          allow-create
          @change="searchEvents"
        >
          <el-option v-for="action in actionOptions" :key="action" :label="action" :value="action" />
        </el-select>

        <el-select
          v-if="isSuper"
          v-model="query.orgId"
          class="org-select"
          placeholder="全部组织"
          clearable
          filterable
          @change="searchEvents"
        >
          <el-option v-for="org in orgOptions" :key="org.id" :label="org.name" :value="org.id" />
        </el-select>

        <el-input
          v-model="query.q"
          class="keyword"
          placeholder="搜索操作人 / 动作 / 详情"
          clearable
          :prefix-icon="Search"
          @keyup.enter="searchEvents"
          @clear="searchEvents"
        />
        <el-button type="primary" plain @click="searchEvents">查询</el-button>
        <el-button text @click="resetQuery">重置</el-button>

        <div class="wl-toolbar__right">
          <span class="count-hint">共 {{ total }} 条</span>
        </div>
      </div>

      <el-table v-loading="loading" :data="events" row-key="id" class="audit-table">
        <el-table-column label="时间" width="118">
          <template #default="{ row }">
            <div class="time-cell">
              <span class="time-date mono">{{ auditDate(row.ts) }}</span>
              <span class="time-clock mono">{{ auditTime(row.ts) }}</span>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="等级" width="88">
          <template #default="{ row }">
            <span class="level-cell">
              <span class="wl-dot" :class="levelDotClass(row.level)" />
              {{ levelText[row.level] ?? row.level }}
            </span>
          </template>
        </el-table-column>

        <el-table-column label="主体" min-width="168">
          <template #default="{ row }">
            <div class="actor-cell">
              <span class="actor-name">{{ row.actorName }}</span>
              <span class="actor-org">{{ row.orgName || '平台' }}</span>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="动作" width="146">
          <template #default="{ row }">
            <span class="action-cell mono">{{ row.action }}</span>
          </template>
        </el-table-column>

        <el-table-column label="详情" min-width="300">
          <template #default="{ row }">
            <el-tooltip :content="row.detail" placement="top" :show-after="200">
              <span class="detail-text">{{ row.detail }}</span>
            </el-tooltip>
          </template>
        </el-table-column>

        <template #empty>
          <EmptyState description="没有符合条件的审计记录" />
        </template>
      </el-table>

      <div class="wl-pagination">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="size"
          :total="total"
          :page-sizes="[20, 30, 50, 100]"
          layout="total, sizes, prev, pager, next"
          background
          @change="onPageChange"
        />
      </div>
    </div>
  </div>
</template>

<style scoped>
.auto-label {
  font-size: 11.5px;
  color: var(--wl-text-3);
}

.audit-toolbar .action-select {
  width: 168px;
}

.audit-toolbar .org-select {
  width: 150px;
}

.audit-toolbar .keyword {
  width: 230px;
}

.count-hint {
  font-size: 11.5px;
  color: var(--wl-text-3);
}

.time-cell {
  display: flex;
  flex-direction: column;
  gap: 1px;
  line-height: 1.35;
}

.time-date {
  font-size: 11.5px;
  color: var(--wl-text-2);
}

.time-clock {
  font-size: 11.5px;
  color: var(--wl-text-3);
}

.level-cell {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 11.5px;
  color: var(--wl-text-2);
}

.actor-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.actor-name {
  font-size: 12px;
  color: var(--wl-text);
}

.actor-org {
  overflow: hidden;
  font-size: 11px;
  color: var(--wl-text-3);
  white-space: nowrap;
  text-overflow: ellipsis;
}

.action-cell {
  font-size: 12px;
  color: var(--wl-text-2);
}

.detail-text {
  display: -webkit-box;
  overflow: hidden;
  font-size: 12px;
  line-height: 1.5;
  color: var(--wl-text-2);
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

@media (max-width: 900px) {
  .audit-toolbar .keyword {
    width: 100%;
  }
}
</style>
