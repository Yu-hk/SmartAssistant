import { Loading } from 'tdesign-react';
import { Message, Model, PermissionRequest, ContentBlock, SessionStatus } from '../types';
import { ToolCallsCollapse } from './ToolCallsCollapse';
import { InlinePermissionCard } from './InlinePermissionCard';
import { SafeMarkdown } from './SafeMarkdown';
import { RefreshCw } from 'lucide-react';

interface ChatMessagesProps {
  messages: Message[];
  models: Model[];
  messagesEndRef: React.RefObject<HTMLDivElement>;
  permissionRequest?: PermissionRequest | null;
  onPermissionAllow?: () => void;
  onPermissionDeny?: () => void;
  queuePosition?: number | null;
  queueEstimatedWait?: number | null;
  progressMessage?: string;
  sessionStatus?: SessionStatus;
  satisfaction?: number | null;
  onRateSession?: (score: number) => void;
  agentName?: string;
  onRecoverMessage?: (messageId: string, requestId: string) => void;
}

export function ChatMessages({ 
  messages, 
  models, 
  messagesEndRef,
  permissionRequest,
  onPermissionAllow,
  onPermissionDeny,
  queuePosition,
  queueEstimatedWait,
  progressMessage,
  sessionStatus,
  satisfaction,
  onRateSession,
  agentName,
  onRecoverMessage,
}: ChatMessagesProps) {
  const satisfactionOptions = [
    { score: 1, emoji: '😞', label: '很不满意' },
    { score: 2, emoji: '😕', label: '不满意' },
    { score: 3, emoji: '😐', label: '一般' },
    { score: 4, emoji: '😊', label: '满意' },
    { score: 5, emoji: '🤩', label: '非常满意' },
  ];
  let lastAssistantIndex = -1;
  for (let index = messages.length - 1; index >= 0; index--) {
    if (messages[index].role === 'assistant') {
      lastAssistantIndex = index;
      break;
    }
  }

  const formatModelName = (modelId: string) => {
    const model = models.find(m => m.modelId === modelId);
    const name = model?.name || modelId;
    return name
      .replace(/^(Claude|GPT|Gemini|Kimi|DeepSeek|Qwen|GLM)\s*/i, '')
      .replace(/-/g, ' ')
      .trim() || name;
  };

  const formatServiceName = (value: string) => {
    const normalized = value.toLowerCase();
    if (normalized.includes('product')) return '商品服务';
    if (normalized.includes('order') || normalized.includes('logistics')) return '订单服务';
    if (normalized.includes('knowledge') || normalized.includes('rag')) return '知识服务';
    if (normalized.includes('general') || normalized.includes('fallback')
        || normalized.includes('orchestrator')) return '智能助手';
    return value.replace(/[_-]+/g, ' ').trim();
  };

  const renderContentBlock = (block: ContentBlock, index: number, isStreaming?: boolean, isLast?: boolean) => {
    if (block.type === 'text') {
      return (
        <div 
          key={`text-${index}`}
          className="animate-fade-in-up"
          style={{
            padding: '14px 18px',
            background: 'var(--nova-bg-glass)',
            color: 'var(--nova-text-primary)',
            borderRadius: '16px 16px 16px 4px',
            border: '1px solid var(--nova-border)',
            backdropFilter: 'blur(8px)',
          }}
        >
          <div className="chat-markdown">
            <SafeMarkdown content={block.text} />
          </div>
          {isStreaming && isLast && (
            <span className="cursor-blink">|</span>
          )}
        </div>
      );
    } else if (block.type === 'tool_use') {
      return (
        <ToolCallsCollapse
          key={`tool-${block.toolCall.id}`}
          toolCalls={[block.toolCall]}
          isStreaming={isStreaming && block.toolCall.status === 'running'}
        />
      );
    }
    return null;
  };

  const renderAssistantContent = (message: Message) => {
    if (message.contentBlocks && message.contentBlocks.length > 0) {
      return message.contentBlocks.map((block, index) => 
        renderContentBlock(block, index, message.isStreaming, index === message.contentBlocks!.length - 1)
      );
    }
    
    return (
      <>
        {message.toolCalls && message.toolCalls.length > 0 && (
          <ToolCallsCollapse
            toolCalls={message.toolCalls}
            isStreaming={message.isStreaming}
          />
        )}
        {message.content && (
          <div 
            className="animate-fade-in-up"
            style={{
              padding: '14px 18px',
              background: 'var(--nova-bg-glass)',
              color: 'var(--nova-text-primary)',
              borderRadius: '16px 16px 16px 4px',
              border: '1px solid var(--nova-border)',
              backdropFilter: 'blur(8px)',
            }}
          >
            <div className="chat-markdown">
              <SafeMarkdown content={message.content} />
            </div>
            {message.isStreaming && (
              <span className="cursor-blink">|</span>
            )}
          </div>
        )}
      </>
    );
  };

  return (
    <div className="flex flex-col gap-5" style={{ maxWidth: '800px', margin: '0 auto' }}>
      {messages.map((message, idx) => (
        <div 
          key={message.id} 
          className={`animate-fade-in-up flex gap-3 ${message.role === 'user' ? 'flex-row-reverse' : ''}`}
          style={{ animationDelay: `${idx * 0.03}s`, animationFillMode: 'both' }}
        >
          {/* 头像 */}
          <div 
            className="flex-shrink-0 self-start"
            style={{
              width: '36px', height: '36px',
              borderRadius: '12px',
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              background: message.role === 'user'
                ? 'linear-gradient(135deg, var(--nova-accent), var(--nova-secondary))'
                : 'var(--nova-bg-component)',
              color: message.role === 'user' ? 'white' : 'var(--nova-text-primary)',
              border: message.role === 'assistant' ? '1px solid var(--nova-border)' : 'none',
              boxShadow: message.role === 'user' ? '0 0 12px var(--nova-accent-glow)' : 'none',
              fontSize: '14px',
              fontWeight: 600,
            }}
          >
            {message.role === 'user' ? (
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" />
                <circle cx="12" cy="7" r="4" />
              </svg>
            ) : (
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <rect x="3" y="11" width="18" height="11" rx="2" ry="2" />
                <path d="M7 11V7a5 5 0 0 1 10 0v4" />
              </svg>
            )}
          </div>

          <div className={`flex flex-col gap-2 ${message.role === 'user' ? 'items-end' : ''}`} style={{ maxWidth: '80%' }}>
            {/* 面向用户展示业务服务名称；内部编排节点名称不得泄漏。 */}
            {message.role === 'assistant' && (agentName || message.model) && (
              <span style={{
                fontSize: '11px',
                color: 'var(--nova-text-tertiary)',
                fontWeight: 500,
                marginLeft: '4px',
                display: 'inline-flex',
                alignItems: 'center',
                gap: '5px',
              }}>
                <span style={{
                  width: '6px', height: '6px', borderRadius: '50%',
                  background: 'var(--nova-accent)',
                  display: 'inline-block',
                  boxShadow: '0 0 6px var(--nova-accent)',
                }} />
                {agentName ? formatServiceName(agentName) : formatModelName(message.model!)}
              </span>
            )}
            
            {/* 用户消息 */}
            {message.role === 'user' && (
              <div 
                className="animate-scale-in"
                style={{
                  padding: '12px 18px',
                  background: 'linear-gradient(135deg, var(--nova-accent), var(--nova-secondary))',
                  color: 'white',
                  borderRadius: '16px 16px 4px 16px',
                  fontSize: '14px',
                  lineHeight: 1.6,
                  boxShadow: '0 4px 16px var(--nova-accent-glow)',
                  whiteSpace: 'pre-wrap',
                  wordBreak: 'break-word',
                }}
              >
                {message.content}
              </div>
            )}
            
            {/* 助手消息 */}
            {message.role === 'assistant' && renderAssistantContent(message)}

            {message.role === 'assistant' && message.requestId
              && (message.recoverable || message.recoveryStatus || message.recoveryError)
              && onRecoverMessage && (
              <div className="chat-recovery-card" aria-live="polite">
                {message.recoveryStatus && (
                  <span className="chat-recovery-status">
                    {formatRecoveryStatus(message.recoveryStatus)}
                  </span>
                )}
                {message.recoveryError && <span className="chat-recovery-error">{message.recoveryError}</span>}
                {message.recoverable && !isRecoveryActive(message.recoveryStatus) && (
                  <button
                    type="button"
                    onClick={() => onRecoverMessage(message.id, message.requestId!)}
                  >
                    <RefreshCw size={14} /> 恢复本次回答
                  </button>
                )}
                {isRecoveryActive(message.recoveryStatus) && (
                  <span className="chat-recovery-progress"><Loading size="small" /> 正在从检查点恢复…</span>
                )}
              </div>
            )}

              {message.role === 'assistant'
                && idx === lastAssistantIndex
                && !message.isStreaming
                && Boolean(message.content || message.contentBlocks?.length)
                && !message.content.trimStart().startsWith('⚠️')
                && sessionStatus === 'active'
                && satisfaction == null
                && onRateSession && (
                <div
                  className="animate-fade-in-up"
                  style={{
                    width: '100%',
                    padding: '12px 14px',
                    borderRadius: '12px',
                    border: '1px solid var(--nova-border)',
                    background: 'var(--nova-bg-component)',
                  }}
                >
                  <div style={{
                    fontSize: '12px',
                    color: 'var(--nova-text-secondary)',
                    marginBottom: '10px',
                  }}>
                    本次回复对你有帮助吗？评价后将结束本次会话
                  </div>
                  <div style={{ display: 'flex', gap: '8px', flexWrap: 'wrap' }}>
                    {satisfactionOptions.map(option => (
                      <button
                        key={option.score}
                        type="button"
                        title={`${option.score} 分 · ${option.label}`}
                        aria-label={`${option.score} 分 · ${option.label}`}
                        onClick={() => onRateSession(option.score)}
                        style={{
                          width: '42px',
                          height: '36px',
                          borderRadius: '10px',
                          border: '1px solid var(--nova-border)',
                          background: 'var(--nova-bg-glass)',
                          cursor: 'pointer',
                          fontSize: '19px',
                          transition: 'all 0.2s',
                        }}
                      >
                        {option.emoji}
                      </button>
                    ))}
                  </div>
                </div>
              )}

            {message.role === 'assistant'
              && idx === lastAssistantIndex
              && sessionStatus === 'closed' && (
                <div style={{
                  padding: '9px 12px',
                  borderRadius: '10px',
                  border: '1px solid var(--nova-border)',
                  color: 'var(--nova-text-secondary)',
                  background: 'var(--nova-bg-component)',
                  fontSize: '12px',
                }}>
                  {satisfaction != null
                    ? `已评价 ${satisfaction} 分 · 本次会话已结束`
                    : '本次会话已结束'}
                </div>
              )}
            
            {/* 思考中 / 排队中 */}
            {message.role === 'assistant' && message.isStreaming && 
             !message.content && 
             (!message.contentBlocks || message.contentBlocks.length === 0) && 
             (!message.toolCalls || message.toolCalls.length === 0) && (
              <div 
                className="flex items-center gap-2"
                style={{
                  padding: '10px 16px',
                  borderRadius: '12px',
                  background: 'var(--nova-bg-component)',
                  border: queuePosition ? '1px solid var(--nova-accent)' : '1px solid var(--nova-border)',
                }}
              >
                <Loading size="small" />
                <span style={{ fontSize: '13px', color: 'var(--nova-text-secondary)' }}>
                  {queuePosition
                    ? `⏳ 排队中，前面还有 ${queuePosition} 人` + (queueEstimatedWait ? `，预计等待 ${Math.ceil(queueEstimatedWait / 1000)} 秒` : '')
                    : progressMessage || '正在处理…'}
                </span>
              </div>
            )}
          </div>
        </div>
      ))}
      
      {/* 内联权限确认 */}
      {permissionRequest && onPermissionAllow && onPermissionDeny && (
        <div className="flex gap-3 ml-12 animate-fade-in">
          <InlinePermissionCard
            request={permissionRequest}
            onAllow={onPermissionAllow}
            onDeny={onPermissionDeny}
          />
        </div>
      )}
      
      <div ref={messagesEndRef} />
    </div>
  );
}

function isRecoveryActive(status?: Message['recoveryStatus']): boolean {
  return Boolean(status && ['REQUESTED', 'QUEUED', 'RECOVERING', 'RETRY_SCHEDULED'].includes(status));
}

function formatRecoveryStatus(status: NonNullable<Message['recoveryStatus']>): string {
  return ({
    REQUESTED: '恢复请求已提交',
    QUEUED: '恢复任务排队中',
    RECOVERING: '正在恢复工作流',
    RETRY_SCHEDULED: '恢复任务等待重试',
    SUCCEEDED: '回答已恢复',
    DEAD_LETTERED: '恢复失败，可再次尝试',
    SKIPPED_ACTIVE: '任务仍在执行，请稍后重试',
    SKIPPED_APPROVAL: '任务正在等待用户确认',
    SKIPPED_SUPERSEDED: '旧检查点已失效',
    SKIPPED_DUPLICATE: '重复恢复已跳过',
    REJECTED_INVALID_COMMAND: '恢复请求无效',
  })[status];
}
