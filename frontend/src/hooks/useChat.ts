import { useState, useCallback, useEffect, useRef } from 'react';
import {
  Message,
  ToolCall,
  PermissionRequest,
  Session,
  ContentBlock,
  FaqItem,
  WorkflowRecoveryJob,
  WorkflowRecoveryStatus,
  normalizeIntentType,
} from '../types';
import { sessions as sessionApi } from '../api';
import { authenticatedFetch } from '../api/client';
import { applyTelemetryEvent } from '../utils/sessionTelemetry';
import { recoveryErrorMessage, publicRecoveryError } from '../utils/workflowRecovery';
import { getAuthToken } from '../api/authStorage';
import { normalizeClarificationForm } from '../utils/clarificationForm';

interface UseChatOptions {
  currentSession: Session | undefined;
  currentSessionId: string | null;
  selectedModel: string;
  setSessions: React.Dispatch<React.SetStateAction<Session[]>>;
  setCurrentSessionId: (id: string | null) => void;
  onConversationConflict?: (sessionId: string) => void;
}

export function useChat(options: UseChatOptions) {
  const { currentSession, currentSessionId, selectedModel, setSessions, setCurrentSessionId, onConversationConflict } = options;

  const [isLoading, setIsLoading] = useState(false);
  const [inputValue, setInputValue] = useState('');
  const [permissionRequest, setPermissionRequest] = useState<PermissionRequest | null>(null);
  // FAQ 建议
  const [faqSuggestions, setFaqSuggestions] = useState<FaqItem[]>([]);
  // ⭐ 排队状态
  const [queuePosition, setQueuePosition] = useState<number | null>(null);
  const [queueEstimatedWait, setQueueEstimatedWait] = useState<number | null>(null);
  const [progressMessage, setProgressMessage] = useState('');

  // ⭐ 当前流式请求的取消控制器（用于停止生成）
  const streamAbortRef = useRef<AbortController | null>(null);
  const activeRequestIdRef = useRef<string | null>(null);
  const recoveryTimersRef = useRef(new Map<string, ReturnType<typeof setTimeout>>());
  const [recoveryAvailable, setRecoveryAvailable] = useState(false);
  const recoveryAuthToken = getAuthToken();

  useEffect(() => {
    let disposed = false;
    setRecoveryAvailable(false);
    if (!recoveryAuthToken) return;
    sessionApi.fetchWorkflowRecoveryCapabilities()
      .then(result => { if (!disposed) setRecoveryAvailable(result.available === true); })
      .catch(() => { if (!disposed) setRecoveryAvailable(false); });
    return () => { disposed = true; };
  }, [recoveryAuthToken]);

  useEffect(() => () => {
    recoveryTimersRef.current.forEach(timer => clearTimeout(timer));
    recoveryTimersRef.current.clear();
  }, []);

  const sendMessage = useCallback(async (
    messageContent: string,
    sessionIdOverride?: string,
    onNavigate?: (path: string) => void,
    voiceReply = false,
    clarification?: import('../utils/clarificationForm').ClarificationSubmission,
  ) => {
    if (!messageContent.trim() || isLoading) return;

    let sessionId = sessionIdOverride || currentSessionId;

    const tempUserMessageId = crypto.randomUUID();
    const tempAssistantMessageId = crypto.randomUUID();
    const workflowRequestId = crypto.randomUUID();

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
      requestId: workflowRequestId,
      deliveryStatus: 'streaming',
      voiceReply,
    };

    // 如果没有会话，本地生成 sessionId 直接开聊（微服务未提供会话创建端点，dev/demo 模式）
    if (!sessionId) {
      const newSessionId = crypto.randomUUID();
      sessionId = newSessionId;
      const newSession: Session = {
        id: newSessionId,
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
      setCurrentSessionId(newSessionId);
      onNavigate?.(`/chat/${newSessionId}`);
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
    setProgressMessage('正在连接服务…');
    activeRequestIdRef.current = workflowRequestId;

    // ⭐ 使用 fetch 读取 SSE，以便携带 Bearer Token
    try {
      await streamWithFetch(
        messageContent, sessionId!, workflowRequestId, selectedModel,
        tempAssistantMessageId, clarification,
      );
    } catch (error) {
      console.error('Chat error:', error);
      setSessions(prev => prev.map(s => {
        if (s.id === sessionId) {
          return {
            ...s,
            messages: s.messages.map(m =>
              m.id === tempAssistantMessageId
                ? {
                  ...m,
                  content: error instanceof Error && error.message === 'CLARIFICATION_REJECTED'
                    ? '补充信息未通过校验或表单已失效，本次没有发起业务处理。请刷新会话后检查表单，也可以直接用文字补充。'
                    : '这次回复没能完整送达。请先查看原请求的结果，避免重复提交业务操作。',
                  isStreaming: false,
                  deliveryStatus: 'failed',
                  recoverable: !(error instanceof Error && error.message === 'CLARIFICATION_REJECTED') && Boolean(m.requestId),
                }
                : m
            ),
          };
        }
        return s;
      }));
    } finally {
      setIsLoading(false);
      setProgressMessage('');
      if (activeRequestIdRef.current === workflowRequestId) {
        activeRequestIdRef.current = null;
      }
    }
  }, [currentSession, currentSessionId, selectedModel, setSessions, setCurrentSessionId, isLoading]);

  /** 使用 fetch 读取 SSE；原生 EventSource 无法附带 Authorization 请求头。 */
  const streamWithFetch = useCallback(async (
    message: string,
    sessionId: string,
    requestId: string,
    model: string,
    assistantMessageId: string,
    clarification?: import('../utils/clarificationForm').ClarificationSubmission,
  ): Promise<void> => {
    let fullContent = '';
    let currentToolCalls: ToolCall[] = [];
    let contentBlocks: ContentBlock[] = [];
    let currentTextBlock = '';
    let realSessionId: string = sessionId;
    let realAssistantMessageId = assistantMessageId;
    let isDone = false;
    let isGateStopped = false;

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

    const url = '/api/math/stream/chat';
    const controller = new AbortController();
    streamAbortRef.current = controller;

    // ⭐ 通用事件处理：解析 SSE 的 data JSON
    const handleEvent = (event: { data: string; type: string }) => {
        try {
          const parsed = JSON.parse(event.data);
          const data = parsed?.data && typeof parsed.data === 'object'
            ? { ...parsed.data, type: parsed.type || parsed.data.type || event.type }
            : { ...parsed, type: parsed.type || event.type };

          if (data.type === 'preprocessing') {
            setProgressMessage('正在理解诉求并准备服务上下文…');
            return;
          }
          if (data.type === 'queue') {
            setProgressMessage('已收到您的问题，正在排队…');
            return;
          }

          if (['token_usage', 'tool_usage', 'tool', 'tool_call', 'tool_result'].includes(data.type)) {
            const patch = applyTelemetryEvent({ toolCalls: currentToolCalls }, data);
            if (!patch) return;
            if (patch.toolCalls) {
              currentToolCalls = patch.toolCalls;
              // Replace block references as calls complete; snapshots must not duplicate calls.
              contentBlocks = contentBlocks.filter(block => block.type !== 'tool_use'
                || currentToolCalls.some(tool => tool.id === block.toolCall.id))
                .map(block => block.type === 'tool_use'
                  ? { ...block, toolCall: currentToolCalls.find(tool => tool.id === block.toolCall.id)! } : block);
              for (const tool of currentToolCalls) {
                if (!contentBlocks.some(block => block.type === 'tool_use' && block.toolCall.id === tool.id)) {
                  currentTextBlock = '';
                  contentBlocks.push({ type: 'tool_use', toolCall: tool });
                }
              }
            }
            if (data.type === 'tool' || data.type === 'tool_call') setProgressMessage('正在查询业务数据…');
            if (data.type === 'tool_result') setProgressMessage('查询完成，正在核实结果…');
            const nextBlocks = [...contentBlocks];
            updateAssistantMessage(current => ({ ...current, ...patch, contentBlocks: nextBlocks }));
            return;
          }

          if (data.type === 'routed') {
            const agents = Array.isArray(data.participatingAgents)
              ? data.participatingAgents.filter((value: unknown): value is string => typeof value === 'string' && !!value)
              : [];
            const agentName = agents.length === 1 ? agents[0]
              : typeof data.agentName === 'string' ? data.agentName : null;
            const intent = normalizeIntentType(data.intentTag ?? agentName);
            setSessions(prev => prev.map(s => s.id === realSessionId || s.id === sessionId
              ? { ...s, agent_name: agentName || s.agent_name, intent: intent === 'unknown' ? s.intent : intent }
              : s));
          }

          if (data.type === 'init') {
            activeRequestIdRef.current = data.requestId || requestId;
            setProgressMessage('正在了解您的问题…');
            realSessionId = data.sessionId || sessionId;
            realAssistantMessageId = data.assistantMessageId || assistantMessageId;
            const normalizedIntent = normalizeIntentType(data.intent);
            if (normalizedIntent !== 'unknown') {
              setSessions(prev => prev.map(s =>
                s.id === realSessionId || s.id === sessionId
                  ? { ...s, intent: normalizedIntent, status: 'active' }
                  : s
              ));
            } else {
              setSessions(prev => prev.map(s =>
                s.id === realSessionId || s.id === sessionId
                  ? { ...s, status: 'active' }
                  : s
              ));
            }
            if (data.faqSuggestions?.length) {
              setFaqSuggestions(data.faqSuggestions);
            }
            updateAssistantMessage(current => ({
              ...current,
              requestId: data.requestId || requestId,
            }));
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
            setProgressMessage('');
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
              clarificationForm: data.type === 'response'
                ? normalizeClarificationForm(data.clarificationForm) : current.clarificationForm,
              toolCalls: [...currentToolCalls],
              contentBlocks: [...contentBlocks],
            }));

          } else if (data.type === 'cancelled') {
            isDone = true;
            isGateStopped = true;
            setProgressMessage('');
            setQueuePosition(null);
            setQueueEstimatedWait(null);
            updateAssistantMessage(current => ({ ...current,
              content: '已停止本次回答。', contentBlocks: [], isStreaming: false,
              deliveryStatus: 'stopped', recoverable: false,
              recoveryStatus: undefined, recoveryError: undefined,
            }));
          } else if (data.type === 'done') {
            isDone = true;
            setProgressMessage('');
            updateAssistantMessage(current => ({
              ...current,
              isStreaming: false,
              deliveryStatus: isGateStopped ? 'stopped' : 'completed',
              recoverable: false,
            }));

          } else if (data.type === 'conversation_suspended' || data.type === 'conversation_frozen') {
            if (typeof data.activeSessionId === 'string' && data.activeSessionId) {
              onConversationConflict?.(data.activeSessionId);
            }
            isGateStopped = true;
            setProgressMessage('');
            setQueuePosition(data.queuePosition || null);
            setQueueEstimatedWait(null);
            updateAssistantMessage(current => ({
              ...current,
              content: '当前账号正在使用其他对话。本对话已暂停，上下文会保留；关闭当前活跃对话后可继续。',
              isStreaming: false,
              deliveryStatus: 'stopped',
              recoverable: false,
            }));
            setSessions(prev => prev.map(session =>
              session.id === realSessionId || session.id === sessionId
                ? { ...session, status: 'suspended' }
                : session
            ));

          } else if (data.type === 'conversation_closed') {
            isGateStopped = true;
            setProgressMessage('');
            updateAssistantMessage(current => ({ ...current,
              content: '该会话已删除或正在删除，请新建对话。', isStreaming: false,
              deliveryStatus: 'stopped', recoverable: false,
            }));
            setSessions(prev => prev.map(session => session.id === realSessionId || session.id === sessionId
              ? { ...session, status: 'closed' } : session));
          } else if (data.type === 'request_blocked') {
            isGateStopped = true;
            setProgressMessage('');
            updateAssistantMessage(current => ({
              ...current,
              content: '上一条问题还在处理中，请等回复完成后再发送。',
              isStreaming: false,
              deliveryStatus: 'stopped',
              recoverable: false,
            }));

          } else if (data.type === 'request_in_progress') {
            isGateStopped = true;
            setProgressMessage('原请求仍在处理中…');
            updateAssistantMessage(current => ({
              ...current,
              content: '这条问题还在处理中，您可以在原对话中查看进展，不需要重复发送。',
              isStreaming: false,
              deliveryStatus: 'stopped',
              recoverable: false,
            }));

          } else if (data.type === 'conversation_gate_unavailable') {
            isDone = true;
            setProgressMessage('');
            updateAssistantMessage(current => ({
              ...current,
              content: '暂时没能确认这段对话的状态，请稍后刷新页面再试。',
              isStreaming: false,
              deliveryStatus: 'failed',
              recoverable: false,
            }));

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
            setProgressMessage('');
            updateAssistantMessage(current => ({
              ...current,
              content: data.content || data.message || '这次没能完成回复，请先查看原请求的结果，避免重复操作。',
              isStreaming: false,
              deliveryStatus: 'failed',
              recoverable: Boolean(current.requestId),
            }));
          } else if (data.type === 'timeout') {
            isDone = true;
            setProgressMessage('');
            updateAssistantMessage(current => ({
              ...current,
              content: data.content || '抱歉让您久等了，回复暂时还没有完成。请先查看原请求的结果，避免重复操作。',
              isStreaming: false,
              deliveryStatus: 'failed',
              recoverable: Boolean(current.requestId),
            }));
          }

          // ⭐ 排队事件
          if (data.type === 'queued') {
            setProgressMessage('已收到您的问题，正在排队…');
            setQueuePosition(data.position);
            setQueueEstimatedWait(data.estimatedWaitMs || data.position * 5000);
          } else if (data.type === 'queue_position') {
            setProgressMessage('还在排队，请稍等…');
            setQueuePosition(data.position);
            setQueueEstimatedWait(data.estimatedWaitMs || data.position * 5000);
          } else if (data.type === 'processing') {
            setProgressMessage('正在为您处理…');
            setQueuePosition(null);
            setQueueEstimatedWait(null);
          } else if (data.type === 'timeout') {
            setQueuePosition(null);
            setQueueEstimatedWait(null);
          }

          const stageMessage = workflowStageMessage(data.type);
          if (stageMessage) setProgressMessage(stageMessage);
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
      const response = await authenticatedFetch(url, {
        method: 'POST',
        headers: {
          Accept: 'text/event-stream',
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          message,
          sessionId,
          requestId,
          model,
          ...(clarification ? { clarification } : {}),
        }),
        signal: controller.signal,
      });
      if (!response.ok) {
        if (clarification && [400, 422].includes(response.status)) throw new Error('CLARIFICATION_REJECTED');
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
        setProgressMessage('');
        updateAssistantMessage(current => ({
          ...current,
          isStreaming: false,
          deliveryStatus: 'stopped',
          recoverable: false,
        }));
        return;
      }
      throw error;
    } finally {
      if (streamAbortRef.current === controller) streamAbortRef.current = null;
    }
  }, [setSessions, setFaqSuggestions, setPermissionRequest, setQueuePosition,
    setQueueEstimatedWait, setProgressMessage, onConversationConflict]);

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

  const updateRecoveredMessage = useCallback((messageId: string, job: WorkflowRecoveryJob) => {
    setSessions(prev => prev.map(session => ({
      ...session,
      messages: session.messages.map(message => {
        if (message.id !== messageId) return message;
        const succeeded = job.status === 'SUCCEEDED';
        const result = job.result?.trim();
        return {
          ...message,
          content: succeeded
            ? result || '工作流已恢复完成，但没有返回可展示的结果。'
            : message.content,
          contentBlocks: succeeded && result ? [{ type: 'text', text: result }] : message.contentBlocks,
          isStreaming: false,
          deliveryStatus: succeeded ? 'completed' : message.deliveryStatus,
          recoverable: succeeded ? false : !ACTIVE_RECOVERY_STATUSES.has(job.status),
          recoveryStatus: job.status,
          recoveryError: job.lastError ? publicRecoveryError(job.lastError) : undefined,
        };
      }),
    })));
  }, [setSessions]);

  const handleRecoverMessage = useCallback(async (messageId: string, requestId: string) => {
    if (!recoveryAvailable) return;
    const previousTimer = recoveryTimersRef.current.get(messageId);
    if (previousTimer) clearTimeout(previousTimer);

    setSessions(prev => prev.map(session => ({
      ...session,
      messages: session.messages.map(message => message.id === messageId
        ? { ...message, recoveryStatus: 'REQUESTED', recoveryError: undefined }
        : message),
    })));

    let polls = 0;
    const schedulePoll = (recoveryId: string) => {
      if (++polls > 60) {
        recoveryTimersRef.current.delete(messageId);
        setSessions(prev => prev.map(session => ({ ...session,
          messages: session.messages.map(message => message.id === messageId
            ? { ...message, recoveryStatus: undefined, recoveryError: recoveryErrorMessage(null) }
            : message),
        })));
        return;
      }
      const timer = setTimeout(async () => {
        try {
          const latest = await sessionApi.fetchWorkflowRecovery(recoveryId);
          updateRecoveredMessage(messageId, latest);
          if (ACTIVE_RECOVERY_STATUSES.has(latest.status)) schedulePoll(recoveryId);
          else recoveryTimersRef.current.delete(messageId);
        } catch (error) {
          setSessions(prev => prev.map(session => ({
            ...session,
            messages: session.messages.map(message => message.id === messageId
              ? { ...message, recoveryStatus: undefined, recoveryError: recoveryErrorMessage(error) }
              : message),
          })));
          const status = (error as { status?: number })?.status;
          if (status && [400, 401, 403, 404, 503].includes(status)) {
            recoveryTimersRef.current.delete(messageId);
            if (status === 404 || status === 503) setRecoveryAvailable(false);
          } else schedulePoll(recoveryId);
        }
      }, 2000);
      recoveryTimersRef.current.set(messageId, timer);
    };

    try {
      const job = await sessionApi.requestWorkflowRecovery(requestId);
      updateRecoveredMessage(messageId, job);
      if (ACTIVE_RECOVERY_STATUSES.has(job.status)) schedulePoll(job.recoveryId);
    } catch (error) {
      const status = (error as { status?: number })?.status;
      if (status === 404 || status === 503) setRecoveryAvailable(false);
      setSessions(prev => prev.map(session => ({
        ...session,
        messages: session.messages.map(message => message.id === messageId
          ? {
            ...message,
            recoveryStatus: undefined,
            recoveryError: recoveryErrorMessage(error),
            recoverable: true,
          }
          : message),
      })));
    }
  }, [setSessions, updateRecoveredMessage, recoveryAvailable]);

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
    const activeRequestId = activeRequestIdRef.current;
    // 中止 fetch，触发后端断开检测
    if (streamAbortRef.current) {
      streamAbortRef.current.abort();
      streamAbortRef.current = null;
    }
    // 使用当前工作流 requestId 通知后端取消；sessionId 不能代替执行 ID。
    if (activeRequestId) {
      void authenticatedFetch('/api/math/stream/chat/cancel', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({ requestId: activeRequestId }),
        keepalive: true,
      }).catch(() => undefined);
    }
    activeRequestIdRef.current = null;
    setProgressMessage('');
    setIsLoading(false);
  }, []);

  return {
    isLoading,
    inputValue,
    setInputValue,
    permissionRequest,
    faqSuggestions,
    queuePosition,
    queueEstimatedWait,
    progressMessage,
    sendMessage,
    handleStop,
    handlePermissionAllow,
    handlePermissionDeny,
    handleRecoverMessage,
    recoveryAvailable,
  };
}

function workflowStageMessage(type: unknown): string | null {
  switch (String(type ?? '')) {
    case 'waiting':
      return '正在了解您的需求…';
    case 'routed':
      return '正在为您处理…';
    case 'node_started':
      return '正在核对相关信息…';
    case 'node_completed':
      return '正在核对查询结果…';
    case 'node_quality_degraded':
      return '正在补充核实信息…';
    case 'node_evidence_limited':
      return '正在整理已确认的信息…';
    case 'summarizing':
      return '正在整理最终答复…';
    default:
      return null;
  }
}

const ACTIVE_RECOVERY_STATUSES = new Set<WorkflowRecoveryStatus>([
  'REQUESTED', 'QUEUED', 'RECOVERING', 'RETRY_SCHEDULED',
]);
