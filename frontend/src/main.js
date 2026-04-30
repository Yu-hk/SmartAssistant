import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import { useAuthStore } from './stores/auth'
import axios from 'axios'

// 配置 axios 默认 baseURL（留空，使用 Vite 代理）
// axios 请求会使用相对路径 /api，由 Vite 代理到 Gateway
axios.defaults.baseURL = ''

// Element Plus（可选，用于更丰富的 UI 组件）
// import ElementPlus from 'element-plus'
// import 'element-plus/dist/index.css'
// import * as ElementPlusIconsVue from '@element-plus/icons-vue'

const app = createApp(App)

// 使用 Pinia 状态管理
const pinia = createPinia()
app.use(pinia)

// 初始化 Auth Store 和 Axios 拦截器
const authStore = useAuthStore()
authStore.initAxiosInterceptor()

// ⭐ 页面加载时恢复用户信息（如果 localStorage 中有 token）
if (authStore.token && !authStore.user) {
  authStore.checkAuth()
}

// 使用 Vue Router
app.use(router)

// 如果使用 Element Plus，取消下面注释
// app.use(ElementPlus)
// for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
//   app.component(key, component)
// }

app.mount('#app')
