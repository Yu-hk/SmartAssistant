import React, { useRef, useEffect, useCallback, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Session, PermissionRequest, FaqItem } from '../types';
import { ChatMessages } from '../components/ChatMessages';
import { FaqSuggestions } from '../components/FaqSuggestions';
import { IntentBadge } from '../components/IntentBadge';
import { sessions as sessionApi } from '../api';
import {
  ArrowRight,
  BookOpenText,
  CircleCheck,
  Compass,
  Headset,
  MessagesSquare,
  PackageSearch,
  PhoneForwarded,
  ShoppingBag,
  Sparkles,
  Workflow,
} from 'lucide-react';

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
  userName?: string;
}

const QUICK_QUESTIONS = [
  { icon: PackageSearch, text: '帮我追踪最近一笔订单' },
  { icon: ShoppingBag, text: '推荐现在的热门商品' },
  { icon: BookOpenText, text: '从知识库查资料并总结' },
];

const CAPABILITIES = [
  { icon: PackageSearch, title: '订单助手', desc: '查订单、跟物流、处理售后', tone: 'cyan', prompt: '请帮我查询订单：' },
  { icon: ShoppingBag, title: '商品顾问', desc: '商品咨询、参数对比与推荐', tone: 'amber', prompt: '请帮我推荐或对比商品：' },
  { icon: BookOpenText, title: '知识检索', desc: '检索资料、文档问答与总结', tone: 'emerald', prompt: '请从知识库中查找并总结：' },
  { icon: Workflow, title: '综合协助', desc: '识别需求并安排合适的处理步骤', tone: 'indigo', prompt: '请帮我处理：' },
];

function getGreeting() {
  const hour = new Date().getHours();
  if (hour < 6) return '夜深了';
  if (hour < 12) return '上午好';
  if (hour < 18) return '下午好';
  return '晚上好';
}

export function CustomerChatPage({
  sessions,
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
  userName,
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

  const homeStats = {
    total: sessions.length,
    active: sessions.filter(s => s.status === 'active').length,
    human: sessions.filter(s => s.status === 'human_transfer').length,
    closed: sessions.filter(s => s.status === 'closed').length,
  };

  return (
    <>
      {/* 消息区域 */}
      <div className={`chat-content flex-1 overflow-y-auto scrollbar-thin ${!hasMessages ? 'is-home' : ''}`}>
        {!hasMessages ? (
          <section className="assistant-home" aria-labelledby="home-title">
            <div className="home-hero">
              <div className="home-eyebrow"><Sparkles size={15} /> 智能服务助手</div>
              <h1 id="home-title">{getGreeting()}{userName ? `，${userName}` : ''}</h1>
              <p>{isClosed
                ? '该会话已结束，请从左侧新建会话后继续。'
                : isSuspended
                  ? '该会话已暂停且上下文已保留；请从左侧暂停列表中主动恢复。'
                  : '直接描述需要处理的事情，或从下方选择服务入口；系统会自动安排后续步骤。'}</p>
            </div>

            <div className="home-stats" aria-label="接待概览">
              <div className="home-stat">
                <span className="home-stat-icon"><MessagesSquare size={17} /></span>
                <span><strong>{homeStats.total}</strong><span>全部会话</span></span>
              </div>
              <div className="home-stat">
                <span className="home-stat-icon is-ok"><Headset size={17} /></span>
                <span><strong>{homeStats.active}</strong><span>进行中</span></span>
              </div>
              <div className="home-stat">
                <span className="home-stat-icon is-warn"><PhoneForwarded size={17} /></span>
                <span><strong>{homeStats.human}</strong><span>转人工</span></span>
              </div>
              <div className="home-stat">
                <span className="home-stat-icon is-muted"><CircleCheck size={17} /></span>
                <span><strong>{homeStats.closed}</strong><span>已结束</span></span>
              </div>
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

            <div className="home-section-heading">
              <span>选择服务能力</span>
              <small>也可以直接在上方输入客户问题</small>
            </div>
            <div className="home-capability-grid">
              {CAPABILITIES.map((item, idx) => {
                const Icon = item.icon;
                return (
                  <button
                    type="button"
                    key={item.title}
                    className={`home-capability-card tone-${item.tone} animate-fade-in-up`}
                    onClick={() => onInputChange(item.prompt)}
                    disabled={isClosed || isSuspended}
                    aria-label={`使用${item.title}`}
                    style={{ animationDelay: `${idx * 0.06}s` }}
                  >
                    <span className="home-capability-icon"><Icon size={21} /></span>
                    <span className="home-capability-copy">
                      <strong>{item.title}</strong>
                      <small>{item.desc}</small>
                    </span>
                    <ArrowRight className="home-card-arrow" size={16} />
                  </button>
                );
              })}
            </div>

            <div className="home-quick-row">
              <span className="home-quick-label"><Compass size={14} /> 快速开始</span>
              <div className="home-quick-actions">
                {QUICK_QUESTIONS.map(q => {
                  const Icon = q.icon;
                  return (
                    <button type="button" key={q.text} disabled={isClosed || isSuspended} onClick={() => handleSend(q.text)}>
                      <Icon size={14} /> {q.text}
                    </button>
                  );
                })}
              </div>
            </div>
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
// 输入框 — 霓虹科技风格
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

  // 自动调整高度
  useEffect(() => {
    const ta = textareaRef.current;
    if (ta) {
      ta.style.height = 'auto';
      ta.style.height = Math.min(ta.scrollHeight, 120) + 'px';
    }
  }, [inputValue]);

  return (
    <div className={`chat-composer-shell ${variant === 'home' ? 'is-home' : 'glass is-docked'}`}>
      <div className={`chat-composer ${isFocused ? 'is-focused' : ''}`}>
        <textarea
          ref={textareaRef}
          value={inputValue}
          onChange={e => onChange(e.target.value)}
          onKeyDown={handleKeyDown}
          onFocus={() => setIsFocused(true)}
          onBlur={() => setIsFocused(false)}
          placeholder={disabled
            ? disabledMessage || '本次会话已结束，请开启新对话'
            : '输入你的问题或业务需求...（Enter 发送）'}
          disabled={disabled || isLoading}
          rows={1}
          autoFocus={variant === 'home'}
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
        <span className="composer-disclaimer">
          AI 生成内容可能存在偏差，涉及订单、金额和关键业务操作时请再次确认
        </span>
      </div>
    </div>
  );
}
