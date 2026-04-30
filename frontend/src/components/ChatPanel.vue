<template>
  <div class="panel chat-panel">
    <h2>💬 发送消息</h2>
    
    <div class="input-group">
      <label for="agentSelect">选择 Agent：</label>
      <select id="agentSelect" v-model="selectedAgent">
        <option value="food_recommendation_agent">美食推荐 Agent</option>
        <option value="location_weather_agent">旅行天气 Agent</option>
      </select>
    </div>

    <div class="input-group">
      <label for="questionInput">问题：</label>
      <textarea 
        id="questionInput"
        v-model="question"
        @keydown.enter.exact.prevent="sendMessage"
        placeholder="例如：四川有什么好吃的？"
        rows="5"
      ></textarea>
    </div>

    <button 
      class="btn btn-primary send-btn" 
      @click="sendMessage"
      :disabled="isLoading"
    >
      {{ isLoading ? '发送中...' : '📤 发送' }}
    </button>

    <!-- 统计信息 -->
    <div class="stats">
      <div class="stat-card">
        <div class="value">{{ sessionStore.requestCount }}</div>
        <div class="label">总请求数</div>
      </div>
      <div class="stat-card">
        <div class="value">{{ sessionStore.avgResponseTime }}ms</div>
        <div class="label">平均响应时间</div>
      </div>
      <div class="stat-card">
        <div class="value">{{ sessionStore.activeSessions }}</div>
        <div class="label">活跃会话</div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import axios from 'axios'
import { useSessionStore } from '../stores/session'

const sessionStore = useSessionStore()

const selectedAgent = ref('food_recommendation_agent')
const question = ref('')
const isLoading = ref(false)

const emit = defineEmits(['response-received', 'send-start'])

async function sendMessage() {
  if (!question.value.trim()) {
    alert('请输入问题！')
    return
  }

  isLoading.value = true
  emit('send-start')
  
  const startTime = Date.now()

  sessionStore.addLog('info', `发送请求: [${getAgentDisplayName(selectedAgent.value)}] ${question.value}`)

  try {
    const apiUrl = selectedAgent.value === 'food_recommendation_agent' 
      ? '/api/food/recommend' 
      : '/api/travel/plan'

    const response = await axios.post(apiUrl, {
      question: question.value
    }, {
      headers: {
        'X-User-Id': sessionStore.userId || ''
      }
    })

    const endTime = Date.now()
    const responseTime = endTime - startTime

    // 更新统计
    sessionStore.incrementRequestStats(responseTime)
    
    // 发送响应给父组件
    emit('response-received', response.data)
    
    sessionStore.addLog('info', `请求成功 (耗时: ${responseTime}ms)`)
    
    // 清空输入
    question.value = ''

  } catch (error) {
    const endTime = Date.now()
    const responseTime = endTime - startTime
    
    sessionStore.incrementRequestStats(responseTime)
    emit('response-received', null, error.message)
    
    sessionStore.addLog('error', `请求失败: ${error.message}`)
  } finally {
    isLoading.value = false
  }
}

function getAgentDisplayName(agentName) {
  const names = {
    'food_recommendation_agent': '美食推荐',
    'location_weather_agent': '旅行天气'
  }
  return names[agentName] || agentName
}
</script>

<style scoped>
.panel {
  background: white;
  border-radius: 12px;
  padding: 25px;
  box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);
}

.chat-panel h2 {
  color: #667eea;
  margin-bottom: 20px;
  font-size: 20px;
}

.input-group {
  margin-bottom: 15px;
}

.input-group label {
  display: block;
  margin-bottom: 8px;
  font-weight: 600;
  color: #555;
}

.input-group select,
.input-group textarea {
  width: 100%;
  padding: 12px;
  border: 2px solid #e0e0e0;
  border-radius: 8px;
  font-size: 14px;
  font-family: inherit;
  transition: border-color 0.3s;
}

.input-group select:focus,
.input-group textarea:focus {
  outline: none;
  border-color: #667eea;
}

.send-btn {
  width: 100%;
  padding: 12px;
  margin-bottom: 20px;
}

.send-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.stats {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 15px;
}

.stat-card {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
  padding: 15px;
  border-radius: 8px;
  text-align: center;
}

.stat-card .value {
  font-size: 24px;
  font-weight: bold;
  margin-bottom: 5px;
}

.stat-card .label {
  font-size: 12px;
  opacity: 0.9;
}
</style>
