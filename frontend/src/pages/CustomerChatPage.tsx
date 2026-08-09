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
  CloudSun,
  Compass,
  PackageSearch,
  ShoppingBag,
  Sparkles,
  Workflow,
} from 'lucide-react';

interface CustomerChatPageProps {
  currentSession: Session | undefined;
  isLoading: boolean;
  inputValue: string;
  permissionRequest: PermissionRequest | null;
  faqSuggestions: FaqItem[];
  queuePosition: number | null;
  queueEstimatedWait: number | null;
  locationEnabled: boolean;
  locationStatus: 'off' | 'ready' | 'denied' | 'unavailable';
  onSendMessage: (message: string, sessionIdOverride?: string, onNavigate?: (path: string) => void) => void;
  onStop: () => void;
  onInputChange: (value: string) => void;
  onPermissionAllow: () => void;
  onPermissionDeny: () => void;
  onRateSession: (score: number) => void;
  onLocationEnabledChange: (enabled: boolean) => void;
  userName?: string;
}

const QUICK_QUESTIONS = [
  { icon: CloudSun, text: '查询我所在城市的天气' },
  { icon: PackageSearch, text: '帮我追踪最近一笔订单' },
  { icon: ShoppingBag, text: '推荐现在的热门商品' },
  { icon: BookOpenText, text: '从知识库查资料并总结' },
];

const CAPABILITIES = [
  { icon: PackageSearch, title: '订单服务', desc: '查订单、跟物流、处理售后', tone: 'indigo', prompt: '请帮我查询订单：' },
  { icon: ShoppingBag, title: '商品助手', desc: '商品咨询、参数对比与推荐', tone: 'amber', prompt: '请帮我推荐或对比商品：' },
  { icon: BookOpenText, title: '知识检索', desc: '检索资料、文档问答与总结', tone: 'emerald', prompt: '请从知识库中查找并总结：' },
  { icon: Workflow, title: '任务协同', desc: '识别意图并路由合适能力', tone: 'cyan', prompt: '请分析并安排合适的智能体处理：' },
];

function getGreeting() {
  const hour = new Date().getHours();
  if (hour < 6) return '夜深了';
  if (hour < 12) return '上午好';
  if (hour < 18) return '下午好';
  return '晚上好';
}

export function CustomerChatPage({
  currentSession,
  isLoading,
  inputValue,
  permissionRequest,
  faqSuggestions,
  queuePosition,
  queueEstimatedWait,
  locationEnabled,
  locationStatus,
  onSendMessage,
  onStop,
  onInputChange,
  onPermissionAllow,
  onPermissionDeny,
  onRateSession,
  onLocationEnabledChange,
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

  return (
    <>
      {/* 消息区域 */}
      <div className={`chat-content flex-1 overflow-y-auto scrollbar-thin ${!hasMessages ? 'is-home' : ''}`}>
        {!hasMessages ? (
          <section className="assistant-home" aria-labelledby="home-title">
            <div className="home-hero">
              <div className="home-eyebrow"><Sparkles size={15} /> 智能业务工作台</div>
              <h1 id="home-title">{getGreeting()}{userName ? `，${userName}` : ''}</h1>
              <p>{isClosed
                ? '该会话已结束，请从左侧新建会话后继续。'
                : '直接描述要处理的事情，我会识别需求、选择能力并完成后续步骤。'}</p>
            </div>

            <CustomerChatInput
              variant="home"
              inputValue={inputValue}
              isLoading={isLoading}
              disabled={isClosed}
              locationEnabled={locationEnabled}
              locationStatus={locationStatus}
              onSend={handleSend}
              onStop={onStop}
              onChange={onInputChange}
              onLocationEnabledChange={onLocationEnabledChange}
            />

            <div className="home-section-heading">
              <span>选择服务能力</span>
              <small>也可以直接在上方输入问题</small>
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
                    disabled={isClosed}
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
                    <button type="button" key={q.text} disabled={isClosed} onClick={() => handleSend(q.text)}>
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
              queuePosition={queuePosition}
              queueEstimatedWait={queueEstimatedWait}
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
          disabled={currentSession?.status === 'closed'}
          locationEnabled={locationEnabled}
          locationStatus={locationStatus}
          onSend={handleSend}
          onStop={onStop}
          onChange={onInputChange}
          onLocationEnabledChange={onLocationEnabledChange}
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
  locationEnabled: boolean;
  locationStatus: 'off' | 'ready' | 'denied' | 'unavailable';
  onSend: (msg: string) => void;
  onStop: () => void;
  onChange: (val: string) => void;
  onLocationEnabledChange: (enabled: boolean) => void;
}

function CustomerChatInput({
  variant = 'docked',
  inputValue,
  isLoading,
  disabled,
  locationEnabled,
  locationStatus,
  onSend,
  onStop,
  onChange,
  onLocationEnabledChange,
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
          placeholder={disabled ? '本次会话已结束，请开启新对话' : '输入你的问题或业务需求...（Enter 发送）'}
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
        <button
          type="button"
          onClick={() => onLocationEnabledChange(!locationEnabled)}
          title="开启后，仅在天气问题缺少地点时请求浏览器定位授权；定位不会保存到会话记录"
          aria-pressed={locationEnabled}
          className={`composer-location ${locationEnabled ? 'is-enabled' : ''}`}
        >
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <path d="M20 10c0 5-8 11-8 11S4 15 4 10a8 8 0 1 1 16 0Z" />
            <circle cx="12" cy="10" r="2.5" />
          </svg>
          定位天气：{locationEnabled ? '已开启' : '未开启'}
          {locationStatus === 'denied' && '（已拒绝）'}
          {locationStatus === 'unavailable' && '（不可用）'}
        </button>
        <span className="composer-disclaimer">
          AI 生成内容可能存在偏差，涉及订单、金额和关键业务操作时请再次确认
        </span>
      </div>
    </div>
  );
}
