import { defineStore } from 'pinia'
import { ref } from 'vue'
import axios from 'axios'
import { useAuthStore } from './auth'

export const useChatStore = defineStore('chat', () => {
  // State
  const messages = ref([])
  const currentThreadId = ref(null)
  const isLoading = ref(false)
  const currentThinking = ref(null)  // ⭐ 当前思考内容
  const thinkingSteps = ref([])      // ⭐ 思考步骤历史

  // Actions
  async function loadHistory(threadId) {
    try {
      const response = await axios.get(`/api/chat/history/${threadId}`)
      messages.value = response.data.messages.map(msg => ({
        id: msg.id,
        role: msg.role,
        content: msg.content,
        agentName: msg.agentName,
        timestamp: new Date(msg.createdAt)
      }))
      currentThreadId.value = threadId
    } catch (error) {
      console.error('加载历史消息失败:', error)
    }
  }

  /**
   * ⭐ SSE 流式发送消息（支持实时思考展示）
   */
  async function sendMessage(content, agentName) {
    const authStore = useAuthStore()

    if (!currentThreadId.value) {
      currentThreadId.value = generateThreadId()
    }

    // 添加用户消息到本地
    const userMessage = {
      id: Date.now(),
      role: 'user',
      content,
      timestamp: new Date()
    }
    messages.value.push(userMessage)

    isLoading.value = true
    thinkingSteps.value = []  // 清空思考历史
    currentThinking.value = null

    // ⭐ 创建 AI 消息占位符
    const aiMessageId = Date.now() + 1
    const aiMessage = {
      id: aiMessageId,
      role: 'assistant',
      content: '',
      thinkingContent: '',  // ⭐ 思考过程内容
      agentName: agentName,
      timestamp: new Date(),
      isStreaming: true  // 标记为流式响应中
    }
    messages.value.push(aiMessage)

    // 找到消息在数组中的索引
    const aiMessageIndex = messages.value.findIndex(m => m.id === aiMessageId)

    try {
      // ⭐ 获取用户 ID
      let userIdValue = 'anonymous'
      if (authStore.currentUser) {
        const rawId = authStore.currentUser.id || authStore.currentUser.userId
        if (rawId !== undefined && rawId !== null) {
          const parsedId = parseInt(String(rawId), 10)
          if (!isNaN(parsedId)) {
            userIdValue = parsedId
          }
        }
      }

      console.log('[Chat] 开始发送消息, aiMessageIndex:', aiMessageIndex)

      // ⭐ 1. 先调用 chat 接口，触发 Router 决策并存入 Redis
      console.log('[Chat] 调用 chat 接口...')
      await fetchChatFirst(content, authStore, aiMessageIndex, messages.value)
      console.log('[Chat] chat 接口调用完成，准备调用 SSE')

      // ⭐ 2. 再调用 SSE 接口，获取实时推理过程
      console.log('[Chat] 调用 SSE 接口...')
      await fetchSSEStream(content, authStore, aiMessageIndex, messages.value)
      console.log('[Chat] SSE 接口调用完成')

      return { success: true }
    } catch (error) {
      console.error('[Chat] 请求失败:', error)
      
      // 更新错误消息
      if (aiMessageIndex >= 0 && messages.value[aiMessageIndex]) {
        messages.value[aiMessageIndex].content = `❌ 请求失败: ${error.message}`
        messages.value[aiMessageIndex].isStreaming = false
        messages.value[aiMessageIndex].isError = true
      }
      return { success: false, error: error.message }
    } finally {
      isLoading.value = false
      currentThinking.value = null
    }
  }

  /**
   * ⭐ 先调用 chat 接口，触发 Router 决策
   */
  async function fetchChatFirst(content, authStore, aiMessageIndex, messagesList) {
    const authToken = authStore.token || localStorage.getItem('token')
    
    // ⭐ 生成请求 ID，用于 SSE 接口从 Redis 获取决策
    const requestId = `req_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`
    
    try {
      const response = await fetch('/api/math/chat', {
        method: 'POST',
        headers: {
          'Authorization': authToken ? `Bearer ${authToken}` : '',
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({
          question: content,
          requestId: requestId,
          userId: getUserId()
        })
      })

      if (!response.ok) {
        throw new Error(`HTTP error! status: ${response.status}`)
      }

      const data = await response.json()
      
      // ⭐ 保存 requestId 到消息对象，供 SSE 接口使用
      if (aiMessageIndex >= 0 && messagesList[aiMessageIndex]) {
        messagesList[aiMessageIndex].requestId = requestId
        // chat 接口返回的结论先显示
        if (data.reply) {
          messagesList[aiMessageIndex].content = data.reply
        }
        if (data.agentName) {
          messagesList[aiMessageIndex].agentName = data.agentName
        }
      }

      logger.info('[Chat] chat 接口调用成功，requestId:', requestId)
      return requestId
    } catch (error) {
      console.error('[Chat] chat 接口调用失败:', error)
      console.error('[Chat] 错误详情:', error.stack)
      throw error
    }
  }

  /**
   * ⭐ 获取用户 ID
   */
  function getUserId() {
    const authStore = useAuthStore()
    if (authStore.currentUser) {
      const rawId = authStore.currentUser.id || authStore.currentUser.userId
      if (rawId !== undefined && rawId !== null) {
        const parsedId = parseInt(String(rawId), 10)
        if (!isNaN(parsedId)) {
          return parsedId
        }
      }
    }
    return 'anonymous'
  }

  /**
   * ⭐ SSE 流式请求（通过 Consumer 统一入口）
   * ⭐ 依赖 fetchChatFirst 先调用，requestId 会存入 messagesList[aiMessageIndex]
   */
  async function fetchSSEStream(content, authStore, aiMessageIndex, messagesList) {
    console.log('[SSE] fetchSSEStream 被调用')
    const authToken = authStore.token || localStorage.getItem('token')
    
    // ⭐ 从消息对象获取 requestId（fetchChatFirst 已设置）
    const requestId = messagesList[aiMessageIndex]?.requestId || ''
    console.log('[SSE] requestId:', requestId)
    
    // ⭐ Consumer 统一流式接口，前端无需知道具体 Agent
    let url = `/api/math/stream/chat?message=${encodeURIComponent(content)}&showThinking=true`
    if (requestId) {
      url += `&requestId=${encodeURIComponent(requestId)}`
    }
    console.log('[SSE] 请求 URL:', url)
    
    const response = await fetch(url, {
      method: 'GET',
      headers: {
        'Authorization': authToken ? `Bearer ${authToken}` : '',
        'Accept': 'text/event-stream'
      }
    })
    console.log('[SSE] 响应状态:', response.status)

    if (!response.ok) {
      throw new Error(`HTTP error! status: ${response.status}`)
    }

    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) break

      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop() || ''  // 保留未完成的行

      for (const line of lines) {
        if (line.startsWith('event:')) {
          continue
        }

        if (line.startsWith('data:')) {
          const data = line.slice(5).trim()
          if (!data) continue

          try {
            const event = JSON.parse(data)
            handleSSEEvent(event, aiMessageIndex, messagesList)
          } catch (e) {
            console.warn('[SSE] 解析失败:', data)
          }
        }
      }
    }

    // ⭐ 流式结束，标记消息完成
    if (aiMessageIndex >= 0 && messagesList[aiMessageIndex]) {
      messagesList[aiMessageIndex].isStreaming = false
    }
  }

  /**
   * ⭐ 处理 SSE 事件
   */
  function handleSSEEvent(event, aiMessageIndex, messagesList) {
    if (aiMessageIndex < 0 || !messagesList[aiMessageIndex]) return

    const aiMessage = messagesList[aiMessageIndex]

    switch (event.type) {
      case 'routed':
        // ⭐ 路由信息（Consumer 告知前端调用的是哪个 Agent）
        const agentDisplayName = getAgentDisplayName(event.agent)
        aiMessage.agentName = event.agent
        aiMessage.thinkingContent = `🎯 正在使用 ${agentDisplayName}...`
        thinkingSteps.value.push({
          type: 'routed',
          content: `路由到: ${agentDisplayName}`,
          agent: event.agent,
          timestamp: Date.now()
        })
        break

      case 'thinking':
        // ⭐ AI 思考过程
        currentThinking.value = event.content
        aiMessage.thinkingContent = event.content
        thinkingSteps.value.push({
          type: 'thinking',
          content: event.content,
          timestamp: Date.now()
        })
        break

      case 'tool_call':
        // ⭐ 工具调用
        thinkingSteps.value.push({
          type: 'tool_call',
          toolName: event.toolName,
          args: event.args,
          timestamp: Date.now()
        })
        aiMessage.thinkingContent = `🔧 调用工具: ${event.toolName}`
        break

      case 'tool_result':
        // ⭐ 工具结果（后端返回 content 字段）
        const resultContent = event.content || event.result
        thinkingSteps.value.push({
          type: 'tool_result',
          toolName: event.toolName || 'tool',
          result: resultContent,
          timestamp: Date.now()
        })
        aiMessage.thinkingContent = `✅ 工具返回: ${resultContent ? resultContent.substring(0, 500) + (resultContent.length > 500 ? '...' : '') : '完成'}`
        break

      case 'response':
        // ⭐ 最终回复
        aiMessage.content = event.content
        aiMessage.isStreaming = false
        break

      case 'done':
        // ⭐ 完成
        aiMessage.isStreaming = false
        break

      case 'error':
        // ⭐ 错误
        aiMessage.content = `❌ 错误: ${event.content}`
        aiMessage.isStreaming = false
        aiMessage.isError = true
        break
    }
  }

  /**
   * ⭐ 普通接口回退
   */
  async function fetchWithFallback(content, userIdValue, aiMessageIndex, messagesList, agentName) {
    const response = await axios.post('/api/math/chat', {
      question: content,
      userId: userIdValue,
      sessionId: currentThreadId.value
    })

    if (aiMessageIndex >= 0 && messagesList[aiMessageIndex]) {
      messagesList[aiMessageIndex].content = response.data.reply || response.data
      messagesList[aiMessageIndex].agentName = response.data.agentName || agentName
      messagesList[aiMessageIndex].isStreaming = false
    }
  }

  async function clearHistory() {
    if (!currentThreadId.value) return

    try {
      await axios.delete(`/api/chat/history/${currentThreadId.value}`)
      messages.value = []
    } catch (error) {
      console.error('清空历史失败:', error)
    }
  }

  function startNewChat() {
    currentThreadId.value = generateThreadId()
    messages.value = []
    thinkingSteps.value = []
    currentThinking.value = null
  }

  function generateThreadId() {
    const timestamp = Date.now()
    const random = Math.floor(Math.random() * 10000)
    return `thread_${timestamp}_${random}`
  }

  /**
   * ⭐ 获取 Agent 显示名称
   */
  function getAgentDisplayName(agentName) {
    if (!agentName) return 'AI';
    // 将 agent-type 转换为可读名称：food_recommendation → 美食推荐
    const names = {
      'food_recommendation': '美食推荐',
      'food_recommendation_agent': '美食推荐',
      'location_weather': '天气助手',
      'location_weather_agent': '天气助手',
      'travel_agent': '旅行规划'
    };
    return names[agentName] || agentName.replace(/_/g, ' ').replace(/\b\w/g, c => c.toUpperCase());
  }

  return {
    messages,
    currentThreadId,
    isLoading,
    currentThinking,
    thinkingSteps,
    loadHistory,
    sendMessage,
    clearHistory,
    startNewChat,
    getAgentDisplayName
  }
})
