import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import '@/styles/fonts.css'
import '@/styles/theme.css'
import App from './App.vue'
import router from './router'
import { setUnauthorizedHandler } from '@/api/client'
import { useAuthStore } from '@/stores/auth'

const app = createApp(App)
const pinia = createPinia()

app.use(pinia)
app.use(router)
app.use(ElementPlus, { locale: zhCn })

// 任一写/读接口返回 401：清理本地会话并回到登录页（仅触发一次，见 client.ts）
setUnauthorizedHandler(() => {
  const auth = useAuthStore(pinia)
  auth.reset()
  const current = router.currentRoute.value
  if (current.name === 'login') return
  void router.replace({
    name: 'login',
    query: current.fullPath && current.fullPath !== '/' ? { redirect: current.fullPath } : {},
  })
})

app.mount('#app')
