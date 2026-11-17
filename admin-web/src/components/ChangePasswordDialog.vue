<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { WarningFilled } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'

const props = withDefaults(
  defineProps<{
    modelValue: boolean
    /** 首次登录强制改密：不可关闭 */
    forced?: boolean
    /** 登录时已输入的旧密码，传入后不再要求重复输入 */
    presetOldPassword?: string
  }>(),
  { forced: false, presetOldPassword: '' },
)

const emit = defineEmits<{
  'update:modelValue': [boolean]
  success: []
}>()

const auth = useAuthStore()
const formRef = ref<FormInstance>()
const loading = ref(false)
const form = reactive({
  oldPassword: '',
  newPassword: '',
  confirmPassword: '',
})

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const showOldPassword = computed(() => !props.presetOldPassword)

const rules = computed<FormRules>(() => ({
  oldPassword: showOldPassword.value ? [{ required: true, message: '请输入原密码', trigger: 'blur' }] : [],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    {
      validator: (_rule, value: string, callback) => {
        if (!value) return callback()
        if (!/^(?=.*[A-Za-z])(?=.*\d).{8,32}$/.test(value)) {
          callback(new Error('8–32 位，且须同时包含字母与数字'))
          return
        }
        if (props.presetOldPassword && value === props.presetOldPassword) {
          callback(new Error('新密码不能与原密码相同'))
          return
        }
        callback()
      },
      trigger: 'blur',
    },
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (_rule, value: string, callback) => {
        if (value !== form.newPassword) callback(new Error('两次输入的密码不一致'))
        else callback()
      },
      trigger: 'blur',
    },
  ],
}))

watch(
  () => props.modelValue,
  (open) => {
    if (!open) return
    form.oldPassword = props.presetOldPassword
    form.newPassword = ''
    form.confirmPassword = ''
    formRef.value?.clearValidate()
  },
)

async function submit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  loading.value = true
  try {
    await auth.changePassword(props.presetOldPassword || form.oldPassword, form.newPassword)
    ElMessage.success('密码修改成功，请使用新密码登录')
    emit('success')
    visible.value = false
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="forced ? '首次登录 · 修改密码' : '修改密码'"
    width="460px"
    :show-close="!forced"
    :close-on-click-modal="!forced"
    :close-on-press-escape="!forced"
    append-to-body
    @closed="formRef?.resetFields()"
  >
    <div class="pwd-note">
      <el-icon class="pwd-note__icon"><WarningFilled /></el-icon>
      <div>
        <p v-if="forced">当前账号使用初始密码，为保障管辖数据安全，请先设置新密码后再进入管理站。</p>
        <p v-else>修改后当前会话继续有效；密码需 8–32 位，且同时包含字母与数字。</p>
      </div>
    </div>

    <el-form ref="formRef" :model="form" :rules="rules" label-width="86px" @submit.prevent>
      <el-form-item v-if="showOldPassword" label="原密码" prop="oldPassword">
        <el-input v-model="form.oldPassword" type="password" show-password placeholder="请输入当前密码" />
      </el-form-item>
      <el-form-item label="新密码" prop="newPassword">
        <el-input v-model="form.newPassword" type="password" show-password placeholder="8–32 位，字母 + 数字" />
      </el-form-item>
      <el-form-item label="确认密码" prop="confirmPassword">
        <el-input
          v-model="form.confirmPassword"
          type="password"
          show-password
          placeholder="再次输入新密码"
          @keyup.enter="submit"
        />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button v-if="!forced" @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="loading" @click="submit">保存新密码</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.pwd-note {
  display: flex;
  gap: 10px;
  padding: 11px 13px;
  margin-bottom: 18px;
  border-radius: var(--wl-radius);
  background: rgba(240, 180, 41, 0.07);
  border: 1px solid rgba(240, 180, 41, 0.2);
  color: #e7c68a;
  font-size: 12.5px;
  line-height: 1.65;
}

.pwd-note__icon {
  margin-top: 2px;
  color: var(--wl-warning);
  flex-shrink: 0;
}

.pwd-note p {
  margin: 0;
}
</style>
