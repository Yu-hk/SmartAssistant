import { useState, useCallback, useEffect, useRef } from 'react';
import { v4 as uuidv4 } from 'uuid';
import { Message, ToolCall, PermissionRequest, Session, ContentBlock, IntentType, FaqItem } from '../types';
import { sessions as sessionApi } from '../api';
import { clearAuthSession, readAccessToken } from '../authStorage';
import { createInactivityWatchdog, type InactivityWatchdog } from './inactivityWatchdog';

interface UseChatOptions {
  currentSession: Session | undefined;
  currentSessionId: string | null;
  selectedModel: string;
  setSessions: React.Dispatch<React.SetStateAction<Session[]>>;
  setCurrentSessionId: (id: string | null) => void;
}

export const CHAT_RESPONSE_TIMEOUT_MS = 5 * 60 * 1000;
export const CHAT_TIMEOUT_MESSAGE = '本次咨询连续 5 分钟未收到响应，系统已自动关闭该次对话。';

type StopReason = 'user' | 'timeout';

interface ActiveChatRequest {
  requestId: string;
  stop: (reason: StopReason) => void;
}

export function useChat(options: UseChatOptions) {
  const { currentSession, currentSessionId, selectedModel, setSessions, setCurrentSessionId } = options;

  const [activeSessionIds, setActiveSessionIds] = useState<Set<string>>(() => new Set());
  const [inputValue, setInputValue] = useState('');
  const [permissionRequest, setPermissionRequest] = useState<PermissionRequest | null>(null);
  // FAQ 建议
  const [faqSuggestions, setFaqSuggestions] = useState<FaqItem[]>([]);
  // ⭐ 排队状态
  const [queuePosition, setQueuePosition] = useState<number | null>(null);
  const [queueEstimatedWait, setQueueEstimatedWait] = useState<number | null>(null);

  // 每个会话独立保存请求与超时控制器，允许多个会话并发且互不影响。
  const activeRequestsRef = useRef<Map<string, ActiveChatRequest>>(new Map());
  const isLoading = currentSessionId != null && activeSessionIds.has(currentSessionId);

  const sendMessage = useCallback(async (
    messageContent: string,
    sessionIdOverride?: string,
    onNavigate?: (path: string) => void
  ) => {
    if (!messageContent.trim()) return;

    let sessionId = sessionIdOverride || currentSessionId;
    if (sessionId && activeRequestsRef.current.has(sessionId)) return;
    if (sessionId && currentSession?.id === sessionId && currentSession.status === 'closed') return;

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

    // 如果没有会话，本地生成 sessionId 直接开聊（微服务未提供会话创建端点，dev/demo 模式）
    if (!sessionId) {
      sessionId = uuidv4();
      const newSession: Session = {
        id: sessionId,
        title: messageContent.slice(0, 30),
        model: selectedModel,
        intent: 'unknown',
        status: 'active',
        satisfaction: null,
        satisfaction_comment: null,
        user_name: '访客',
        agent_name: null,
        createdAt: new Date(),
        timedOut: false,
        messages: [userMessage, assistantMessage],
      };
      setSessions(prev => [newSession, ...prev]);
      setCurrentSessionId(sessionId);
      onNavigate?.(`/chat/${sessionId}`);
    } else {
      setSessions(prev => prev.map(s => {
        if (s.id === sessionId) {
          const newTitle = s.messages.length === 0
            ? messageContent.slice(0, 30) + (messageContent.length > 30 ? '...' : '')
            : s.title;
          return {
            ...s,
            title: newTitle,
            status: 'active',
            timedOut: false,
            messages: [...s.messages, userMessage, assistantMessage],
          };
        }
        return s;
      }));
    }

    setInputValue('');
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
    }
  }, [currentSession, currentSessionId, selectedModel, setSessions, setCurrentSessionId]);

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
      let settled = false;
      let watchdog: InactivityWatchdog | null = null;

      const requestId = `${sessionId}-${uuidv4()}`;
      const url = `/api/math/stream/chat?message=${encodeURIComponent(message)}`
        + `&sessionId=${encodeURIComponent(sessionId)}`
        + `&requestId=${encodeURIComponent(requestId)}`
        + `&model=${encodeURIComponent(model)}`;
      const controller = new AbortController();
      const es = { close: () => controller.abort() };

      const setSessionActive = (active: boolean) => {
        setActiveSessionIds(prev => {
          const next = new Set(prev);
          if (active) next.add(sessionId);
          else next.delete(sessionId);
          return next;
        });
      };

      const cancelBackend = () => {
        void fetch('/api/math/stream/chat/cancel', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ requestId }),
          credentials: 'same-origin',
          keepalive: true,
        }).catch(() => {});
      };

      const cleanup = () => {
        watchdog?.cancel();
        watchdog = null;
        const active = activeRequestsRef.current.get(sessionId);
        if (active?.requestId === requestId) {
          activeRequestsRef.current.delete(sessionId);
        }
        setSessionActive(false);
      };

      const finish = () => {
        if (settled) return;
        settled = true;
        cleanup();
        resolve();
      };

      const stop = (reason: StopReason) => {
        if (settled) return;
        isDone = true;
        es.close();
        cancelBackend();
        setSessions(prev => prev.map(s => {
          if (s.id !== sessionId) return s;
          return {
            ...s,
            status: reason === 'timeout' ? 'closed' : s.status,
            timedOut: reason === 'timeout',
            messages: s.messages.map(m =>
              m.id === realAssistantMessageId && m.isStreaming
                ? {
                    ...m,
                    content: reason === 'timeout'
                      ? `⚠️ ${CHAT_TIMEOUT_MESSAGE}`
                      : (m.content || '已停止生成。'),
                    isStreaming: false,
                  }
                : m
            ),
          };
        }));
        finish();
      };

      const armInactivityTimeout = () => {
        watchdog?.touch();
      };

      activeRequestsRef.current.set(sessionId, { requestId, stop });
      setSessionActive(true);
      watchdog = createInactivityWatchdog(
        CHAT_RESPONSE_TIMEOUT_MS,
        () => stop('timeout'),
      );

      // ⭐ 通用事件处理：解析 data: JSON
      const handleEvent = (event: MessageEvent) => {
        try {
          const data = JSON.parse(event.data);
          armInactivityTimeout();

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

          } else if (data.type === 'done') {
            isDone = true;
            setSessions(prev => prev.map(s => {
              if (s.id === realSessionId) {
                return {
                  ...s,
                  messages: s.messages.map(m =>
                    m.id === realAssistantMessageId
                      ? {
                          ...m,
                          content: fullContent || m.content,
                          toolCalls: currentToolCalls.length > 0 ? [...currentToolCalls] : m.toolCalls,
                          contentBlocks: contentBlocks.length > 0 ? [...contentBlocks] : m.contentBlocks,
                          isStreaming: false,
                        }
                      : m
                  ),
                };
              }
              return s;
            }));
            es.close();
            finish();

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
            isDone = true;
            es.close();
            finish();
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
      void (async () => {
        try {
          const token = readAccessToken();
          const response = await fetch(url, {
            method: 'GET',
            headers: {
              Accept: 'text/event-stream',
              ...(token ? { Authorization: `Bearer ${token}` } : {}),
            },
            credentials: 'same-origin',
            signal: controller.signal,
          });
          if (!response.ok) {
            if (response.status === 401) clearAuthSession();
            throw new Error(response.status === 401
              ? '登录状态已失效，请重新登录'
              : `SSE 请求失败: HTTP ${response.status}`);
          }
          if (!response.body) throw new Error('浏览器不支持流式响应');

          const reader = response.body.getReader();
          const decoder = new TextDecoder('utf-8');
          let buffer = '';
          const consumeFrames = () => {
            while (true) {
              const boundary = buffer.match(/\r?\n\r?\n/);
              if (!boundary || boundary.index === undefined) return;
              const frame = buffer.slice(0, boundary.index);
              buffer = buffer.slice(boundary.index + boundary[0].length);
              const data = frame.split(/\r?\n/)
                .filter(line => line.startsWith('data:'))
                .map(line => line.slice(5).trimStart())
                .join('\n');
              if (data) handleEvent({ data } as MessageEvent);
            }
          };
          while (!isDone) {
            const { value, done } = await reader.read();
            if (done) break;
            buffer += decoder.decode(value, { stream: true });
            consumeFrames();
          }
          buffer += decoder.decode();
          consumeFrames();
          if (!isDone) throw new Error('SSE 连接在完成事件前关闭');
        } catch (error) {
          if (isDone || (error instanceof DOMException && error.name === 'AbortError')) return;
          cleanup();
          reject(error);
        }
      })();
    });
  }, [setSessions, setFaqSuggestions, setPermissionRequest, setQueuePosition, setQueueEstimatedWait]);

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
    if (!currentSessionId) return;
    activeRequestsRef.current.get(currentSessionId)?.stop('user');
  }, [currentSessionId]);

  useEffect(() => () => {
    activeRequestsRef.current.forEach(request => request.stop('user'));
    activeRequestsRef.current.clear();
  }, []);

  return {
    isLoading,
    inputValue,
    setInputValue,
    permissionRequest,
    faqSuggestions,
    queuePosition,
    queueEstimatedWait,
    sendMessage,
    handleStop,
    handlePermissionAllow,
    handlePermissionDeny,
  };
}
