<template>
  <div id="app">
    <!-- 导航栏（仅登录后显示） -->
    <nav class="navbar" v-if="authStore.isAuthenticated">
      <div class="navbar-brand">
        <span class="logo">🤖</span>
        <h1>A2A 智能系统</h1>
      </div>
      
      <div class="navbar-menu">
        <router-link to="/chat" class="nav-item" active-class="active">
          <span class="icon">💬</span>
          <span class="text">智能对话</span>
        </router-link>
        
        <router-link to="/analytics" class="nav-item" active-class="active">
          <span class="icon">📊</span>
          <span class="text">数据分析</span>
        </router-link>
        
        <!-- 用户信息和退出按钮 -->
        <div class="user-info">
          <span class="username">{{ authStore.currentUser?.username || '用户' }}</span>
          <button @click="handleLogout" class="logout-btn" title="退出登录">
            <span>🚪 退出</span>
          </button>
        </div>
      </div>
    </nav>
    
    <!-- 路由视图 -->
    <main class="main-content" :class="{ 'full-height': !authStore.isAuthenticated }">
      <router-view v-slot="{ Component }">
        <transition name="fade" mode="out-in">
          <component :is="Component" />
        </transition>
      </router-view>
    </main>
  </div>
</template>

<script>
import { useAuthStore } from './stores/auth'
import { useRouter } from 'vue-router'

export default {
  name: 'App',
  setup() {
    const authStore = useAuthStore()
    const router = useRouter()
    
    const handleLogout = async () => {
      await authStore.logout()
      router.push('/login')
    }
    
    return {
      authStore,
      handleLogout
    }
  }
}
</script>

<style>
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

body {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
  background: #f5f7fa;
  color: #303133;
}

#app {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}

/* 导航栏样式 */
.navbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  height: 60px;
  background: white;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
  position: sticky;
  top: 0;
  z-index: 1000;
}

.navbar-brand {
  display: flex;
  align-items: center;
  gap: 12px;
}

.logo {
  font-size: 28px;
}

.navbar-brand h1 {
  font-size: 20px;
  font-weight: 600;
  color: #303133;
}

.navbar-menu {
  display: flex;
  gap: 8px;
  align-items: center;
}

.user-info {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-left: 16px;
  padding-left: 16px;
  border-left: 1px solid #e4e7ed;
}

.username {
  font-size: 14px;
  color: #606266;
  font-weight: 500;
}

.logout-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  padding: 6px 12px;
  border: 1px solid #e4e7ed;
  background: white;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.3s;
  font-size: 14px;
  color: #606266;
}

.logout-btn:hover {
  background: #fef0f0;
  border-color: #f56c6c;
  color: #f56c6c;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 16px;
  border-radius: 8px;
  text-decoration: none;
  color: #606266;
  font-size: 14px;
  transition: all 0.3s;
}

.nav-item:hover {
  background: #f5f7fa;
  color: #409EFF;
}

.nav-item.active {
  background: #ecf5ff;
  color: #409EFF;
  font-weight: 500;
}

.icon {
  font-size: 18px;
}

/* 主内容区域 */
.main-content {
  flex: 1;
  padding: 24px;
  overflow-y: auto;
}

/* 未登录时全屏显示 */
.main-content.full-height {
  height: calc(100vh - 0px);
  padding: 0;
}

/* 过渡动画 */
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.3s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}

/* 响应式设计 */
@media (max-width: 768px) {
  .navbar {
    padding: 0 16px;
  }
  
  .navbar-brand h1 {
    font-size: 16px;
  }
  
  .nav-item .text {
    display: none;
  }
  
  .nav-item {
    padding: 8px 12px;
  }
  
  .main-content {
    padding: 16px;
  }
}
</style>
