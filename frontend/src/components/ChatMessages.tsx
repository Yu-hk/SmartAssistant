import { Loading } from 'tdesign-react';
import { ChatMarkdown } from '@tdesign-react/chat';
import { Message, Model, PermissionRequest, ContentBlock } from '../types';
import { ToolCallsCollapse } from './ToolCallsCollapse';
import { InlinePermissionCard } from './InlinePermissionCard';

interface ChatMessagesProps {
  messages: Message[];
  models: Model[];
  messagesEndRef: React.RefObject<HTMLDivElement>;
  permissionRequest?: PermissionRequest | null;
  onPermissionAllow?: () => void;
  onPermissionDeny?: () => void;
  queuePosition?: number | null;
  queueEstimatedWait?: number | null;
}

export function ChatMessages({ 
  messages, 
  models, 
  messagesEndRef,
  permissionRequest,
  onPermissionAllow,
  onPermissionDeny,
  queuePosition,
  queueEstimatedWait
}: ChatMessagesProps) {
  const formatModelName = (modelId: string) => {
    const model = models.find(m => m.modelId === modelId);
    const name = model?.name || modelId;
    return name
      .replace(/^(Claude|GPT|Gemini|Kimi|DeepSeek|Qwen|GLM)\s*/i, '')
      .replace(/-/g, ' ')
      .trim() || name;
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
            <ChatMarkdown content={block.text} />
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
              <ChatMarkdown content={message.content} />
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
            {/* 模型标签 */}
            {message.role === 'assistant' && message.model && (
              <span style={{
                fontSize: '11px',
                color: 'var(--nova-text-tertiary)',
                fontWeight: 500,
                marginLeft: '4px',
              }}>
                {formatModelName(message.model)}
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
                    : '思考中...'}
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
