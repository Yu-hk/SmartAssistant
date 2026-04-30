import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import axios from 'axios'

export const useSessionStore = defineStore('session', () => {
  // State
  const userId = ref(localStorage.getItem('userId') || '')
  const currentThreadId = ref(null)
  const activeSessions = ref(0)
  const requestCount = ref(0)
  const totalResponseTime = ref(0)
  const logs = ref([])

  // Getters
  const avgResponseTime = computed(() => {
    if (requestCount.value === 0) return 0
    return Math.round(totalResponseTime.value / requestCount.value)
  })

  const isAuthenticated = computed(() => {
    return userId.value && userId.value.trim() !== ''
  })

  // Actions
  function setUserId(id) {
    userId.value = id
    if (id) {
      localStorage.setItem('userId', id)
    } else {
      localStorage.removeItem('userId')
    }
    addLog('info', `用户 ID 已${id ? '设置' : '清除'}: ${id || '匿名模式'}`)
  }

  function setCurrentThreadId(threadId) {
    currentThreadId.value = threadId
  }

  async function refreshSession() {
    try {
      addLog('info', '正在刷新会话...')
      const response = await axios.post('/api/session/refresh', {
        userId: userId.value || null
      })
      
      if (response.data.success) {
        currentThreadId.value = response.data.threadId
        addLog('info', `会话已刷新: ${response.data.threadId}`)
        return response.data.threadId
      }
    } catch (error) {
      addLog('error', `刷新会话失败: ${error.message}`)
      throw error
    }
  }

  async function fetchSessionStats() {
    try {
      const response = await axios.get('/api/session/stats')
      activeSessions.value = response.data.activeSessions
    } catch (error) {
      console.error('获取会话统计失败:', error)
    }
  }

  function incrementRequestStats(responseTime) {
    requestCount.value++
    totalResponseTime.value += responseTime
  }

  function addLog(level, message) {
    const timestamp = new Date().toLocaleTimeString('zh-CN')
    logs.value.push({
      timestamp,
      level,
      message
    })
    
    // 限制日志数量
    if (logs.value.length > 100) {
      logs.value.shift()
    }
  }

  function clearLogs() {
    logs.value = []
  }

  function resetStats() {
    requestCount.value = 0
    totalResponseTime.value = 0
  }

  return {
    // State
    userId,
    currentThreadId,
    activeSessions,
    requestCount,
    totalResponseTime,
    logs,
    
    // Getters
    avgResponseTime,
    isAuthenticated,
    
    // Actions
    setUserId,
    setCurrentThreadId,
    refreshSession,
    fetchSessionStats,
    incrementRequestStats,
    addLog,
    clearLogs,
    resetStats
  }
})
