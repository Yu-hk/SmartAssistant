import { useState, useCallback, useRef } from 'react';
import { v4 as uuidv4 } from 'uuid';
import { Message, ToolCall, PermissionRequest, Session, ContentBlock, IntentType, FaqItem } from '../types';
import { sessions as sessionApi } from '../api';

interface UseChatOptions {
  currentSession: Session | undefined;
  currentSessionId: string | null;
  selectedModel: string;
  setSessions: React.Dispatch<React.SetStateAction<Session[]>>;
  setCurrentSessionId: (id: string | null) => void;
}

export function useChat(options: UseChatOptions) {
  const { currentSession, currentSessionId, selectedModel, setSessions, setCurrentSessionId } = options;

  const [isLoading, setIsLoading] = useState(false);
  const [inputValue, setInputValue] = useState('');
  const [permissionRequest, setPermissionRequest] = useState<PermissionRequest | null>(null);
  // 转人工状态
  const [transferPending, setTransferPending] = useState(false);
  // 满意度弹窗
  const [showSatisfaction, setShowSatisfaction] = useState(false);
  // FAQ 建议
  const [faqSuggestions, setFaqSuggestions] = useState<FaqItem[]>([]);
  // ⭐ 排队状态
  const [queuePosition, setQueuePosition] = useState<number | null>(null);
  const [queueEstimatedWait, setQueueEstimatedWait] = useState<number | null>(null);

  // ⭐ 当前的 EventSource 引用（用于取消请求）
  const eventSourceRef = useRef<EventSource | null>(null);

  const sendMessage = useCallback(async (
    messageContent: string,
    sessionIdOverride?: string,
    onNavigate?: (path: string) => void
  ) => {
    if (!messageContent.trim() || isLoading) return;

    let sessionId = sessionIdOverride || currentSessionId;

    const tempUserMessageId = uuidv4();
    const tempAssistantMessageId = uuidv4();

    const userMessage: Message = {
      id: tempUserMessageId,
      role: 'user',
      content: messageContent,
      timestamp: new Date(),
    };

    const assistantMessage: Message = {
      id: tempAssistantMessageId,
      role: 'assistant',
      content: '',
      model: selectedModel,
      timestamp: new Date(),
      isStreaming: true,
      contentBlocks: [],
    };

    // 如果没有会话，先创建
    if (!sessionId) {
      try {
        const res = await fetch('/api/sessions', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ model: selectedModel, title: messageContent.slice(0, 30) }),
        });
        const data = await res.json();
        sessionId = data.session.id;
        const newSession: Session = {
          id: sessionId!,
          title: data.session.title,
          model: selectedModel,
          intent: 'unknown',
          status: 'active',
          satisfaction: null,
          satisfaction_comment: null,
          user_name: '访客',
          agent_name: null,
          createdAt: new Date(data.session.created_at),
          messages: [userMessage, assistantMessage],
        };
        setSessions(prev => [newSession, ...prev]);
        setCurrentSessionId(sessionId!);
        onNavigate?.(`/chat/${sessionId}`);
      } catch (e) {
        console.error('Failed to create session:', e);
        return;
      }
    } else {
      setSessions(prev => prev.map(s => {
        if (s.id === sessionId) {
          const newTitle = s.messages.length === 0
            ? messageContent.slice(0, 30) + (messageContent.length > 30 ? '...' : '')
            : s.title;
          return { ...s, title: newTitle, messages: [...s.messages, userMessage, assistantMessage] };
        }
        return s;
      }));
    }

    setInputValue('');
    setIsLoading(true);
    setFaqSuggestions([]);
    // ⭐ 清除排队状态
    setQueuePosition(null);
    setQueueEstimatedWait(null);

    // ⭐ 使用 EventSource 实现 SSE 流式连接（支持自动重连 + Last-Event-ID）
    try {
      await streamWithEventSource(messageContent, sessionId!, selectedModel, tempAssistantMessageId);
    } catch (error) {
      console.error('Chat error:', error);
      setSessions(prev => prev.map(s => {
        if (s.id === sessionId) {
          return {
            ...s,
            messages: s.messages.map(m =>
              m.id === tempAssistantMessageId
                ? { ...m, content: '⚠️ 发生错误，请重试', isStreaming: false }
                : m
            ),
          };
        }
        return s;
      }));
    } finally {
      setIsLoading(false);
    }
  }, [currentSession, currentSessionId, selectedModel, setSessions, setCurrentSessionId, isLoading]);

  /**
   * ⭐ 使用 EventSource 实现 SSE 流式连接。
   * <p>
   * 浏览器原生 EventSource API 支持：
   * <ul>
   *   <li>自动重连连接断开</li>
   *   <li>自动发送 {@code Last-Event-ID} 请求头</li>
   *   <li>服务端可从断点续传未送达事件</li>
   * </ul>
   * </p>
   */
  const streamWithEventSource = useCallback((
    message: string,
    sessionId: string,
    model: string,
    assistantMessageId: string
  ): Promise<void> => {
    return new Promise((resolve, reject) => {
      let fullContent = '';
      let currentToolCalls: ToolCall[] = [];
      let contentBlocks: ContentBlock[] = [];
      let currentTextBlock = '';
      let realSessionId: string = sessionId;
      let realAssistantMessageId = assistantMessageId;
      let isDone = false;

      const url = `/api/math/stream/chat?message=${encodeURIComponent(message)}&sessionId=${encodeURIComponent(sessionId)}&model=${encodeURIComponent(model)}`;
      const es = new EventSource(url);
      eventSourceRef.current = es; // ⭐ 保存引用，供 handleStop 关闭

      // ⭐ 通用事件处理：解析 data: JSON
      const handleEvent = (event: MessageEvent) => {
        try {
          const data = JSON.parse(event.data);

          if (data.type === 'init') {
            realSessionId = data.sessionId || sessionId;
            realAssistantMessageId = data.assistantMessageId || assistantMessageId;
            if (data.intent && data.intent !== 'unknown') {
              setSessions(prev => prev.map(s =>
                s.id === realSessionId ? { ...s, intent: data.intent as IntentType } : s
              ));
            }
            if (data.faqSuggestions?.length) {
              setFaqSuggestions(data.faqSuggestions);
            }
            if (realAssistantMessageId !== assistantMessageId) {
              setSessions(prev => prev.map(s => {
                if (s.id === realSessionId) {
                  return {
                    ...s,
                    messages: s.messages.map(m =>
                      m.id === assistantMessageId ? { ...m, id: realAssistantMessageId } : m
                    ),
                  };
                }
                return s;
              }));
            }

          } else if (data.type === 'text') {
            fullContent += data.content;
            currentTextBlock += data.content;
            const lastBlock = contentBlocks[contentBlocks.length - 1];
            if (lastBlock && lastBlock.type === 'text') {
              lastBlock.text = currentTextBlock;
            } else if (currentTextBlock) {
              contentBlocks.push({ type: 'text', text: currentTextBlock });
            }
            setSessions(prev => prev.map(s => {
              if (s.id === realSessionId) {
                return {
                  ...s,
                  messages: s.messages.map(m =>
                    m.id === realAssistantMessageId
                      ? { ...m, content: fullContent, toolCalls: [...currentToolCalls], contentBlocks: [...contentBlocks] }
                      : m
                  ),
                };
              }
              return s;
            }));

          } else if (data.type === 'tool') {
            currentTextBlock = '';
            const toolCall: ToolCall = { id: data.id || uuidv4(), name: data.name, input: data.input, status: 'running' };
            currentToolCalls.push(toolCall);
            contentBlocks.push({ type: 'tool_use', toolCall });
            setSessions(prev => prev.map(s => {
              if (s.id === realSessionId) {
                return {
                  ...s,
                  messages: s.messages.map(m =>
                    m.id === realAssistantMessageId
                      ? { ...m, toolCalls: [...currentToolCalls], contentBlocks: [...contentBlocks] }
                      : m
                  ),
                };
              }
              return s;
            }));

          } else if (data.type === 'tool_result') {
            const idx = data.toolId
              ? currentToolCalls.findIndex(t => t.id === data.toolId)
              : currentToolCalls.length - 1;
            if (idx >= 0) {
              currentToolCalls[idx].status = data.isError ? 'error' : 'completed';
              currentToolCalls[idx].result = typeof data.content === 'string' ? data.content : JSON.stringify(data.content);
              const blockIdx = contentBlocks.findIndex(b => b.type === 'tool_use' && b.toolCall.id === currentToolCalls[idx].id);
              if (blockIdx >= 0) (contentBlocks[blockIdx] as any).toolCall = { ...currentToolCalls[idx] };
              setSessions(prev => prev.map(s => {
                if (s.id === realSessionId) {
                  return {
                    ...s,
                    messages: s.messages.map(m =>
                      m.id === realAssistantMessageId
                        ? { ...m, toolCalls: [...currentToolCalls], contentBlocks: [...contentBlocks] }
                        : m
                    ),
                  };
                }
                return s;
              }));
            }

          } else if (data.type === 'transfer_to_human') {
            setSessions(prev => prev.map(s =>
              s.id === realSessionId ? { ...s, status: 'human_transfer' } : s
            ));
            setTransferPending(true);

          } else if (data.type === 'done') {
            isDone = true;
            setSessions(prev => prev.map(s => {
              if (s.id === realSessionId) {
                return {
                  ...s,
                  messages: s.messages.map(m =>
                    m.id === realAssistantMessageId ? { ...m, isStreaming: false } : m
                  ),
                };
              }
              return s;
            }));
            setTimeout(() => setShowSatisfaction(true), 2000);
            eventSourceRef.current = null; // 清除引用
            es.close();
            resolve();

          } else if (data.type === 'permission_request') {
            setPermissionRequest({
              requestId: data.requestId,
              toolUseId: data.toolUseId,
              toolName: data.toolName,
              input: data.input,
              sessionId: data.sessionId,
              timestamp: data.timestamp,
            });

          } else if (data.type === 'error') {
            setSessions(prev => prev.map(s => {
              if (s.id === realSessionId) {
                return {
                  ...s,
                  messages: s.messages.map(m =>
                    m.id === realAssistantMessageId
                      ? { ...m, content: `⚠️ ${data.content || data.message}`, isStreaming: false }
                      : m
                  ),
                };
              }
              return s;
            }));
            es.close();
            eventSourceRef.current = null; // 清除引用
            resolve();
          }

          // ⭐ 排队事件
          if (data.type === 'queued') {
            setQueuePosition(data.position);
            setQueueEstimatedWait(data.estimatedWaitMs || data.position * 5000);
          } else if (data.type === 'queue_position') {
            setQueuePosition(data.position);
            setQueueEstimatedWait(data.estimatedWaitMs || data.position * 5000);
          } else if (data.type === 'processing') {
            setQueuePosition(null);
            setQueueEstimatedWait(null);
          } else if (data.type === 'timeout') {
            setQueuePosition(null);
            setQueueEstimatedWait(null);
          }
        } catch { /* ignore invalid JSON */ }
      };

      // 监听所有 SSE 命名事件类型
      const eventTypes = ['thinking', 'tool_call', 'tool_result', 'waiting', 'queued',
        'queue_position', 'processing', 'response', 'done', 'error', 'init', 'text',
        'tool', 'routed', 'timeout', 'transfer_to_human', 'permission_request'];
      eventTypes.forEach(type => es.addEventListener(type, handleEvent));

      // ⭐ 回退：监听未命名事件（EventSource 标准消息）
      es.onmessage = handleEvent;

      // 监听错误（含自动重连）
      es.onerror = (error) => {
        // EventSource 会自动重连，不要在 onerror 中直接 reject
        // 但如果已经收到 done 事件后的错误，忽略
        if (isDone) return;
        // EventSource readyState:
        // 0=CONNECTING, 1=OPEN, 2=CLOSED
        if (es.readyState === EventSource.CLOSED && !isDone) {
          console.error('EventSource closed unexpectedly');
          reject(error);
        }
      };
    });
  }, [setSessions, setFaqSuggestions, setTransferPending, setShowSatisfaction, setPermissionRequest, setQueuePosition, setQueueEstimatedWait]);

  // 转人工
  const handleTransferToHuman = useCallback(async () => {
    if (!currentSessionId) return;
    try {
      await sessionApi.transferToHuman(currentSessionId, { reason: '用户请求人工客服', agentId: '' });
      setSessions(prev => prev.map(s =>
        s.id === currentSessionId ? { ...s, status: 'human_transfer' } : s
      ));
      setTransferPending(false);
    } catch (e) { console.error(e); }
  }, [currentSessionId, setSessions]);

  // 提交满意度
  const handleSatisfaction = useCallback(async (score: number, comment?: string) => {
    if (!currentSessionId) return;
    try {
      await sessionApi.submitSatisfaction(currentSessionId, { score, comment });
      setSessions(prev => prev.map(s =>
        s.id === currentSessionId ? { ...s, satisfaction: score, status: 'closed' } : s
      ));
      setShowSatisfaction(false);
    } catch (e) { console.error(e); }
  }, [currentSessionId, setSessions]);

  // 权限处理
  const handlePermissionAllow = useCallback(async () => {
    if (!permissionRequest) return;
    await sessionApi.allowPermission(permissionRequest.requestId);
    setPermissionRequest(null);
  }, [permissionRequest]);

  const handlePermissionDeny = useCallback(async () => {
    if (!permissionRequest) return;
    await sessionApi.denyPermission(permissionRequest.requestId);
    setPermissionRequest(null);
  }, [permissionRequest]);

  /**
   * ⭐ 停止生成：关闭 EventSource 连接，真正取消后端请求。
   * <p>
   * 后端 Consumer 的 forwardSSE() 检测到客户端断开后：
   * 1. 释放 LLM 槽位 (slots.release())
   * 2. 关闭与 Agent 的 HTTP 连接
   * 3. 最终 finally 块清理资源
   * </p>
   */
  const handleStop = useCallback(() => {
    // 关闭 EventSource，触发后端断开检测
    if (eventSourceRef.current) {
      eventSourceRef.current.close();
      eventSourceRef.current = null;
    }
    // 通知后端释放 LLM 槽位（冗余保障）
    try {
      navigator.sendBeacon('/api/math/stream/chat/cancel', JSON.stringify({ requestId: currentSessionId }));
    } catch { /* ignore */ }
    setIsLoading(false);
  }, [currentSessionId]);

  return {
    isLoading,
    inputValue,
    setInputValue,
    permissionRequest,
    transferPending,
    showSatisfaction,
    faqSuggestions,
    queuePosition,
    queueEstimatedWait,
    sendMessage,
    handleStop,
    handleTransferToHuman,
    handleSatisfaction,
    handlePermissionAllow,
    handlePermissionDeny,
    setShowSatisfaction,
    setTransferPending,
  };
}
