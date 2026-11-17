<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { Lock, User } from '@element-plus/icons-vue'
import ChangePasswordDialog from '@/components/ChangePasswordDialog.vue'
import { errorMessage } from '@/api/error'
import { mockMode } from '@/api/client'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const formRef = ref<FormInstance>()
const loading = ref(false)
const errorText = ref('')
const form = reactive({ username: '', password: '' })

const pwdVisible = ref(false)
const presetOldPassword = ref('')

const rules: FormRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 32, message: '用户名长度为 3–32 位', trigger: 'blur' },
  ],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

const redirectTarget = computed(() => {
  const target = route.query.redirect
  if (typeof target === 'string' && target.startsWith('/') && !target.startsWith('//')) return target
  return '/dashboard'
})

const specs = [
  { label: '账号体系', value: '组织隔离 · 激活码开通' },
  { label: '协同房间', value: '单房间最多 12 人' },
  { label: '操作审计', value: '登录与变更全量留痕' },
]

function fillDemo(username: string, password: string): void {
  form.username = username
  form.password = password
  errorText.value = ''
}

async function submit(): Promise<void> {
  if (loading.value) return
  errorText.value = ''
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    const user = await auth.login(form.username.trim(), form.password)
    if (user.mustChangePassword) {
      presetOldPassword.value = form.password
      pwdVisible.value = true
      return
    }
    ElMessage.success(`欢迎回来，${user.displayName}`)
    await router.replace(redirectTarget.value)
  } catch (error) {
    errorText.value = errorMessage(error) || '登录失败，请检查用户名与密码'
  } finally {
    loading.value = false
  }
}

async function onPasswordChanged(): Promise<void> {
  pwdVisible.value = false
  presetOldPassword.value = ''
  ElMessage.success('密码已更新，正在进入管理中心')
  await router.replace(redirectTarget.value)
}
</script>

<template>
  <div class="login">
    <div class="login__grid" aria-hidden="true" />

    <div class="login__body">
      <section class="side">
        <header class="side__brand">
          <span class="side__mark">围</span>
          <span class="side__brand-text">
            <span class="side__name">围猎 · 管理中心</span>
            <span class="side__sub">WEILIE ADMIN CONSOLE</span>
          </span>
        </header>

        <div class="side__lead">
          <p class="wl-eyebrow">公安无线网络 · 外场采样</p>
          <h1 class="side__title">外场采样的<br />管理端</h1>
          <p class="side__desc">
            组织、账号与激活码在一处开通，房间任务、在线轨迹与取证照片实时汇总，
            每一次授权与变更都写进审计日志。
          </p>
        </div>

        <dl class="specs">
          <div v-for="item in specs" :key="item.label" class="specs__row">
            <dt>{{ item.label }}</dt>
            <dd>{{ item.value }}</dd>
          </div>
        </dl>

        <footer class="side__foot">
          <span class="mono">v1.12.0</span>
          <span class="side__foot-sep" />
          <span>内网部署 · 仅限授权人员</span>
        </footer>
      </section>

      <section class="panel">
        <div class="panel__inner">
          <p class="wl-eyebrow panel__eyebrow">管理员登录</p>
          <h2 class="panel__title">登录管理中心</h2>
          <p class="panel__hint">使用管理员账号登录，本次登录将记入审计日志。</p>

          <el-alert v-if="errorText" class="panel__error" :title="errorText" type="error" show-icon :closable="false" />

          <el-form
            ref="formRef"
            :model="form"
            :rules="rules"
            label-position="top"
            size="large"
            class="panel__form"
            @submit.prevent="submit"
          >
            <el-form-item label="用户名" prop="username">
              <el-input
                v-model="form.username"
                placeholder="管理员用户名"
                autocomplete="username"
                :prefix-icon="User"
              />
            </el-form-item>
            <el-form-item label="密码" prop="password">
              <el-input
                v-model="form.password"
                type="password"
                show-password
                placeholder="登录密码"
                autocomplete="current-password"
                :prefix-icon="Lock"
              />
            </el-form-item>
            <el-button
              class="panel__submit"
              type="primary"
              size="large"
              :loading="loading"
              native-type="submit"
            >
              登录
            </el-button>
          </el-form>

          <div v-if="mockMode === 'on'" class="demo">
            <p class="demo__head">
              <span class="wl-dot wl-dot--warning" />
              演示数据模式（未连接服务端）
            </p>
            <button type="button" class="demo__row" @click="fillDemo('admin', 'admin12345')">
              <span class="mono">admin / admin12345</span>
              <span class="demo__role">超级管理员 · 首登改密</span>
            </button>
            <button type="button" class="demo__row" @click="fillDemo('orgadmin', 'org12345')">
              <span class="mono">orgadmin / org12345</span>
              <span class="demo__role">组织管理员</span>
            </button>
          </div>

          <p class="panel__note">连续输错密码会锁定账号；忘记密码请联系超级管理员重置。</p>
        </div>
      </section>
    </div>

    <footer class="login__foot">
      <span>© 2026 围猎 · 内部管理平台</span>
      <span class="login__foot-sep">·</span>
      <span>操作留痕，仅限授权人员使用</span>
    </footer>

    <ChangePasswordDialog
      v-model="pwdVisible"
      forced
      :preset-old-password="presetOldPassword"
      @success="onPasswordChanged"
    />
  </div>
</template>

<style scoped>
.login {
  position: relative;
  display: flex;
  flex-direction: column;
  min-height: 100vh;
  background: var(--wl-bg);
  overflow: hidden;
}

/* 单一技术底纹：2% 发丝网格，向四周淡出 */
.login__grid {
  position: absolute;
  inset: 0;
  pointer-events: none;
  background-image:
    linear-gradient(rgba(255, 255, 255, 0.022) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.022) 1px, transparent 1px);
  background-size: 64px 64px;
  mask-image: radial-gradient(ellipse 130% 105% at 12% 0%, rgba(0, 0, 0, 0.92) 0%, rgba(0, 0, 0, 0.38) 44%, transparent 84%);
  -webkit-mask-image: radial-gradient(ellipse 130% 105% at 12% 0%, rgba(0, 0, 0, 0.92) 0%, rgba(0, 0, 0, 0.38) 44%, transparent 84%);
}

.login__body {
  position: relative;
  z-index: 1;
  display: grid;
  grid-template-columns: minmax(0, 1.05fr) minmax(380px, 0.95fr);
  gap: 0;
  width: 100%;
  max-width: 1180px;
  margin: auto;
  padding: 48px 40px 24px;
  align-items: center;
}

/* ---------------- 左侧叙述 ---------------- */
.side {
  display: flex;
  flex-direction: column;
  gap: 38px;
  padding-right: 56px;
  border-right: 1px solid var(--wl-line);
  min-height: 520px;
}

.side__brand {
  display: flex;
  align-items: center;
  gap: 10px;
}

.side__mark {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border-radius: 8px;
  font-size: 15px;
  font-weight: 700;
  color: var(--wl-cyan);
  background: var(--wl-cyan-wash);
  box-shadow: inset 0 0 0 1px rgba(34, 211, 238, 0.28);
}

.side__brand-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.side__name {
  font-size: 13.5px;
  font-weight: 600;
  letter-spacing: -0.01em;
  color: var(--wl-text);
}

.side__sub {
  font-family: var(--wl-mono);
  font-size: 9px;
  letter-spacing: 0.2em;
  color: var(--wl-text-4);
}

.side__lead {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.side__title {
  margin: 0;
  font-size: 42px;
  line-height: 1.12;
  font-weight: 550;
  letter-spacing: -0.038em;
  color: var(--wl-text);
}

.side__desc {
  margin: 0;
  max-width: 40ch;
  font-size: 13.5px;
  line-height: 1.75;
  color: var(--wl-text-2);
}

.specs {
  margin: 0;
  border-top: 1px solid var(--wl-line);
}

.specs__row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 16px;
  padding: 11px 0;
  border-bottom: 1px solid var(--wl-line);
}

.specs__row dt {
  font-size: 11px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--wl-text-4);
}

.specs__row dd {
  margin: 0;
  font-size: 12.5px;
  color: var(--wl-text-2);
  text-align: right;
}

.side__foot {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: auto;
  font-size: 11px;
  color: var(--wl-text-4);
}

.side__foot-sep {
  width: 12px;
  height: 1px;
  background: var(--wl-line-3);
}

/* ---------------- 右侧表单 ---------------- */
.panel {
  display: flex;
  align-items: center;
  justify-content: center;
  padding-left: 56px;
}

.panel__inner {
  width: 100%;
  max-width: 356px;
  display: flex;
  flex-direction: column;
  gap: 0;
}

.panel__eyebrow {
  margin: 0 0 12px;
}

.panel__title {
  margin: 0;
  font-size: 23px;
  font-weight: 600;
  letter-spacing: -0.026em;
  color: var(--wl-text);
}

.panel__hint {
  margin: 8px 0 22px;
  font-size: 12.5px;
  line-height: 1.65;
  color: var(--wl-text-3);
}

.panel__error {
  margin-bottom: 16px;
}

.panel__form :deep(.el-form-item) {
  margin-bottom: 18px;
}

.panel__submit {
  width: 100%;
  height: 42px;
  margin-top: 4px;
  font-size: 13.5px;
}

.panel__note {
  margin: 18px 0 0;
  font-size: 11.5px;
  line-height: 1.65;
  color: var(--wl-text-4);
}

.demo {
  margin-top: 22px;
  padding-top: 18px;
  border-top: 1px solid var(--wl-line);
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.demo__head {
  display: flex;
  align-items: center;
  gap: 7px;
  margin: 0 0 2px;
  font-size: 11px;
  color: var(--wl-text-3);
}

.demo__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
  padding: 9px 11px;
  border: 1px solid var(--wl-line);
  border-radius: var(--wl-radius-sm);
  background: var(--wl-sunken);
  color: var(--wl-text-2);
  font-size: 11.5px;
  cursor: pointer;
  transition: border-color 0.14s ease, background 0.14s ease;
  text-align: left;
}
.demo__row:hover {
  border-color: var(--wl-line-3);
  background: var(--wl-panel);
}
.demo__row .mono {
  font-size: 11px;
  color: var(--wl-text);
}
.demo__role {
  color: var(--wl-text-4);
  white-space: nowrap;
}

.login__foot {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 18px 24px 22px;
  font-size: 11px;
  color: var(--wl-text-4);
}

.login__foot-sep {
  color: var(--wl-text-4);
}

@media (max-width: 980px) {
  .login__body {
    grid-template-columns: minmax(0, 1fr);
    max-width: 520px;
    padding: 36px 24px 12px;
    gap: 34px;
  }
  .side {
    padding-right: 0;
    border-right: none;
    min-height: 0;
    gap: 26px;
  }
  .side__title {
    font-size: 32px;
  }
  .specs {
    display: none;
  }
  .side__foot {
    margin-top: 4px;
  }
  .panel {
    padding-left: 0;
  }
  .panel__inner {
    max-width: none;
  }
}
</style>
