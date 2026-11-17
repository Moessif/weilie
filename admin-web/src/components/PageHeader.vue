<script setup lang="ts">
withDefaults(
  defineProps<{
    title: string
    description?: string
    eyebrow?: string
  }>(),
  { description: '', eyebrow: '' },
)
</script>

<template>
  <div class="wl-page__head">
    <div class="wl-page__titles">
      <p v-if="eyebrow" class="wl-eyebrow head__eyebrow">{{ eyebrow }}</p>
      <h1 class="wl-page__title">{{ title }}</h1>
      <p v-if="description" class="wl-page__desc">{{ description }}</p>
    </div>
    <div v-if="$slots.actions" class="wl-page__actions">
      <slot name="actions" />
    </div>
  </div>
</template>

<style scoped>
.head__eyebrow {
  margin: 0 0 8px;
}
</style>
