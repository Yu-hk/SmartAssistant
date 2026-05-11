import React, { useRef, useEffect, useCallback, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Session, PermissionRequest, FaqItem } from '../types';
import { ChatMessages } from '../components/ChatMessages';
import { TransferBanner } from '../components/TransferBanner';
import { SatisfactionDialog } from '../components/SatisfactionDialog';
import { FaqSuggestions } from '../components/FaqSuggestions';
import { IntentBadge } from '../components/IntentBadge';
import { sessions as sessionApi } from '../api';

interface CustomerChatPageProps {
  currentSession: Session | undefined;
  isLoading: boolean;
  inputValue: string;
  permissionRequest: PermissionRequest | null;
  transferPending: boolean;
  showSatisfaction: boolean;
  faqSuggestions: FaqItem[];
  queuePosition: number | null;
  queueEstimatedWait: number | null;
  onSendMessage: (message: string, sessionIdOverride?: string, onNavigate?: (path: string) => void) => void;
  onStop: () => void;
  onInputChange: (value: string) => void;
  onTransfer: () => void;
  onSatisfaction: (score: number, comment?: string) => void;
  onPermissionAllow: () => void;
  onPermissionDeny: () => void;
  onSkipSatisfaction: () => void;
}

// 快捷问题 — 旅游主题
const QUICK_QUESTIONS = [
  { icon: '🗺️', text: '九寨沟有什么好玩的', gradient: 'from-blue-500 to-cyan-500' },
  { icon: '🍜', text: '成都有什么美食推荐', gradient: 'from-orange-500 to-amber-500' },
  { icon: '🏨', text: '推荐丽江的住宿', gradient: 'from-purple-500 to-pink-500' },
  { icon: '🛤️', text: '大理三日游行程规划', gradient: 'from-green-500 to-emerald-500' },
  { icon: '🌤️', text: '黄山最近天气适合去吗', gradient: 'from-rose-500 to-red-500' },
];

export function CustomerChatPage({
  currentSession,
  isLoading,
  inputValue,
  permissionRequest,
  transferPending,
  showSatisfaction,
  faqSuggestions,
  queuePosition,
  queueEstimatedWait,
  onSendMessage,
  onStop,
  onInputChange,
  onTransfer,
  onSatisfaction,
  onPermissionAllow,
  onPermissionDeny,
  onSkipSatisfaction,
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

  return (
    <>
      {/* 消息区域 */}
      <div className="flex-1 overflow-y-auto scrollbar-thin" style={{ padding: '20px 24px' }}>
        {!hasMessages ? (
          /* ===== 欢迎页 ===== */
          <div style={{ maxWidth: '680px', margin: '0 auto', paddingTop: '20px' }}>
            {/* 欢迎头部 — 科技感 */}
            <div style={{ textAlign: 'center', marginBottom: '44px' }}>
              {/* Logo 光效 */}
              <div style={{
                position: 'relative',
                width: '80px', height: '80px',
                margin: '0 auto 20px',
              }}>
                <div style={{
                  width: '80px', height: '80px',
                  borderRadius: '24px',
                  background: 'linear-gradient(135deg, var(--nova-accent), var(--nova-secondary))',
                  display: 'flex', alignItems: 'center', justifyContent: 'center',
                  fontSize: '34px', fontWeight: 800, color: 'white',
                  boxShadow: '0 0 40px var(--nova-accent-glow), 0 8px 32px rgba(0,0,0,0.2)',
                  position: 'relative',
                  zIndex: 1,
                }}>
                  N
                </div>
                {/* 装饰光环 */}
                <div style={{
                  position: 'absolute', inset: '-8px',
                  borderRadius: '28px',
                  border: '1px solid var(--nova-accent-glow)',
                  opacity: 0.5,
                  animation: 'breathe 3s ease-in-out infinite',
                }} />
                <div style={{
                  position: 'absolute', inset: '-16px',
                  borderRadius: '36px',
                  border: '1px solid var(--nova-accent-glow)',
                  opacity: 0.2,
                }} />
              </div>

              <h2 style={{
                fontSize: '24px', fontWeight: 700,
                color: 'var(--nova-text-primary)',
                margin: '0 0 6px',
                letterSpacing: '0.02em',
              }}>
                Nova 旅行规划
              </h2>
              <p style={{
                fontSize: '14px',
                color: 'var(--nova-text-secondary)',
                margin: 0,
                lineHeight: 1.6,
              }}>
                AI 旅行规划助手 · 探索目的地、美食推荐、行程定制、天气查询
              </p>
            </div>

            {/* 服务能力卡片 — 玻璃拟态网格 */}
            <div style={{
              display: 'grid', gridTemplateColumns: '1fr 1fr',
              gap: '12px', marginBottom: '36px',
            }}>
              {[
                { icon: '🗺️', title: '目的地探索', desc: '景点推荐、旅行攻略、当地文化', accent: '#6366f1' },
                { icon: '🍜', title: '美食推荐', desc: '本地特色、餐厅推荐、小吃攻略', accent: '#f59e0b' },
                { icon: '🏔️', title: '行程规划', desc: '多日行程、路线安排、时间管理', accent: '#10b981' },
                { icon: '🌤️', title: '天气指南', desc: '实时天气、穿衣建议、最佳出行时间', accent: '#06b6d4' },
              ].map((item, idx) => (
                <div
                  key={item.title}
                  className="glass-card animate-fade-in-up"
                  style={{
                    padding: '18px',
                    borderRadius: '14px',
                    animationDelay: `${idx * 0.08}s`,
                    animationFillMode: 'both',
                  }}
                >
                  {/* 顶部色条 */}
                  <div style={{
                    width: '32px', height: '3px',
                    borderRadius: '2px',
                    background: item.accent,
                    marginBottom: '12px',
                    boxShadow: `0 0 8px ${item.accent}60`,
                  }} />
                  <div style={{ fontSize: '26px', marginBottom: '8px' }}>{item.icon}</div>
                  <div style={{
                    fontSize: '14px', fontWeight: 600,
                    color: 'var(--nova-text-primary)',
                    marginBottom: '4px',
                  }}>
                    {item.title}
                  </div>
                  <div style={{
                    fontSize: '12px',
                    color: 'var(--nova-text-secondary)',
                  }}>
                    {item.desc}
                  </div>
                </div>
              ))}
            </div>

            {/* 快捷问题 */}
            <div>
              <div style={{
                fontSize: '12px',
                color: 'var(--nova-text-tertiary)',
                marginBottom: '10px',
                fontWeight: 500,
                letterSpacing: '0.05em',
                textTransform: 'uppercase',
              }}>
                ⚡ 热门旅行目的地快捷查询
              </div>
              <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
                {QUICK_QUESTIONS.map((q, idx) => (
                  <button
                    key={q.text}
                    onClick={() => handleSend(q.text)}
                    className="glass-card animate-fade-in-up"
                    style={{
                      display: 'flex', alignItems: 'center', gap: '12px',
                      padding: '12px 16px',
                      borderRadius: '12px',
                      border: '1px solid var(--nova-border)',
                      cursor: 'pointer',
                      textAlign: 'left',
                      fontSize: '14px',
                      color: 'var(--nova-text-primary)',
                      background: 'var(--nova-bg-glass)',
                      animationDelay: `${0.3 + idx * 0.06}s`,
                      animationFillMode: 'both',
                    }}
                  >
                    <span style={{
                      fontSize: '20px', width: '32px', textAlign: 'center',
                    }}>
                      {q.icon}
                    </span>
                    <span style={{ flex: 1 }}>{q.text}</span>
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none"
                      stroke="currentColor" strokeWidth="2" strokeLinecap="round"
                      strokeLinejoin="round"
                      style={{ color: 'var(--nova-text-tertiary)' }}>
                      <line x1="5" y1="12" x2="19" y2="12" />
                      <polyline points="12 5 19 12 12 19" />
                    </svg>
                  </button>
                ))}
              </div>
            </div>
          </div>
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
            />
          </div>
        )}
      </div>

      {/* 转人工横幅 */}
      {currentSession && (
        <TransferBanner
          status={currentSession.status}
          onRequestTransfer={onTransfer}
        />
      )}

      {/* 输入框 */}
      <CustomerChatInput
        inputValue={inputValue}
        isLoading={isLoading}
        disabled={currentSession?.status === 'closed'}
        onSend={handleSend}
        onStop={onStop}
        onChange={onInputChange}
      />

      {/* 满意度弹窗 */}
      <SatisfactionDialog
        visible={showSatisfaction && !!currentSession}
        onSubmit={onSatisfaction}
        onSkip={onSkipSatisfaction}
      />
    </>
  );
}

// ===================================================
// 输入框 — 霓虹科技风格
// ===================================================
interface CustomerChatInputProps {
  inputValue: string;
  isLoading: boolean;
  disabled?: boolean;
  onSend: (msg: string) => void;
  onStop: () => void;
  onChange: (val: string) => void;
}

function CustomerChatInput({ inputValue, isLoading, disabled, onSend, onStop, onChange }: CustomerChatInputProps) {
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
    <div className="glass" style={{
      padding: '12px 24px 18px',
      borderTop: '1px solid var(--nova-border)',
      position: 'relative',
      zIndex: 5,
    }}>
      <div style={{
        maxWidth: '800px', margin: '0 auto',
        display: 'flex', alignItems: 'flex-end', gap: '10px',
        padding: '10px 14px',
        borderRadius: '14px',
        border: `1.5px solid ${isFocused ? 'var(--nova-accent)' : 'var(--nova-border)'}`,
        background: 'var(--nova-bg-component)',
        transition: 'all 0.3s cubic-bezier(0.4, 0, 0.2, 1)',
        boxShadow: isFocused ? '0 0 20px var(--nova-accent-glow)' : 'none',
      }}>
        <textarea
          ref={textareaRef}
          value={inputValue}
          onChange={e => onChange(e.target.value)}
          onKeyDown={handleKeyDown}
          onFocus={() => setIsFocused(true)}
          onBlur={() => setIsFocused(false)}
          placeholder={disabled ? '本次行程咨询已结束，请开启新对话' : '输入目的地或旅行需求... (Enter 发送)'}
          disabled={disabled || isLoading}
          rows={1}
          style={{
            flex: 1, border: 'none', background: 'transparent',
            fontSize: '14px', resize: 'none', outline: 'none',
            color: 'var(--nova-text-primary)', fontFamily: 'inherit',
            lineHeight: '1.5', maxHeight: '120px', overflowY: 'auto',
            opacity: disabled ? 0.4 : 1,
          }}
        />
        {isLoading ? (
          <button
            onClick={onStop}
            style={{
              padding: '8px 16px', borderRadius: '10px',
              border: 'none',
              background: 'linear-gradient(135deg, #ef4444, #dc2626)',
              color: '#fff',
              fontSize: '13px', cursor: 'pointer',
              fontWeight: 600, flexShrink: 0,
              display: 'flex', alignItems: 'center', gap: '6px',
              boxShadow: '0 0 12px rgba(239, 68, 68, 0.3)',
              transition: 'all 0.2s',
            }}
            onMouseEnter={e => {
              (e.currentTarget as HTMLElement).style.boxShadow = '0 0 24px rgba(239, 68, 68, 0.5)';
            }}
            onMouseLeave={e => {
              (e.currentTarget as HTMLElement).style.boxShadow = '0 0 12px rgba(239, 68, 68, 0.3)';
            }}
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
            style={{
              padding: '8px 18px', borderRadius: '10px',
              border: 'none',
              background: inputValue.trim() && !disabled
                ? 'linear-gradient(135deg, var(--nova-accent), var(--nova-secondary))'
                : 'var(--nova-bg-component-hover)',
              color: inputValue.trim() && !disabled ? '#fff' : 'var(--nova-text-tertiary)',
              fontSize: '13px',
              cursor: inputValue.trim() && !disabled ? 'pointer' : 'not-allowed',
              fontWeight: 600, flexShrink: 0,
              transition: 'all 0.2s',
              display: 'flex', alignItems: 'center', gap: '6px',
              boxShadow: inputValue.trim() && !disabled ? '0 0 16px var(--nova-accent-glow)' : 'none',
            }}
            onMouseEnter={e => {
              if (inputValue.trim() && !disabled) {
                (e.currentTarget as HTMLElement).style.boxShadow = '0 0 32px var(--nova-accent-glow)';
                (e.currentTarget as HTMLElement).style.transform = 'translateY(-1px)';
              }
            }}
            onMouseLeave={e => {
              if (inputValue.trim() && !disabled) {
                (e.currentTarget as HTMLElement).style.boxShadow = '0 0 16px var(--nova-accent-glow)';
                (e.currentTarget as HTMLElement).style.transform = 'none';
              }
            }}
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
              <line x1="22" y1="2" x2="11" y2="13" />
              <polygon points="22 2 15 22 11 13 2 9 22 2" />
            </svg>
            发送
          </button>
        )}
      </div>
      <div style={{
        textAlign: 'center',
        fontSize: '11px',
        color: 'var(--nova-text-tertiary)',
        marginTop: '8px',
        maxWidth: '800px',
        margin: '8px auto 0',
      }}>
        AI 旅行建议仅供参考，出行前请核实最新信息 · 祝您旅途愉快 🌍
      </div>
    </div>
  );
}
