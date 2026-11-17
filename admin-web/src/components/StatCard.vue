<script setup lang="ts">
import type { Component } from 'vue'

withDefaults(
  defineProps<{
    label: string
    value: string
    hint?: string
    delta?: string
    deltaTone?: 'up' | 'down' | 'flat'
    icon?: Component
    accent?: 'cyan' | 'teal' | 'warning' | 'danger'
  }>(),
  { hint: '', delta: '', deltaTone: 'flat', accent: 'cyan' },
)
</script>

<template>
  <section class="kpi" :class="`kpi--${accent}`">
    <header class="kpi__head">
      <span class="kpi__label">{{ label }}</span>
      <span v-if="icon" class="kpi__icon">
        <el-icon :size="15"><component :is="icon" /></el-icon>
      </span>
    </header>
    <p class="kpi__value num">{{ value }}</p>
    <footer class="kpi__foot">
      <span v-if="delta" class="kpi__delta mono" :class="`kpi__delta--${deltaTone}`">{{ delta }}</span>
      <span v-if="hint" class="kpi__hint">{{ hint }}</span>
    </footer>
  </section>
</template>

<style scoped>
.kpi {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-width: 0;
  padding: 16px 18px 15px;
  background: var(--wl-panel);
  border: 1px solid var(--wl-line);
  border-radius: var(--wl-radius-lg);
  transition: border-color 0.16s ease, background 0.16s ease;
}

.kpi:hover {
  border-color: var(--wl-line-3);
  background: var(--wl-panel-2);
}

.kpi__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.kpi__label {
  font-size: 11px;
  font-weight: 500;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--wl-text-3);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.kpi__icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  flex-shrink: 0;
  border-radius: 7px;
  color: var(--wl-cyan);
  background: var(--wl-cyan-wash);
}

.kpi--teal .kpi__icon {
  color: var(--wl-teal);
  background: rgba(94, 234, 212, 0.09);
}
.kpi--warning .kpi__icon {
  color: var(--wl-warning);
  background: rgba(240, 180, 41, 0.1);
}
.kpi--danger .kpi__icon {
  color: var(--wl-danger);
  background: rgba(242, 112, 95, 0.1);
}

.kpi__value {
  margin: 0;
  font-size: 27px;
  line-height: 1.1;
  font-weight: 600;
  letter-spacing: -0.028em;
  color: var(--wl-text);
  font-variant-numeric: tabular-nums;
}

.kpi__foot {
  display: flex;
  align-items: baseline;
  gap: 8px;
  flex-wrap: wrap;
  min-height: 15px;
  font-size: 11.5px;
}

.kpi__delta {
  font-size: 11px;
}
.kpi__delta--up {
  color: var(--wl-success);
}
.kpi__delta--down {
  color: var(--wl-danger);
}
.kpi__delta--flat {
  color: var(--wl-text-3);
}

.kpi__hint {
  color: var(--wl-text-4);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
