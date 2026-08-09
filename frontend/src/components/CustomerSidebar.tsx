import React from 'react';
import { Session, INTENT_LABELS, INTENT_COLORS } from '../types';

interface CustomerSidebarProps {
  sessions: Session[];
  currentSessionId: string | null;
  theme: 'light' | 'dark';
  onNewChat: () => void;
  onSelectSession: (id: string) => void;
  onDeleteSession: (id: string) => void;
  onSelectAgent: (name: string) => void;
  onOpenAdmin?: () => void;
  onToggleTheme: () => void;
  isOpen?: boolean;
  onClose?: () => void;
}

/**
 * 智能体团队 — 当前为 UI 展示用静态列表（贴合「多智能体客服」主题）。
 * TODO: 接入运行时智能体注册表后，改为从后端拉取在线状态与归属。
 */
const AGENT_TEAM: { name: string; glyph: string; color: string }[] = [
  { name: '售前顾问', glyph: '售', color: '#14B8A6' },
  { name: '技术支持', glyph: '技', color: '#38BDF8' },
  { name: '订单助手', glyph: '单', color: '#A78BFA' },
  { name: '投诉处理', glyph: '诉', color: '#FB7185' },
  { name: '知识管家', glyph: '知', color: '#FBBF24' },
];

const STATUS_COLORS: Record<string, string> = {
  active: 'var(--nova-success)',
  human_transfer: 'var(--nova-warm)',
  closed: 'var(--nova-text-tertiary)',
};

const STATUS_LABELS: Record<string, string> = {
  active: '进行中',
  human_transfer: '转人工',
  closed: '已结束',
};

export function CustomerSidebar({
  sessions, currentSessionId, theme,
  onNewChat, onSelectSession, onDeleteSession, onSelectAgent, onOpenAdmin, onToggleTheme,
  isOpen = false, onClose,
}: CustomerSidebarProps) {
  return (
    <aside className={`customer-sidebar glass ${isOpen ? 'is-open' : ''}`} style={{
      flexShrink: 0,
      display: 'flex',
      flexDirection: 'column',
      borderRight: '1px solid var(--nova-border)',
      height: '100vh',
      zIndex: 20,
    }}>
      {/* 品牌区域 */}
      <div style={{
        padding: '18px 16px 14px',
        borderBottom: '1px solid var(--nova-border)',
      }}>
        <div style={{
          display: 'flex', alignItems: 'center', gap: '11px',
          marginBottom: '14px',
        }}>
          {/* 品牌 Logo — 罗盘渐变 */}
          <div style={{
            width: '38px', height: '38px', borderRadius: '11px',
            background: 'linear-gradient(135deg, var(--nova-accent), var(--nova-secondary))',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            fontSize: '17px', fontWeight: 800, color: 'white',
            flexShrink: 0,
            boxShadow: '0 0 18px var(--nova-accent-glow)',
          }}>
            智
          </div>
          <div>
            <div style={{
              fontSize: '14px', fontWeight: 700,
              color: 'var(--nova-text-primary)',
              letterSpacing: '0.02em', lineHeight: 1.25,
            }}>
              智服 SmartAssistant
            </div>
            <div style={{
              fontSize: '10px', color: 'var(--nova-secondary)',
              fontWeight: 500, letterSpacing: '0.04em',
            }}>
              多智能体客服工作台
            </div>
          </div>
          <button
            type="button"
            className="sidebar-close-button"
            aria-label="关闭侧边栏"
            onClick={onClose}
          >
            ×
          </button>
        </div>

        {/* 新建会话按钮 — 渐变实心 */}
        <button
          onClick={onNewChat}
          className="neon-btn"
          style={{
            width: '100%',
            padding: '10px 12px',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            gap: '8px',
            fontSize: '13px',
          }}
        >
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
            <line x1="12" y1="5" x2="12" y2="19" />
            <line x1="5" y1="12" x2="19" y2="12" />
          </svg>
          新建会话
        </button>
      </div>

      {/* 可滚动区域：智能体团队 + 我的会话 */}
      <div style={{ flex: 1, overflow: 'auto', padding: '12px 10px' }}>
        {/* 智能体团队 */}
        <div style={{
          fontSize: '11px', fontWeight: 600,
          color: 'var(--nova-text-tertiary)',
          letterSpacing: '0.08em', textTransform: 'uppercase',
          padding: '4px 6px 8px',
        }}>
          服务能力入口
        </div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: '2px', marginBottom: '6px' }}>
          {AGENT_TEAM.map(agent => (
            <button
              type="button"
              key={agent.name}
              className="glass-hover"
              onClick={() => onSelectAgent(agent.name)}
              aria-label={`与${agent.name}开始对话`}
              style={{
                width: '100%',
                display: 'flex', alignItems: 'center', gap: '10px',
                padding: '8px 10px', borderRadius: '10px',
                border: '1px solid transparent', cursor: 'pointer',
                background: 'transparent', fontFamily: 'inherit', textAlign: 'left',
              }}
            >
              {/* Agent 图标 */}
              <div style={{
                width: '30px', height: '30px', borderRadius: '9px',
                flexShrink: 0,
                display: 'flex', alignItems: 'center', justifyContent: 'center',
                fontSize: '13px', fontWeight: 700, color: '#fff',
                background: agent.color + '2e',
                boxShadow: `inset 0 0 0 1px ${agent.color}55`,
              }}>
                <span style={{ color: agent.color }}>{agent.glyph}</span>
              </div>
              <span style={{
                flex: 1, fontSize: '13px', fontWeight: 500,
                color: 'var(--nova-text-primary)',
                overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
              }}>
                {agent.name}
              </span>
              <span style={{
                padding: '2px 6px', borderRadius: '999px',
                background: 'var(--nova-bg-component)',
                color: 'var(--nova-text-tertiary)',
                fontSize: '9px', flexShrink: 0,
              }}>入口</span>
            </button>
          ))}
        </div>

        {/* 分隔线 */}
        <div style={{
          height: '1px', background: 'var(--nova-border)',
          margin: '10px 6px 12px',
        }} />

        {/* 我的会话 */}
        <div style={{
          fontSize: '11px', fontWeight: 600,
          color: 'var(--nova-text-tertiary)',
          letterSpacing: '0.08em', textTransform: 'uppercase',
          padding: '0 6px 8px',
        }}>
          我的会话
        </div>
        {sessions.length === 0 ? (
          <div style={{
            textAlign: 'center',
            color: 'var(--nova-text-tertiary)',
            padding: '24px 12px',
            fontSize: '13px',
          }}>
            <svg width="36" height="36" viewBox="0 0 24 24" fill="none"
              stroke="currentColor" strokeWidth="1.5" strokeLinecap="round"
              strokeLinejoin="round"
              style={{ margin: '0 auto 10px', display: 'block', opacity: 0.4 }}>
              <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
            </svg>
            暂无会话记录<br />
            <span style={{ fontSize: '11px' }}>
              点击「新建会话」开始咨询
            </span>
          </div>
        ) : (
          sessions.map(session => (
            <SessionItem
              key={session.id}
              session={session}
              isActive={session.id === currentSessionId}
              onSelect={() => onSelectSession(session.id)}
              onDelete={() => onDeleteSession(session.id)}
            />
          ))
        )}
      </div>

      {/* 底部工具栏 — 玻璃效果 */}
      <div style={{
        padding: '12px',
        borderTop: '1px solid var(--nova-border)',
        display: 'flex', gap: '8px',
      }}>
        {onOpenAdmin && <button
          onClick={onOpenAdmin}
          className="glass-hover"
          style={{
            flex: 1, padding: '8px 12px', borderRadius: '8px',
            border: '1px solid var(--nova-border)',
            background: 'transparent',
            color: 'var(--nova-text-secondary)',
            fontSize: '12px', cursor: 'pointer',
            display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '6px',
            transition: 'all 0.2s',
          }}
          onMouseEnter={e => {
            (e.currentTarget as HTMLElement).style.color = 'var(--nova-accent)';
            (e.currentTarget as HTMLElement).style.borderColor = 'var(--nova-accent-glow)';
          }}
          onMouseLeave={e => {
            (e.currentTarget as HTMLElement).style.color = 'var(--nova-text-secondary)';
            (e.currentTarget as HTMLElement).style.borderColor = 'var(--nova-border)';
          }}
        >
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none"
            stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <rect x="3" y="3" width="7" height="7" />
            <rect x="14" y="3" width="7" height="7" />
            <rect x="3" y="14" width="7" height="7" />
            <rect x="14" y="14" width="7" height="7" />
          </svg>
          管理后台
        </button>}
        <button
          onClick={onToggleTheme}
          title={theme === 'light' ? '切换深色模式' : '切换浅色模式'}
          className="glass-hover"
          style={{
            width: onOpenAdmin ? '36px' : '100%', height: '36px', borderRadius: '8px',
            border: '1px solid var(--nova-border)',
            background: 'transparent', cursor: 'pointer', fontSize: '16px',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            transition: 'all 0.2s',
            color: 'var(--nova-text-secondary)',
          }}
          onMouseEnter={e => {
            (e.currentTarget as HTMLElement).style.borderColor = 'var(--nova-accent-glow)';
          }}
          onMouseLeave={e => {
            (e.currentTarget as HTMLElement).style.borderColor = 'var(--nova-border)';
          }}
        >
          {theme === 'light' ? '🌙' : '☀️'}
        </button>
      </div>
    </aside>
  );
}

// ===================================================
// 单条会话项 — 科技感卡片
// ===================================================
function SessionItem({ session, isActive, onSelect, onDelete }: {
  session: Session;
  isActive: boolean;
  onSelect: () => void;
  onDelete: () => void;
}) {
  const [showDel, setShowDel] = React.useState(false);
  const intentColor = INTENT_COLORS[session.intent] || 'var(--nova-text-tertiary)';
  const statusColor = STATUS_COLORS[session.status] || 'var(--nova-text-tertiary)';

  return (
    <div
      onClick={onSelect}
      onMouseEnter={() => {
        setShowDel(true);
        if (!isActive) {
          const el = document.getElementById(`session-${session.id}`);
          if (el) {
            el.style.background = 'var(--nova-bg-glass-hover)';
            el.style.borderColor = 'var(--nova-border)';
          }
        }
      }}
      onMouseLeave={() => {
        setShowDel(false);
        if (!isActive) {
          const el = document.getElementById(`session-${session.id}`);
          if (el) {
            el.style.background = 'transparent';
            el.style.borderColor = 'transparent';
          }
        }
      }}
      style={{
        padding: '10px 12px',
        borderRadius: '10px',
        marginBottom: '4px',
        cursor: 'pointer',
        position: 'relative',
        transition: 'all 0.2s cubic-bezier(0.4, 0, 0.2, 1)',
        background: isActive
          ? 'var(--nova-accent-light)'
          : 'transparent',
        border: isActive
          ? '1px solid var(--nova-accent-glow)'
          : '1px solid transparent',
      }}
      id={`session-${session.id}`}
    >
      {/* 左侧意图色条 */}
      {session.intent !== 'unknown' && (
        <div style={{
          position: 'absolute', left: '2px', top: '30%',
          width: '3px', height: '40%',
          borderRadius: '2px',
          background: intentColor,
          boxShadow: `0 0 6px ${intentColor}60`,
        }} />
      )}

      {/* 标题行 */}
      <div style={{
        display: 'flex', alignItems: 'center', gap: '6px', marginBottom: '4px',
        paddingLeft: session.intent !== 'unknown' ? '8px' : '0',
      }}>
        <span style={{
          width: '5px', height: '5px', borderRadius: '50%',
          background: statusColor, flexShrink: 0,
          boxShadow: `0 0 6px ${statusColor}`,
        }} />
        <span style={{
          flex: 1, fontSize: '13px', fontWeight: isActive ? 600 : 400,
          color: 'var(--nova-text-primary)',
          overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
        }}>
          {session.title}
        </span>
        {showDel && (
          <button
            type="button"
            aria-label={`删除会话：${session.title}`}
            title="删除会话"
            onClick={e => { e.stopPropagation(); onDelete(); }}
            style={{
              background: 'none', border: 'none', cursor: 'pointer',
              color: 'var(--nova-error)', fontSize: '14px',
              padding: '0 2px', flexShrink: 0, lineHeight: 1,
            }}
          >×</button>
        )}
      </div>

      {/* 元信息行 */}
      <div style={{
        display: 'flex', alignItems: 'center', gap: '6px',
        paddingLeft: session.intent !== 'unknown' ? '8px' : '0',
      }}>
        {session.intent !== 'unknown' && (
          <span style={{
            fontSize: '10px', padding: '1px 6px', borderRadius: '100px',
            background: intentColor + '18', color: intentColor,
            border: `1px solid ${intentColor}30`,
            fontWeight: 500,
          }}>
            {INTENT_LABELS[session.intent]}
          </span>
        )}
        <span style={{
          fontSize: '10px', color: statusColor, fontWeight: 500,
        }}>
          ● {STATUS_LABELS[session.status] || session.status}
        </span>
      </div>
    </div>
  );
}
