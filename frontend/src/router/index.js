import { createRouter, createWebHistory } from 'vue-router'
import SmartChat from '@/components/SmartChat.vue'
import AnalyticsDashboard from '@/components/AnalyticsDashboard.vue'
import Login from '@/views/Login.vue'
import Register from '@/views/Register.vue'

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: Login,
    meta: {
      title: '登录',
      requiresAuth: false
    }
  },
  {
    path: '/register',
    name: 'Register',
    component: Register,
    meta: {
      title: '注册',
      requiresAuth: false
    }
  },
  {
    path: '/',
    redirect: '/chat'
  },
  {
    path: '/chat',
    name: 'Chat',
    component: SmartChat,
    meta: {
      title: '智能对话',
      icon: 'ChatDotRound',
      requiresAuth: true
    }
  },
  {
    path: '/analytics',
    name: 'Analytics',
    component: AnalyticsDashboard,
    meta: {
      title: '数据分析',
      icon: 'DataAnalysis',
      requiresAuth: true
    }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 路由守卫 - 认证检查和页面标题
router.beforeEach(async (to, from, next) => {
  // 设置页面标题
  document.title = to.meta.title ? `${to.meta.title} - A2A 智能系统` : 'A2A 智能系统'
  
  // 获取 auth store
  const authStore = await import('@/stores/auth')
  const { useAuthStore } = authStore
  const store = useAuthStore()
  
  // 检查是否需要认证
  if (to.meta.requiresAuth !== false) {
    // 需要认证的页面
    if (!store.isAuthenticated) {
      // 未登录，跳转到登录页
      next({
        path: '/login',
        query: { redirect: to.fullPath }
      })
      return
    }
  } else {
    // 不需要认证的页面（登录/注册）
    if (store.isAuthenticated) {
      // 已登录，跳转到首页
      next('/')
      return
    }
  }
  
  next()
})

export default router
