<template>
  <div class="chat-container">
    <!-- 顶部栏 -->
    <header class="chat-header">
      <div class="header-left">
        <h1>🤖 A2A 智能助手</h1>
        <span v-if="authStore.currentUser" class="user-info">
          👤 {{ authStore.currentUser.username }}
        </span>
      </div>
      <div class="header-right">
        <button @click="startNewChat" class="btn-secondary">
          ➕ 新对话
        </button>
        <button @click="clearHistory" class="btn-danger">
          🗑️ 清空
        </button>
        <button @click="handleLogout" class="btn-outline">
          🚪 退出
        </button>
      </div>
    </header>

    <!-- 消息列表 -->
    <div class="messages-container" ref="messagesContainerRef">
      <div v-if="chatStore.messages.length === 0" class="empty-state">
        <div class="empty-icon">💬</div>
        <h2>开始新的对话</h2>
        <p>选择 Agent，输入问题，开始智能对话</p>
      </div>
      
      <div 
        v-for="message in chatStore.messages" 
        :key="message.id"
        class="message-wrapper"
        :class="message.role"
      >
        <div class="message-bubble" :class="{ error: message.isError }">
          <div class="message-header">
            <span class="role-badge">{{ message.role === 'user' ? '👤 你' : '🤖 AI' }}</span>
            <span v-if="message.agentName" class="agent-badge">
              {{ getAgentDisplayName(message.agentName) }}
            </span>
            <span class="timestamp">{{ formatTime(message.timestamp) }}</span>
          </div>
          <div class="message-content">
            {{ message.content }}
          </div>
          <!-- ⭐ 思考过程展示 -->
          <div v-if="message.thinkingContent || message.isStreaming" class="thinking-panel">
            <div class="thinking-header">
              <span class="thinking-icon">🤔</span>
              <span>AI 思考中...</span>
            </div>
            <div class="thinking-content" v-if="message.thinkingContent">
              {{ message.thinkingContent }}
            </div>
            <div class="thinking-steps" v-if="getThinkingSteps(message.id).length > 0">
              <div 
                v-for="(step, idx) in getThinkingSteps(message.id)" 
                :key="idx"
                class="thinking-step"
                :class="step.type"
              >
                <span v-if="step.type === 'routed'">🎯 {{ step.content }}</span>
                <span v-else-if="step.type === 'tool_call'">🔧 调用工具: {{ step.toolName }}</span>
                <span v-else-if="step.type === 'tool_result'">✅ {{ step.toolName }} 返回</span>
                <span v-else>{{ step.content }}</span>
              </div>
            </div>
            <div v-if="message.isStreaming" class="streaming-indicator">
              <span class="dot"></span>
              <span class="dot"></span>
              <span class="dot"></span>
            </div>
          </div>
        </div>
      </div>
      
      <!-- 加载指示器 -->
      <div v-if="chatStore.isLoading" class="loading-indicator">
        <div class="typing-dots">
          <span></span>
          <span></span>
          <span></span>
        </div>
        <span>AI 正在思考...</span>
      </div>
    </div>

    <!-- 输入区域 -->
    <div class="input-area">
      <div class="input-controls">
        <select v-model="selectedAgent" class="agent-select">
          <option value="food_recommendation_agent">🍜 美食推荐</option>
          <option value="location_weather_agent">✈️ 旅行天气</option>
        </select>
      </div>
      
      <div class="input-wrapper">
        <textarea
          v-model="inputMessage"
          @keydown.enter.exact.prevent="sendMessage"
          placeholder="输入你的问题... (Enter 发送)"
          rows="3"
          :disabled="chatStore.isLoading"
        ></textarea>
        <button 
          @click="sendMessage"
          class="btn-send"
          :disabled="!inputMessage.trim() || chatStore.isLoading"
        >
          📤 发送
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, nextTick, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { useChatStore } from '../stores/chat'

const router = useRouter()
const authStore = useAuthStore()
const chatStore = useChatStore()

const selectedAgent = ref('food_recommendation_agent')
const inputMessage = ref('')
const messagesContainerRef = ref(null)

onMounted(() => {
  scrollToBottom()
})

async function sendMessage() {
  if (!inputMessage.value.trim() || chatStore.isLoading) return
  
  const content = inputMessage.value.trim()
  inputMessage.value = ''
  
  await chatStore.sendMessage(content, selectedAgent.value)
  
  await nextTick()
  scrollToBottom()
}

function startNewChat() {
  chatStore.startNewChat()
}

async function clearHistory() {
  if (confirm('确定要清空当前对话历史吗？')) {
    await chatStore.clearHistory()
  }
}

async function handleLogout() {
  await authStore.logout()
  router.push('/login')
}

function scrollToBottom() {
  if (messagesContainerRef.value) {
    messagesContainerRef.value.scrollTop = messagesContainerRef.value.scrollHeight
  }
}

function formatTime(timestamp) {
  if (!timestamp) return ''
  const date = new Date(timestamp)
  return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
}

function getAgentDisplayName(agentName) {
  const names = {
    'food_recommendation_agent': '美食推荐',
    'location_weather_agent': '旅行天气',
    'travel_agent': '旅行规划'
  }
  return names[agentName] || agentName || 'AI'
}

// ⭐ 获取指定消息的思考步骤
function getThinkingSteps(messageId) {
  // 找到对应 AI 消息及其之前的思考步骤
  const steps = []
  for (const step of chatStore.thinkingSteps) {
    steps.push(step)
  }
  return steps
}
</script>

<style scoped>
.chat-container {
  height: 100vh;
  display: flex;
  flex-direction: column;
  background: #f5f7fa;
}

/* 顶部栏 */
.chat-header {
  background: white;
  padding: 16px 24px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
  display: flex;
  justify-content: space-between;
  align-items: center;
  z-index: 10;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 16px;
}

.header-left h1 {
  color: #667eea;
  font-size: 20px;
  margin: 0;
}

.user-info {
  color: #666;
  font-size: 14px;
}

.header-right {
  display: flex;
  gap: 10px;
}

.btn-secondary, .btn-danger, .btn-outline {
  padding: 8px 16px;
  border-radius: 6px;
  font-size: 14px;
  cursor: pointer;
  transition: all 0.3s;
  border: none;
}

.btn-secondary {
  background: #667eea;
  color: white;
}

.btn-secondary:hover {
  background: #5568d3;
}

.btn-danger {
  background: #f56565;
  color: white;
}

.btn-danger:hover {
  background: #e53e3e;
}

.btn-outline {
  background: transparent;
  border: 2px solid #667eea;
  color: #667eea;
}

.btn-outline:hover {
  background: #667eea;
  color: white;
}

/* 消息容器 */
.messages-container {
  flex: 1;
  overflow-y: auto;
  padding: 24px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.empty-state {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: #999;
}

.empty-icon {
  font-size: 64px;
  margin-bottom: 16px;
}

.empty-state h2 {
  color: #667eea;
  margin-bottom: 8px;
}

/* 消息气泡 */
.message-wrapper {
  display: flex;
  animation: fadeIn 0.3s ease-in;
}

@keyframes fadeIn {
  from { opacity: 0; transform: translateY(10px); }
  to { opacity: 1; transform: translateY(0); }
}

.message-wrapper.user {
  justify-content: flex-end;
}

.message-wrapper.assistant {
  justify-content: flex-start;
}

.message-bubble {
  max-width: 70%;
  background: white;
  padding: 16px;
  border-radius: 12px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
}

.message-wrapper.user .message-bubble {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
}

.message-bubble.error {
  background: #fee;
  border: 2px solid #fcc;
}

.message-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  font-size: 12px;
}

.role-badge {
  font-weight: 600;
}

.agent-badge {
  background: rgba(102, 126, 234, 0.1);
  color: #667eea;
  padding: 2px 8px;
  border-radius: 12px;
  font-size: 11px;
}

.timestamp {
  color: #999;
  margin-left: auto;
}

.message-content {
  line-height: 1.6;
  white-space: pre-wrap;
  word-wrap: break-word;
}

/* ⭐ 思考过程面板样式 */
.thinking-panel {
  margin-top: 12px;
  padding: 12px;
  background: linear-gradient(135deg, #f8f9fa 0%, #e9ecef 100%);
  border-radius: 8px;
  border-left: 3px solid #667eea;
  font-size: 13px;
}

.thinking-header {
  display: flex;
  align-items: center;
  gap: 6px;
  color: #667eea;
  font-weight: 600;
  margin-bottom: 8px;
}

.thinking-icon {
  font-size: 16px;
}

.thinking-content {
  color: #495057;
  line-height: 1.5;
  padding: 8px;
  background: white;
  border-radius: 4px;
  margin-bottom: 8px;
}

.thinking-steps {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.thinking-step {
  padding: 6px 10px;
  border-radius: 4px;
  font-size: 12px;
  display: flex;
  align-items: center;
  gap: 6px;
}

.thinking-step.tool_call {
  background: #fff3cd;
  color: #856404;
  border-left: 3px solid #ffc107;
}

.thinking-step.tool_result {
  background: #d4edda;
  color: #155724;
  border-left: 3px solid #28a745;
}

.thinking-step.thinking {
  background: #e8eaf6;
  color: #3f51b5;
  border-left: 3px solid #667eea;
}

/* ⭐ 路由步骤样式 */
.thinking-step.routed {
  background: #e3f2fd;
  color: #1565c0;
  border-left: 3px solid #2196f3;
}

.streaming-indicator {
  display: flex;
  gap: 4px;
  justify-content: center;
  margin-top: 8px;
}

.streaming-indicator .dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #667eea;
  animation: pulse 1.4s infinite ease-in-out both;
}

.streaming-indicator .dot:nth-child(1) { animation-delay: -0.32s; }
.streaming-indicator .dot:nth-child(2) { animation-delay: -0.16s; }

@keyframes pulse {
  0%, 80%, 100% { transform: scale(0.6); opacity: 0.5; }
  40% { transform: scale(1); opacity: 1; }
}

/* 加载指示器 */
.loading-indicator {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px;
  color: #667eea;
  font-size: 14px;
}

.typing-dots {
  display: flex;
  gap: 4px;
}

.typing-dots span {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #667eea;
  animation: bounce 1.4s infinite ease-in-out both;
}

.typing-dots span:nth-child(1) { animation-delay: -0.32s; }
.typing-dots span:nth-child(2) { animation-delay: -0.16s; }

@keyframes bounce {
  0%, 80%, 100% { transform: scale(0); }
  40% { transform: scale(1); }
}

/* 输入区域 */
.input-area {
  background: white;
  padding: 16px 24px;
  box-shadow: 0 -2px 8px rgba(0, 0, 0, 0.1);
}

.input-controls {
  margin-bottom: 12px;
}

.agent-select {
  padding: 8px 12px;
  border: 2px solid #e0e0e0;
  border-radius: 6px;
  font-size: 14px;
  cursor: pointer;
}

.input-wrapper {
  display: flex;
  gap: 12px;
  align-items: flex-end;
}

.input-wrapper textarea {
  flex: 1;
  padding: 12px;
  border: 2px solid #e0e0e0;
  border-radius: 8px;
  font-size: 14px;
  resize: none;
  font-family: inherit;
}

.input-wrapper textarea:focus {
  outline: none;
  border-color: #667eea;
}

.btn-send {
  padding: 12px 24px;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
  border: none;
  border-radius: 8px;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.3s;
}

.btn-send:hover:not(:disabled) {
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(102, 126, 234, 0.4);
}

.btn-send:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
</style>
