<template>
  <div class="smart-chat-container">
    <!-- 对话历史 -->
    <div class="chat-history" ref="chatHistory">
      <div 
        v-for="(message, index) in messages" 
        :key="index" 
        class="message-item"
        :class="[message.role, { 'error-message': message.isError }]"
      >
        <div class="message-avatar">
          {{ message.role === 'user' ? '👤' : message.isError ? '⚠️' : '🤖' }}
        </div>
        <div class="message-content">
          <div class="message-header">
            <span class="role-badge">{{ message.role === 'user' ? '你' : 'AI' }}</span>
            <span v-if="message.agentName" class="agent-badge">{{ getAgentDisplayName(message.agentName) }}</span>
          </div>
          <div class="message-text" v-html="formatMessage(message.content)"></div>
          <div v-if="message.thinkingContent" class="thinking-panel">
            <div class="thinking-header">🤔 AI 推理过程</div>
            <div class="thinking-content">{{ message.thinkingContent }}</div>
          </div>
          
          <!-- 智能建议按钮 -->
          <div v-if="message.suggestions && message.suggestions.length > 0" 
               class="suggestions-container">
            <div class="suggestions-title">💡 智能建议：</div>
            <div class="suggestions-list">
              <button 
                v-for="(suggestion, idx) in message.suggestions" 
                :key="idx"
                @click="handleSuggestionClick(suggestion, idx, message.sessionId)"
                class="suggestion-btn"
                :disabled="isProcessing"
              >
                <span class="suggestion-icon">💬</span>
                {{ suggestion }}
              </button>
            </div>
          </div>
        </div>
      </div>
      
      <!-- 加载状态 -->
      <div v-if="isProcessing" class="message-item system">
        <div class="message-avatar">🤖</div>
        <div class="message-content">
          <div class="typing-indicator">
            <span></span><span></span><span></span>
          </div>
        </div>
      </div>
    </div>
    
    <!-- 输入框 -->
    <div class="input-container">
      <input 
        v-model="userInput" 
        @keyup.enter="sendMessage"
        :disabled="isProcessing"
        placeholder="输入您的问题..."
        class="chat-input"
      />
      <button 
        @click="sendMessage" 
        :disabled="isProcessing || !userInput.trim()"
        class="send-btn"
      >
        发送
      </button>
    </div>
  </div>
</template>

<script>
import axios from 'axios'
import { useAuthStore } from '../stores/auth'

export default {
  name: 'SmartChat',
    data() {
    return {
      messages: [],
      userInput: '',
      isProcessing: false,
      userId: null,
      sessionId: null,
      _postHasReply: false,
      apiBaseUrl: '/api/math'
    }
  },
  computed: {
    // ⭐ 从 authStore 获取当前用户
    authStore() {
      return useAuthStore()
    }
  },
  methods: {
    /**
     * 发送消息
     */
    async sendMessage() {
      if (!this.userInput.trim() || this.isProcessing) return;
      
      const message = this.userInput.trim();
      this.userInput = '';
      
      // ⭐ 从 authStore 获取真实的 userId（数字类型）
      const currentUserId = this.authStore.currentUser?.id || 'anonymous';
      
      // ⭐ 生成请求 ID，用于 SSE 接口从 Redis 获取决策
      const requestId = `req_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
      
      // 添加用户消息
      this.messages.push({
        role: 'user',
        content: message,
        timestamp: Date.now()
      });
      
      this.scrollToBottom();
      
      // ⭐ 创建 AI 占位消息，SSE 立即发起（不等 POST 完成）
      const aiMsgIndex = this.messages.push({
        role: 'system',
        content: '',
        requestId: requestId,
        timestamp: Date.now()
      }) - 1;
      
      // ⭐ SSE 立即发起，从 Redis 获取路由决策
      this.fetchSSEReply(message, requestId, aiMsgIndex);
      
      // ⭐ POST 同时进行，完成后填充内容
      await this.fetchReply(message, currentUserId, requestId, aiMsgIndex);
    },
    
    /**
     * 获取系统回复（触发路由决策）
     */
    async fetchReply(message, userId, requestId, aiMessageIndex) {
      this.isProcessing = true;
      
      try {
        const response = await axios.post(`${this.apiBaseUrl}/chat`, {
          question: message,
          userId: userId || 'anonymous',
          sessionId: this.sessionId,
          requestId: requestId
        })
        
        const data = response.data
        
        if (!this.sessionId && data.sessionId) {
          this.sessionId = data.sessionId;
        }
        
        const replyContent = data.reply || data;
        this._postHasReply = typeof replyContent === 'string' && replyContent.length > 0 && !replyContent.startsWith('❌');
        
        // 更新占位消息内容
        if (aiMessageIndex >= 0 && this.messages[aiMessageIndex]) {
          this.messages[aiMessageIndex].content = replyContent;
          this.messages[aiMessageIndex].suggestions = data.suggestions || [];
          this.messages[aiMessageIndex].sessionId = data.sessionId;
          this.messages[aiMessageIndex].intent = data.intent;
          this.messages[aiMessageIndex].agentName = data.agentName;
        }
        
        this.scrollToBottom();
        
      } catch (error) {
        console.error('获取回复失败:', error);
        const errorMsg = error.response?.data?.error || error.message || '服务暂时不可用';
        if (aiMessageIndex >= 0 && this.messages[aiMessageIndex]) {
          this.messages[aiMessageIndex].content = `❌ ${errorMsg}`;
        } else {
          this.messages.push({
            role: 'system',
            content: `❌ ${errorMsg}`,
            timestamp: Date.now()
          });
        }
      } finally {
        this.isProcessing = false;
      }
    },
    
    /**
     * ⭐ SSE 流式获取回复（从 Redis 获取路由决策）
     */
    async fetchSSEReply(message, requestId, aiMessageIndex) {
      console.log('[SSE] fetchSSEReply 被调用, requestId:', requestId);
      this.isProcessing = true;
      
      try {
        let url = `${this.apiBaseUrl}/stream/chat?message=${encodeURIComponent(message)}&showThinking=true`;
        if (requestId) {
          url += `&requestId=${encodeURIComponent(requestId)}`;
        }
        
        const response = await fetch(url, {
          method: 'GET',
          headers: {
            'Authorization': `Bearer ${this.authStore.token || localStorage.getItem('token')}`,
            'Accept': 'text/event-stream'
          }
        });
        
        if (!response.ok) {
          throw new Error(`SSE 请求失败: ${response.status}`);
        }
        
        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';
        let lastAIIndex = aiMessageIndex;
        
        while (true) {
          const { done, value } = await reader.read();
          if (done) break;
          
          buffer += decoder.decode(value, { stream: true });
          const lines = buffer.split('\n');
          buffer = lines.pop() || '';
          
          for (const line of lines) {
            if (line.startsWith('data:')) {
              let data = line.slice(5).trim();
              if (!data) continue;
              
              try {
                const event = JSON.parse(data);
                
                if (event.type === 'routed') {
                  if (lastAIIndex >= 0) {
                    this.messages[lastAIIndex].agentName = event.agent;
                    this.messages[lastAIIndex].thinkingContent = `🎯 正在使用 ${this.getAgentDisplayName(event.agent)}...`;
                  }
                } else if (event.type === 'thinking') {
                  if (lastAIIndex >= 0) {
                    this.messages[lastAIIndex].thinkingContent = event.content || '';
                  }
                } else if (event.type === 'tool_call') {
                  if (lastAIIndex >= 0) {
                    this.messages[lastAIIndex].thinkingContent = `🔧 调用工具: ${event.toolName}`;
                  }
                } else if (event.type === 'tool_result') {
                  if (lastAIIndex >= 0) {
                    const result = event.content || event.result || '';
                    const preview = result.substring(0, 500) + (result.length > 500 ? '...' : '');
                    this.messages[lastAIIndex].thinkingContent = `✅ ${event.toolName || '工具'} 返回: ${preview}`;
                  }
                } else if (event.type === 'response') {
                  if (lastAIIndex >= 0 && event.content) {
                    // ⭐ 多 Agent 场景：分隔不同 Agent 的回复
                    if (event.agent && lastAIIndex >= 0) {
                      this.messages[lastAIIndex].thinkingContent = '';
                    }
                    this.messages[lastAIIndex].content = event.content;
                    this.scrollToBottom();
                  }
                } else if (event.type === 'summarizing') {
                  // ⭐ 任务拆解汇总中
                  if (lastAIIndex >= 0) {
                    this.messages[lastAIIndex].thinkingContent = '🔄 正在汇总多源信息...';
                  }
                } else if (event.type === 'done') {
                  if (lastAIIndex >= 0) {
                    this.messages[lastAIIndex].isStreaming = false;
                    this.messages[lastAIIndex].thinkingContent = '';
                  }
                } else if (event.type === 'error') {
                  if (lastAIIndex >= 0) {
                    const msg = this.messages[lastAIIndex];
                    msg.isError = true;
                    if (!this._postHasReply && !msg.content) {
                      msg.content = `❌ ${event.message || event.content || '处理失败'}`;
                    }
                    msg.thinkingContent = `❌ 错误: ${event.content || event.message || '处理异常'}`;
                    msg.isStreaming = false;
                  }
                }
              } catch (e) {
                console.error('[SSE] 解析数据失败:', e);
              }
            }
          }
        }
      } catch (error) {
        console.error('[SSE] SSE 请求失败:', error);
      } finally {
        this.isProcessing = false;
        this.scrollToBottom();
      }
    },
    
    /**
     * 处理建议点击
     */
    async handleSuggestionClick(suggestion, index, sessionId) {
      // 1. 记录点击事件（用于 A/B 测试和用户画像）
      try {
        await fetch(`${this.apiBaseUrl}/suggestion/click`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json'
          },
          body: JSON.stringify({
            userId: this.userId,
            suggestionIndex: index,
            sessionId: sessionId
          })
        });
      } catch (error) {
        console.warn('记录点击失败:', error);
      }
      
      // 2. 将建议作为新问题发送
      this.userInput = suggestion;
      await this.sendMessage();
    },
    
    /**
     * 格式化消息（支持 Markdown）
     */
    formatMessage(text) {
      if (!text) return '';
      
      return text
        .replace(/\*\*(.*?)\*\*/g, '<strong>$1</strong>')
        .replace(/\n/g, '<br>');
    },
    
    getAgentDisplayName(agentName) {
      const names = {
        'food_recommendation_agent': '美食推荐',
        'location_weather_agent': '天气助手',
        'travel_agent': '旅行规划',
        'router_agent': '路由助手'
      };
      return names[agentName] || agentName || 'AI';
    },

    scrollToBottom() {
      this.$nextTick(() => {
        const container = this.$refs.chatHistory;
        if (container) {
          container.scrollTop = container.scrollHeight;
        }
      });
    }
  },
  mounted() {
    // 欢迎消息
    this.messages.push({
      role: 'system',
      content: '你好！我是智能助手，可以帮你查询美食、旅游、天气等信息。请问有什么可以帮助你的吗？',
      timestamp: Date.now()
    });
  }
}
</script>

<style scoped>
.smart-chat-container {
  display: flex;
  flex-direction: column;
  height: 600px;
  max-width: 800px;
  margin: 0 auto;
  border: 1px solid #e0e0e0;
  border-radius: 12px;
  background: white;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
}

.chat-history {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
  background: #f5f5f5;
}

.message-item {
  display: flex;
  margin-bottom: 16px;
  animation: fadeIn 0.3s ease-in;
}

.message-item.user {
  flex-direction: row-reverse;
}

.message-avatar {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 20px;
  background: white;
  box-shadow: 0 2px 4px rgba(0, 0, 0, 0.1);
  flex-shrink: 0;
}

.message-content {
  max-width: 70%;
  margin: 0 12px;
}

.message-text {
  padding: 12px 16px;
  border-radius: 12px;
  background: white;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.1);
  line-height: 1.6;
  word-wrap: break-word;
}

.message-item.user .message-text {
  background: #409EFF;
  color: white;
}

.message-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}

.role-badge {
  font-size: 12px;
  color: #909399;
  font-weight: 500;
}

.agent-badge {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 10px;
  background: #ecf5ff;
  color: #409EFF;
}

.error-message .message-text {
  background: #fef0f0;
  color: #f56c6c;
  border: 1px solid #fbc4c4;
}

.thinking-panel {
  margin-top: 8px;
  padding: 10px 12px;
  background: #fdf6ec;
  border-radius: 8px;
  border-left: 3px solid #e6a23c;
}

.thinking-header {
  font-size: 12px;
  color: #e6a23c;
  font-weight: 500;
  margin-bottom: 4px;
}

.thinking-content {
  font-size: 13px;
  color: #606266;
  line-height: 1.5;
  word-wrap: break-word;
}

/* 智能建议样式 */
.suggestions-container {
  margin-top: 12px;
  padding: 12px;
  background: #f0f9ff;
  border-radius: 8px;
  border-left: 3px solid #409EFF;
}

.suggestions-title {
  font-size: 14px;
  color: #606266;
  margin-bottom: 8px;
  font-weight: 500;
}

.suggestions-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.suggestion-btn {
  display: inline-flex;
  align-items: center;
  padding: 8px 16px;
  border: 1px solid #409EFF;
  border-radius: 20px;
  background: white;
  color: #409EFF;
  font-size: 13px;
  cursor: pointer;
  transition: all 0.3s;
  outline: none;
}

.suggestion-btn:hover:not(:disabled) {
  background: #409EFF;
  color: white;
  transform: translateY(-2px);
  box-shadow: 0 4px 8px rgba(64, 158, 255, 0.3);
}

.suggestion-btn:active:not(:disabled) {
  transform: translateY(0);
}

.suggestion-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.suggestion-icon {
  margin-right: 6px;
  font-size: 14px;
}

/* 输入框样式 */
.input-container {
  display: flex;
  padding: 16px;
  border-top: 1px solid #e0e0e0;
  background: white;
  border-radius: 0 0 12px 12px;
}

.chat-input {
  flex: 1;
  padding: 12px 16px;
  border: 1px solid #dcdfe6;
  border-radius: 20px;
  font-size: 14px;
  outline: none;
  transition: border-color 0.3s;
}

.chat-input:focus {
  border-color: #409EFF;
}

.chat-input:disabled {
  background: #f5f7fa;
  cursor: not-allowed;
}

.send-btn {
  margin-left: 12px;
  padding: 12px 24px;
  background: #409EFF;
  color: white;
  border: none;
  border-radius: 20px;
  font-size: 14px;
  cursor: pointer;
  transition: all 0.3s;
  outline: none;
}

.send-btn:hover:not(:disabled) {
  background: #66b1ff;
  transform: translateY(-1px);
  box-shadow: 0 4px 8px rgba(64, 158, 255, 0.3);
}

.send-btn:disabled {
  background: #a0cfff;
  cursor: not-allowed;
}

/* 打字指示器 */
.typing-indicator {
  display: flex;
  gap: 4px;
  padding: 12px 16px;
}

.typing-indicator span {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #909399;
  animation: typing 1.4s infinite;
}

.typing-indicator span:nth-child(2) {
  animation-delay: 0.2s;
}

.typing-indicator span:nth-child(3) {
  animation-delay: 0.4s;
}

@keyframes typing {
  0%, 60%, 100% {
    transform: translateY(0);
    opacity: 0.7;
  }
  30% {
    transform: translateY(-10px);
    opacity: 1;
  }
}

@keyframes fadeIn {
  from {
    opacity: 0;
    transform: translateY(10px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

/* 响应式设计 */
@media (max-width: 768px) {
  .smart-chat-container {
    height: 100vh;
    max-width: 100%;
    border-radius: 0;
  }
  
  .message-content {
    max-width: 85%;
  }
  
  .suggestions-list {
    flex-direction: column;
  }
  
  .suggestion-btn {
    width: 100%;
    justify-content: flex-start;
  }
}
</style>
