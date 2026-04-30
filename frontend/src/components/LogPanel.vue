<template>
  <div class="panel log-panel">
    <h2>📝 会话日志</h2>
    <div class="log-area" ref="logAreaRef">
      <div 
        v-for="(log, index) in sessionStore.logs" 
        :key="index"
        class="log-entry"
        :class="log.level"
      >
        [{{ log.timestamp }}] {{ log.message }}
      </div>
      <div v-if="sessionStore.logs.length === 0" class="log-entry info">
        系统初始化完成...
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, watch } from 'vue'
import { useSessionStore } from '../stores/session'

const sessionStore = useSessionStore()
const logAreaRef = ref(null)

// 自动滚动到最新日志
watch(() => sessionStore.logs.length, () => {
  if (logAreaRef.value) {
    logAreaRef.value.scrollTop = logAreaRef.value.scrollHeight
  }
})
</script>

<style scoped>
.panel {
  background: white;
  border-radius: 12px;
  padding: 25px;
  box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);
}

.log-panel h2 {
  color: #667eea;
  margin-bottom: 20px;
  font-size: 20px;
}

.log-area {
  background: #2d3748;
  color: #68d391;
  border-radius: 8px;
  padding: 15px;
  font-family: 'Courier New', monospace;
  font-size: 12px;
  max-height: 300px;
  overflow-y: auto;
}

.log-entry {
  margin-bottom: 5px;
  padding: 3px 0;
  border-bottom: 1px solid #4a5568;
}

.log-entry.info {
  color: #63b3ed;
}

.log-entry.warn {
  color: #fbd38d;
}

.log-entry.error {
  color: #fc8181;
}
</style>
