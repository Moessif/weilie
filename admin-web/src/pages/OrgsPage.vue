<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { CopyDocument, Plus, Refresh, Search } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import { api } from '@/api'
import type { CreateOrgPayload } from '@/api'
import type { Org, OrgApplication, OrgStatus } from '@/types'
import type { TagType } from '@/utils/format'
import { copyText } from '@/utils/csv'
import { formatDateTime } from '@/utils/format'
import { useAuthStore } from '@/stores/auth'
import { useAppStore } from '@/stores/app'

const auth = useAuthStore()
const appStore = useAppStore()

const canManage = computed(() => auth.isSuperAdmin)

type OrgTab = 'list' | 'applications'
const activeTab = ref<OrgTab>('list')

const ORG_STATUS_TEXT: Record<OrgStatus, string> = {
  ACTIVE: '正常',
  PENDING: '待审核',
  SUSPENDED: '已停用',
}

const ORG_STATUS_TAG: Record<OrgStatus, TagType> = {
  ACTIVE: 'success',
  PENDING: 'warning',
  SUSPENDED: 'danger',
}

const APP_STATUS_TEXT: Record<string, string> = {
  PENDING: '待审核',
  APPROVED: '已通过',
  REJECTED: '已驳回',
}

const APP_STATUS_TAG: Record<string, TagType> = {
  PENDING: 'warning',
  APPROVED: 'success',
  REJECTED: 'danger',
}

/* ------------------------------ 组织列表 ------------------------------ */

const loading = ref(false)
const orgs = ref<Org[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const query = reactive({ q: '', status: '' as OrgStatus | '' })

const statusOptions: { label: string; value: OrgStatus | '' }[] = [
  { label: '全部状态', value: '' },
  { label: '正常', value: 'ACTIVE' },
  { label: '待审核', value: 'PENDING' },
  { label: '已停用', value: 'SUSPENDED' },
]

async function loadOrgs(): Promise<void> {
  loading.value = true
  try {
    const res = await api.orgs.list({
      q: query.q.trim() || undefined,
      status: query.status || undefined,
      page: page.value,
      size: size.value,
    })
    orgs.value = res.items
    total.value = res.total
  } catch {
    orgs.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function searchOrgs(): void {
  page.value = 1
  void loadOrgs()
}

function resetQuery(): void {
  query.q = ''
  query.status = ''
  page.value = 1
  void loadOrgs()
}

function onPageChange(): void {
  void loadOrgs()
}

/* ------------------------------ 组织详情 ------------------------------ */

const detailVisible = ref(false)
const detailOrg = ref<Org | null>(null)

function openDetail(org: Org): void {
  detailOrg.value = org
  detailVisible.value = true
}

/* ------------------------------ 新建组织 ------------------------------ */

function randomDigits(length = 3): string {
  return String(Math.floor(Math.random() * 9 * 10 ** (length - 1)) + 10 ** (length - 1))
}

function suggestPassword(): string {
  const chars = 'ABCDEFGHJKMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz'
  let body = ''
  for (let i = 0; i < 6; i++) body += chars[Math.floor(Math.random() * chars.length)]
  return `Wl@${body}${randomDigits(3)}`
}

const createVisible = ref(false)
const creating = ref(false)
const createFormRef = ref<FormInstance>()
const createForm = reactive<CreateOrgPayload>({
  name: '',
  code: '',
  contactName: '',
  contactPhone: '',
  quotaUsers: 80,
  quotaRooms: 20,
  adminUsername: '',
  adminDisplayName: '',
  adminPassword: '',
})

const createRules: FormRules = {
  name: [
    { required: true, message: '请输入组织名称', trigger: 'blur' },
    { min: 2, max: 40, message: '组织名称长度为 2–40 个字符', trigger: 'blur' },
  ],
  code: [
    { required: true, message: '请输入组织编码', trigger: 'blur' },
    { pattern: /^[A-Za-z0-9_-]{2,16}$/, message: '2–16 位字母、数字、下划线或短横线', trigger: 'blur' },
  ],
  contactName: [{ required: true, message: '请输入联系人姓名', trigger: 'blur' }],
  contactPhone: [
    { required: true, message: '请输入联系电话', trigger: 'blur' },
    { pattern: /^[0-9+\-\s]{6,20}$/, message: '请输入有效的联系电话', trigger: 'blur' },
  ],
  quotaUsers: [{ required: true, message: '请设置用户配额', trigger: 'blur' }],
  quotaRooms: [{ required: true, message: '请设置房间配额', trigger: 'blur' }],
  adminUsername: [
    { required: true, message: '请输入管理员用户名', trigger: 'blur' },
    { min: 3, max: 32, message: '用户名长度为 3–32 位', trigger: 'blur' },
  ],
  adminDisplayName: [{ required: true, message: '请输入管理员显示名', trigger: 'blur' }],
  adminPassword: [
    { required: true, message: '请设置初始密码', trigger: 'blur' },
    {
      validator: (_rule, value: string, callback) => {
        if (!/^(?=.*[A-Za-z])(?=.*\d).{8,32}$/.test(value || '')) {
          callback(new Error('8–32 位，且须同时包含字母与数字'))
        } else callback()
      },
      trigger: 'blur',
    },
  ],
}

function openCreate(): void {
  const suffix = randomDigits(3)
  createForm.name = ''
  createForm.code = `ORG${suffix}`
  createForm.contactName = ''
  createForm.contactPhone = ''
  createForm.quotaUsers = 80
  createForm.quotaRooms = 20
  createForm.adminUsername = `orgadmin${suffix}`
  createForm.adminDisplayName = ''
  createForm.adminPassword = suggestPassword()
  createVisible.value = true
  createFormRef.value?.clearValidate()
}

function regeneratePassword(): void {
  createForm.adminPassword = suggestPassword()
}

async function submitCreate(): Promise<void> {
  const valid = await createFormRef.value?.validate().catch(() => false)
  if (!valid) return
  creating.value = true
  try {
    const res = await api.orgs.create({ ...createForm })
    ElMessage.success(
      `组织「${res.org.name}」已创建，管理员账号 ${res.adminUser.username} 已开通（首登需改密）`,
    )
    createVisible.value = false
    page.value = 1
    await loadOrgs()
    void appStore.loadOrgs(true)
    void loadApplications()
  } catch {
    /* 错误提示由 api client 统一处理 */
  } finally {
    creating.value = false
  }
}

/* ------------------------------ 编辑 / 停用 ------------------------------ */

const editVisible = ref(false)
const editing = ref<Org | null>(null)
const editFormRef = ref<FormInstance>()
const editForm = reactive({
  name: '',
  contactName: '',
  contactPhone: '',
  quotaUsers: 0,
  quotaRooms: 0,
})
const editRules: FormRules = {
  name: [{ required: true, message: '请输入组织名称', trigger: 'blur' }],
  contactName: [{ required: true, message: '请输入联系人姓名', trigger: 'blur' }],
  contactPhone: [
    { required: true, message: '请输入联系电话', trigger: 'blur' },
    { pattern: /^[0-9+\-\s]{6,20}$/, message: '请输入有效的联系电话', trigger: 'blur' },
  ],
}

function openEdit(org: Org): void {
  editing.value = org
  editForm.name = org.name
  editForm.contactName = org.contactName
  editForm.contactPhone = org.contactPhone
  editForm.quotaUsers = org.quotaUsers
  editForm.quotaRooms = org.quotaRooms
  editVisible.value = true
  editFormRef.value?.clearValidate()
}

async function submitEdit(): Promise<void> {
  const org = editing.value
  if (!org) return
  const valid = await editFormRef.value?.validate().catch(() => false)
  if (!valid) return
  await api.orgs.update(org.id, { ...editForm })
  ElMessage.success('组织信息已更新')
  editVisible.value = false
  await loadOrgs()
}

async function toggleOrgStatus(org: Org): Promise<void> {
  const suspend = org.status !== 'SUSPENDED'
  const action = suspend ? '停用' : '启用'
  try {
    await ElMessageBox.confirm(
      suspend
        ? `停用后「${org.name}」的成员将无法登录与采样，激活码同时失效。`
        : `启用后「${org.name}」将恢复成员登录与采样能力。`,
      `${action}组织`,
      {
        confirmButtonText: `确认${action}`,
        cancelButtonText: '取消',
        type: 'warning',
      },
    )
  } catch {
    return
  }
  await api.orgs.update(org.id, { status: suspend ? 'SUSPENDED' : 'ACTIVE' })
  ElMessage.success(`组织已${action}`)
  await loadOrgs()
  void appStore.loadOrgs(true)
}

/* ------------------------------ 组织申请 ------------------------------ */

const appLoading = ref(false)
const applications = ref<OrgApplication[]>([])
const appTotal = ref(0)
const appPage = ref(1)
const appSize = ref(10)
const appStatus = ref<'PENDING' | 'APPROVED' | 'REJECTED' | ''>('PENDING')
const pendingCount = ref(0)

const appStatusOptions: { label: string; value: '' | 'PENDING' | 'APPROVED' | 'REJECTED' }[] = [
  { label: '全部状态', value: '' },
  { label: '待审核', value: 'PENDING' },
  { label: '已通过', value: 'APPROVED' },
  { label: '已驳回', value: 'REJECTED' },
]

async function loadApplications(): Promise<void> {
  appLoading.value = true
  try {
    const res = await api.applications.list({
      status: appStatus.value || undefined,
      page: appPage.value,
      size: appSize.value,
    })
    applications.value = res.items
    appTotal.value = res.total
  } catch {
    applications.value = []
    appTotal.value = 0
  } finally {
    appLoading.value = false
  }
}

async function loadPendingCount(): Promise<void> {
  const res = await api.applications.list({ status: 'PENDING', page: 1, size: 1 }).catch(() => null)
  pendingCount.value = res?.total ?? 0
}

function onTabChange(name: string | number): void {
  if (name === 'applications') void loadApplications()
}

const approveVisible = ref(false)
const approvingApp = ref<OrgApplication | null>(null)
const approveFormRef = ref<FormInstance>()
const approveForm = reactive({
  code: '',
  adminUsername: '',
  adminPassword: '',
})
const approveRules: FormRules = {
  code: [
    { required: true, message: '请输入组织编码', trigger: 'blur' },
    { pattern: /^[A-Za-z0-9_-]{2,16}$/, message: '2–16 位字母、数字、下划线或短横线', trigger: 'blur' },
  ],
  adminUsername: [{ required: true, message: '请输入管理员用户名', trigger: 'blur' }],
  adminPassword: [
    { required: true, message: '请设置初始密码', trigger: 'blur' },
    {
      validator: (_rule, value: string, callback) => {
        if (!/^(?=.*[A-Za-z])(?=.*\d).{8,32}$/.test(value || '')) {
          callback(new Error('8–32 位，且须同时包含字母与数字'))
        } else callback()
      },
      trigger: 'blur',
    },
  ],
}

function openApprove(appItem: OrgApplication): void {
  approvingApp.value = appItem
  const suffix = randomDigits(3)
  approveForm.code = `ORG${suffix}`
  approveForm.adminUsername = `orgadmin${suffix}`
  approveForm.adminPassword = suggestPassword()
  approveVisible.value = true
  approveFormRef.value?.clearValidate()
}

async function submitApprove(): Promise<void> {
  const appItem = approvingApp.value
  if (!appItem) return
  const valid = await approveFormRef.value?.validate().catch(() => false)
  if (!valid) return
  await api.applications.approve(appItem.id, { ...approveForm })
  ElMessage.success(`申请已通过，组织「${appItem.orgName}」已开通`)
  approveVisible.value = false
  await Promise.all([loadApplications(), loadPendingCount(), loadOrgs()])
  void appStore.loadOrgs(true)
}

const rejectVisible = ref(false)
const rejectingApp = ref<OrgApplication | null>(null)
const rejectReason = ref('')
const rejectLoading = ref(false)

function openReject(appItem: OrgApplication): void {
  rejectingApp.value = appItem
  rejectReason.value = ''
  rejectVisible.value = true
}

async function submitReject(): Promise<void> {
  const appItem = rejectingApp.value
  if (!appItem) return
  if (rejectReason.value.trim().length < 5) {
    ElMessage.warning('请填写至少 5 个字的驳回理由')
    return
  }
  rejectLoading.value = true
  try {
    await api.applications.reject(appItem.id, rejectReason.value.trim())
    ElMessage.success('申请已驳回')
    rejectVisible.value = false
    await Promise.all([loadApplications(), loadPendingCount()])
  } finally {
    rejectLoading.value = false
  }
}

onMounted(() => {
  void loadOrgs()
  void loadPendingCount()
})

/* ------------------------------ 展示辅助 ------------------------------ */

async function copyValue(text: string): Promise<void> {
  const ok = await copyText(text)
  if (ok) ElMessage.success('已复制')
  else ElMessage.error('复制失败，请手动选择复制')
}

function userQuotaPercent(org: Org): number {
  if (!org.quotaUsers) return 0
  return Math.min(100, Math.round(((org.userCount ?? 0) / org.quotaUsers) * 100))
}

function roomQuotaPercent(org: Org): number {
  if (!org.quotaRooms) return 0
  return Math.min(100, Math.round(((org.roomCount ?? 0) / org.quotaRooms) * 100))
}
</script>

<template>
  <div class="wl-page orgs">
    <PageHeader
      eyebrow="运营管理"
      title="组织管理"
      description="维护组织信息、成员配额与组织管理员；审核来自公开渠道的组织开通申请。"
    >
      <template #actions>
        <el-button v-if="canManage" type="primary" :icon="Plus" @click="openCreate">新建组织</el-button>
      </template>
    </PageHeader>

    <el-tabs v-model="activeTab" class="org-tabs" @tab-change="onTabChange">
      <el-tab-pane name="list">
        <template #label>
          <span class="tab-label">组织列表</span>
        </template>

        <div class="wl-panel">
          <div class="wl-toolbar">
            <el-radio-group v-model="query.status" class="wl-segmented" @change="searchOrgs">
              <el-radio-button
                v-for="option in statusOptions"
                :key="option.label"
                :value="option.value"
              >
                {{ option.value ? option.label : '全部' }}
              </el-radio-button>
            </el-radio-group>
            <el-input
              v-model="query.q"
              placeholder="搜索名称 / 编码 / 联系人"
              clearable
              :prefix-icon="Search"
              class="keyword"
              @keyup.enter="searchOrgs"
              @clear="searchOrgs"
            />
            <el-button type="primary" plain @click="searchOrgs">查询</el-button>
            <el-button :icon="Refresh" text @click="resetQuery">重置</el-button>
            <div class="wl-toolbar__right">
              <span class="toolbar-hint">共 {{ total }} 个组织</span>
            </div>
          </div>

          <el-table v-loading="loading" :data="orgs" row-key="id" class="org-table">
            <el-table-column label="组织" min-width="232">
              <template #default="{ row }">
                <div class="org-cell">
                  <span class="org-cell__mark">{{ row.name.slice(0, 1) }}</span>
                  <div class="org-cell__text">
                    <span class="org-cell__name">{{ row.name }}</span>
                    <span class="org-cell__code mono">{{ row.code }}</span>
                  </div>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="联系人" min-width="150">
              <template #default="{ row }">
                <div class="contact-cell">
                  <span>{{ row.contactName }}</span>
                  <span class="dim mono">{{ row.contactPhone }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="成员 / 配额" min-width="150">
              <template #default="{ row }">
                <div class="quota">
                  <div class="quota__text">
                    <span class="num">{{ row.userCount ?? 0 }}</span>
                    <span class="dim">/ {{ row.quotaUsers }}</span>
                  </div>
                  <el-progress
                    :percentage="userQuotaPercent(row)"
                    :stroke-width="4"
                    :show-text="false"
                  />
                </div>
              </template>
            </el-table-column>
            <el-table-column label="房间" width="106" align="right">
              <template #default="{ row }">
                <span class="num">{{ row.roomCount ?? 0 }}</span>
                <span class="dim"> / {{ row.quotaRooms }}</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="104" align="center">
              <template #default="{ row }">
                <span class="status">
                  <span
                    class="wl-dot"
                    :class="`wl-dot--${ORG_STATUS_TAG[row.status as OrgStatus]}`"
                  />
                  <span>{{ ORG_STATUS_TEXT[row.status as OrgStatus] }}</span>
                </span>
              </template>
            </el-table-column>
            <el-table-column label="创建时间" width="146">
              <template #default="{ row }">
                <span class="dim mono">{{ formatDateTime(row.createdAt) }}</span>
              </template>
            </el-table-column>
            <el-table-column v-if="canManage" label="操作" width="176" fixed="right" align="right">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="openDetail(row)">详情</el-button>
                <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
                <el-button
                  link
                  :type="row.status === 'SUSPENDED' ? 'success' : 'danger'"
                  size="small"
                  @click="toggleOrgStatus(row)"
                >
                  {{ row.status === 'SUSPENDED' ? '启用' : '停用' }}
                </el-button>
              </template>
            </el-table-column>
            <template #empty>
              <EmptyState description="没有匹配的组织" />
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
      </el-tab-pane>

      <el-tab-pane v-if="canManage" name="applications">
        <template #label>
          <span class="tab-label">
            组织申请
            <span v-if="pendingCount" class="tab-badge">{{ pendingCount }}</span>
          </span>
        </template>

        <div class="wl-panel">
          <div class="wl-toolbar">
            <el-radio-group
              v-model="appStatus"
              class="wl-segmented"
              @change="appPage = 1; loadApplications()"
            >
              <el-radio-button
                v-for="option in appStatusOptions"
                :key="option.label"
                :value="option.value"
              >
                {{ option.value ? option.label : '全部' }}
              </el-radio-button>
            </el-radio-group>
            <el-button :icon="Refresh" text @click="loadApplications">刷新</el-button>
            <div class="wl-toolbar__right">
              <span class="toolbar-hint">共 {{ appTotal }} 条申请</span>
            </div>
          </div>

          <el-table v-loading="appLoading" :data="applications" row-key="id" class="app-table">
            <el-table-column label="申请单位" min-width="196">
              <template #default="{ row }">
                <div class="org-cell">
                  <span class="org-cell__mark">{{ row.orgName.slice(0, 1) }}</span>
                  <div class="org-cell__text">
                    <span class="org-cell__name">{{ row.orgName }}</span>
                    <span class="org-cell__code">{{ row.email || '未填写邮箱' }}</span>
                  </div>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="联系人" min-width="150">
              <template #default="{ row }">
                <div class="contact-cell">
                  <span>{{ row.contactName }}</span>
                  <span class="dim mono">{{ row.contactPhone }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="申请理由" min-width="230">
              <template #default="{ row }">
                <el-tooltip :content="row.reason" placement="top" :show-after="300">
                  <span class="reason">{{ row.reason }}</span>
                </el-tooltip>
              </template>
            </el-table-column>
            <el-table-column label="提交时间" width="146">
              <template #default="{ row }">
                <span class="dim mono">{{ formatDateTime(row.createdAt) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="104" align="center">
              <template #default="{ row }">
                <span class="status">
                  <span class="wl-dot" :class="`wl-dot--${APP_STATUS_TAG[row.status]}`" />
                  <span>{{ APP_STATUS_TEXT[row.status] }}</span>
                </span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="140" fixed="right" align="right">
              <template #default="{ row }">
                <template v-if="row.status === 'PENDING'">
                  <el-button link type="primary" size="small" @click="openApprove(row)">通过</el-button>
                  <el-button link type="danger" size="small" @click="openReject(row)">驳回</el-button>
                </template>
                <el-tooltip v-else :content="row.reviewNote || '已完成审核'" placement="top">
                  <span class="dim">审核备注</span>
                </el-tooltip>
              </template>
            </el-table-column>
            <template #empty>
              <EmptyState description="暂无组织申请" />
            </template>
          </el-table>

          <div class="wl-pagination">
            <el-pagination
              v-model:current-page="appPage"
              v-model:page-size="appSize"
              :total="appTotal"
              :page-sizes="[10, 20, 50]"
              layout="total, sizes, prev, pager, next"
              background
              @change="loadApplications"
            />
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- 新建组织 -->
    <el-dialog v-model="createVisible" title="新建组织" width="720px" :close-on-click-modal="false">
      <p class="dialog-hint">
        创建组织的同时会开通一名组织管理员账号；初始密码请通过安全渠道转交，首次登录将强制修改。
      </p>
      <el-form
        ref="createFormRef"
        :model="createForm"
        :rules="createRules"
        label-position="top"
      >
        <div class="wl-label form-section">组织信息</div>
        <div class="form-grid">
          <el-form-item label="组织名称" prop="name">
            <el-input v-model="createForm.name" placeholder="如：网安学院" />
          </el-form-item>
          <el-form-item label="组织编码" prop="code">
            <el-input v-model="createForm.code" class="mono" placeholder="如：ORG001" />
          </el-form-item>
          <el-form-item label="联系人" prop="contactName">
            <el-input v-model="createForm.contactName" placeholder="联系人姓名" />
          </el-form-item>
          <el-form-item label="联系电话" prop="contactPhone">
            <el-input v-model="createForm.contactPhone" placeholder="手机号或座机" />
          </el-form-item>
          <el-form-item label="用户配额" prop="quotaUsers">
            <el-input-number
              v-model="createForm.quotaUsers"
              :min="1"
              :max="5000"
              controls-position="right"
            />
          </el-form-item>
          <el-form-item label="房间配额" prop="quotaRooms">
            <el-input-number
              v-model="createForm.quotaRooms"
              :min="1"
              :max="2000"
              controls-position="right"
            />
          </el-form-item>
        </div>

        <div class="wl-label form-section">管理员账号</div>
        <div class="form-grid">
          <el-form-item label="用户名" prop="adminUsername">
            <el-input v-model="createForm.adminUsername" />
          </el-form-item>
          <el-form-item label="显示名" prop="adminDisplayName">
            <el-input v-model="createForm.adminDisplayName" placeholder="如：张老师" />
          </el-form-item>
          <el-form-item label="初始密码" prop="adminPassword" class="form-grid__full">
            <div class="pwd-row">
              <el-input v-model="createForm.adminPassword" class="mono" />
              <el-button
                :icon="CopyDocument"
                text
                type="primary"
                @click="copyValue(createForm.adminPassword)"
              >
                复制
              </el-button>
              <el-button text type="primary" @click="regeneratePassword">重新生成</el-button>
            </div>
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">创建组织</el-button>
      </template>
    </el-dialog>

    <!-- 编辑组织 -->
    <el-dialog v-model="editVisible" title="编辑组织" width="560px" :close-on-click-modal="false">
      <el-form ref="editFormRef" :model="editForm" :rules="editRules" label-position="top">
        <div class="form-grid">
          <el-form-item label="组织名称" prop="name" class="form-grid__full">
            <el-input v-model="editForm.name" />
          </el-form-item>
          <el-form-item label="联系人" prop="contactName">
            <el-input v-model="editForm.contactName" />
          </el-form-item>
          <el-form-item label="联系电话" prop="contactPhone">
            <el-input v-model="editForm.contactPhone" />
          </el-form-item>
          <el-form-item label="用户配额">
            <el-input-number
              v-model="editForm.quotaUsers"
              :min="1"
              :max="5000"
              controls-position="right"
            />
          </el-form-item>
          <el-form-item label="房间配额">
            <el-input-number
              v-model="editForm.quotaRooms"
              :min="1"
              :max="2000"
              controls-position="right"
            />
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" @click="submitEdit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 通过申请 -->
    <el-dialog
      v-model="approveVisible"
      title="通过组织申请"
      width="600px"
      :close-on-click-modal="false"
    >
      <p class="dialog-hint">
        为「{{ approvingApp?.orgName }}」开通组织并创建管理员账号，审核结果将通知申请人。
      </p>
      <el-form
        ref="approveFormRef"
        :model="approveForm"
        :rules="approveRules"
        label-position="top"
      >
        <div class="form-grid">
          <el-form-item label="组织编码" prop="code">
            <el-input v-model="approveForm.code" class="mono" />
          </el-form-item>
          <el-form-item label="管理员用户名" prop="adminUsername">
            <el-input v-model="approveForm.adminUsername" />
          </el-form-item>
          <el-form-item label="管理员密码" prop="adminPassword" class="form-grid__full">
            <div class="pwd-row">
              <el-input v-model="approveForm.adminPassword" class="mono" />
              <el-button
                :icon="CopyDocument"
                text
                type="primary"
                @click="copyValue(approveForm.adminPassword)"
              >
                复制
              </el-button>
            </div>
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="approveVisible = false">取消</el-button>
        <el-button type="primary" @click="submitApprove">通过并开通</el-button>
      </template>
    </el-dialog>

    <!-- 驳回申请 -->
    <el-dialog
      v-model="rejectVisible"
      title="驳回组织申请"
      width="520px"
      :close-on-click-modal="false"
    >
      <p class="dialog-hint">驳回「{{ rejectingApp?.orgName }}」的申请，理由将通过邮件告知申请人。</p>
      <el-input
        v-model="rejectReason"
        type="textarea"
        :rows="4"
        maxlength="200"
        show-word-limit
        placeholder="请填写驳回理由（至少 5 个字）"
      />
      <template #footer>
        <el-button @click="rejectVisible = false">取消</el-button>
        <el-button type="danger" :loading="rejectLoading" @click="submitReject">确认驳回</el-button>
      </template>
    </el-dialog>

    <!-- 组织详情 -->
    <el-drawer v-model="detailVisible" size="520px" destroy-on-close>
      <template #header>
        <div class="drawer-head">
          <span class="drawer-head__title">{{ detailOrg?.name }}</span>
          <span class="drawer-head__sub mono">{{ detailOrg?.code }}</span>
        </div>
      </template>
      <div v-if="detailOrg" class="detail">
        <div class="detail__status">
          <span class="wl-dot" :class="`wl-dot--${ORG_STATUS_TAG[detailOrg.status]}`" />
          <span>{{ ORG_STATUS_TEXT[detailOrg.status] }}</span>
        </div>
        <div class="detail__grid">
          <div class="detail__item">
            <span class="wl-label">联系人</span>
            <span class="detail__value">{{ detailOrg.contactName }}</span>
          </div>
          <div class="detail__item">
            <span class="wl-label">联系电话</span>
            <span class="detail__value mono">{{ detailOrg.contactPhone }}</span>
          </div>
          <div class="detail__item">
            <span class="wl-label">成员数</span>
            <span class="detail__value num">
              {{ detailOrg.userCount ?? 0 }}
              <span class="dim">/ {{ detailOrg.quotaUsers }}</span>
            </span>
          </div>
          <div class="detail__item">
            <span class="wl-label">房间数</span>
            <span class="detail__value num">
              {{ detailOrg.roomCount ?? 0 }}
              <span class="dim">/ {{ detailOrg.quotaRooms }}</span>
            </span>
          </div>
          <div class="detail__item detail__item--full">
            <span class="wl-label">创建时间</span>
            <span class="detail__value mono">{{ formatDateTime(detailOrg.createdAt) }}</span>
          </div>
        </div>
        <div class="detail__section">
          <span class="wl-label">配额使用</span>
          <div class="quota-bar">
            <div class="quota-bar__head">
              <span>成员配额</span>
              <span class="num">{{ detailOrg.userCount ?? 0 }} / {{ detailOrg.quotaUsers }}</span>
            </div>
            <el-progress
              :percentage="userQuotaPercent(detailOrg)"
              :stroke-width="6"
              :show-text="false"
            />
          </div>
          <div class="quota-bar">
            <div class="quota-bar__head">
              <span>房间配额</span>
              <span class="num">{{ detailOrg.roomCount ?? 0 }} / {{ detailOrg.quotaRooms }}</span>
            </div>
            <el-progress
              :percentage="roomQuotaPercent(detailOrg)"
              :stroke-width="6"
              :show-text="false"
            />
          </div>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.org-tabs :deep(.el-tabs__header) {
  margin-bottom: 16px;
}

.tab-label {
  display: inline-flex;
  align-items: center;
  gap: 7px;
}

.tab-badge {
  min-width: 17px;
  height: 17px;
  padding: 0 5px;
  border-radius: 9px;
  font-size: 10.5px;
  line-height: 17px;
  text-align: center;
  font-weight: 600;
  color: var(--wl-bg);
  background: var(--wl-cyan);
}

.toolbar-hint {
  font-size: 12px;
  color: var(--wl-text-3);
}

.orgs .keyword {
  width: 260px;
}

.org-table,
.app-table {
  background: transparent;
}

.org-cell {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.org-cell__mark {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  flex-shrink: 0;
  border-radius: var(--wl-radius-sm);
  font-size: 13px;
  font-weight: 600;
  color: var(--wl-cyan);
  background: var(--wl-cyan-wash);
  border: 1px solid var(--wl-line-2);
}

.org-cell__text {
  display: flex;
  flex-direction: column;
  min-width: 0;
  line-height: 1.4;
}

.org-cell__name {
  font-size: 13px;
  font-weight: 500;
  color: var(--wl-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.org-cell__code {
  font-size: 10.5px;
  color: var(--wl-text-3);
}

.contact-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 12.5px;
}

.contact-cell .dim {
  font-size: 11px;
}

.quota {
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-width: 120px;
}

.quota__text {
  font-size: 12px;
  color: var(--wl-text);
}

.quota__text .dim {
  font-size: 11px;
}

.status {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--wl-text-2);
}

.reason {
  display: block;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  font-size: 12.5px;
  color: var(--wl-text-2);
}

.dialog-hint {
  margin: 0 0 18px;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--wl-text-2);
}

.form-section {
  margin: 0 0 12px;
}

.form-section + .form-grid {
  margin-bottom: 6px;
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  column-gap: 16px;
}

.form-grid :deep(.el-form-item) {
  margin-bottom: 16px;
}

.form-grid__full {
  grid-column: 1 / -1;
}

.pwd-row {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
}

.pwd-row .el-input {
  flex: 1;
}

:deep(.el-input-number) {
  width: 100%;
}

.drawer-head {
  display: flex;
  flex-direction: column;
  gap: 5px;
  min-width: 0;
}

.drawer-head__title {
  font-size: 16px;
  font-weight: 600;
  color: var(--wl-text);
}

.drawer-head__sub {
  font-size: 11px;
  color: var(--wl-text-3);
}

.detail__status {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 18px;
  font-size: 12.5px;
  color: var(--wl-text-2);
}

.detail__grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 18px 20px;
}

.detail__item {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
}

.detail__item--full {
  grid-column: 1 / -1;
}

.detail__value {
  font-size: 13px;
  color: var(--wl-text);
}

.detail__section {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-top: 22px;
  padding-top: 18px;
  border-top: 1px solid var(--wl-line);
}

.quota-bar {
  display: flex;
  flex-direction: column;
  gap: 7px;
}

.quota-bar__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  font-size: 12px;
  color: var(--wl-text-3);
}

@media (max-width: 900px) {
  .orgs .keyword {
    width: 100%;
  }
}
</style>
