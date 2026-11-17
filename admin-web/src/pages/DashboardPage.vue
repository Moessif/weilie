<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  Connection,
  House,
  OfficeBuilding,
  Refresh,
  TrendCharts,
  User,
} from '@element-plus/icons-vue'
import type { EChartsOption } from 'echarts'
import PageHeader from '@/components/PageHeader.vue'
import StatCard from '@/components/StatCard.vue'
import ChartCard from '@/components/ChartCard.vue'
import BaseChart from '@/components/BaseChart.vue'
import EmptyState from '@/components/EmptyState.vue'
import { api } from '@/api'
import type {
  AuditEvent,
  DashboardSummary,
  HeatCell,
  RangeKey,
  RecentRoom,
  RoomStatusCount,
  StatusCount,
  TimePoint,
  TopOrgItem,
} from '@/types'
import {
  formatCompact,
  formatDuration,
  formatNumber,
  formatRelative,
  formatTime,
  statusText,
} from '@/utils/format'
import { areaGradient, barGradient, baseGrid, timeLabel } from '@/utils/chart'

const router = useRouter()

const loading = ref(true)
const refreshing = ref(false)
const lastUpdated = ref<number>(0)

const summary = ref<DashboardSummary | null>(null)
const trendRange = ref<RangeKey>('24h')
const trendPoints = ref<TimePoint[]>([])
const onlinePoints = ref<TimePoint[]>([])
const wsPoints = ref<TimePoint[]>([])
const topOrgs = ref<TopOrgItem[]>([])
const heatCells = ref<HeatCell[]>([])
const userStatus = ref<StatusCount[]>([])
const roomStatus = ref<RoomStatusCount[]>([])
const recentRooms = ref<RecentRoom[]>([])
const auditEvents = ref<AuditEvent[]>([])

const kpi = computed(() => summary.value?.kpi ?? null)

const headerDesc = computed(() => {
  const server = summary.value?.server
  if (!server) return '组织、成员、房间与采样态势总览；数据每 30 秒自动刷新。'
  return `服务端 v${server.version} · 已运行 ${formatDuration(server.uptimeSec)} · 数据每 30 秒自动刷新`
})

const updatedLabel = computed(() => (lastUpdated.value ? `更新于 ${formatTime(lastUpdated.value)}` : ''))

/* ------------------------------ 数据加载 ------------------------------ */

async function loadSummary(): Promise<void> {
  const res = await api.dashboard.summary().catch(() => null)
  if (res) summary.value = res
}

async function loadTrend(): Promise<void> {
  const res = await api.dashboard.timeseries('samples', trendRange.value).catch(() => null)
  trendPoints.value = res?.points ?? []
}

async function loadOnlineWs(): Promise<void> {
  const [online, ws] = await Promise.all([
    api.dashboard.timeseries('online', '24h').catch(() => null),
    api.dashboard.timeseries('ws', '24h').catch(() => null),
  ])
  onlinePoints.value = online?.points ?? []
  wsPoints.value = ws?.points ?? []
}

async function loadAudit(): Promise<void> {
  const res = await api.audit.list({ page: 1, size: 20 }).catch(() => null)
  auditEvents.value = res?.items ?? []
}

async function loadAll(): Promise<void> {
  loading.value = true
  try {
    await Promise.allSettled([
      loadSummary(),
      loadTrend(),
      loadOnlineWs(),
      (async () => {
        const res = await api.dashboard.topOrgs(24).catch(() => null)
        topOrgs.value = res?.items ?? []
      })(),
      (async () => {
        const res = await api.dashboard.heatmap(7).catch(() => null)
        heatCells.value = res?.cells ?? []
      })(),
      (async () => {
        const res = await api.dashboard.userStatus().catch(() => null)
        userStatus.value = res?.items ?? []
      })(),
      (async () => {
        const res = await api.dashboard.roomStatus().catch(() => null)
        roomStatus.value = res?.items ?? []
      })(),
      (async () => {
        const res = await api.dashboard.recentRooms().catch(() => null)
        recentRooms.value = res?.items ?? []
      })(),
      loadAudit(),
    ])
    lastUpdated.value = Date.now()
  } finally {
    loading.value = false
  }
}

async function loadLive(): Promise<void> {
  await Promise.allSettled([loadSummary(), loadOnlineWs()])
  lastUpdated.value = Date.now()
}

async function refreshAll(): Promise<void> {
  refreshing.value = true
  try {
    await loadAll()
    ElMessage.success('仪表盘数据已刷新')
  } finally {
    refreshing.value = false
  }
}

let liveTimer: number | undefined
let auditTimer: number | undefined

onMounted(() => {
  void loadAll()
  liveTimer = window.setInterval(() => void loadLive(), 30_000)
  auditTimer = window.setInterval(() => void loadAudit(), 15_000)
})

onBeforeUnmount(() => {
  if (liveTimer) window.clearInterval(liveTimer)
  if (auditTimer) window.clearInterval(auditTimer)
})

watch(trendRange, () => void loadTrend())

/* ------------------------------ 图表配置 ------------------------------ */

function axisInterval(length: number): number {
  return Math.max(0, Math.floor(length / 6) - 1)
}

const trendOption = computed<EChartsOption>(() => {
  const points = trendPoints.value
  return {
    grid: baseGrid({ top: 22, bottom: 4, left: 4, right: 16 }),
    tooltip: { trigger: 'axis' },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: points.map((p) => timeLabel(p.ts, trendRange.value)),
      axisLabel: { interval: axisInterval(points.length), color: '#7E8A9C', fontSize: 11 },
    },
    yAxis: {
      type: 'value',
      splitLine: { lineStyle: { color: 'rgba(255,255,255,0.055)', type: 'dashed' } },
      axisLabel: { color: '#7E8A9C', fontSize: 11, formatter: (value: number) => formatCompact(value) },
    },
    series: [
      {
        name: '采样点',
        type: 'line',
        smooth: true,
        showSymbol: false,
        lineStyle: { width: 1.6, color: '#22D3EE' },
        itemStyle: { color: '#22D3EE' },
        areaStyle: { color: areaGradient('#22D3EE', '3A') },
        data: points.map((p) => p.value),
      },
    ],
  }
})

const onlineWsOption = computed<EChartsOption>(() => {
  const online = onlinePoints.value
  const ws = wsPoints.value
  return {
    grid: { left: 4, right: 4, top: 20, bottom: 28, containLabel: true },
    tooltip: { trigger: 'axis' },
    legend: {
      bottom: 0,
      left: 0,
      itemWidth: 8,
      itemHeight: 8,
      icon: 'circle',
      itemGap: 14,
      textStyle: { color: '#9AA8BD', fontSize: 11 },
      data: ['在线成员', 'WS 连接'],
    },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: online.map((p) => timeLabel(p.ts, '24h')),
      axisLabel: { interval: axisInterval(online.length), color: '#7E8A9C', fontSize: 10 },
    },
    yAxis: [
      {
        type: 'value',
        axisLabel: { color: '#7E8A9C', fontSize: 10 },
        splitLine: { lineStyle: { color: 'rgba(255,255,255,0.055)', type: 'dashed' } },
      },
      {
        type: 'value',
        axisLabel: { color: '#7E8A9C', fontSize: 10 },
        splitLine: { show: false },
      },
    ],
    series: [
      {
        name: '在线成员',
        type: 'line',
        yAxisIndex: 0,
        smooth: true,
        showSymbol: false,
        lineStyle: { width: 1.6, color: '#22D3EE' },
        itemStyle: { color: '#22D3EE' },
        areaStyle: { color: areaGradient('#22D3EE', '24') },
        data: online.map((p) => p.value),
      },
      {
        name: 'WS 连接',
        type: 'line',
        yAxisIndex: 1,
        smooth: true,
        showSymbol: false,
        lineStyle: { width: 1.4, color: '#5EEAD4', type: 'dashed' },
        itemStyle: { color: '#5EEAD4' },
        data: ws.map((p) => p.value),
      },
    ],
  }
})

const topOrgsOption = computed<EChartsOption>(() => ({
  grid: baseGrid({ top: 16, bottom: 26, left: 4, right: 8 }),
  tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
  xAxis: {
    type: 'category',
    data: topOrgs.value.map((item) => item.orgName),
    axisLabel: {
      color: '#7E8A9C',
      fontSize: 10,
      interval: 0,
      margin: 8,
    },
    axisTick: { show: false },
  },
  yAxis: {
    type: 'value',
    splitLine: { lineStyle: { color: 'rgba(255,255,255,0.05)', type: 'dashed' } },
    axisLabel: { color: '#7E8A9C', fontSize: 10, formatter: (value: number) => formatCompact(value) },
  },
  series: [
    {
      name: '采样点',
      type: 'bar',
      barWidth: 16,
      itemStyle: { color: barGradient('#22D3EE'), borderRadius: [3, 3, 0, 0] },
      data: topOrgs.value.map((item) => item.samples),
    },
  ],
}))

const USER_STATUS_META: { key: string; color: string }[] = [
  { key: 'ACTIVE', color: '#22D3EE' },
  { key: 'PENDING', color: '#F0B429' },
  { key: 'SUSPENDED', color: '#F2705F' },
  { key: 'LOCKED', color: '#5B6675' },
]

const userStatusMap = computed(() => {
  const map: Record<string, number> = {}
  for (const item of userStatus.value) map[item.status] = item.count
  return map
})

const userLegend = computed(() =>
  USER_STATUS_META.map((meta) => ({
    key: meta.key,
    label: statusText(meta.key),
    color: meta.color,
    count: userStatusMap.value[meta.key] ?? 0,
  })),
)

const userTotal = computed(() => Object.values(userStatusMap.value).reduce((sum, n) => sum + n, 0))

const userDonutOption = computed<EChartsOption>(() => ({
  tooltip: { trigger: 'item' },
  series: [
    {
      name: '用户状态',
      type: 'pie',
      radius: ['62%', '84%'],
      center: ['50%', '50%'],
      avoidLabelOverlap: true,
      label: { show: false },
      labelLine: { show: false },
      itemStyle: { borderColor: '#12161D', borderWidth: 2 },
      data: userLegend.value.map((item) => ({
        name: item.label,
        value: item.count,
        itemStyle: { color: item.color },
      })),
    },
  ],
}))

const ROOM_STATUS_META: { key: string; label: string; color: string }[] = [
  { key: 'WITH_ZONE', label: '有任务区', color: '#22D3EE' },
  { key: 'POINT_ONLY', label: '仅有标点', color: '#5EEAD4' },
  { key: 'EMPTY', label: '空房', color: '#F0B429' },
  { key: 'CLOSED', label: '已关闭', color: '#F2705F' },
]

const roomStatusRows = computed(() => {
  const map: Record<string, number> = {}
  for (const item of roomStatus.value) map[item.key] = item.count
  const total = Object.values(map).reduce((sum, n) => sum + n, 0) || 1
  return ROOM_STATUS_META.map((meta) => {
    const count = map[meta.key] ?? 0
    return { ...meta, count, percent: Math.round((count / total) * 100) }
  })
})

const roomTotal = computed(() => roomStatusRows.value.reduce((sum, row) => sum + row.count, 0))

const weekdayLabels = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']
const hourLabels = Array.from({ length: 24 }, (_, i) => `${i}`.padStart(2, '0'))

const HEAT_STOPS: Array<[number, [number, number, number]]> = [
  [0, [13, 16, 22]],
  [0.3, [16, 74, 86]],
  [0.58, [20, 124, 145]],
  [0.84, [34, 211, 238]],
  [1, [140, 240, 255]],
]

function heatColor(value: number, max: number): string {
  const t = max > 0 ? Math.min(1, Math.max(0, value / max)) : 0
  let from = HEAT_STOPS[0]
  let to = HEAT_STOPS[HEAT_STOPS.length - 1]
  for (let i = 1; i < HEAT_STOPS.length; i++) {
    if (t <= HEAT_STOPS[i][0]) {
      from = HEAT_STOPS[i - 1]
      to = HEAT_STOPS[i]
      break
    }
  }
  const span = to[0] - from[0] || 1
  const k = (t - from[0]) / span
  const rgb = from[1].map((c, i) => Math.round(c + (to[1][i] - c) * k))
  return `rgb(${rgb[0]}, ${rgb[1]}, ${rgb[2]})`
}

const heatMax = computed(() => heatCells.value.reduce((max, cell) => Math.max(max, cell.value), 0))

const heatmapOption = computed<EChartsOption>(() => {
  const map = new Map<string, number>()
  let max = 0
  for (const cell of heatCells.value) {
    map.set(`${cell.weekday}-${cell.hour}`, cell.value)
    if (cell.value > max) max = cell.value
  }
  const data: Array<{ value: [number, number, number]; itemStyle: { color: string } }> = []
  for (let weekday = 0; weekday < 7; weekday++) {
    for (let hour = 0; hour < 24; hour++) {
      const value = map.get(`${weekday}-${hour}`) ?? 0
      data.push({
        value: [hour, weekday, value],
        itemStyle: { color: heatColor(value, max) },
      })
    }
  }
  return {
    grid: { left: 4, right: 8, top: 4, bottom: 4, containLabel: true },
    tooltip: {
      position: 'top',
      formatter: (params: unknown) => {
        const p = params as { value: [number, number, number] }
        return `${weekdayLabels[p.value[1]]} ${hourLabels[p.value[0]]}:00 · ${formatNumber(p.value[2])}`
      },
    },
    xAxis: {
      type: 'category',
      data: hourLabels,
      splitArea: { show: false },
      axisLabel: { color: '#7E8A9C', fontSize: 10, interval: 2 },
      axisLine: { lineStyle: { color: 'rgba(255,255,255,0.12)' } },
      axisTick: { show: false },
    },
    yAxis: {
      type: 'category',
      data: weekdayLabels,
      inverse: true,
      axisLabel: { color: '#7E8A9C', fontSize: 10 },
      axisLine: { show: false },
      axisTick: { show: false },
    },
    series: [
      {
        name: '采样活跃度',
        type: 'heatmap',
        data,
        itemStyle: { borderColor: '#0A0C11', borderWidth: 2, borderRadius: 2 },
        emphasis: {
          itemStyle: {
            borderColor: 'rgba(255,255,255,0.6)',
            borderWidth: 1,
            shadowBlur: 8,
            shadowColor: 'rgba(34,211,238,0.6)',
          },
        },
      },
    ],
  }
})

const AUDIT_DOT: Record<AuditEvent['level'], string> = {
  info: '#7BA7D7',
  success: '#3ECF8E',
  warning: '#F0B429',
  danger: '#F2705F',
}

const ROOM_DOT: Record<string, string> = {
  OPEN: '#22D3EE',
  IDLE: '#5B6675',
  CLOSED: '#F2705F',
}

/* ------------------------------ 展示辅助 ------------------------------ */

function num(value: number | undefined | null): string {
  return value === null || value === undefined ? '—' : formatNumber(value)
}

function deltaText(value: number | undefined | null, suffix = ''): string {
  if (value === null || value === undefined) return ''
  return `${value > 0 ? '+' : ''}${value}${suffix}`
}

function deltaTone(value: number | undefined | null): 'up' | 'down' | 'flat' {
  if (!value) return 'flat'
  return value > 0 ? 'up' : 'down'
}

function goRooms(): void {
  router.push('/rooms')
}
</script>
<template>
  <div class="wl-page dash">
    <PageHeader eyebrow="运营管理" title="仪表盘" :description="headerDesc">
      <template #actions>
        <span class="dash__stamp">
          <span class="wl-dot wl-dot--active" />
          {{ updatedLabel || '正在加载' }}
        </span>
        <el-button :icon="Refresh" :loading="refreshing" plain @click="refreshAll">刷新</el-button>
      </template>
    </PageHeader>

    <div v-loading="loading" class="dash__body">
      <!-- 关键指标 -->
      <div class="kpi-grid">
        <StatCard
          label="组织"
          :value="num(kpi?.orgs)"
          :delta="deltaText(kpi?.orgsDelta, ' 本周')"
          :delta-tone="deltaTone(kpi?.orgsDelta)"
          :icon="OfficeBuilding"
        />
        <StatCard
          label="用户"
          :value="num(kpi?.users)"
          :hint="kpi ? `已激活 ${formatNumber(kpi.activeUsers)} · 待激活 ${formatNumber(kpi.pendingUsers)}` : ''"
          :icon="User"
          accent="teal"
        />
        <StatCard
          label="在线成员"
          :value="num(kpi?.onlineMembers)"
          :hint="kpi ? `峰值 ${formatNumber(kpi.onlinePeak)} · 实时` : ''"
          :icon="Connection"
        />
        <StatCard
          label="房间"
          :value="num(kpi?.rooms)"
          :hint="kpi ? `活跃 ${formatNumber(kpi.activeRooms)} · TTL 3 天` : ''"
          :icon="House"
          accent="teal"
        />
        <StatCard
          label="今日采样点"
          :value="num(kpi?.todaySamples)"
          :delta="deltaText(kpi?.samplesDeltaPct, '% 较昨日')"
          :delta-tone="deltaTone(kpi?.samplesDeltaPct)"
          :icon="TrendCharts"
        />
      </div>

      <!-- 趋势 -->
      <div class="grid grid--trend">
        <ChartCard
          title="采样点趋势"
          :subtitle="`近 ${trendRange === '24h' ? '24 小时 · 1 小时粒度' : trendRange === '7d' ? '7 天 · 6 小时粒度' : '30 天 · 1 天粒度'}`"
          :empty="!trendPoints.length"
          empty-text="暂无采样趋势数据"
        >
          <template #extra>
            <el-radio-group v-model="trendRange" size="small" class="wl-segmented">
              <el-radio-button value="24h">24h</el-radio-button>
              <el-radio-button value="7d">7d</el-radio-button>
              <el-radio-button value="30d">30d</el-radio-button>
            </el-radio-group>
          </template>
          <BaseChart :option="trendOption" height="248px" />
        </ChartCard>

        <ChartCard title="在线成员 / WS 连接" subtitle="近 24 小时 · 10 秒刷新" :empty="!onlinePoints.length" empty-text="暂无在线数据">
          <BaseChart :option="onlineWsOption" height="248px" />
        </ChartCard>
      </div>

      <!-- 分布 -->
      <div class="grid grid--dist">
        <ChartCard title="活跃组织 Top 8" subtitle="近 24 小时采样点" :empty="!topOrgs.length" empty-text="暂无组织采样数据">
          <BaseChart :option="topOrgsOption" height="204px" />
        </ChartCard>

        <ChartCard title="用户状态" :subtitle="`共 ${formatNumber(userTotal)} 人`" :empty="!userStatus.length" empty-text="暂无用户数据">
          <div class="donut">
            <BaseChart :option="userDonutOption" height="146px" />
            <div class="donut__center">
              <span class="donut__value num">{{ formatNumber(kpi?.activeUsers ?? userStatusMap.ACTIVE ?? 0) }}</span>
              <span class="donut__label">已激活</span>
            </div>
          </div>
          <ul class="legend">
            <li v-for="item in userLegend" :key="item.key" class="legend__item">
              <span class="legend__dot" :style="{ background: item.color }" />
              <span class="legend__label">{{ item.label }}</span>
              <span class="legend__count num">{{ formatNumber(item.count) }}</span>
            </li>
          </ul>
        </ChartCard>

        <ChartCard title="房间状态" :subtitle="`共 ${formatNumber(roomTotal)} 个`" :empty="!roomTotal" empty-text="暂无房间数据">
          <div class="bars">
            <div v-for="row in roomStatusRows" :key="row.key" class="bars__row">
              <div class="bars__head">
                <span class="bars__label">{{ row.label }}</span>
                <span class="bars__value">
                  <span class="num">{{ formatNumber(row.count) }}</span>
                  <span class="bars__pct num">{{ row.percent }}%</span>
                </span>
              </div>
              <div class="bars__track">
                <div
                  class="bars__fill"
                  :style="{ width: `${Math.max(row.percent, row.count ? 2 : 0)}%`, background: row.color }"
                />
              </div>
            </div>
          </div>
        </ChartCard>

        <ChartCard title="活跃热力图" subtitle="星期 × 小时 · 近 7 天审计" :empty="!heatCells.length" empty-text="暂无活跃数据">
          <template #extra>
            <div class="heat-legend">
              <span>低</span>
              <i />
              <span>高</span>
            </div>
          </template>
          <BaseChart :option="heatmapOption" height="204px" />
        </ChartCard>
      </div>

      <!-- 明细 -->
      <div class="grid grid--detail">
        <ChartCard title="最近房间" subtitle="按最近活动排序 · 8 条" flush>
          <template #extra>
            <el-button text size="small" @click="goRooms">查看全部</el-button>
          </template>
          <el-table v-if="recentRooms.length" :data="recentRooms" size="small">
            <el-table-column label="房间" min-width="150">
              <template #default="{ row }">
                <div class="room-cell">
                  <span class="wl-dot" :style="{ background: ROOM_DOT[row.status] || '#5B6675' }" />
                  <span class="mono room-cell__id" :title="row.roomId">{{ row.roomId }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column prop="orgName" label="组织" min-width="110" show-overflow-tooltip />
            <el-table-column label="成员" width="72" align="right">
              <template #default="{ row }">
                <span class="num">{{ formatNumber(row.members) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="采样" width="86" align="right">
              <template #default="{ row }">
                <span class="num">{{ formatNumber(row.samples) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="最近活动" width="96" align="right">
              <template #default="{ row }">
                <span class="dim">{{ formatRelative(row.lastActiveAt) }}</span>
              </template>
            </el-table-column>
          </el-table>
          <EmptyState v-else description="暂无房间数据" />
        </ChartCard>

        <ChartCard title="实时审计流" subtitle="最近 20 条 · 15 秒刷新" flush>
          <template #extra>
            <span class="live-chip"><span class="live-chip__dot" />自动刷新</span>
          </template>
          <ul v-if="auditEvents.length" class="audit-list">
            <li v-for="event in auditEvents" :key="event.id" class="audit">
              <span class="audit__dot" :style="{ background: AUDIT_DOT[event.level] }" />
              <span class="audit__time mono">{{ formatTime(event.ts) }}</span>
              <div class="audit__main">
                <div class="audit__meta">
                  <span class="audit__actor">{{ event.actorName }}</span>
                  <span v-if="event.orgName" class="audit__org">{{ event.orgName }}</span>
                </div>
                <p class="audit__text">
                  <span class="mono audit__action">{{ event.action }}</span>
                  <span v-if="event.detail" class="audit__detail">{{ event.detail }}</span>
                </p>
              </div>
            </li>
          </ul>
          <EmptyState v-else description="暂无审计事件" />
        </ChartCard>
      </div>
    </div>
  </div>
</template>

<style scoped>
.dash__body {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-height: 320px;
}

.dash__stamp {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 11.5px;
  color: var(--wl-text-3);
}

.kpi-grid {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 16px;
}

.grid {
  display: grid;
  gap: 16px;
}

.grid--trend {
  grid-template-columns: minmax(0, 1.85fr) minmax(0, 1fr);
}

.grid--dist {
  grid-template-columns: minmax(0, 1.2fr) minmax(0, 0.86fr) minmax(0, 0.9fr) minmax(0, 1.12fr);
}

.grid--detail {
  grid-template-columns: minmax(0, 1.4fr) minmax(0, 1fr);
}

/* 用户状态环 */
.donut {
  position: relative;
  padding: 2px 6px 0;
}

.donut__center {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 1px;
  pointer-events: none;
}

.donut__value {
  font-size: 24px;
  font-weight: 600;
  letter-spacing: -0.02em;
  line-height: 1.1;
  color: var(--wl-text);
}

.donut__label {
  font-size: 10px;
  letter-spacing: 0.06em;
  color: var(--wl-text-4);
}

.legend {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 4px 14px;
  margin: 0;
  padding: 4px 12px 6px;
  list-style: none;
}

.legend__item {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 11.5px;
  color: var(--wl-text-2);
}

.legend__dot {
  width: 6px;
  height: 6px;
  border-radius: 2px;
  flex-shrink: 0;
}

.legend__label {
  flex: 1;
}

.legend__count {
  color: var(--wl-text);
  font-size: 11.5px;
}

/* 房间状态条 */
.bars {
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 15px;
  min-height: 210px;
  padding: 8px 10px 4px;
}

.bars__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 7px;
  font-size: 12px;
}

.bars__label {
  color: var(--wl-text-2);
}

.bars__value {
  display: inline-flex;
  align-items: baseline;
  gap: 7px;
  color: var(--wl-text);
  font-size: 12px;
}

.bars__pct {
  font-size: 10.5px;
  color: var(--wl-text-4);
}

.bars__track {
  height: 4px;
  border-radius: 3px;
  background: rgba(255, 255, 255, 0.06);
  overflow: hidden;
}

.bars__fill {
  height: 100%;
  border-radius: 3px;
  transition: width 0.6s cubic-bezier(0.22, 1, 0.36, 1);
}

/* 热力图图例 */
.heat-legend {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 10px;
  color: var(--wl-text-4);
}

.heat-legend i {
  display: block;
  width: 72px;
  height: 4px;
  border-radius: 2px;
  background: linear-gradient(
    90deg,
    rgb(13, 16, 22),
    rgb(16, 74, 86),
    rgb(20, 124, 145),
    rgb(34, 211, 238),
    rgb(140, 240, 255)
  );
}

/* 最近房间 */
.room-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}

.room-cell__id {
  font-size: 12px;
  color: var(--wl-text-2);
}

/* 审计流 */
.live-chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 2px 7px;
  border-radius: 5px;
  font-size: 10px;
  letter-spacing: 0.04em;
  color: var(--wl-success);
  background: rgba(62, 207, 142, 0.08);
  border: 1px solid rgba(62, 207, 142, 0.18);
  white-space: nowrap;
}

.live-chip__dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--wl-success);
  animation: blink 2.4s ease-in-out infinite;
}

@keyframes blink {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.3;
  }
}

.audit-list {
  margin: 0;
  padding: 2px 0 0;
  list-style: none;
  max-height: 336px;
  overflow-y: auto;
}

.audit {
  display: flex;
  gap: 10px;
  padding: 9px 18px;
  border-bottom: 1px solid var(--wl-line);
  transition: background 0.14s ease;
}

.audit:hover {
  background: rgba(255, 255, 255, 0.025);
}

.audit:last-child {
  border-bottom: none;
}

.audit__dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  margin-top: 6px;
  flex-shrink: 0;
}

.audit__time {
  flex-shrink: 0;
  width: 42px;
  padding-top: 1px;
  font-size: 10.5px;
  color: var(--wl-text-4);
}

.audit__main {
  min-width: 0;
  flex: 1;
}

.audit__meta {
  display: flex;
  align-items: baseline;
  gap: 8px;
  flex-wrap: wrap;
}

.audit__actor {
  font-size: 12px;
  font-weight: 500;
  color: var(--wl-text);
}

.audit__org {
  font-size: 10.5px;
  color: var(--wl-text-4);
}

.audit__text {
  margin: 2px 0 0;
  font-size: 11.5px;
  line-height: 1.55;
  color: var(--wl-text-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.audit__action {
  font-size: 11px;
  color: var(--wl-text-2);
}

.audit__detail {
  margin-left: 6px;
  font-size: 10.5px;
  color: var(--wl-text-4);
}

@media (max-width: 1560px) {
  .grid--dist {
    grid-template-columns: minmax(0, 1.24fr) minmax(0, 0.92fr) minmax(0, 0.94fr);
  }
  .grid--dist > :last-child {
    grid-column: 1 / -1;
  }
  .grid--dist > :last-child :deep(.wl-chart) {
    height: 150px !important;
  }
}

@media (max-width: 1360px) {
  .kpi-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 1180px) {
  .grid--trend,
  .grid--detail {
    grid-template-columns: minmax(0, 1fr);
  }
}

@media (max-width: 900px) {
  .kpi-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .grid--dist {
    grid-template-columns: minmax(0, 1fr);
  }
  .grid--dist > :last-child {
    grid-column: auto;
  }
}

@media (max-width: 620px) {
  .kpi-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
