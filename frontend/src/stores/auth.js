import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import axios from 'axios'

export const useAuthStore = defineStore('auth', () => {
  // State
  const user = ref(null)
  const token = ref(localStorage.getItem('token') || null)
  const refreshToken = ref(localStorage.getItem('refreshToken') || null)
  
  // ⭐ 初始化时设置 axios 默认 header (如果 localStorage 中有 token)
  if (token.value) {
    axios.defaults.headers.common['Authorization'] = `Bearer ${token.value}`
    console.log('[Auth] 从 localStorage 恢复 Token')
  }
  
  // Getters
  const isAuthenticated = computed(() => !!token.value)
  const currentUser = computed(() => user.value)
  
  // Actions
  async function login(username, password) {
    try {
      console.log('[Auth] 调用登录 API...')
      const response = await axios.post('/api/auth/login', {
        username,
        password
      })
      
      console.log('[Auth] API 响应:', response.data)
      const { token: accessToken, refreshToken: newRefreshToken, userId, username: uname, email } = response.data
      
      console.log('[Auth] 设置认证信息...')
      setAuth(accessToken, newRefreshToken, { id: userId, username: uname, email })
      
      console.log('[Auth] 登录成功')
      return { success: true }
    } catch (error) {
      console.error('[Auth] 登录失败:', error)
      return { 
        success: false, 
        error: error.response?.data?.error || '登录失败' 
      }
    }
  }
  
  async function register(username, password, email) {
    try {
      const response = await axios.post('/api/auth/register', {
        username,
        password,
        email
      })
      
      const { token: accessToken, refreshToken: newRefreshToken, userId, username: uname, email: userEmail } = response.data
      
      setAuth(accessToken, newRefreshToken, { id: userId, username: uname, email: userEmail })
      
      return { success: true }
    } catch (error) {
      return { 
        success: false, 
        error: error.response?.data?.message || '注册失败' 
      }
    }
  }
  
  async function logout() {
    clearAuth()
  }
  
  async function checkAuth() {
    if (!token.value) {
      return false
    }
    
    try {
      const response = await axios.get('/api/auth/me', {
        headers: {
          'Authorization': `Bearer ${token.value}`
        }
      })
      
      // ⭐ 确保 userId 是数字类型
      const userData = response.data
      if (userData) {
        console.log('[Auth] /me 返回原始数据:', userData, 'id类型:', typeof userData.id, 'id值:', userData.id)
        
        // 如果 id 是字符串（可能返回了 "8" 而不是 8），尝试解析为数字
        if (typeof userData.id === 'string') {
          const parsed = parseInt(userData.id, 10)
          if (!isNaN(parsed)) {
            console.log('[Auth] 将 id 从字符串 "', userData.id, '" 转换为数字', parsed)
            userData.id = parsed
          }
        }
        // 如果 id 不存在但有 userId 字段，使用 userId
        if (userData.id === undefined && userData.userId !== undefined) {
          userData.id = userData.userId
        }
      }
      
      user.value = userData
      console.log('[Auth] /me 返回的用户数据:', user.value)
      return true
    } catch (error) {
      console.error('[Auth] /me 接口失败:', error)
      clearAuth()
      return false
    }
  }
  
  function setAuth(accessToken, newRefreshToken, userData) {
    token.value = accessToken
    refreshToken.value = newRefreshToken
    user.value = userData
    
    localStorage.setItem('token', accessToken)
    localStorage.setItem('refreshToken', newRefreshToken)
    
    // 设置 axios 默认 header
    axios.defaults.headers.common['Authorization'] = `Bearer ${accessToken}`
  }
  
  function clearAuth() {
    token.value = null
    refreshToken.value = null
    user.value = null
    
    localStorage.removeItem('token')
    localStorage.removeItem('refreshToken')
    
    delete axios.defaults.headers.common['Authorization']
  }
  
  function initAxiosInterceptor() {
    // 请求拦截器：自动添加 Token
    axios.interceptors.request.use(config => {
      if (token.value) {
        config.headers.Authorization = `Bearer ${token.value}`
      }
      return config
    })
    
    // 响应拦截器：处理 401 错误
    axios.interceptors.response.use(
      response => response,
      error => {
        if (error.response?.status === 401) {
          // Token 无效，跳转到登录页
          clearAuth()
          window.location.href = '/login'
        }
        return Promise.reject(error)
      }
    )
  }
  
  return {
    user,
    token,
    refreshToken,
    isAuthenticated,
    currentUser,
    login,
    register,
    logout,
    checkAuth,
    initAxiosInterceptor
  }
})
