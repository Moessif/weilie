<script setup lang="ts">
import { onMounted, reactive, ref, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { CopyDocument, Refresh, Search } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import StatCard from '@/components/StatCard.vue'
import EmptyState from '@/components/EmptyState.vue'
import { api } from '@/api'
import type { ActivationCode, CodeStatus } from '@/types'
import { copyText } from '@/utils/csv'
import { formatDateTime, statusTagType, statusText } from '@/utils/format'
import { useAuthStore } from '@/stores/auth'
import { useAppStore } from '@/stores/app'

const auth = useAuthStore()
const appStore = useAppStore()
const isSuper = computed(() => auth.isSuperAdmin)

const loading = ref(false)
const codes = ref<ActivationCode[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(10)

const query = reactive({
  orgId: undefined as number | undefined,
  status: 'UNUSED' as CodeStatus | '',
  q: '',
})

const statusOptions: { label: string; value: CodeStatus | '' }[] = [
  { label: '全部状态', value: '' },
  { label: '未使用', value: 'UNUSED' },
  { label: '已使用', value: 'USED' },
  { label: '已过期', value: 'EXPIRED' },
  { label: '已作废', value: 'REVOKED' },
]

const orgOptions = computed(() => appStore.orgs)

const counts = reactive<Record<string, number>>({
  UNUSED: 0,
  USED: 0,
  EXPIRED: 0,
  REVOKED: 0,
})

async function loadCodes(): Promise<void> {
  loading.value = true
  try {
    const res = await api.codes.list({
      orgId: query.orgId,
      status: query.status || undefined,
      q: query.q.trim() || undefined,
      page: page.value,
      size: size.value,
    })
    codes.value = res.items
    total.value = res.total
  } catch {
    codes.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

async function loadCounts(): Promise<void> {
  const statuses = ['UNUSED', 'USED', 'EXPIRED', 'REVOKED'] as const
  const results = await Promise.allSettled(
    statuses.map((status) =>
      api.codes.list({ status, orgId: query.orgId, page: 1, size: 1 }),
    ),
  )
  results.forEach((result, index) => {
    const key = statuses[index]
    counts[key] = result.status === 'fulfilled' ? result.value.total : 0
  })
}

function searchCodes(): void {
  page.value = 1
  void loadCodes()
}

function resetQuery(): void {
  query.q = ''
  query.status = 'UNUSED'
  if (isSuper.value) query.orgId = undefined
  page.value = 1
  void Promise.all([loadCodes(), loadCounts()])
}

function onPageChange(): void {
  void loadCodes()
}

async function copyCode(code: string): Promise<void> {
  const ok = await copyText(code)
  if (ok) ElMessage.success(`激活码 ${code} 已复制`)
  else ElMessage.error('复制失败，请手动选择复制')
}

async function revokeCode(code: ActivationCode): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认作废激活码 ${code.code}？该码将不能再次用于激活「${code.displayName}」的账号。`,
      '作废激活码',
      { confirmButtonText: '确认作废', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  await api.codes.revoke(code.id)
  ElMessage.success('激活码已作废')
  await Promise.all([loadCodes(), loadCounts()])
}

onMounted(async () => {
  if (isSuper.value) {
    await appStore.loadOrgs()
  } else if (auth.user?.orgId) {
    query.orgId = auth.user.orgId
  }
  await Promise.all([loadCodes(), loadCounts()])
})
</script>

<template>
  <div class="wl-page codes">
    <PageHeader
      eyebrow="运营管理"
      title="激活码"
      description="查看组织成员的一次性激活码，支持按状态与关键词筛选；未使用的激活码可随时作废。"
    >
      <template #actions>
        <el-button :icon="Refresh" plain @click="loadCodes(); loadCounts()">刷新</el-button>
      </template>
    </PageHeader>

    <div class="stat-grid">
      <div class="stat-click" @click="query.status = 'UNUSED'; searchCodes()">
        <StatCard label="未使用" :value="String(counts.UNUSED)" hint="可下发给待激活用户" accent="cyan" />
      </div>
      <div class="stat-click" @click="query.status = 'USED'; searchCodes()">
        <StatCard label="已使用" :value="String(counts.USED)" hint="已绑定账号与设备" accent="teal" />
      </div>
      <div class="stat-click" @click="query.status = 'EXPIRED'; searchCodes()">
        <StatCard label="已过期" :value="String(counts.EXPIRED)" hint="需重新签发" accent="warning" />
      </div>
      <div class="stat-click" @click="query.status = 'REVOKED'; searchCodes()">
        <StatCard label="已作废" :value="String(counts.REVOKED)" hint="已手动撤销" accent="danger" />
      </div>
    </div>

    <div class="wl-panel">
      <div class="wl-toolbar">
        <el-select
          v-if="isSuper"
          v-model="query.orgId"
          placeholder="全部组织"
          clearable
          filterable
          class="org-select"
          @change="searchCodes(); loadCounts()"
        >
          <el-option v-for="org in orgOptions" :key="org.id" :label="org.name" :value="org.id" />
        </el-select>
        <el-select v-model="query.status" placeholder="激活码状态" class="status-select" @change="searchCodes">
          <el-option
            v-for="option in statusOptions"
            :key="option.label"
            :label="option.label"
            :value="option.value"
          />
        </el-select>
        <el-input
          v-model="query.q"
          placeholder="搜索激活码 / 用户名 / 姓名"
          clearable
          :prefix-icon="Search"
          class="keyword"
          @keyup.enter="searchCodes"
          @clear="searchCodes"
        />
        <el-button type="primary" plain @click="searchCodes">查询</el-button>
        <el-button :icon="Refresh" text @click="resetQuery">重置</el-button>
        <div class="wl-toolbar__right">
          <span class="toolbar-hint">共 {{ total }} 个激活码</span>
        </div>
      </div>

      <el-table v-loading="loading" :data="codes" row-key="id" class="code-table">
        <el-table-column label="激活码" min-width="212">
          <template #default="{ row }">
            <span class="code-cell">
              <span class="code-cell__value mono">{{ row.code }}</span>
              <el-button
                link
                size="small"
                :icon="CopyDocument"
                title="复制激活码"
                @click="copyCode(row.code)"
              />
            </span>
          </template>
        </el-table-column>
        <el-table-column label="组织" min-width="136" show-overflow-tooltip>
          <template #default="{ row }"><span class="dim">{{ row.orgName }}</span></template>
        </el-table-column>
        <el-table-column label="用途" width="96">
          <template #default="{ row }">
            <el-tag
              size="small"
              effect="plain"
              :type="row.purpose === 'RESET_PASSWORD' ? 'warning' : 'info'"
            >
              {{ statusText(row.purpose) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="96" align="center">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" size="small" effect="dark">
              {{ statusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="有效期 / 使用时间" width="172">
          <template #default="{ row }">
            <span v-if="row.status === 'USED'" class="dim mono">
              {{ formatDateTime(row.usedAt) }}
            </span>
            <span
              v-else
              class="mono"
              :class="row.status === 'UNUSED' ? 'expire-soon' : 'dim'"
            >
              {{ formatDateTime(row.expiresAt) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="使用者" min-width="152">
          <template #default="{ row }">
            <div class="user-cell">
              <span class="user-cell__name">{{ row.displayName }}</span>
              <span class="user-cell__account mono">@{{ row.username }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="146">
          <template #default="{ row }">
            <span class="dim mono">{{ formatDateTime(row.createdAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="88" fixed="right" align="right">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'UNUSED'"
              link
              type="danger"
              size="small"
              @click="revokeCode(row)"
            >
              作废
            </el-button>
            <span v-else class="dim">—</span>
          </template>
        </el-table-column>
        <template #empty>
          <EmptyState description="没有匹配的激活码" />
        </template>
      </el-table>

      <div class="wl-pagination">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="size"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          background
          @change="onPageChange"
        />
      </div>
    </div>
  </div>
</template>

<style scoped>
.codes .org-select {
  width: 190px;
}

.codes .status-select {
  width: 140px;
}

.codes .keyword {
  width: 240px;
}

.toolbar-hint {
  font-size: 12px;
  color: var(--wl-text-3);
}

.stat-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--wl-gap);
  margin-bottom: var(--wl-gap);
}

.stat-click {
  cursor: pointer;
  border-radius: var(--wl-radius-lg);
}

.code-table {
  background: transparent;
}

.code-cell {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.code-cell__value {
  font-size: 16px;
  letter-spacing: 0.04em;
  color: var(--wl-text);
}

.user-cell {
  display: flex;
  flex-direction: column;
  line-height: 1.4;
  min-width: 0;
}

.user-cell__name {
  font-size: 12.5px;
  color: var(--wl-text);
}

.user-cell__account {
  font-size: 10.5px;
  color: var(--wl-text-3);
}

.expire-soon {
  color: var(--wl-warning);
}

@media (max-width: 1180px) {
  .stat-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 900px) {
  .codes .keyword {
    width: 100%;
  }
}

@media (max-width: 620px) {
  .stat-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
