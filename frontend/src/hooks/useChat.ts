import { useState, useCallback } from 'react';
import { v4 as uuidv4 } from 'uuid';
import { Message, ToolCall, PermissionRequest, Session, ContentBlock, IntentType, FaqItem } from '../types';

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

    try {
      const response = await fetch('/api/chat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sessionId, message: messageContent, model: selectedModel }),
      });

      const reader = response.body?.getReader();
      const decoder = new TextDecoder();
      let fullContent = '';
      let currentToolCalls: ToolCall[] = [];
      let contentBlocks: ContentBlock[] = [];
      let currentTextBlock = '';
      let realSessionId: string = sessionId!;
      let realAssistantMessageId = tempAssistantMessageId;

      if (reader) {
        while (true) {
          const { done, value } = await reader.read();
          if (done) break;
          const chunk = decoder.decode(value);
          const lines = chunk.split('\n');

          for (const line of lines) {
            if (!line.startsWith('data: ')) continue;
            try {
              const data = JSON.parse(line.slice(6));

              if (data.type === 'init') {
                realSessionId = data.sessionId || sessionId!;
                realAssistantMessageId = data.assistantMessageId || tempAssistantMessageId;

                // 更新意图
                if (data.intent && data.intent !== 'unknown') {
                  setSessions(prev => prev.map(s =>
                    s.id === realSessionId ? { ...s, intent: data.intent as IntentType } : s
                  ));
                }
                // FAQ 建议
                if (data.faqSuggestions?.length) {
                  setFaqSuggestions(data.faqSuggestions);
                }
                // 同步消息ID
                if (realAssistantMessageId !== tempAssistantMessageId) {
                  setSessions(prev => prev.map(s => {
                    if (s.id === realSessionId) {
                      return {
                        ...s,
                        messages: s.messages.map(m =>
                          m.id === tempAssistantMessageId ? { ...m, id: realAssistantMessageId } : m
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
                // 对话结束后延迟显示满意度
                setTimeout(() => setShowSatisfaction(true), 2000);
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
                          ? { ...m, content: `⚠️ ${data.message}`, isStreaming: false }
                          : m
                      ),
                    };
                  }
                  return s;
                }));
              }
            } catch { /* ignore */ }
          }
        }
      }
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

  // 转人工
  const handleTransferToHuman = useCallback(async () => {
    if (!currentSessionId) return;
    try {
      await fetch(`/api/sessions/${currentSessionId}/transfer`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ reason: '用户请求人工客服' }),
      });
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
      await fetch(`/api/sessions/${currentSessionId}/satisfaction`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ score, comment }),
      });
      setSessions(prev => prev.map(s =>
        s.id === currentSessionId ? { ...s, satisfaction: score, status: 'closed' } : s
      ));
      setShowSatisfaction(false);
    } catch (e) { console.error(e); }
  }, [currentSessionId, setSessions]);

  // 权限处理
  const handlePermissionAllow = useCallback(async () => {
    if (!permissionRequest) return;
    await fetch('/api/permission-response', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ requestId: permissionRequest.requestId, behavior: 'allow' }),
    });
    setPermissionRequest(null);
  }, [permissionRequest]);

  const handlePermissionDeny = useCallback(async () => {
    if (!permissionRequest) return;
    await fetch('/api/permission-response', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ requestId: permissionRequest.requestId, behavior: 'deny' }),
    });
    setPermissionRequest(null);
  }, [permissionRequest]);

  const handleStop = useCallback(() => setIsLoading(false), []);

  return {
    isLoading,
    inputValue,
    setInputValue,
    permissionRequest,
    transferPending,
    showSatisfaction,
    faqSuggestions,
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
