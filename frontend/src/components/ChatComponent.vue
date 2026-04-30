<template>
  <div class="chat-container">
    <!-- 聊天头部 -->
    <div class="chat-header">
      <h3>🤖 智能旅行助手</h3>
      <div class="header-info">
        <span v-if="connectionStatus === 'connected'" class="status connected">
          ● 已连接
        </span>
        <span v-else-if="connectionStatus === 'connecting'" class="status connecting">
          ◐ 连接中...
        </span>
        <span v-else class="status disconnected">
          ○ 未连接
        </span>
        <button @click="clearChat" class="clear-btn" title="清空对话">🗑️</button>
      </div>
    </div>

    <!-- 消息列表 -->
    <div class="messages-container" ref="messagesContainer">
      <div v-if="messages.length === 0" class="welcome-message">
        <p>👋 您好！我是智能旅行助手</p>
        <p>可以帮您查询天气、推荐美食、规划行程等</p>
        <p class="hint">试试问我："北京的天气怎么样？"</p>
      </div>

      <div
        v-for="(msg, index) in messages"
        :key="index"
        :class="['message', msg.isUser ? 'user-message' : 'ai-message']"
      >
        <div class="message-avatar">
          {{ msg.isUser ? '👤' : '🤖' }}
        </div>
        <div class="message-content">
          <div class="message-text">{{ msg.content }}</div>
          <div v-if="msg.metadata" class="message-metadata">
            <span v-if="msg.metadata.targetAgent">
              🎯 {{ msg.metadata.targetAgent }}
            </span>
            <span v-if="msg.metadata.turnCount">
              | 第 {{ msg.metadata.turnCount }} 轮
            </span>
          </div>
          <div class="message-time">{{ formatTime(msg.timestamp) }}</div>
        </div>
      </div>

      <!-- 加载指示器 -->
      <div v-if="isProcessing" class="message ai-message">
        <div class="message-avatar">🤖</div>
        <div class="message-content">
          <div class="typing-indicator">
            <span></span>
            <span></span>
            <span></span>
          </div>
        </div>
      </div>
    </div>

    <!-- 输入区域 -->
    <div class="input-container">
      <input
        v-model="inputMessage"
        @keyup.enter="sendMessage"
        :disabled="!isConnected || isProcessing"
        placeholder="输入您的问题..."
        class="message-input"
      />
      <button
        @click="sendMessage"
        :disabled="!isConnected || isProcessing || !inputMessage.trim()"
        class="send-button"
      >
        {{ isProcessing ? '发送中...' : '发送' }}
      </button>
    </div>
  </div>
</template>

<script>
export default {
  name: 'ChatComponent',
  
  data() {
    return {
      ws: null,
      sessionId: null,
      userId: '1', // 可以从用户store获取
      inputMessage: '',
      messages: [],
      isProcessing: false,
      connectionStatus: 'disconnected', // disconnected, connecting, connected
      reconnectAttempts: 0,
      maxReconnectAttempts: 5
    };
  },

  computed: {
    isConnected() {
      return this.connectionStatus === 'connected';
    }
  },

  mounted() {
    this.connectWebSocket();
  },

  beforeUnmount() {
    this.disconnectWebSocket();
  },

  methods: {
    /**
     * 连接 WebSocket
     */
    connectWebSocket() {
      this.connectionStatus = 'connecting';
      
      const wsUrl = 'ws://localhost:8081/ws/conversation';
      this.ws = new WebSocket(wsUrl);

      this.ws.onopen = () => {
        console.log('WebSocket 连接成功');
        this.connectionStatus = 'connected';
        this.reconnectAttempts = 0;
        this.createSession();
      };

      this.ws.onmessage = (event) => {
        const data = JSON.parse(event.data);
        this.handleMessage(data);
      };

      this.ws.onerror = (error) => {
        console.error('WebSocket 错误:', error);
        this.connectionStatus = 'disconnected';
      };

      this.ws.onclose = () => {
        console.log('WebSocket 连接关闭');
        this.connectionStatus = 'disconnected';
        
        // 自动重连
        if (this.reconnectAttempts < this.maxReconnectAttempts) {
          this.reconnectAttempts++;
          console.log(`尝试重连 (${this.reconnectAttempts}/${this.maxReconnectAttempts})...`);
          setTimeout(() => this.connectWebSocket(), 3000);
        }
      };
    },

    /**
     * 断开 WebSocket
     */
    disconnectWebSocket() {
      if (this.ws) {
        this.ws.close();
        this.ws = null;
      }
    },

    /**
     * 创建会话
     */
    createSession() {
      if (!this.ws || this.ws.readyState !== WebSocket.OPEN) return;

      this.ws.send(JSON.stringify({
        type: 'create_session',
        userId: this.userId
      }));
    },

    /**
     * 处理收到的消息
     */
    handleMessage(data) {
      console.log('收到消息:', data);

      switch (data.type) {
        case 'session_created':
          this.sessionId = data.sessionId;
          console.log('会话创建成功:', this.sessionId);
          break;

        case 'processing':
          this.isProcessing = true;
          this.scrollToBottom();
          break;

        case 'chat_response':
          this.isProcessing = false;
          
          // 添加 AI 回复
          this.messages.push({
            content: this.formatResponse(data),
            isUser: false,
            timestamp: new Date(),
            metadata: {
              targetAgent: data.targetAgent,
              turnCount: data.turnCount
            }
          });
          
          this.scrollToBottom();
          break;

        case 'context':
          console.log('上下文信息:', data.context);
          break;

        case 'error':
          this.isProcessing = false;
          this.messages.push({
            content: '❌ 错误: ' + data.message,
            isUser: false,
            timestamp: new Date()
          });
          this.scrollToBottom();
          break;

        default:
          console.warn('未知消息类型:', data.type);
      }
    },

    /**
     * 发送消息
     */
    sendMessage() {
      if (!this.inputMessage.trim() || !this.sessionId || this.isProcessing) {
        return;
      }

      const message = this.inputMessage.trim();

      // 添加用户消息到界面
      this.messages.push({
        content: message,
        isUser: true,
        timestamp: new Date()
      });

      // 发送到 WebSocket
      this.ws.send(JSON.stringify({
        type: 'chat',
        sessionId: this.sessionId,
        userId: parseInt(this.userId),
        message: message
      }));

      // 清空输入框
      this.inputMessage = '';
      this.scrollToBottom();
    },

    /**
     * 格式化响应
     */
    formatResponse(data) {
      // 优先使用 routerResponse
      if (data.routerResponse && data.routerResponse !== '⚠️ Router 返回空结果') {
        return data.routerResponse;
      }

      // 否则显示路由信息
      if (data.targetAgent) {
        return `✅ 已为您路由到: ${data.targetAgent}\n\n请问还有什么可以帮助您的吗？`;
      }

      return '处理完成';
    },

    /**
     * 清空对话
     */
    clearChat() {
      if (confirm('确定要清空对话记录吗？')) {
        this.messages = [];
        this.sessionId = null;
        this.createSession();
      }
    },

    /**
     * 滚动到底部
     */
    scrollToBottom() {
      this.$nextTick(() => {
        const container = this.$refs.messagesContainer;
        if (container) {
          container.scrollTop = container.scrollHeight;
        }
      });
    },

    /**
     * 格式化时间
     */
    formatTime(timestamp) {
      if (!timestamp) return '';
      const date = new Date(timestamp);
      return date.toLocaleTimeString('zh-CN', { 
        hour: '2-digit', 
        minute: '2-digit' 
      });
    }
  }
};
</script>

<style scoped>
.chat-container {
  display: flex;
  flex-direction: column;
  height: 600px;
  background: #f5f5f5;
  border-radius: 12px;
  box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);
  overflow: hidden;
}

/* 头部样式 */
.chat-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 20px;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
}

.chat-header h3 {
  margin: 0;
  font-size: 18px;
}

.header-info {
  display: flex;
  align-items: center;
  gap: 12px;
}

.status {
  font-size: 12px;
  padding: 4px 8px;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.2);
}

.status.connected {
  background: rgba(76, 175, 80, 0.3);
}

.status.connecting {
  background: rgba(255, 193, 7, 0.3);
}

.status.disconnected {
  background: rgba(244, 67, 54, 0.3);
}

.clear-btn {
  background: none;
  border: none;
  font-size: 18px;
  cursor: pointer;
  opacity: 0.7;
  transition: opacity 0.2s;
}

.clear-btn:hover {
  opacity: 1;
}

/* 消息容器 */
.messages-container {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
  background: white;
}

.welcome-message {
  text-align: center;
  padding: 40px 20px;
  color: #666;
}

.welcome-message p {
  margin: 8px 0;
}

.welcome-message .hint {
  font-size: 12px;
  color: #999;
  margin-top: 16px;
}

/* 消息样式 */
.message {
  display: flex;
  margin-bottom: 16px;
  animation: fadeIn 0.3s ease-in;
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

.user-message {
  flex-direction: row-reverse;
}

.ai-message {
  flex-direction: row;
}

.message-avatar {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 20px;
  background: #e0e0e0;
  flex-shrink: 0;
}

.user-message .message-avatar {
  background: #667eea;
}

.message-content {
  max-width: 70%;
  margin: 0 12px;
}

.message-text {
  padding: 12px 16px;
  border-radius: 16px;
  background: #f0f0f0;
  word-wrap: break-word;
  line-height: 1.5;
}

.user-message .message-text {
  background: #667eea;
  color: white;
  border-bottom-right-radius: 4px;
}

.ai-message .message-text {
  background: #f0f0f0;
  border-bottom-left-radius: 4px;
}

.message-metadata {
  font-size: 11px;
  color: #999;
  margin-top: 4px;
  padding: 0 4px;
}

.message-time {
  font-size: 10px;
  color: #bbb;
  margin-top: 4px;
  padding: 0 4px;
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
  background: #999;
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
  }
  30% {
    transform: translateY(-10px);
  }
}

/* 输入区域 */
.input-container {
  display: flex;
  padding: 16px;
  background: white;
  border-top: 1px solid #e0e0e0;
  gap: 12px;
}

.message-input {
  flex: 1;
  padding: 12px 16px;
  border: 1px solid #ddd;
  border-radius: 24px;
  outline: none;
  font-size: 14px;
  transition: border-color 0.2s;
}

.message-input:focus {
  border-color: #667eea;
}

.message-input:disabled {
  background: #f5f5f5;
  cursor: not-allowed;
}

.send-button {
  padding: 12px 24px;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
  border: none;
  border-radius: 24px;
  cursor: pointer;
  font-size: 14px;
  font-weight: 500;
  transition: opacity 0.2s, transform 0.1s;
}

.send-button:hover:not(:disabled) {
  opacity: 0.9;
}

.send-button:active:not(:disabled) {
  transform: scale(0.98);
}

.send-button:disabled {
  background: #ccc;
  cursor: not-allowed;
}

/* 滚动条样式 */
.messages-container::-webkit-scrollbar {
  width: 6px;
}

.messages-container::-webkit-scrollbar-track {
  background: #f1f1f1;
}

.messages-container::-webkit-scrollbar-thumb {
  background: #888;
  border-radius: 3px;
}

.messages-container::-webkit-scrollbar-thumb:hover {
  background: #555;
}
</style>
