<template>
  <div class="panel response-panel">
    <h2>📋 AI 响应</h2>
    <div 
      class="response-area" 
      :class="{ loading: isLoading, error: isError }"
    >
      {{ displayText }}
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'

const props = defineProps({
  response: {
    type: String,
    default: null
  },
  error: {
    type: String,
    default: null
  },
  isLoading: {
    type: Boolean,
    default: false
  }
})

const displayText = computed(() => {
  if (props.isLoading) {
    return '正在思考中...'
  }
  if (props.error) {
    return `❌ 请求失败: ${props.error}`
  }
  if (props.response) {
    return props.response
  }
  return '等待发送消息...'
})

const isError = computed(() => !!props.error)
</script>

<style scoped>
.panel {
  background: white;
  border-radius: 12px;
  padding: 25px;
  box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);
}

.response-panel h2 {
  color: #667eea;
  margin-bottom: 20px;
  font-size: 20px;
}

.response-area {
  background: #f7fafc;
  border: 2px solid #e2e8f0;
  border-radius: 8px;
  padding: 15px;
  min-height: 400px;
  max-height: 600px;
  overflow-y: auto;
  white-space: pre-wrap;
  font-size: 14px;
  line-height: 1.6;
  transition: all 0.3s;
}

.response-area.loading {
  color: #999;
  font-style: italic;
}

.response-area.error {
  color: #f56565;
  background: #fff5f5;
  border-color: #feb2b2;
}
</style>
