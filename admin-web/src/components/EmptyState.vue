<script setup lang="ts">
withDefaults(
  defineProps<{
    description?: string
    size?: number
  }>(),
  { description: '暂无数据', size: 64 },
)
</script>

<template>
  <div class="empty">
    <div class="empty__mark" :style="{ width: `${size}px`, height: `${size}px` }" aria-hidden="true">
      <span class="empty__line" />
      <span class="empty__line empty__line--short" />
      <span class="empty__line empty__line--dim" />
    </div>
    <p class="empty__text">{{ description }}</p>
    <div v-if="$slots.default" class="empty__extra">
      <slot />
    </div>
  </div>
</template>

<style scoped>
.empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 14px;
  min-height: 148px;
  padding: 14px 0;
}

.empty__mark {
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 5px;
  padding: 0 14px;
  border: 1px dashed var(--wl-line-3);
  border-radius: var(--wl-radius);
  background: var(--wl-sunken);
}

.empty__line {
  display: block;
  height: 3px;
  border-radius: 2px;
  background: var(--wl-line-3);
}
.empty__line--short {
  width: 62%;
}
.empty__line--dim {
  background: var(--wl-line-2);
}

.empty__text {
  margin: 0;
  font-size: 12.5px;
  color: var(--wl-text-3);
}

.empty__extra {
  margin-top: -6px;
  font-size: 12px;
  color: var(--wl-text-4);
  line-height: 1.6;
  text-align: center;
}
</style>
