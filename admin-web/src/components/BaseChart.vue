<script setup lang="ts">
import '@/utils/chart'
import VChart from 'vue-echarts'
import type { EChartsOption } from 'echarts'

withDefaults(
  defineProps<{
    option: EChartsOption
    height?: string
  }>(),
  { height: '260px' },
)
</script>

<template>
  <div class="wl-chart-wrap">
    <VChart
      class="wl-chart"
      :option="option"
      :update-options="{ notMerge: true }"
      theme="weilie"
      autoresize
      :style="{ height }"
    />
  </div>
</template>

<style scoped>
.wl-chart-wrap {
  width: 100%;
  min-width: 0;
}
.wl-chart {
  width: 100%;
}
</style>
