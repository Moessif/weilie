<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import {
  CopyDocument,
  Key,
  MoreFilled,
  Plus,
  Refresh,
  Search,
  Upload,
} from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import { api } from '@/api'
import type { ImportUserRow } from '@/api'
import type { ActivationCode, ImportResult, UserItem, UserStatus } from '@/types'
import {
  copyText,
  downloadText,
  parseUserCsv,
  toCsv,
} from '@/utils/csv'
import {
  formatDateTime,
  formatRelative,
  shortId,
  statusTagType,
  statusText,
} from '@/utils/format'
import { useAuthStore } from '@/stores/auth'
import { useAppStore } from '@/stores/app'

const auth = useAuthStore()
const appStore = useAppStore()

const canManage = computed(
  () => auth.user?.role === 'SUPER_ADMIN' || auth.user?.role === 'ORG_ADMIN',
)
const isSuper = computed(() => auth.isSuperAdmin)

/* ------------------------------ 列表 ------------------------------ */

const loading = ref(false)
const users = ref<UserItem[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(10)

const query = reactive({
  orgId: undefined as number | undefined,
  status: '' as UserStatus | '',
  q: '',
})

const statusOptions: { label: string; value: UserStatus | '' }[] = [
  { label: '全部状态', value: '' },
  { label: '待激活', value: 'PENDING' },
  { label: '已激活', value: 'ACTIVE' },
  { label: '已停用', value: 'SUSPENDED' },
  { label: '已锁定', value: 'LOCKED' },
]

const orgOptions = computed(() => appStore.orgs)

async function loadUsers(): Promise<void> {
  loading.value = true
  try {
    const res = await api.users.list({
      orgId: query.orgId,
      status: query.status || undefined,
      q: query.q.trim() || undefined,
      page: page.value,
      size: size.value,
    })
    users.value = res.items
    total.value = res.total
  } catch {
    users.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function searchUsers(): void {
  page.value = 1
  void loadUsers()
}

function resetQuery(): void {
  query.q = ''
  query.status = ''
  if (isSuper.value) query.orgId = undefined
  page.value = 1
  void loadUsers()
}

function onPageChange(): void {
  void loadUsers()
}

/* ------------------------------ 新建用户 ------------------------------ */

const createVisible = ref(false)
const creating = ref(false)
const createFormRef = ref<FormInstance>()
const createForm = reactive({
  orgId: undefined as number | undefined,
  username: '',
  displayName: '',
  phone: '',
  role: 'USER',
})

const roleOptions = computed(() =>
  isSuper.value
    ? [
        { label: '普通用户', value: 'USER' },
        { label: '组织管理员', value: 'ORG_ADMIN' },
        { label: '审计员', value: 'AUDITOR' },
      ]
    : [{ label: '普通用户', value: 'USER' }],
)

function optionalPhoneValidator(_rule: unknown, value: string, callback: (error?: Error) => void): void {
  if (value && !/^1[3-9]\d{9}$/.test(value)) callback(new Error('请输入有效的手机号'))
  else callback()
}

const createRules: FormRules = {
  orgId: [{ required: true, message: '请选择所属组织', trigger: 'change' }],
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    {
      pattern: /^[a-zA-Z0-9_.-]{3,32}$/,
      message: '3–32 位字母、数字或 _ . -',
      trigger: 'blur',
    },
  ],
  displayName: [
    { required: true, message: '请输入姓名或显示名', trigger: 'blur' },
    { max: 20, message: '显示名不超过 20 个字符', trigger: 'blur' },
  ],
  phone: [{ validator: optionalPhoneValidator, trigger: 'blur' }],
}

function openCreate(): void {
  createForm.orgId = isSuper.value ? (query.orgId ?? appStore.orgs[0]?.id) : auth.user?.orgId ?? undefined
  createForm.username = ''
  createForm.displayName = ''
  createForm.phone = ''
  createForm.role = 'USER'
  createVisible.value = true
  createFormRef.value?.clearValidate()
}

async function submitCreate(): Promise<void> {
  const valid = await createFormRef.value?.validate().catch(() => false)
  if (!valid || !createForm.orgId) return
  creating.value = true
  try {
    const res = await api.users.create({
      orgId: createForm.orgId,
      username: createForm.username.trim(),
      displayName: createForm.displayName.trim(),
      phone: createForm.phone.trim(),
      role: createForm.role,
    })
    ElMessage.success(`用户 ${res.user.displayName} 已创建，请在列表中生成激活码交予用户`)
    createVisible.value = false
    page.value = 1
    await loadUsers()
  } catch {
    /* 错误提示由 api client 统一处理 */
  } finally {
    creating.value = false
  }
}

/* ------------------------------ 批量导入 ------------------------------ */

const importVisible = ref(false)
const importStep = ref<'input' | 'result'>('input')
const importOrgId = ref<number | undefined>()
const importText = ref('')
const importing = ref(false)
const importResult = ref<ImportResult | null>(null)

const parsed = computed(() => parseUserCsv(importText.value))
const tooManyRows = computed(() => parsed.value.valid.length > 200)

function openImport(): void {
  importStep.value = 'input'
  importText.value = ''
  importResult.value = null
  importOrgId.value = isSuper.value ? (query.orgId ?? appStore.orgs[0]?.id) : auth.user?.orgId ?? undefined
  importVisible.value = true
}

async function submitImport(): Promise<void> {
  if (!importOrgId.value) {
    ElMessage.warning('请选择要导入到的组织')
    return
  }
  if (!parsed.value.valid.length) {
    ElMessage.warning('没有可导入的有效行')
    return
  }
  if (tooManyRows.value) {
    ElMessage.warning('单次最多导入 200 行，请分批操作')
    return
  }
  const rows: ImportUserRow[] = parsed.value.valid.map((row) => ({
    username: row.username,
    displayName: row.displayName,
    phone: row.phone,
  }))
  importing.value = true
  try {
    importResult.value = await api.users.import(importOrgId.value, rows)
    importStep.value = 'result'
    ElMessage.success(`已导入 ${importResult.value.created} 个用户`)
    await loadUsers()
  } catch {
    /* 错误提示由 api client 统一处理 */
  } finally {
    importing.value = false
  }
}

function downloadImportCodes(): void {
  const result = importResult.value
  if (!result?.codes.length) return
  const content = toCsv(
    ['username', 'code'],
    result.codes.map((item) => [item.username, item.code]),
  )
  downloadText(`激活码-${new Date().toISOString().slice(0, 10)}.csv`, content, 'text/csv;charset=utf-8')
}

async function copyImportCodes(): Promise<void> {
  const result = importResult.value
  if (!result?.codes.length) return
  const ok = await copyText(result.codes.map((item) => `${item.username}\t${item.code}`).join('\n'))
  if (ok) ElMessage.success('激活码已复制')
  else ElMessage.error('复制失败，请手动选择复制')
}

function closeImport(): void {
  importVisible.value = false
}

/* ------------------------------ 激活码 ------------------------------ */

const codeVisible = ref(false)
const codeUser = ref<UserItem | null>(null)
const codeList = ref<ActivationCode[]>([])
const codeLoading = ref(false)
const issuing = ref(false)

const activeCode = computed(() => codeList.value.find((item) => item.status === 'UNUSED') ?? null)

async function openCodes(user: UserItem): Promise<void> {
  codeUser.value = user
  codeVisible.value = true
  await loadUserCodes()
}

async function loadUserCodes(): Promise<void> {
  const user = codeUser.value
  if (!user) return
  codeLoading.value = true
  try {
    const res = await api.users.codes(user.id)
    codeList.value = res.items
  } catch {
    codeList.value = []
  } finally {
    codeLoading.value = false
  }
}

async function generateCode(): Promise<void> {
  const user = codeUser.value
  if (!user) return
  if (activeCode.value) {
    try {
      await ElMessageBox.confirm(
        '当前存在未使用的激活码，生成新码会立即使旧码失效。是否继续？',
        '生成新激活码',
        { confirmButtonText: '生成并作废旧码', cancelButtonText: '取消', type: 'warning' },
      )
    } catch {
      return
    }
  }
  issuing.value = true
  try {
    const res = await api.users.activationCode(user.id)
    ElMessage.success(`激活码 ${res.code} 已生成（7 天有效）`)
    await loadUserCodes()
    await loadUsers()
  } finally {
    issuing.value = false
  }
}

async function revokeCode(code: ActivationCode): Promise<void> {
  try {
    await ElMessageBox.confirm(`确认作废激活码 ${code.code}？作废后不可恢复。`, '作废激活码', {
      confirmButtonText: '确认作废',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  await api.codes.revoke(code.id)
  ElMessage.success('激活码已作废')
  await loadUserCodes()
}

async function copyCode(code: string): Promise<void> {
  const ok = await copyText(code)
  if (ok) ElMessage.success('已复制到剪贴板')
  else ElMessage.error('复制失败，请手动选择复制')
}

/* ------------------------------ 重置密码 / 解绑 / 状态 ------------------------------ */

const resetVisible = ref(false)
const resetResult = ref<{ code: string; expiresAt: number; user: UserItem } | null>(null)

async function resetPassword(user: UserItem): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `将为「${user.displayName}」生成重置密码码，并解绑其当前登录设备。用户需使用新码激活并重新设置密码。`,
      '重置密码',
      { confirmButtonText: '确认重置', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  const res = await api.users.resetPassword(user.id)
  resetResult.value = { code: res.code, expiresAt: res.expiresAt, user }
  resetVisible.value = true
  await loadUsers()
}

async function unbindDevice(user: UserItem): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认解绑「${user.displayName}」的设备「${user.deviceName || user.deviceId || ''}」？解绑后该设备需重新登录。`,
      '解绑设备',
      { confirmButtonText: '确认解绑', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  await api.users.unbindDevice(user.id)
  ElMessage.success('设备已解绑')
  await loadUsers()
}

function canToggle(user: UserItem): boolean {
  return ['ACTIVE', 'SUSPENDED', 'LOCKED'].includes(user.status)
}

function toggleLabel(user: UserItem): string {
  if (user.status === 'ACTIVE') return '禁用账号'
  if (user.status === 'SUSPENDED') return '启用账号'
  if (user.status === 'LOCKED') return '解除锁定'
  return ''
}

async function toggleUser(user: UserItem): Promise<void> {
  const suspend = user.status === 'ACTIVE'
  const action = suspend ? '禁用' : user.status === 'LOCKED' ? '解除锁定' : '启用'
  try {
    await ElMessageBox.confirm(
      suspend
        ? `禁用后「${user.displayName}」将无法登录、无法上报采样，且设备会被解绑。`
        : `确认${action}「${user.displayName}」的账号？`,
      `${action}账号`,
      { confirmButtonText: `确认${action}`, cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  await api.users.update(user.id, { status: suspend ? 'SUSPENDED' : 'ACTIVE' })
  ElMessage.success(`账号已${action}`)
  await loadUsers()
}

type RowAction = 'reset' | 'unbind' | 'toggle'
interface RowCommand {
  action: RowAction
  user: UserItem
}

function handleRowCommand(command: string | number | object): void {
  const cmd = command as RowCommand
  if (!cmd?.user) return
  if (cmd.action === 'reset') void resetPassword(cmd.user)
  else if (cmd.action === 'unbind') void unbindDevice(cmd.user)
  else if (cmd.action === 'toggle') void toggleUser(cmd.user)
}

/* ------------------------------ 展示辅助 ------------------------------ */

function roleTagType(role: UserItem['role']): 'danger' | 'warning' | 'info' {
  if (role === 'SUPER_ADMIN') return 'danger'
  if (role === 'ORG_ADMIN') return 'warning'
  return 'info'
}

function avatarText(user: UserItem): string {
  return (user.displayName || user.username).slice(0, 1).toUpperCase()
}

onMounted(async () => {
  if (isSuper.value) {
    await appStore.loadOrgs()
  } else if (auth.user?.orgId) {
    query.orgId = auth.user.orgId
  }
  await loadUsers()
})
</script>

<template>
  <div class="wl-page users">
    <PageHeader
      eyebrow="账号"
      title="用户与账号"
      description="管理组织成员、登录设备与激活状态；批量导入会为每个成功用户生成一次性激活码。"
    >
      <template #actions>
        <template v-if="canManage">
          <el-button :icon="Upload" plain @click="openImport">批量导入</el-button>
          <el-button type="primary" :icon="Plus" @click="openCreate">新建用户</el-button>
        </template>
      </template>
    </PageHeader>

    <div class="wl-panel">
      <div class="wl-toolbar">
        <el-select
          v-if="isSuper"
          v-model="query.orgId"
          placeholder="全部组织"
          clearable
          filterable
          class="org-select"
          @change="searchUsers"
        >
          <el-option v-for="org in orgOptions" :key="org.id" :label="org.name" :value="org.id" />
        </el-select>
        <el-radio-group v-model="query.status" class="wl-segmented" @change="searchUsers">
          <el-radio-button v-for="option in statusOptions" :key="option.label" :value="option.value">
            {{ option.value ? option.label : '全部' }}
          </el-radio-button>
        </el-radio-group>
        <el-input
          v-model="query.q"
          placeholder="搜索用户名 / 姓名 / 手机号"
          clearable
          :prefix-icon="Search"
          class="keyword"
          @keyup.enter="searchUsers"
          @clear="searchUsers"
        />
        <el-button type="primary" plain @click="searchUsers">查询</el-button>
        <el-button :icon="Refresh" text @click="resetQuery">重置</el-button>
        <div class="wl-toolbar__right">
          <span class="toolbar-hint">共 {{ total }} 名用户</span>
        </div>
      </div>

      <el-table v-loading="loading" :data="users" row-key="id" class="user-table">
        <el-table-column label="用户" min-width="186">
          <template #default="{ row }">
            <div class="user-cell">
              <span class="user-cell__avatar">{{ avatarText(row) }}</span>
              <div class="user-cell__text">
                <span class="user-cell__name">{{ row.displayName }}</span>
                <span class="user-cell__account mono">@{{ row.username }}</span>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="组织" min-width="122" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="dim">{{ row.orgName }}</span>
          </template>
        </el-table-column>
        <el-table-column label="角色" width="104">
          <template #default="{ row }">
            <el-tag :type="roleTagType(row.role)" size="small" effect="plain">
              {{ statusText(row.role) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="98">
          <template #default="{ row }">
            <span class="status-cell">
              <span class="wl-dot" :class="`wl-dot--${statusTagType(row.status)}`" />
              <span class="status-cell__text">{{ statusText(row.status) }}</span>
            </span>
          </template>
        </el-table-column>
        <el-table-column label="设备绑定" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">
            <div v-if="row.deviceId" class="device-cell">
              <span class="status-cell">
                <span class="wl-dot wl-dot--success" />
                <span class="status-cell__text">已绑定</span>
              </span>
              <span class="device-cell__name mono">
                {{ row.deviceName || '安卓设备' }}
                <span class="device-cell__id">{{ shortId(row.deviceId) }}</span>
              </span>
            </div>
            <span v-else class="status-cell">
              <span class="wl-dot" />
              <span class="status-cell__text dim">未绑定</span>
            </span>
          </template>
        </el-table-column>
        <el-table-column label="最近登录" width="112">
          <template #default="{ row }">
            <el-tooltip
              v-if="row.lastLoginAt"
              :content="formatDateTime(row.lastLoginAt)"
              placement="top"
            >
              <span class="dim">{{ formatRelative(row.lastLoginAt) }}</span>
            </el-tooltip>
            <span v-else class="dim">—</span>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="148">
          <template #default="{ row }">
            <span class="mono time-cell">{{ formatDateTime(row.createdAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column v-if="canManage" label="操作" width="158" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" :icon="Key" @click="openCodes(row)">
              激活码
            </el-button>
            <el-dropdown
              trigger="click"
              placement="bottom-end"
              @command="handleRowCommand"
            >
              <el-button link size="small" :icon="MoreFilled">更多</el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item :command="{ action: 'reset', user: row }">重置密码</el-dropdown-item>
                  <el-dropdown-item
                    :command="{ action: 'unbind', user: row }"
                    :disabled="!row.deviceId"
                  >
                    解绑设备
                  </el-dropdown-item>
                  <el-dropdown-item
                    v-if="canToggle(row)"
                    :command="{ action: 'toggle', user: row }"
                    divided
                  >
                    {{ toggleLabel(row) }}
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>
        </el-table-column>
        <template #empty>
          <EmptyState description="没有匹配的用户" />
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

    <!-- 新建用户 -->
    <el-dialog v-model="createVisible" title="新建用户" width="620px" :close-on-click-modal="false">
      <p class="dialog-hint">
        新建用户初始为「待激活」状态。创建后在列表点击「激活码」生成一次性激活码，连同账号交给用户激活。
      </p>
      <el-form
        ref="createFormRef"
        :model="createForm"
        :rules="createRules"
        label-position="top"
        class="create-form"
      >
        <div class="form-grid">
          <el-form-item label="所属组织" prop="orgId" class="form-grid__full">
            <el-select
              v-model="createForm.orgId"
              placeholder="请选择组织"
              filterable
              :disabled="!isSuper"
              class="full"
            >
              <el-option v-for="org in orgOptions" :key="org.id" :label="org.name" :value="org.id" />
              <el-option
                v-if="!isSuper && auth.user"
                :label="auth.user.orgName || '当前组织'"
                :value="auth.user.orgId || 0"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="用户名" prop="username">
            <el-input v-model="createForm.username" class="mono" placeholder="登录账号" />
          </el-form-item>
          <el-form-item label="显示名" prop="displayName">
            <el-input v-model="createForm.displayName" placeholder="姓名或昵称" />
          </el-form-item>
          <el-form-item label="手机号" prop="phone">
            <el-input v-model="createForm.phone" placeholder="可选" />
          </el-form-item>
          <el-form-item label="角色">
            <el-select v-model="createForm.role" :disabled="!isSuper" class="full">
              <el-option
                v-for="option in roleOptions"
                :key="option.value"
                :label="option.label"
                :value="option.value"
              />
            </el-select>
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">创建用户</el-button>
      </template>
    </el-dialog>

    <!-- 批量导入 -->
    <el-dialog
      v-model="importVisible"
      title="批量导入用户"
      width="780px"
      :close-on-click-modal="false"
      @closed="closeImport"
    >
      <template v-if="importStep === 'input'">
        <p class="dialog-hint">
          每行一个用户，列顺序固定；导入成功后为每个用户生成一次性激活码。
        </p>
        <div class="csv-block">
          <div class="wl-label">CSV 格式说明</div>
          <pre class="csv-sample mono">用户名, 显示名, 手机号
zhangsan, 张三, 13800000001
lisi, 李四, 13800000002
wangwu, 王五</pre>
          <p class="csv-block__hint">支持逗号或制表符分隔，可选表头，单次最多 200 行。</p>
        </div>
        <el-form label-position="top" class="import-form">
          <el-form-item label="导入到组织">
            <el-select
              v-model="importOrgId"
              placeholder="请选择组织"
              filterable
              :disabled="!isSuper"
              class="full"
            >
              <el-option v-for="org in orgOptions" :key="org.id" :label="org.name" :value="org.id" />
              <el-option
                v-if="!isSuper && auth.user"
                :label="auth.user.orgName || '当前组织'"
                :value="auth.user.orgId || 0"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="粘贴数据">
            <el-input
              v-model="importText"
              type="textarea"
              :rows="8"
              class="mono import-input"
              placeholder="zhangsan,张三,13800000001&#10;lisi,李四,13800000002&#10;wangwu,王五"
            />
          </el-form-item>
        </el-form>

        <div v-if="importText.trim()" class="parse-summary">
          <span class="parse-chip parse-chip--ok">有效 {{ parsed.valid.length }} 行</span>
          <span v-if="parsed.invalid.length" class="parse-chip parse-chip--bad">
            异常 {{ parsed.invalid.length }} 行
          </span>
          <span v-if="parsed.headerDetected" class="parse-chip">已识别表头</span>
          <span v-if="tooManyRows" class="parse-chip parse-chip--bad">超过 200 行，请分批导入</span>
        </div>

        <el-table
          v-if="parsed.valid.length"
          :data="parsed.valid.slice(0, 6)"
          size="small"
          class="preview-table"
        >
          <el-table-column label="行" width="52">
            <template #default="{ row }"><span class="dim">{{ row.row }}</span></template>
          </el-table-column>
          <el-table-column prop="username" label="用户名" min-width="130" />
          <el-table-column prop="displayName" label="显示名" min-width="120" />
          <el-table-column prop="phone" label="手机号" min-width="130">
            <template #default="{ row }"><span class="dim">{{ row.phone || '—' }}</span></template>
          </el-table-column>
        </el-table>
        <p v-if="parsed.valid.length > 6" class="preview-more dim">
          仅预览前 6 行，共 {{ parsed.valid.length }} 行有效数据
        </p>

        <div v-if="parsed.invalid.length" class="invalid-block">
          <div class="invalid-block__title">以下 {{ parsed.invalid.length }} 行未通过校验，将被跳过</div>
          <div v-for="row in parsed.invalid.slice(0, 8)" :key="row.row" class="invalid-row">
            <span class="dim">第 {{ row.row }} 行</span>
            <span class="mono">{{ row.username || '（空用户名）' }}</span>
            <span class="invalid-row__reason">{{ row.error }}</span>
          </div>
          <p v-if="parsed.invalid.length > 8" class="preview-more dim">
            还有 {{ parsed.invalid.length - 8 }} 行异常数据未展示
          </p>
        </div>
      </template>

      <template v-else>
        <div class="import-result">
          <div class="import-result__head">
            <span class="wl-dot wl-dot--success import-result__dot" />
            <div>
              <div class="import-result__title">导入完成，成功 {{ importResult?.created ?? 0 }} 个用户</div>
              <div class="import-result__desc">
                {{
                  importResult?.failed.length
                    ? `另有 ${importResult.failed.length} 行失败，可修正后重新导入。`
                    : '全部数据已成功导入，激活码已同时生成。'
                }}
              </div>
            </div>
          </div>

          <template v-if="importResult?.codes.length">
            <div class="result-section-title">
              <span>激活码（{{ importResult.codes.length }}）</span>
              <div class="result-actions">
                <el-button link type="primary" size="small" :icon="CopyDocument" @click="copyImportCodes">
                  复制全部
                </el-button>
                <el-button link type="primary" size="small" @click="downloadImportCodes">下载 CSV</el-button>
              </div>
            </div>
            <el-table :data="importResult.codes" size="small" max-height="220" class="preview-table">
              <el-table-column prop="username" label="用户名" min-width="160" />
              <el-table-column label="激活码" min-width="140">
                <template #default="{ row }">
                  <span class="mono code-text">{{ row.code }}</span>
                </template>
              </el-table-column>
              <el-table-column label="" width="72" align="right">
                <template #default="{ row }">
                  <el-button link type="primary" size="small" @click="copyCode(row.code)">复制</el-button>
                </template>
              </el-table-column>
            </el-table>
          </template>

          <template v-if="importResult?.failed.length">
            <div class="result-section-title result-section-title--bad">
              失败行（{{ importResult.failed.length }}）
            </div>
            <el-table :data="importResult.failed" size="small" max-height="180" class="preview-table">
              <el-table-column prop="row" label="行号" width="80" />
              <el-table-column prop="reason" label="失败原因" min-width="260" />
            </el-table>
          </template>
        </div>
      </template>

      <template #footer>
        <template v-if="importStep === 'input'">
          <el-button @click="importVisible = false">取消</el-button>
          <el-button
            type="primary"
            :loading="importing"
            :disabled="!parsed.valid.length || tooManyRows"
            @click="submitImport"
          >
            开始导入{{ parsed.valid.length ? `（${parsed.valid.length} 行）` : '' }}
          </el-button>
        </template>
        <el-button v-else type="primary" @click="importVisible = false">完成</el-button>
      </template>
    </el-dialog>

    <!-- 激活码 -->
    <el-dialog v-model="codeVisible" title="激活码" width="640px">
      <div v-if="codeUser" class="code-user">
        <span class="code-user__name">{{ codeUser.displayName }}</span>
        <span class="code-user__meta mono">@{{ codeUser.username }}</span>
        <span class="code-user__meta dim">· {{ codeUser.orgName }}</span>
      </div>

      <div v-loading="codeLoading" class="active-code">
        <template v-if="activeCode">
          <div class="wl-label">当前可用激活码</div>
          <div class="active-code__row">
            <span class="active-code__value mono">{{ activeCode.code }}</span>
            <el-button
              type="primary"
              plain
              size="small"
              :icon="CopyDocument"
              @click="copyCode(activeCode.code)"
            >
              复制
            </el-button>
          </div>
          <div class="active-code__meta">
            {{ statusText(activeCode.purpose) }} · 有效期至 {{ formatDateTime(activeCode.expiresAt) }}
          </div>
        </template>
        <template v-else>
          <EmptyState description="当前没有可用的激活码" :size="48" />
          <p class="active-code__empty-hint">生成后交给用户完成设备激活。</p>
        </template>
        <div class="active-code__actions">
          <el-button type="primary" :loading="issuing" @click="generateCode">
            {{ activeCode ? '重新生成激活码' : '生成激活码' }}
          </el-button>
          <span class="active-code__hint">8 位大写字母与数字，7 天内有效，仅可使用一次</span>
        </div>
      </div>

      <div class="code-history">
        <div class="code-history__title">历史记录</div>
        <el-table :data="codeList" size="small" max-height="210" class="preview-table">
          <el-table-column label="激活码" min-width="120">
            <template #default="{ row }">
              <span class="mono code-text">{{ row.code }}</span>
            </template>
          </el-table-column>
          <el-table-column label="用途" width="92">
            <template #default="{ row }"><span class="dim">{{ statusText(row.purpose) }}</span></template>
          </el-table-column>
          <el-table-column label="状态" width="92">
            <template #default="{ row }">
              <el-tag :type="statusTagType(row.status)" size="small" effect="dark">
                {{ statusText(row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="创建时间" width="150">
            <template #default="{ row }">
              <span class="mono time-cell">{{ formatDateTime(row.createdAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="64" align="right">
            <template #default="{ row }">
              <el-button
                v-if="row.status === 'UNUSED'"
                link
                type="danger"
                size="small"
                @click="revokeCode(row)"
              >
                作废
              </el-button>
            </template>
          </el-table-column>
          <template #empty>
            <EmptyState description="暂无历史记录" :size="40" />
          </template>
        </el-table>
      </div>
    </el-dialog>

    <!-- 重置密码 -->
    <el-dialog v-model="resetVisible" title="重置密码" width="480px">
      <div v-if="resetResult" class="reset-result">
        <p class="dialog-hint">
          已为「{{ resetResult.user.displayName }}」生成重置密码码，并解绑其登录设备。请将验证码交给用户，用户使用后需重新设置密码。
        </p>
        <div class="reset-code mono">{{ resetResult.code }}</div>
        <div class="reset-meta">有效期至 {{ formatDateTime(resetResult.expiresAt) }}</div>
        <div class="reset-actions">
          <el-button type="primary" plain :icon="CopyDocument" @click="copyCode(resetResult.code)">
            复制验证码
          </el-button>
        </div>
      </div>
      <template #footer>
        <el-button type="primary" @click="resetVisible = false">完成</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.users .org-select {
  width: 190px;
}
.users .keyword {
  width: 240px;
}
.toolbar-hint {
  font-size: 12px;
  color: var(--wl-text-3);
}

.user-table {
  background: transparent;
}
.user-table :deep(.el-table-fixed-column--right) {
  background: var(--wl-panel);
}
.user-table :deep(.el-table__body tr:hover > td.el-table-fixed-column--right) {
  background: var(--el-table-row-hover-bg-color);
}
.user-cell {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}
.user-cell__avatar {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  width: 24px;
  height: 24px;
  border-radius: var(--wl-radius-sm);
  background: var(--wl-cyan-wash);
  border: 1px solid var(--wl-line-2);
  color: var(--wl-cyan);
  font-size: 11px;
  font-weight: 600;
}
.user-cell__text {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.user-cell__name {
  font-size: 12.5px;
  color: var(--wl-text);
  line-height: 1.35;
}
.user-cell__account {
  font-size: 10.5px;
  color: var(--wl-text-3);
  line-height: 1.35;
}
.status-cell {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--wl-text-2);
}
.status-cell__text {
  line-height: 1.35;
}
.device-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.device-cell__name {
  font-size: 11px;
  color: var(--wl-text-3);
  line-height: 1.35;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.device-cell__id {
  color: var(--wl-text-4);
}
.time-cell {
  font-size: 11.5px;
  color: var(--wl-text-3);
}

.dialog-hint {
  margin: 0 0 16px;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--wl-text-2);
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
.full {
  width: 100%;
}

.csv-block {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 18px;
}
.csv-sample {
  margin: 0;
  padding: 12px 14px;
  background: var(--wl-sunken);
  border: 1px solid var(--wl-line);
  border-radius: var(--wl-radius-sm);
  font-size: 11.5px;
  line-height: 1.8;
  color: var(--wl-text-2);
  overflow-x: auto;
}
.csv-block__hint {
  margin: 0;
  font-size: 11.5px;
  color: var(--wl-text-3);
}
.import-form :deep(.el-form-item) {
  margin-bottom: 16px;
}
.import-input :deep(.el-textarea__inner) {
  font-family: var(--wl-mono);
  font-size: 12px;
  line-height: 1.7;
}

.parse-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  margin: 0 0 14px;
}
.parse-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 11.5px;
  color: var(--wl-text-2);
}
.parse-chip::before {
  content: '';
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--wl-text-4);
}
.parse-chip--ok::before {
  background: var(--wl-success);
}
.parse-chip--bad::before {
  background: var(--wl-danger);
}

.preview-table {
  background: transparent;
}
.preview-more {
  margin: 8px 0 0;
  font-size: 11.5px;
}
.invalid-block {
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px solid var(--wl-line);
}
.invalid-block__title {
  margin-bottom: 4px;
  font-size: 12px;
  color: var(--wl-warning);
}
.invalid-row {
  display: flex;
  align-items: baseline;
  gap: 10px;
  padding: 6px 0;
  font-size: 11.5px;
  border-bottom: 1px solid var(--wl-line);
}
.invalid-row__reason {
  margin-left: auto;
  color: var(--wl-text-3);
}

.import-result__head {
  display: flex;
  gap: 10px;
  align-items: flex-start;
  margin-bottom: 4px;
}
.import-result__dot {
  margin-top: 6px;
}
.import-result__title {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--wl-text);
}
.import-result__desc {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.65;
  color: var(--wl-text-3);
}
.result-section-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin: 18px 0 10px;
  font-size: 12px;
  color: var(--wl-text-2);
}
.result-section-title--bad {
  color: var(--wl-warning);
  justify-content: flex-start;
}
.result-actions {
  display: flex;
  gap: 4px;
}
.code-text {
  font-size: 12px;
  letter-spacing: 0.04em;
  color: var(--wl-text);
}

.code-user {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 14px;
}
.code-user__name {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--wl-text);
}
.code-user__meta {
  font-size: 11.5px;
  color: var(--wl-text-3);
}
.active-code {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 16px;
  background: var(--wl-sunken);
  border: 1px solid var(--wl-line);
  border-radius: var(--wl-radius);
}
.active-code__row {
  display: flex;
  align-items: center;
  gap: 12px;
}
.active-code__value {
  font-size: 20px;
  letter-spacing: 0.12em;
  color: var(--wl-text);
}
.active-code__meta {
  font-size: 11.5px;
  color: var(--wl-text-3);
}
.active-code__empty-hint {
  margin: 0;
  font-size: 11.5px;
  color: var(--wl-text-3);
  text-align: center;
}
.active-code__actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 2px;
  padding-top: 12px;
  border-top: 1px solid var(--wl-line);
}
.active-code__hint {
  font-size: 11px;
  color: var(--wl-text-4);
}
.code-history {
  margin-top: 18px;
  padding-top: 16px;
  border-top: 1px solid var(--wl-line);
}
.code-history__title {
  margin-bottom: 10px;
  font-size: 11px;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: var(--wl-text-3);
}

.reset-result {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.reset-code {
  padding: 14px 16px;
  background: var(--wl-sunken);
  border: 1px solid var(--wl-line);
  border-radius: var(--wl-radius);
  font-size: 20px;
  letter-spacing: 0.14em;
  text-align: center;
  color: var(--wl-text);
}
.reset-meta {
  font-size: 11.5px;
  color: var(--wl-text-3);
}
.reset-actions {
  display: flex;
  justify-content: flex-end;
}

@media (max-width: 900px) {
  .users .keyword {
    width: 100%;
  }
}
</style>
