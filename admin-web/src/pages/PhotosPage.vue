<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { ArrowLeft, ArrowRight, CopyDocument, Search } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import { api } from '@/api'
import type { PhotoItem, PhotoKind } from '@/types'
import { formatBytes, formatDateTime } from '@/utils/format'
import { copyText } from '@/utils/csv'

const loading = ref(false)
const photos = ref<PhotoItem[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(24)
const broken = reactive(new Set<string>())

const query = reactive({
  kind: '' as PhotoKind | '',
  q: '',
  onlyUploaded: false,
})

async function loadPhotos(): Promise<void> {
  loading.value = true
  try {
    const res = await api.photos.list({
      kind: query.kind || undefined,
      q: query.q.trim() || undefined,
      onlyUploaded: query.onlyUploaded || undefined,
      page: page.value,
      size: size.value,
    })
    photos.value = res.items
    total.value = res.total
  } catch {
    photos.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function searchPhotos(): void {
  page.value = 1
  void loadPhotos()
}

function onKindChange(value: string | number | boolean): void {
  query.kind = String(value) as PhotoKind | ''
  searchPhotos()
}

function resetQuery(): void {
  query.kind = ''
  query.q = ''
  query.onlyUploaded = false
  page.value = 1
  void loadPhotos()
}

function onPageChange(): void {
  void loadPhotos()
}

function markBroken(code: string): void {
  broken.add(code)
}

function thumbUrl(photo: PhotoItem): string {
  return api.photos.fileUrl(photo.code, photo.kind)
}

async function copySha(value: string): Promise<void> {
  const ok = await copyText(value)
  if (ok) ElMessage.success('SHA-256 已复制')
  else ElMessage.error('复制失败，请手动选择')
}

/* ------------------------------ 大图预览 ------------------------------ */

const previewVisible = ref(false)
const previewIndex = ref(0)

const previewPhoto = computed<PhotoItem | null>(() => photos.value[previewIndex.value] || null)

function openPreview(index: number): void {
  previewIndex.value = index
  previewVisible.value = true
}

function stepPreview(step: number): void {
  const next = previewIndex.value + step
  if (next < 0 || next >= photos.value.length) return
  previewIndex.value = next
}

onMounted(() => {
  void loadPhotos()
})
</script>

<template>
  <div class="wl-page">
    <PageHeader
      title="取证照片"
      eyebrow="取证照片 · PHOTOS"
      description="演示手机上传的现场取证照片，包含拍摄坐标、设备与 SHA-256 摘要；可疑照片由成员手动标记。"
    >
      <template #actions>
        <span class="count-hint">共 {{ total }} 张</span>
        <el-button plain @click="loadPhotos">刷新</el-button>
      </template>
    </PageHeader>

    <div class="wl-panel">
      <div class="wl-toolbar">
        <el-radio-group :model-value="query.kind" class="wl-segmented" @change="onKindChange">
          <el-radio-button value="">全部</el-radio-button>
          <el-radio-button value="PHOTO">普通照片</el-radio-button>
          <el-radio-button value="SUSPICIOUS">可疑照片</el-radio-button>
        </el-radio-group>

        <el-input
          v-model="query.q"
          class="keyword"
          placeholder="搜索照片编号 / 设备 / 备注"
          clearable
          :prefix-icon="Search"
          @keyup.enter="searchPhotos"
          @clear="searchPhotos"
        />

        <el-switch v-model="query.onlyUploaded" active-text="只看已上传原图" @change="searchPhotos" />

        <div class="wl-toolbar__right">
          <el-button type="primary" plain @click="searchPhotos">查询</el-button>
          <el-button text @click="resetQuery">重置</el-button>
        </div>
      </div>

      <div v-loading="loading" class="photo-grid-wrap">
        <div v-if="photos.length" class="photo-grid">
          <button
            v-for="(photo, index) in photos"
            :key="photo.code"
            type="button"
            class="photo-card"
            :title="photo.code"
            @click="openPreview(index)"
          >
            <span class="photo-thumb">
              <img
                v-if="photo.hasFile && !broken.has(photo.code)"
                class="photo-img"
                :src="thumbUrl(photo)"
                :alt="photo.code"
                loading="lazy"
                @error="markBroken(photo.code)"
              />
              <EmptyState
                v-else
                :size="26"
                :description="photo.hasFile ? '缩略图加载失败' : '原图未上传'"
              />
            </span>
            <span class="photo-meta">
              <span class="photo-meta__row">
                <el-tag
                  size="small"
                  effect="plain"
                  :type="photo.kind === 'SUSPICIOUS' ? 'warning' : 'info'"
                >
                  {{ photo.kind === 'SUSPICIOUS' ? '可疑照片' : '普通照片' }}
                </el-tag>
                <span class="photo-time mono">{{ formatDateTime(photo.ts) }}</span>
              </span>
              <span class="photo-meta__row">
                <span class="photo-device">{{ photo.device }}</span>
                <span class="photo-size mono">{{ formatBytes(photo.sizeBytes, 0) }}</span>
              </span>
            </span>
          </button>
        </div>
        <EmptyState v-else-if="!loading" description="没有符合条件的照片" />
      </div>

      <div class="wl-pagination">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="size"
          :total="total"
          :page-sizes="[12, 24, 48]"
          layout="total, sizes, prev, pager, next"
          background
          @change="onPageChange"
        />
      </div>
    </div>

    <el-dialog v-model="previewVisible" width="880px" class="photo-preview" destroy-on-close>
      <template #header>
        <div class="preview-head">
          <div class="preview-head__left">
            <span class="preview-code mono">{{ previewPhoto?.code }}</span>
            <el-tag
              v-if="previewPhoto"
              size="small"
              effect="plain"
              :type="previewPhoto.kind === 'SUSPICIOUS' ? 'warning' : 'info'"
            >
              {{ previewPhoto.kind === 'SUSPICIOUS' ? '可疑照片' : '普通照片' }}
            </el-tag>
          </div>
          <div class="preview-head__nav">
            <el-button
              size="small"
              :icon="ArrowLeft"
              :disabled="previewIndex <= 0"
              @click="stepPreview(-1)"
            >
              上一张
            </el-button>
            <span class="preview-count mono">{{ previewIndex + 1 }} / {{ photos.length }}</span>
            <el-button
              size="small"
              :disabled="previewIndex >= photos.length - 1"
              @click="stepPreview(1)"
            >
              下一张<el-icon class="el-icon--right"><ArrowRight /></el-icon>
            </el-button>
          </div>
        </div>
      </template>

      <div v-if="previewPhoto" class="preview-body">
        <div class="preview-stage">
          <img
            v-if="previewPhoto.hasFile && !broken.has(previewPhoto.code)"
            class="preview-img"
            :src="thumbUrl(previewPhoto)"
            :alt="previewPhoto.code"
            @error="markBroken(previewPhoto.code)"
          />
          <EmptyState
            v-else
            :size="40"
            :description="previewPhoto.hasFile ? '原图加载失败' : '该照片未上传原图，仅保留元数据'"
          />
        </div>

        <aside class="preview-side">
          <dl class="meta-list">
            <div class="meta-row">
              <dt>拍摄时间</dt>
              <dd class="mono">{{ formatDateTime(previewPhoto.ts) }}</dd>
            </div>
            <div class="meta-row">
              <dt>上传时间</dt>
              <dd class="mono">{{ formatDateTime(previewPhoto.serverTs) }}</dd>
            </div>
            <div class="meta-row">
              <dt>坐标</dt>
              <dd class="mono">
                {{ previewPhoto.lat.toFixed(6) }}, {{ previewPhoto.lon.toFixed(6) }}
              </dd>
            </div>
            <div class="meta-row">
              <dt>设备</dt>
              <dd>{{ previewPhoto.device }}</dd>
            </div>
            <div class="meta-row">
              <dt>大小</dt>
              <dd class="mono">{{ formatBytes(previewPhoto.sizeBytes) }}</dd>
            </div>
            <div class="meta-row">
              <dt>SHA-256</dt>
              <dd class="sha-cell">
                <span class="mono sha-text" :title="previewPhoto.sha256">
                  {{ previewPhoto.sha256.slice(0, 16) }}…
                </span>
                <el-button
                  text
                  size="small"
                  :icon="CopyDocument"
                  @click="copySha(previewPhoto.sha256)"
                >
                  复制
                </el-button>
              </dd>
            </div>
            <div class="meta-row">
              <dt>备注</dt>
              <dd>{{ previewPhoto.note || '—' }}</dd>
            </div>
          </dl>

          <a
            class="verify-link"
            :href="`/v/${previewPhoto.code}`"
            target="_blank"
            rel="noopener noreferrer"
          >
            打开验真页
          </a>
        </aside>
      </div>
    </el-dialog>
  </div>
</template>

<style scoped>
.count-hint {
  font-size: 11.5px;
  color: var(--wl-text-3);
}

.wl-toolbar .keyword {
  width: 240px;
}

.photo-grid-wrap {
  min-height: 260px;
  padding: 16px 18px;
}

.photo-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 16px;
}

.photo-card {
  display: flex;
  flex-direction: column;
  padding: 0;
  overflow: hidden;
  border: 1px solid var(--wl-line);
  border-radius: var(--wl-radius-lg);
  background: var(--wl-panel);
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
  transition: border-color 0.16s ease;
}

.photo-card:hover {
  border-color: var(--wl-line-3);
}

.photo-thumb {
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  aspect-ratio: 4 / 3;
  border-bottom: 1px solid var(--wl-line);
  background: var(--wl-sunken);
}

.photo-img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
  transition: transform 0.3s ease;
}

.photo-card:hover .photo-img {
  transform: scale(1.02);
}

.photo-meta {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 10px 12px 12px;
}

.photo-meta__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-width: 0;
}

.photo-time {
  flex: none;
  font-size: 11px;
  color: var(--wl-text-3);
}

.photo-device {
  min-width: 0;
  overflow: hidden;
  font-size: 11.5px;
  color: var(--wl-text-2);
  white-space: nowrap;
  text-overflow: ellipsis;
}

.photo-size {
  flex: none;
  font-size: 11px;
  color: var(--wl-text-4);
}

.preview-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.preview-head__left {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.preview-code {
  overflow: hidden;
  font-size: 13px;
  color: var(--wl-text);
  white-space: nowrap;
  text-overflow: ellipsis;
}

.preview-head__nav {
  display: flex;
  align-items: center;
  gap: 8px;
}

.preview-count {
  font-size: 11.5px;
  color: var(--wl-text-3);
}

.preview-body {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 260px;
  gap: 16px;
}

.preview-stage {
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  max-height: 68vh;
  min-height: 280px;
  border: 1px solid var(--wl-line);
  border-radius: var(--wl-radius);
  background: var(--wl-sunken);
}

.preview-img {
  display: block;
  max-width: 100%;
  max-height: 68vh;
  object-fit: contain;
}

.preview-side {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
}

.meta-list {
  display: flex;
  flex-direction: column;
  margin: 0;
}

.meta-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 7px 0;
  border-bottom: 1px solid var(--wl-line);
}

.meta-row:last-child {
  border-bottom: 0;
}

.meta-row dt {
  flex: none;
  font-size: 11px;
  color: var(--wl-text-3);
}

.meta-row dd {
  min-width: 0;
  margin: 0;
  font-size: 11.5px;
  color: var(--wl-text-2);
  text-align: right;
  overflow-wrap: anywhere;
}

.sha-cell {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.sha-text {
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.verify-link {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: 32px;
  border: 1px solid var(--wl-line-2);
  border-radius: var(--wl-radius-sm);
  color: var(--wl-cyan);
  font-size: 12px;
  text-decoration: none;
  transition: border-color 0.16s ease;
}

.verify-link:hover {
  border-color: var(--wl-line-3);
}

@media (max-width: 900px) {
  .photo-grid-wrap {
    padding: 14px;
  }

  .preview-body {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
