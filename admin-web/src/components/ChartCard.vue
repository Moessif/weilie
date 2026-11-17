<script setup lang="ts">
import EmptyState from './EmptyState.vue'

withDefaults(
  defineProps<{
    title: string
    subtitle?: string
    empty?: boolean
    emptyText?: string
    flush?: boolean
  }>(),
  { subtitle: '', empty: false, emptyText: '暂无数据', flush: false },
)
</script>

<template>
  <section class="card">
    <header class="card__head">
      <div class="card__titles">
        <h3 class="card__title">{{ title }}</h3>
        <p v-if="subtitle" class="card__subtitle">{{ subtitle }}</p>
      </div>
      <div class="card__extra">
        <slot name="extra" />
      </div>
    </header>
    <div class="card__body" :class="{ 'card__body--flush': flush }">
      <EmptyState v-if="empty" :description="emptyText" />
      <slot v-else />
    </div>
  </section>
</template>

<style scoped>
.card {
  display: flex;
  flex-direction: column;
  min-width: 0;
  background: var(--wl-panel);
  border: 1px solid var(--wl-line);
  border-radius: var(--wl-radius-lg);
}

.card__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 16px 18px 2px;
}

.card__titles {
  min-width: 0;
}

.card__title {
  margin: 0;
  font-size: 13.5px;
  font-weight: 600;
  letter-spacing: -0.008em;
  color: var(--wl-text);
  white-space: nowrap;
}

.card__subtitle {
  margin: 4px 0 0;
  font-size: 11.5px;
  color: var(--wl-text-4);
  line-height: 1.5;
}

.card__extra {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}

.card__body {
  flex: 1;
  min-width: 0;
  padding: 8px 12px 12px;
}

.card__body--flush {
  padding: 2px 0 0;
}
</style>
