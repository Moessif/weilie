// ECharts 按需注册 + 围猎深色主题与常用 option 片段
import { use, registerTheme, graphic } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { BarChart, HeatmapChart, LineChart, PieChart } from 'echarts/charts'
import {
  GridComponent,
  LegendComponent,
  MarkLineComponent,
  TooltipComponent,
  VisualMapComponent,
} from 'echarts/components'

use([
  CanvasRenderer,
  LineChart,
  BarChart,
  PieChart,
  HeatmapChart,
  GridComponent,
  TooltipComponent,
  LegendComponent,
  VisualMapComponent,
  MarkLineComponent,
])

export const CHART_COLORS = ['#22D3EE', '#5EEAD4', '#7BA7D7', '#F0B429', '#F2705F', '#3ECF8E']

registerTheme('weilie', {
  color: CHART_COLORS,
  backgroundColor: 'transparent',
  textStyle: {
    color: '#8A94A6',
    fontFamily:
      "'Instrument Sans', -apple-system, 'PingFang SC', 'Microsoft YaHei', sans-serif",
    fontSize: 11.5,
  },
  title: {
    textStyle: { color: '#E8EDF5', fontWeight: 600, fontSize: 13.5 },
    subtextStyle: { color: '#7E8A9C', fontSize: 11.5 },
  },
  legend: {
    textStyle: { color: '#A7B2C3', fontSize: 11.5 },
    icon: 'roundRect',
    itemWidth: 7,
    itemHeight: 7,
    itemGap: 14,
  },
  tooltip: {
    backgroundColor: 'rgba(26, 32, 41, 0.97)',
    borderColor: 'rgba(255,255,255,0.12)',
    borderWidth: 1,
    padding: [10, 12],
    textStyle: { color: '#E8EDF5', fontSize: 11.5 },
    extraCssText: 'box-shadow: 0 18px 44px rgba(0,0,0,.55); border-radius: 8px;',
  },
  categoryAxis: {
    axisLine: { lineStyle: { color: 'rgba(255,255,255,0.1)' } },
    axisTick: { show: false },
    axisLabel: { color: '#7E8A9C', fontSize: 11 },
    splitLine: { show: false },
  },
  valueAxis: {
    axisLine: { show: false },
    axisTick: { show: false },
    axisLabel: { color: '#7E8A9C', fontSize: 11 },
    splitLine: { lineStyle: { color: 'rgba(255,255,255,0.05)' } },
  },
  line: {
    symbol: 'none',
    smooth: 0.25,
    lineStyle: { width: 1.6 },
  },
  bar: {
    barWidth: 14,
    itemStyle: { borderRadius: [3, 3, 0, 0] },
  },
  pie: {
    itemStyle: { borderColor: '#12161D', borderWidth: 2 },
  },
})

/** 面积渐变（顶部半透明 → 底部透明） */
export function areaGradient(color = '#22D3EE', topAlpha = '38'): graphic.LinearGradient {
  return new graphic.LinearGradient(0, 0, 0, 1, [
    { offset: 0, color: `${color}${topAlpha}` },
    { offset: 1, color: `${color}00` },
  ])
}

/** 柱状渐变（上亮下暗） */
export function barGradient(color = '#22D3EE'): graphic.LinearGradient {
  return new graphic.LinearGradient(0, 0, 0, 1, [
    { offset: 0, color },
    { offset: 1, color: `${color}66` },
  ])
}

export const AXIS_LABEL_COLOR = '#7E8A9C'
export const SPLIT_LINE_COLOR = 'rgba(255,255,255,0.05)'

/** 通用坐标轴网格配置 */
export function baseGrid(extra: Record<string, unknown> = {}): Record<string, unknown> {
  return { left: 8, right: 16, top: 18, bottom: 8, containLabel: true, ...extra }
}

/** 空数据占位文本（无数据时由页面展示空状态，这里仅作为兜底） */
export function emptyOption(text = '暂无数据'): Record<string, unknown> {
  return {
    title: {
      text,
      left: 'center',
      top: 'middle',
      textStyle: { color: '#7E8A9C', fontSize: 11.5, fontWeight: 400 },
    },
  }
}

/** 毫秒时间戳 -> 图表 x 轴标签 */
export function timeLabel(ts: number, range: '24h' | '7d' | '30d'): string {
  const d = new Date(ts)
  const p = (n: number) => (n < 10 ? `0${n}` : String(n))
  if (range === '24h') return `${p(d.getHours())}:${p(d.getMinutes())}`
  if (range === '7d') return `${p(d.getMonth() + 1)}/${p(d.getDate())} ${p(d.getHours())}:00`
  return `${p(d.getMonth() + 1)}/${p(d.getDate())}`
}
