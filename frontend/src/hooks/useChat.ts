import { useState, useCallback, useRef } from 'react';
import { v4 as uuidv4 } from 'uuid';
import { Message, ToolCall, PermissionRequest, Session, ContentBlock, IntentType, FaqItem } from '../types';
import { sessions as sessionApi } from '../api';
import { getAuthToken } from '../api/auth';

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
  // FAQ 建议
  const [faqSuggestions, setFaqSuggestions] = useState<FaqItem[]>([]);
  // ⭐ 排队状态
  const [queuePosition, setQueuePosition] = useState<number | null>(null);
  const [queueEstimatedWait, setQueueEstimatedWait] = useState<number | null>(null);

  // ⭐ 当前流式请求的取消控制器（用于停止生成）
  const streamAbortRef = useRef<AbortController | null>(null);

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

    // ⭐ 使用 fetch 读取 SSE，以便携带 Bearer Token
    try {
      await streamWithFetch(messageContent, sessionId!, selectedModel, tempAssistantMessageId);
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

  /** 使用 fetch 读取 SSE；原生 EventSource 无法附带 Authorization 请求头。 */
  const streamWithFetch = useCallback(async (
    message: string,
    sessionId: string,
    model: string,
    assistantMessageId: string
  ): Promise<void> => {
    let fullContent = '';
    let currentToolCalls: ToolCall[] = [];
    let contentBlocks: ContentBlock[] = [];
    let currentTextBlock = '';
    let realSessionId: string = sessionId;
    let realAssistantMessageId = assistantMessageId;
    let isDone = false;

    const updateAssistantMessage = (updater: (message: Message) => Message) => {
      setSessions(prev => prev.map(current => {
        if (current.id !== realSessionId && current.id !== sessionId) {
          return current;
        }

        const exactMatch = current.messages.some(message =>
          message.id === realAssistantMessageId || message.id === assistantMessageId
        );
        let fallbackMessageId: string | null = null;
        if (!exactMatch) {
          for (let index = current.messages.length - 1; index >= 0; index--) {
            const candidate = current.messages[index];
            if (candidate.role === 'assistant' && candidate.isStreaming) {
              fallbackMessageId = candidate.id;
              break;
            }
          }
        }

        return {
          ...current,
          messages: current.messages.map(message => {
            const isTarget = exactMatch
              ? message.id === realAssistantMessageId || message.id === assistantMessageId
              : message.id === fallbackMessageId;
            return isTarget ? updater(message) : message;
          }),
        };
      }));
    };

    const url = `/api/math/stream/chat?message=${encodeURIComponent(message)}&sessionId=${encodeURIComponent(sessionId)}&model=${encodeURIComponent(model)}`;
    const controller = new AbortController();
    streamAbortRef.current = controller;

    // ⭐ 通用事件处理：解析 SSE 的 data JSON
    const handleEvent = (event: { data: string; type: string }) => {
        try {
          const parsed = JSON.parse(event.data);
          const data = parsed?.data && typeof parsed.data === 'object'
            ? { ...parsed.data, type: parsed.type || parsed.data.type || event.type }
            : { ...parsed, type: parsed.type || event.type };

          if (data.type === 'init') {
            realSessionId = data.sessionId || sessionId;
            realAssistantMessageId = data.assistantMessageId || assistantMessageId;
            if (data.intent && data.intent !== 'unknown') {
              setSessions(prev => prev.map(s =>
                s.id === realSessionId || s.id === sessionId
                  ? { ...s, intent: data.intent as IntentType }
                  : s
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

          } else if (data.type === 'text' || data.type === 'response') {
            const chunk = typeof data.content === 'string'
              ? data.content
              : typeof data.message === 'string' ? data.message : '';
            if (!chunk) return;
            fullContent += chunk;
            currentTextBlock += chunk;
            const lastBlock = contentBlocks[contentBlocks.length - 1];
            if (lastBlock && lastBlock.type === 'text') {
              lastBlock.text = currentTextBlock;
            } else if (currentTextBlock) {
              contentBlocks.push({ type: 'text', text: currentTextBlock });
            }
            updateAssistantMessage(current => ({
              ...current,
              content: fullContent,
              toolCalls: [...currentToolCalls],
              contentBlocks: [...contentBlocks],
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
            updateAssistantMessage(current => ({ ...current, isStreaming: false }));

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
            isDone = true;
            updateAssistantMessage(current => ({
              ...current,
              content: `⚠️ ${data.content || data.message}`,
              isStreaming: false,
            }));
          } else if (data.type === 'timeout') {
            isDone = true;
            updateAssistantMessage(current => ({
              ...current,
              content: `⚠️ ${data.content || '请求超时，请稍后重试'}`,
              isStreaming: false,
            }));
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

    const dispatchBlock = (block: string) => {
      let eventType = 'message';
      const dataLines: string[] = [];
      block.split('\n').forEach(line => {
        if (line.startsWith('event:')) {
          eventType = line.slice(6).trim() || 'message';
        } else if (line.startsWith('data:')) {
          dataLines.push(line.slice(5).trimStart());
        }
      });
      if (dataLines.length > 0) {
        handleEvent({ type: eventType, data: dataLines.join('\n') });
      }
    };

    try {
      const token = getAuthToken();
      const response = await fetch(url, {
        method: 'GET',
        headers: {
          Accept: 'text/event-stream',
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
        },
        signal: controller.signal,
      });
      if (!response.ok) {
        throw new Error(`流式请求失败: HTTP ${response.status}`);
      }
      if (!response.body) {
        throw new Error('浏览器未提供流式响应体');
      }

      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';

      while (!isDone) {
        const { done, value } = await reader.read();
        buffer = (buffer + decoder.decode(value, { stream: !done })).replace(/\r\n/g, '\n');

        let boundary = buffer.indexOf('\n\n');
        while (boundary >= 0) {
          const block = buffer.slice(0, boundary);
          buffer = buffer.slice(boundary + 2);
          if (block.trim()) dispatchBlock(block);
          if (isDone) break;
          boundary = buffer.indexOf('\n\n');
        }

        if (done) {
          if (buffer.trim()) dispatchBlock(buffer);
          break;
        }
      }

      if (isDone) {
        await reader.cancel().catch(() => undefined);
      } else {
        throw new Error('流式连接在完成事件前关闭');
      }
    } catch (error) {
      if (controller.signal.aborted) {
        updateAssistantMessage(current => ({ ...current, isStreaming: false }));
        return;
      }
      throw error;
    } finally {
      if (streamAbortRef.current === controller) streamAbortRef.current = null;
    }
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
   * ⭐ 停止生成：中止 fetch 流式连接，真正取消后端请求。
   * <p>
   * 后端 Consumer 的 forwardSSE() 检测到客户端断开后：
   * 1. 释放 LLM 槽位 (slots.release())
   * 2. 关闭与 Agent 的 HTTP 连接
   * 3. 最终 finally 块清理资源
   * </p>
   */
  const handleStop = useCallback(() => {
    // 中止 fetch，触发后端断开检测
    if (streamAbortRef.current) {
      streamAbortRef.current.abort();
      streamAbortRef.current = null;
    }
    // 通知后端释放 LLM 槽位（冗余保障），并保持与主请求一致的鉴权方式
    const token = getAuthToken();
    void fetch('/api/math/stream/chat/cancel', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      body: JSON.stringify({ requestId: currentSessionId }),
      keepalive: true,
    }).catch(() => undefined);
    setIsLoading(false);
  }, [currentSessionId]);

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
