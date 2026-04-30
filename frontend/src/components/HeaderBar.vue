<template>
  <header class="header">
    <h1>🤖 A2A 智能助手 - 长期记忆演示</h1>
    <div class="session-info">
      <div class="input-group">
        <label for="userId">用户 ID：</label>
        <input 
          type="text" 
          id="userId"
          v-model="localUserId"
          @change="handleUserIdChange"
          placeholder="输入用户ID（留空为匿名）"
        />
      </div>
      <div class="thread-info">
        <label>当前 ThreadId：</label>
        <span class="thread-id">{{ sessionStore.currentThreadId || '未设置' }}</span>
      </div>
      <button class="btn btn-primary" @click="handleRefreshSession">
        🔄 刷新会话
      </button>
      <button class="btn btn-danger" @click="$emit('clear-history')">
        🗑️ 清除历史
      </button>
    </div>
  </header>
</template>

<script setup>
import { ref, watch } from 'vue'
import { useSessionStore } from '../stores/session'

const sessionStore = useSessionStore()
const localUserId = ref(sessionStore.userId)

const emit = defineEmits(['refresh-session', 'clear-history'])

watch(() => sessionStore.userId, (newVal) => {
  localUserId.value = newVal
})

function handleUserIdChange() {
  sessionStore.setUserId(localUserId.value.trim())
}

async function handleRefreshSession() {
  try {
    await sessionStore.refreshSession()
    emit('refresh-session')
  } catch (error) {
    console.error('刷新会话失败:', error)
  }
}
</script>

<style scoped>
.header {
  background: white;
  border-radius: 12px;
  padding: 20px 30px;
  margin-bottom: 20px;
  box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);
}

.header h1 {
  color: #667eea;
  margin-bottom: 10px;
  font-size: 24px;
}

.session-info {
  display: flex;
  gap: 20px;
  align-items: center;
  flex-wrap: wrap;
}

.input-group {
  display: flex;
  align-items: center;
  gap: 8px;
}

.input-group label {
  font-weight: 600;
  color: #333;
  white-space: nowrap;
}

.input-group input {
  padding: 8px 12px;
  border: 2px solid #e0e0e0;
  border-radius: 6px;
  font-size: 14px;
  transition: border-color 0.3s;
  min-width: 200px;
}

.input-group input:focus {
  outline: none;
  border-color: #667eea;
}

.thread-info {
  display: flex;
  align-items: center;
  gap: 8px;
}

.thread-info label {
  font-weight: 600;
  color: #333;
  white-space: nowrap;
}

.thread-id {
  color: #667eea;
  font-family: monospace;
  font-size: 13px;
  max-width: 250px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.btn {
  padding: 8px 16px;
  border: none;
  border-radius: 6px;
  cursor: pointer;
  font-size: 14px;
  font-weight: 600;
  transition: all 0.3s;
  white-space: nowrap;
}

.btn-primary {
  background: #667eea;
  color: white;
}

.btn-primary:hover {
  background: #5568d3;
  transform: translateY(-2px);
  box-shadow: 0 4px 8px rgba(102, 126, 234, 0.3);
}

.btn-danger {
  background: #f56565;
  color: white;
}

.btn-danger:hover {
  background: #e53e3e;
}
</style>
