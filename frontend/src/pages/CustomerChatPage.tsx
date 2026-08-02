import React, { useRef, useEffect, useCallback, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Session, PermissionRequest, FaqItem } from '../types';
import { ChatMessages } from '../components/ChatMessages';
import { FaqSuggestions } from '../components/FaqSuggestions';
import { IntentBadge } from '../components/IntentBadge';
import { sessions as sessionApi } from '../api';
import {
  ArrowRight,
  FileText,
  Headphones,
  PackageSearch,
  ReceiptText,
  RotateCcw,
  ShieldCheck,
  ShoppingBag,
  Truck,
  UserRound,
} from 'lucide-react';

interface CustomerChatPageProps {
  currentSession: Session | undefined;
  isLoading: boolean;
  inputValue: string;
  permissionRequest: PermissionRequest | null;
  faqSuggestions: FaqItem[];
  queuePosition: number | null;
  queueEstimatedWait: number | null;
  onSendMessage: (message: string, sessionIdOverride?: string, onNavigate?: (path: string) => void) => void;
  onStop: () => void;
  onInputChange: (value: string) => void;
  onPermissionAllow: () => void;
  onPermissionDeny: () => void;
}

// 客服高频问题
const QUICK_QUESTIONS = [
  { icon: Truck, text: '查询我的订单物流进度' },
  { icon: RotateCcw, text: '商品如何申请退货退款' },
  { icon: ShoppingBag, text: '咨询商品规格和库存' },
  { icon: ReceiptText, text: '如何申请电子发票' },
  { icon: UserRound, text: '转接人工客服' },
];

const SERVICE_ITEMS = [
  { icon: PackageSearch, title: '订单与物流', desc: '订单状态、配送进度、收货异常' },
  { icon: RotateCcw, title: '退换与售后', desc: '退货退款、换货维修、售后进度' },
  { icon: ShoppingBag, title: '商品咨询', desc: '规格参数、库存价格、使用说明' },
  { icon: FileText, title: '账户与发票', desc: '账户服务、电子发票、支付问题' },
];

export function CustomerChatPage({
  currentSession,
  isLoading,
  inputValue,
  permissionRequest,
  faqSuggestions,
  queuePosition,
  queueEstimatedWait,
  onSendMessage,
  onStop,
  onInputChange,
  onPermissionAllow,
  onPermissionDeny,
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
      <div className="customer-chat-scroll flex-1 overflow-y-auto scrollbar-thin">
        {!hasMessages ? (
          /* ===== 欢迎页 ===== */
          <div className="customer-welcome">
            {/* 欢迎头部 */}
            <div className="customer-welcome__hero">
              <div className="customer-hero-icon">
                <Headphones size={30} strokeWidth={1.9} />
                <span className="customer-online-dot" aria-label="客服在线" />
              </div>
              <div className="customer-status-pill">
                <span /> 在线服务中 · 通常几秒内回复
              </div>
              <h1>您好，我是小智客服</h1>
              <p>
                有订单、物流或售后问题都可以直接告诉我。涉及具体订单时，我会先为您查询，再请您确认需要处理的内容。
              </p>
              <div className="customer-service-metrics" aria-label="服务能力说明">
                <span><strong>7×24</strong><small>全天候服务</small></span>
                <i aria-hidden="true" />
                <span><strong>隐私保护</strong><small>敏感信息脱敏</small></span>
                <i aria-hidden="true" />
                <span><strong>人工协同</strong><small>复杂问题可转接</small></span>
              </div>
            </div>

            {/* 服务能力卡片 */}
            <div className="customer-service-grid">
              {SERVICE_ITEMS.map((item, idx) => {
                const ServiceIcon = item.icon;
                return <button
                  key={item.title}
                  className="customer-service-card animate-fade-in-up"
                  onClick={() => handleSend(item.title)}
                  style={{
                    animationDelay: `${idx * 0.08}s`,
                    animationFillMode: 'both',
                  }}
                >
                  <span className="customer-service-card__icon"><ServiceIcon size={20} /></span>
                  <span style={{ flex: 1 }}>
                    <strong>{item.title}</strong>
                    <small>{item.desc}</small>
                  </span>
                  <ArrowRight size={16} className="customer-card-arrow" />
                </button>;
              })}
            </div>

            <div className="customer-trust-row">
              <span><ShieldCheck size={14} /> 会话安全保护</span>
              <span><Headphones size={14} /> 支持转接人工</span>
              <span><Truck size={14} /> 订单进度实时查询</span>
            </div>

            {/* 快捷问题 */}
            <div className="customer-quick-section">
              <div className="customer-section-heading">
                <span>
                  <strong>您可能想问</strong>
                  <small>点击即可发起咨询</small>
                </span>
                <span className="customer-section-heading__hint">无需填写订单号</span>
              </div>
              <div className="customer-question-list">
                {QUICK_QUESTIONS.map((q, idx) => {
                  const QuestionIcon = q.icon;
                  return <button
                    key={q.text}
                    onClick={() => handleSend(q.text)}
                    className="customer-question animate-fade-in-up"
                    style={{
                      animationDelay: `${0.25 + idx * 0.05}s`,
                      animationFillMode: 'both',
                    }}
                  >
                    <span className="customer-question__icon">
                      <QuestionIcon size={17} />
                    </span>
                    <span style={{ flex: 1 }}>{q.text}</span>
                    <ArrowRight size={15} className="customer-card-arrow" />
                  </button>;
                })}
              </div>
            </div>
          </div>
        ) : (
          /* ===== 对话区域 ===== */
          <div className="customer-conversation">
            {/* 会话意图 */}
            {currentSession && currentSession.intent !== 'unknown' && (
              <div className="customer-intent-row">
                <span>
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
              onOrderSelect={handleSend}
              onFollowUpSelect={handleSend}
              orderSelectionDisabled={isLoading || currentSession?.status === 'closed'}
            />
          </div>
        )}
      </div>

      {/* 输入框 */}
      <CustomerChatInput
        inputValue={inputValue}
        isLoading={isLoading}
        disabled={currentSession?.status === 'closed'}
        onSend={handleSend}
        onStop={onStop}
        onChange={onInputChange}
      />

    </>
  );
}

// ===================================================
// 客服消息输入框
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
    <div className="customer-composer glass">
      <div className={`customer-composer__box${isFocused ? ' customer-composer__box--focused' : ''}`}>
        <textarea
          ref={textareaRef}
          value={inputValue}
          onChange={e => onChange(e.target.value)}
          onKeyDown={handleKeyDown}
          onFocus={() => setIsFocused(true)}
          onBlur={() => setIsFocused(false)}
          placeholder={disabled ? '本次咨询已结束，请开启新对话' : '请简要描述您遇到的问题…（Enter 发送）'}
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
                ? 'var(--nova-accent)'
                : 'var(--nova-bg-component-hover)',
              color: inputValue.trim() && !disabled ? '#fff' : 'var(--nova-text-tertiary)',
              fontSize: '13px',
              cursor: inputValue.trim() && !disabled ? 'pointer' : 'not-allowed',
              fontWeight: 600, flexShrink: 0,
              transition: 'all 0.2s',
              display: 'flex', alignItems: 'center', gap: '6px',
              boxShadow: inputValue.trim() && !disabled ? 'var(--nova-shadow-sm)' : 'none',
            }}
            onMouseEnter={e => {
              if (inputValue.trim() && !disabled) {
                (e.currentTarget as HTMLElement).style.boxShadow = 'var(--nova-shadow-md)';
                (e.currentTarget as HTMLElement).style.transform = 'translateY(-1px)';
              }
            }}
            onMouseLeave={e => {
              if (inputValue.trim() && !disabled) {
                (e.currentTarget as HTMLElement).style.boxShadow = 'var(--nova-shadow-sm)';
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
      <div className="customer-composer__notice">
        智能客服提供的信息仅供参考，涉及账户与资金操作时请核对关键信息
      </div>
    </div>
  );
}
