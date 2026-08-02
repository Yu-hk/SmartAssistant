import { Loading } from 'tdesign-react';
import { ChatMarkdown } from '@tdesign-react/chat';
import { Message, Model, PermissionRequest, ContentBlock } from '../types';
import { ToolCallsCollapse } from './ToolCallsCollapse';
import { InlinePermissionCard } from './InlinePermissionCard';
import { OrderChoicePanel } from './OrderChoicePanel';
import { FollowUpSuggestionPanel } from './FollowUpSuggestionPanel';

interface ChatMessagesProps {
  messages: Message[];
  models: Model[];
  messagesEndRef: React.RefObject<HTMLDivElement>;
  permissionRequest?: PermissionRequest | null;
  onPermissionAllow?: () => void;
  onPermissionDeny?: () => void;
  queuePosition?: number | null;
  queueEstimatedWait?: number | null;
  onOrderSelect?: (orderId: string) => void;
  orderSelectionDisabled?: boolean;
  onFollowUpSelect?: (suggestion: string) => void;
}

export function ChatMessages({ 
  messages, 
  models: _models,
  messagesEndRef,
  permissionRequest,
  onPermissionAllow,
  onPermissionDeny,
  queuePosition,
  queueEstimatedWait,
  onOrderSelect,
  orderSelectionDisabled,
  onFollowUpSelect,
}: ChatMessagesProps) {
  const renderContentBlock = (block: ContentBlock, index: number, isStreaming?: boolean, isLast?: boolean) => {
    if (block.type === 'text') {
      return (
        <div 
          key={`text-${index}`}
          className="customer-assistant-bubble animate-fade-in-up"
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
            className="customer-assistant-bubble animate-fade-in-up"
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
    <div className="customer-message-list flex flex-col gap-5">
      {messages.map((message, idx) => (
        <div 
          key={message.id} 
          className={`animate-fade-in-up flex gap-3 ${message.role === 'user' ? 'flex-row-reverse' : ''}`}
          style={{ animationDelay: `${idx * 0.03}s`, animationFillMode: 'both' }}
        >
          {/* 头像 */}
          <div className={`customer-message-avatar flex-shrink-0 self-start customer-message-avatar--${message.role}`}>
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

          <div className={`customer-message-content flex flex-col gap-2 ${message.role === 'user' ? 'items-end' : ''}`}>
            {/* 面向用户展示客服身份，不暴露底层模型名称 */}
            {message.role === 'assistant' && (
              <span className="customer-message-agent">
                小智客服 <i aria-hidden="true" />
              </span>
            )}
            
            {/* 用户消息 */}
            {message.role === 'user' && (
              <div 
                className="customer-user-bubble animate-scale-in"
              >
                {message.content}
              </div>
            )}
            
            {/* 助手消息 */}
            {message.role === 'assistant' && renderAssistantContent(message)}

            {message.role === 'assistant' && !message.isStreaming && onOrderSelect && (
              <OrderChoicePanel
                key={message.id}
                content={message.content}
                disabled={orderSelectionDisabled}
                onSelect={onOrderSelect}
              />
            )}

            {message.role === 'assistant' && !message.isStreaming && onFollowUpSelect && (
              <FollowUpSuggestionPanel
                key={`follow-up-${message.id}`}
                content={message.content}
                disabled={orderSelectionDisabled}
                onSelect={onFollowUpSelect}
              />
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
