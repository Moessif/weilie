<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ArrowDown,
  CopyDocument,
  Download,
  Refresh,
  Search,
  View,
} from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import { api } from '@/api'
import type {
  RoomDetailResult,
  RoomExportFormat,
  RoomItem,
  RoomSample,
  RoomStatusKey,
} from '@/types'
import { copyText } from '@/utils/csv'
import { formatDateTime, formatRelative } from '@/utils/format'
import { useAppStore } from '@/stores/app'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const appStore = useAppStore()
const isSuper = computed(() => auth.isSuperAdmin)

const statusTabs: { label: string; value: RoomStatusKey | '' }[] = [
  { label: '全部房间', value: '' },
  { label: '在线', value: 'OPEN' },
  { label: '闲置', value: 'IDLE' },
  { label: '已关闭', value: 'CLOSED' },
]

const loading = ref(false)
const rows = ref<RoomItem[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)

const query = reactive({
  status: '' as RoomStatusKey | '',
  orgId: undefined as number | undefined,
  q: '',
})

const orgOptions = computed(() => appStore.orgs)

async function loadRooms(): Promise<void> {
  loading.value = true
  try {
    const res = await api.rooms.list({
      status: query.status || undefined,
      orgId: isSuper.value ? query.orgId : auth.user?.orgId ?? undefined,
      q: query.q.trim() || undefined,
      page: page.value,
      size: size.value,
    })
    rows.value = res.items
    total.value = res.total
  } catch {
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function searchRooms(): void {
  page.value = 1
  void loadRooms()
}

function resetQuery(): void {
  query.q = ''
  query.orgId = undefined
  query.status = ''
  page.value = 1
  void loadRooms()
}

function changeStatus(value: RoomStatusKey | ''): void {
  query.status = value
  searchRooms()
}

function onStatusChange(value: string | number | boolean): void {
  changeStatus(String(value) as RoomStatusKey | '')
}

function onPageChange(): void {
  void loadRooms()
}

async function copyJoinCode(code: string): Promise<void> {
  const ok = await copyText(code)
  if (ok) ElMessage.success(`邀请码 ${code} 已复制`)
  else ElMessage.error('复制失败，请手动选择复制')
}

/* ------------------------------ 详情抽屉 ------------------------------ */

const drawerVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<RoomDetailResult | null>(null)
const samples = ref<RoomSample[]>([])
const canvasRef = ref<HTMLCanvasElement | null>(null)

const statusTagType = computed(() => {
  const status = detail.value?.room.status
  return status === 'OPEN' ? 'success' : status === 'IDLE' ? 'warning' : 'info'
})

async function openDetail(room: RoomItem): Promise<void> {
  drawerVisible.value = true
  detailLoading.value = true
  detail.value = null
  samples.value = []
  try {
    const [roomDetail, sampleRes] = await Promise.all([
      api.rooms.get(room.roomId),
      api.rooms.samples(room.roomId),
    ])
    detail.value = roomDetail
    samples.value = sampleRes.items
    await nextTick()
    drawScatter()
  } catch {
    detail.value = null
  } finally {
    detailLoading.value = false
  }
}

function drawScatter(): void {
  const canvas = canvasRef.value
  if (!canvas) return
  const host = canvas.parentElement
  const width = Math.max(320, host?.clientWidth || 640)
  const height = 280
  const dpr = window.devicePixelRatio || 1
  canvas.width = Math.round(width * dpr)
  canvas.height = Math.round(height * dpr)
  canvas.style.width = `${width}px`
  canvas.style.height = `${height}px`
  const ctx = canvas.getContext('2d')
  if (!ctx) return
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0)

  ctx.clearRect(0, 0, width, height)
  const bg = ctx.createLinearGradient(0, 0, width, height)
  bg.addColorStop(0, '#08121F')
  bg.addColorStop(1, '#060B14')
  ctx.fillStyle = bg
  ctx.fillRect(0, 0, width, height)

  // 经纬网
  ctx.strokeStyle = 'rgba(148,163,184,0.13)'
  ctx.lineWidth = 1
  for (let i = 1; i < 6; i++) {
    const x = (width / 6) * i
    const y = (height / 6) * i
    ctx.beginPath()
    ctx.moveTo(x, 0)
    ctx.lineTo(x, height)
    ctx.stroke()
    ctx.beginPath()
    ctx.moveTo(0, y)
    ctx.lineTo(width, y)
    ctx.stroke()
  }

  const points = samples.value
  if (!points.length) {
    ctx.fillStyle = 'rgba(154,168,189,0.86)'
    ctx.font = '13px system-ui, -apple-system, sans-serif'
    ctx.textAlign = 'center'
    ctx.fillText('该房间暂无可绘制的采样点', width / 2, height / 2)
    return
  }

  const lats = points.map((point) => point.lat)
  const lons = points.map((point) => point.lon)
  const minLat = Math.min(...lats)
  const maxLat = Math.max(...lats)
  const minLon = Math.min(...lons)
  const maxLon = Math.max(...lons)
  const padLat = Math.max((maxLat - minLat) * 0.16, 0.00008)
  const padLon = Math.max((maxLon - minLon) * 0.16, 0.00008)
  const pad = 22
  const project = (lat: number, lon: number): [number, number] => [
    pad + ((lon - (minLon - padLon)) / (maxLon - minLon + padLon * 2)) * (width - pad * 2),
    height - pad - ((lat - (minLat - padLat)) / (maxLat - minLat + padLat * 2)) * (height - pad * 2),
  ]

  // 采样点连线（按时间顺序）
  ctx.strokeStyle = 'rgba(34,211,238,0.24)'
  ctx.lineWidth = 1
  ctx.beginPath()
  points.forEach((point, index) => {
    const [x, y] = project(point.lat, point.lon)
    if (index === 0) ctx.moveTo(x, y)
    else ctx.lineTo(x, y)
  })
  ctx.stroke()

  points.forEach((point) => {
    const [x, y] = project(point.lat, point.lon)
    ctx.beginPath()
    ctx.arc(x, y, point.targetSeen ? 3.4 : 2.4, 0, Math.PI * 2)
    if (point.targetSeen) {
      ctx.fillStyle = '#22D3EE'
      ctx.shadowColor = 'rgba(34,211,238,0.85)'
      ctx.shadowBlur = 8
    } else {
      ctx.fillStyle = 'rgba(94,234,212,0.45)'
      ctx.shadowBlur = 0
    }
    ctx.fill()
    ctx.shadowBlur = 0
  })

  // 角标注
  ctx.fillStyle = 'rgba(102,117,139,0.9)'
  ctx.font = '11px system-ui, -apple-system, sans-serif'
  ctx.textAlign = 'left'
  ctx.fillText(`${minLat.toFixed(5)}, ${minLon.toFixed(5)}`, 8, height - 8)
  ctx.textAlign = 'right'
  ctx.fillText(`${maxLat.toFixed(5)}, ${maxLon.toFixed(5)}`, width - 8, 16)
}

function handleResize(): void {
  if (drawerVisible.value && detail.value) drawScatter()
}

/* ------------------------------ 操作 ------------------------------ */

async function exportRoom(row: RoomItem, format: RoomExportFormat): Promise<void> {
  try {
    await api.rooms.exportFile(row.roomId, format)
    ElMessage.success(`已开始导出 ${format.toUpperCase()} 文件`)
  } catch {
    /* client 已统一提示 */
  }
}

async function closeRoom(row: RoomItem): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认关闭房间「${row.name}」？关闭后成员将无法继续采样，历史数据仍可导出。`,
      '关闭房间',
      { confirmButtonText: '确认关闭', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  await api.rooms.close(row.roomId)
  ElMessage.success('房间已关闭')
  if (detail.value?.room.roomId === row.roomId) drawerVisible.value = false
  await loadRooms()
}

/* ------------------------------ 清理空房 ---------------------------- */

const purgeVisible = ref(false)
const purgeDays = ref(3)
const purging = ref(false)

async function submitPurge(): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `将清理 ${purgeDays.value} 天前不再活跃的空闲房间，清理后不可恢复。确认继续？`,
      '清理空房',
      { confirmButtonText: '确认清理', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  purging.value = true
  try {
    const res = await api.rooms.purge(purgeDays.value, true)
    ElMessage.success(`清理完成，共移除 ${res.removed} 个空闲房间`)
    purgeVisible.value = false
    page.value = 1
    await loadRooms()
  } finally {
    purging.value = false
  }
}

onMounted(async () => {
  window.addEventListener('resize', handleResize)
  if (isSuper.value) await appStore.loadOrgs()
  await loadRooms()
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
})
</script>

<template>
  <div class="wl-page rooms">
    <PageHeader
      title="房间与任务"
      eyebrow="实测房间 · ROOMS"
      description="查看实测房间的成员、采样与任务区概况；可导出采样数据或清理长期不活跃的空房。"
    >
      <template #actions>
        <el-button type="danger" plain @click="purgeVisible = true">清理空房</el-button>
        <el-button :icon="Refresh" plain @click="loadRooms">刷新</el-button>
      </template>
    </PageHeader>

    <div class="wl-panel">
      <div class="wl-toolbar rooms-toolbar">
        <el-radio-group :model-value="query.status" class="wl-segmented" @change="onStatusChange">
          <el-radio-button v-for="tab in statusTabs" :key="tab.value" :value="tab.value">
            {{ tab.label }}
          </el-radio-button>
        </el-radio-group>

        <el-select
          v-if="isSuper"
          v-model="query.orgId"
          class="org-select"
          placeholder="全部组织"
          clearable
          filterable
          @change="searchRooms"
        >
          <el-option v-for="org in orgOptions" :key="org.id" :label="org.name" :value="org.id" />
        </el-select>

        <el-input
          v-model="query.q"
          class="keyword"
          placeholder="搜索房间号 / 名称 / 邀请码"
          clearable
          :prefix-icon="Search"
          @keyup.enter="searchRooms"
          @clear="searchRooms"
        />

        <el-button type="primary" plain @click="searchRooms">查询</el-button>
        <el-button text @click="resetQuery">重置</el-button>

        <div class="wl-toolbar__right">
          <span class="count-hint">共 {{ total }} 个房间</span>
        </div>
      </div>

      <el-table v-loading="loading" :data="rows" row-key="roomId" class="room-table">
        <el-table-column label="房间" min-width="220">
          <template #default="{ row }">
            <div class="room-cell">
              <span class="room-name">{{ row.name }}</span>
              <span class="room-id mono">{{ row.roomId }}</span>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="邀请码" width="132">
          <template #default="{ row }">
            <span class="invite-cell">
              <span class="mono invite-code">{{ row.joinCode }}</span>
              <el-button
                link
                type="primary"
                size="small"
                :icon="CopyDocument"
                @click="copyJoinCode(row.joinCode)"
              />
            </span>
          </template>
        </el-table-column>

        <el-table-column label="组织" min-width="140" show-overflow-tooltip>
          <template #default="{ row }"><span class="cell-dim">{{ row.orgName }}</span></template>
        </el-table-column>

        <el-table-column label="成员数" width="76" align="right">
          <template #default="{ row }"><span class="num">{{ row.members }}</span></template>
        </el-table-column>

        <el-table-column label="采样点" width="92" align="right">
          <template #default="{ row }">
            <span class="num">{{ row.samples.toLocaleString('zh-CN') }}</span>
          </template>
        </el-table-column>

        <el-table-column label="标点数" width="78" align="right">
          <template #default="{ row }"><span class="num cell-dim">{{ row.markers }}</span></template>
        </el-table-column>

        <el-table-column label="任务区数" width="86" align="right">
          <template #default="{ row }"><span class="num cell-dim">{{ row.zones }}</span></template>
        </el-table-column>

        <el-table-column label="状态" width="92">
          <template #default="{ row }">
            <span class="status-cell">
              <span
                class="wl-dot"
                :class="
                  row.status === 'OPEN'
                    ? 'wl-dot--success'
                    : row.status === 'IDLE'
                      ? 'wl-dot--warning'
                      : 'wl-dot--info'
                "
              />
              {{ row.status === 'OPEN' ? '在线' : row.status === 'IDLE' ? '闲置' : '已关闭' }}
            </span>
          </template>
        </el-table-column>

        <el-table-column label="最后活跃" width="118">
          <template #default="{ row }">
            <el-tooltip :content="formatDateTime(row.lastActiveAt)" placement="top" :show-after="200">
              <span class="cell-dim">{{ formatRelative(row.lastActiveAt) }}</span>
            </el-tooltip>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="218" fixed="right" align="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" :icon="View" @click="openDetail(row)">
              详情
            </el-button>
            <el-dropdown trigger="click" @command="(format: RoomExportFormat) => exportRoom(row, format)">
              <el-button link type="primary" size="small" :icon="Download">
                导出<el-icon class="el-icon--right"><ArrowDown /></el-icon>
              </el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="csv">CSV 采样点</el-dropdown-item>
                  <el-dropdown-item command="geojson">GeoJSON</el-dropdown-item>
                  <el-dropdown-item command="kml">KML</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
            <el-button
              v-if="row.status !== 'CLOSED'"
              link
              type="danger"
              size="small"
              @click="closeRoom(row)"
            >
              关闭
            </el-button>
            <span v-else class="cell-dim">—</span>
          </template>
        </el-table-column>

        <template #empty>
          <EmptyState description="没有符合条件的房间" />
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

    <!-- 房间详情 -->
    <el-drawer v-model="drawerVisible" size="640px" :destroy-on-close="true" class="room-drawer">
      <template #header>
        <div class="drawer-head">
          <div class="drawer-head__titles">
            <span class="drawer-head__title">{{ detail?.room.name || '房间详情' }}</span>
            <span class="drawer-head__sub mono">{{ detail?.room.roomId || '' }}</span>
          </div>
          <span v-if="detail" class="status-cell">
            <span
              class="wl-dot"
              :class="
                detail.room.status === 'OPEN'
                  ? 'wl-dot--success'
                  : detail.room.status === 'IDLE'
                    ? 'wl-dot--warning'
                    : 'wl-dot--info'
              "
            />
            {{ detail.room.status === 'OPEN' ? '在线' : detail.room.status === 'IDLE' ? '闲置' : '已关闭' }}
          </span>
        </div>
      </template>

      <div v-loading="detailLoading" class="drawer-body">
        <template v-if="detail">
          <div class="drawer-kv">
            <div class="kv">
              <span class="kv-k">组织</span>
              <span class="kv-v">{{ detail.room.orgName }}</span>
            </div>
            <div class="kv">
              <span class="kv-k">邀请码</span>
              <span class="kv-v kv-v--inline">
                <span class="mono">{{ detail.room.joinCode }}</span>
                <el-button
                  link
                  type="primary"
                  size="small"
                  :icon="CopyDocument"
                  @click="copyJoinCode(detail.room.joinCode)"
                />
              </span>
            </div>
            <div class="kv">
              <span class="kv-k">成员</span>
              <span class="kv-v num">{{ detail.room.members }} 人</span>
            </div>
            <div class="kv">
              <span class="kv-k">采样点</span>
              <span class="kv-v num">{{ detail.room.samples.toLocaleString('zh-CN') }} 点</span>
            </div>
            <div class="kv">
              <span class="kv-k">标点 / 任务区</span>
              <span class="kv-v num">{{ detail.room.markers }} / {{ detail.room.zones }}</span>
            </div>
            <div class="kv">
              <span class="kv-k">最后活跃</span>
              <span class="kv-v mono">{{ formatDateTime(detail.room.lastActiveAt) }}</span>
            </div>
          </div>

          <section class="drawer-section">
            <div class="section-head">
              <span class="wl-label">采样散点</span>
              <span class="section-note">近 {{ samples.length }} 条 · 经纬度自适应缩放</span>
            </div>
            <div class="scatter-wrap">
              <canvas ref="canvasRef" class="scatter-canvas" />
            </div>
            <div class="scatter-legend">
              <span><i class="legend-dot legend-dot--target" />发现目标</span>
              <span><i class="legend-dot legend-dot--normal" />未发现</span>
            </div>
          </section>

          <section class="drawer-section">
            <div class="section-head">
              <span class="wl-label">成员</span>
              <span class="section-note">{{ detail.members.length }} 人</span>
            </div>
            <el-table :data="detail.members" size="small" row-key="userId">
              <el-table-column label="成员" min-width="150">
                <template #default="{ row }">
                  <span class="member-cell">
                    <span class="wl-dot" :class="row.online ? 'wl-dot--success' : 'wl-dot--info'" />
                    <span>{{ row.name }}</span>
                    <span class="member-role">{{ row.role }}</span>
                  </span>
                </template>
              </el-table-column>
              <el-table-column label="坐标" min-width="160">
                <template #default="{ row }">
                  <span class="mono cell-dim">{{ row.lat.toFixed(5) }}, {{ row.lon.toFixed(5) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="最近在线" width="104" align="right">
                <template #default="{ row }">
                  <span class="cell-dim">{{ formatRelative(row.lastSeenAt) }}</span>
                </template>
              </el-table-column>
            </el-table>
          </section>

          <section class="drawer-section">
            <div class="section-head">
              <span class="wl-label">任务区与标点</span>
              <span class="section-note">{{ detail.zones.length }} 个任务区 · {{ detail.markers.length }} 个标点</span>
            </div>
            <div class="chip-list">
              <el-tag v-for="zone in detail.zones" :key="zone.id" effect="plain" size="small">
                {{ zone.kind }} · {{ zone.radiusM }}m
              </el-tag>
              <span v-if="!detail.zones.length" class="section-note">暂无任务区</span>
            </div>
            <el-table v-if="detail.markers.length" :data="detail.markers" size="small" row-key="id">
              <el-table-column label="标点" prop="name" width="104" />
              <el-table-column label="类型" width="112">
                <template #default="{ row }">
                  <el-tag size="small" effect="plain">{{ row.kind }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="标记人" prop="byName" width="112" />
              <el-table-column label="时间" align="right">
                <template #default="{ row }">
                  <span class="mono cell-dim">{{ formatDateTime(row.ts) }}</span>
                </template>
              </el-table-column>
            </el-table>
            <EmptyState v-else description="暂无标点" :size="52" />
          </section>

          <section class="drawer-section">
            <div class="section-head">
              <span class="wl-label">导出</span>
              <span class="section-note">按当前房间导出采样数据</span>
            </div>
            <div class="export-row">
              <el-button text :icon="Download" @click="exportRoom(detail.room, 'csv')">CSV</el-button>
              <el-button text :icon="Download" @click="exportRoom(detail.room, 'geojson')">GeoJSON</el-button>
              <el-button text :icon="Download" @click="exportRoom(detail.room, 'kml')">KML</el-button>
            </div>
          </section>

          <div class="danger-zone">
            <div class="danger-copy">
              <span class="danger-title">关闭房间</span>
              <span class="danger-desc">关闭后成员将无法继续采样，历史数据仍可导出。</span>
            </div>
            <el-button
              type="danger"
              plain
              :disabled="detail.room.status === 'CLOSED'"
              @click="closeRoom(detail.room)"
            >
              关闭房间
            </el-button>
          </div>
        </template>
        <EmptyState v-else-if="!detailLoading" description="房间详情加载失败" />
      </div>
    </el-drawer>

    <!-- 清理空房 -->
    <el-dialog v-model="purgeVisible" title="清理空房" width="420px">
      <p class="dialog-hint">将删除长期不活跃（非在线）的空闲房间及其标点，历史采样数据不可恢复。</p>
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
.rooms-toolbar .org-select {
  width: 168px;
}

.rooms-toolbar .keyword {
  width: 240px;
}

.count-hint {
  font-size: 11.5px;
  color: var(--wl-text-3);
}

.room-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.room-name {
  overflow: hidden;
  font-size: 12.5px;
  font-weight: 500;
  color: var(--wl-text);
  white-space: nowrap;
  text-overflow: ellipsis;
}

.room-id {
  font-size: 11px;
  color: var(--wl-text-3);
}

.invite-cell {
  display: inline-flex;
  align-items: center;
  gap: 2px;
}

.invite-code {
  font-size: 12px;
  color: var(--wl-text-2);
}

.cell-dim {
  font-size: 11.5px;
  color: var(--wl-text-3);
}

.status-cell {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 11.5px;
  color: var(--wl-text-2);
}

.drawer-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
}

.drawer-head__titles {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.drawer-head__title {
  overflow: hidden;
  font-size: 15px;
  font-weight: 600;
  color: var(--wl-text);
  white-space: nowrap;
  text-overflow: ellipsis;
}

.drawer-head__sub {
  font-size: 11px;
  color: var(--wl-text-3);
}

.drawer-body {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.drawer-kv {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px 20px;
}

.kv {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}

.kv-k {
  font-size: 10.5px;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  color: var(--wl-text-3);
}

.kv-v {
  font-size: 12.5px;
  color: var(--wl-text);
  overflow-wrap: anywhere;
}

.kv-v--inline {
  display: inline-flex;
  align-items: center;
  gap: 2px;
}

.drawer-section {
  display: flex;
  flex-direction: column;
  gap: 10px;
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

.scatter-wrap {
  overflow: hidden;
  border: 1px solid var(--wl-line);
  border-radius: var(--wl-radius);
  background: var(--wl-sunken);
}

.scatter-canvas {
  display: block;
}

.scatter-legend {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 14px;
  font-size: 11px;
  color: var(--wl-text-3);
}

.legend-dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  margin-right: 5px;
  border-radius: 50%;
}

.legend-dot--target {
  background: var(--wl-cyan);
}

.legend-dot--normal {
  background: var(--wl-teal);
}

.member-cell {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--wl-text);
}

.member-role {
  font-size: 10.5px;
  color: var(--wl-text-4);
}

.chip-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.export-row {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 8px;
}

.danger-zone {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 14px;
  border: 1px solid var(--wl-line);
  border-radius: var(--wl-radius);
  background: var(--wl-sunken);
}

.danger-copy {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.danger-title {
  font-size: 12px;
  font-weight: 500;
  color: var(--wl-text);
}

.danger-desc {
  font-size: 11px;
  color: var(--wl-text-3);
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

@media (max-width: 900px) {
  .rooms-toolbar .keyword {
    width: 100%;
  }

  .drawer-kv {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
