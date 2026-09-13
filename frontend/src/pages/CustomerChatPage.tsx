import React, { useRef, useEffect, useCallback, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Session, PermissionRequest, FaqItem } from '../types';
import { ChatMessages } from '../components/ChatMessages';
import { SessionExecutionSteps } from '../components/SessionExecutionSteps';
import { ScenarioExamples } from '../components/ScenarioExamples';
import { DocumentExamples } from '../components/DocumentExamples';
import { FaqSuggestions } from '../components/FaqSuggestions';
import { IntentBadge } from '../components/IntentBadge';
import { sessions as sessionApi } from '../api';
import { Headset, FileText } from 'lucide-react';

interface CustomerChatPageProps {
  sessions: Session[];
  currentSession: Session | undefined;
  isLoading: boolean;
  inputValue: string;
  permissionRequest: PermissionRequest | null;
  faqSuggestions: FaqItem[];
  queuePosition: number | null;
  queueEstimatedWait: number | null;
  progressMessage: string;
  onSendMessage: (message: string, sessionIdOverride?: string, onNavigate?: (path: string) => void) => void;
  onStop: () => void;
  onInputChange: (value: string) => void;
  onPermissionAllow: () => void;
  onPermissionDeny: () => void;
  onRecoverMessage: (messageId: string, requestId: string) => void;
  onRateSession: (score: number) => void;
}

export function CustomerChatPage({
  currentSession,
  isLoading,
  inputValue,
  permissionRequest,
  faqSuggestions,
  queuePosition,
  queueEstimatedWait,
  progressMessage,
  onSendMessage,
  onStop,
  onInputChange,
  onPermissionAllow,
  onPermissionDeny,
  onRecoverMessage,
  onRateSession,
}: CustomerChatPageProps) {
  const navigate = useNavigate();
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const [currentFaqSuggestions, setCurrentFaqSuggestions] = useState<FaqItem[]>([]);

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [currentSession?.messages]);

  useEffect(() => {
    if (faqSuggestions.length > 0) {
      setCurrentFaqSuggestions(faqSuggestions);
    }
  }, [faqSuggestions]);

  const handleSend = useCallback((message: string) => {
    setCurrentFaqSuggestions([]);
    if (!currentSession) {
      onSendMessage(message, undefined, (path) => navigate(path));
    } else {
      onSendMessage(message);
    }
  }, [currentSession, onSendMessage, navigate]);

  const handleFaqSelect = useCallback((faq: FaqItem) => {
    onSendMessage(faq.question);
    sessionApi.hitFaq(faq.id).catch(() => {});
  }, [onSendMessage]);

  const hasMessages = currentSession && currentSession.messages.length > 0;
  const isClosed = currentSession?.status === 'closed';
  const isSuspended = currentSession?.status === 'suspended';

  return (
    <>
      {/* 消息区域 */}
      <div className={`chat-content flex-1 overflow-y-auto scrollbar-thin ${!hasMessages ? 'is-home' : ''}`}>
        {!hasMessages ? (
          <section className="assistant-home" aria-labelledby="home-title">
            <div className="home-hero">
              <div className="home-eyebrow"><Headset size={18} /> 智服 · 智能客服助手</div>
              <h1 id="home-title">有什么可以帮你？</h1>
              <p>{isClosed
                ? '该会话已结束，请从左侧新建会话后继续。'
                : isSuspended
                  ? '该会话已暂停且上下文已保留；请从左侧暂停列表中主动恢复。'
                  : '查订单、选商品、查资料，从一个问题开始。'}</p>
            </div>

            <CustomerChatInput
              variant="home"
              inputValue={inputValue}
              isLoading={isLoading}
              disabled={isClosed || isSuspended}
              disabledMessage={isSuspended
                ? '该会话已暂停，请从左侧暂停列表中选择恢复'
                : undefined}
              onSend={handleSend}
              onStop={onStop}
              onChange={onInputChange}
            />

            <ScenarioExamples disabled={isClosed || isSuspended || isLoading} onSelect={onInputChange} />
            <details className="home-documents" key={currentSession?.id || 'new'}>
              <summary><FileText size={17} /><span>文档问答<small>导入资料，或试用示例文档</small></span><span className="home-documents-toggle" aria-hidden="true">+</span></summary>
              <DocumentExamples disabled={isClosed || isSuspended || isLoading}
                hasDraft={Boolean(inputValue.trim())} onSelect={onInputChange} />
            </details>
          </section>
        ) : (
          /* ===== 对话区域 ===== */
          <div style={{ maxWidth: '800px', margin: '0 auto' }}>
            {/* 会话意图 */}
            {currentSession && currentSession.intent !== 'unknown' && (
              <div style={{
                display: 'flex', alignItems: 'center', gap: '8px',
                marginBottom: '16px', padding: '0 4px',
              }}>
                <span style={{
                  fontSize: '11px', color: 'var(--nova-text-tertiary)',
                  fontWeight: 500, letterSpacing: '0.05em',
                  textTransform: 'uppercase',
                }}>
                  本次咨询分类
                </span>
                <IntentBadge intent={currentSession.intent} size="sm" />
              </div>
            )}

            {/* FAQ 建议 */}
            {currentFaqSuggestions.length > 0 && (
              <FaqSuggestions
                faqs={currentFaqSuggestions}
                onSelect={handleFaqSelect}
                onDismiss={() => setCurrentFaqSuggestions([])}
              />
            )}

            {/* 消息列表 */}
            <div className="chat-mobile-execution">
              <SessionExecutionSteps key={currentSession!.id} messages={currentSession!.messages} defaultOpen={false} />
            </div>
            <ChatMessages
              messages={currentSession!.messages}
              models={[]}
              messagesEndRef={messagesEndRef}
              permissionRequest={permissionRequest}
              onPermissionAllow={onPermissionAllow}
              onPermissionDeny={onPermissionDeny}
              onRecoverMessage={onRecoverMessage}
              queuePosition={queuePosition}
              queueEstimatedWait={queueEstimatedWait}
              progressMessage={progressMessage}
              sessionStatus={currentSession?.status}
              satisfaction={currentSession?.satisfaction}
              onRateSession={onRateSession}
              agentName={currentSession?.agent_name ?? undefined}
            />
          </div>
        )}
      </div>

      {hasMessages && (
        <CustomerChatInput
          inputValue={inputValue}
          isLoading={isLoading}
          disabled={currentSession?.status === 'closed' || currentSession?.status === 'suspended'}
          disabledMessage={currentSession?.status === 'suspended'
            ? '该会话已暂停，请从左侧暂停列表中选择恢复'
            : undefined}
          onSend={handleSend}
          onStop={onStop}
          onChange={onInputChange}
        />
      )}

    </>
  );
}

// ===================================================
// 输入框 — 首页与对话页共享
// ===================================================
interface CustomerChatInputProps {
  variant?: 'home' | 'docked';
  inputValue: string;
  isLoading: boolean;
  disabled?: boolean;
  disabledMessage?: string;
  onSend: (msg: string) => void;
  onStop: () => void;
  onChange: (val: string) => void;
}

function CustomerChatInput({
  variant = 'docked',
  inputValue,
  isLoading,
  disabled,
  disabledMessage,
  onSend,
  onStop,
  onChange,
}: CustomerChatInputProps) {
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const [isFocused, setIsFocused] = useState(false);

  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault();
      if (inputValue.trim() && !isLoading && !disabled) onSend(inputValue);
    }
  };

  // 单行不预留滚动条；多行按内容扩展，达到上限后才允许滚动。
  useEffect(() => {
    const ta = textareaRef.current;
    if (!ta) return;
    const resize = () => {
      ta.style.overflowY = 'hidden';
      ta.style.height = 'auto';
      const contentHeight = ta.scrollHeight;
      ta.style.height = Math.min(contentHeight, 120) + 'px';
      ta.style.overflowY = contentHeight > 120 ? 'auto' : 'hidden';
    };
    resize();
    if (typeof ResizeObserver === 'undefined') return;
    // 页面宽度或侧栏变化也会导致换行，仅宽度变化时重算，避免高度观察循环。
    let width = ta.getBoundingClientRect().width;
    const observer = new ResizeObserver(() => {
      const nextWidth = ta.getBoundingClientRect().width;
      if (nextWidth === width) return;
      width = nextWidth;
      resize();
    });
    observer.observe(ta);
    return () => observer.disconnect();
  }, [inputValue]);

  return (
    <div className={`chat-composer-shell ${variant === 'home' ? 'is-home' : 'glass is-docked'}`}>
      <div className={`chat-composer ${isFocused ? 'is-focused' : ''}`}>
        <textarea
          ref={textareaRef}
          value={inputValue}
          aria-label="输入你的问题"
          onChange={e => onChange(e.target.value)}
          onKeyDown={handleKeyDown}
          onFocus={() => setIsFocused(true)}
          onBlur={() => setIsFocused(false)}
          placeholder={disabled
            ? disabledMessage || '本次会话已结束，请开启新对话'
            : '输入你的问题…'}
          disabled={disabled || isLoading}
          rows={1}
          className="chat-composer-input"
        />
        {isLoading ? (
          <button
            onClick={onStop}
            className="chat-composer-action is-stop"
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
              <rect x="6" y="6" width="12" height="12" rx="2" />
            </svg>
            停止
          </button>
        ) : (
          <button
            onClick={() => inputValue.trim() && !disabled && onSend(inputValue)}
            disabled={!inputValue.trim() || disabled}
            className="chat-composer-action is-send"
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
              <line x1="22" y1="2" x2="11" y2="13" />
              <polygon points="22 2 15 22 11 13 2 9 22 2" />
            </svg>
            发送
          </button>
        )}
      </div>
      <div className="chat-composer-meta">
        <span className="composer-shortcut">Enter 发送 · Shift + Enter 换行</span>
        <span className="composer-disclaimer">
          AI 回复仅供参考，关键业务信息请核实
        </span>
      </div>
    </div>
  );
}
