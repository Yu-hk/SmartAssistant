import React, { useState, useEffect, useCallback } from 'react';
import { AdminStats, IntentType, INTENT_LABELS, INTENT_COLORS, FaqItem } from '../types';
import { sessions as sessionApi, admin as adminApi } from '../api';

interface AdminSession {
  id: string;
  title: string;
  model: string;
  intent: IntentType;
  status: string;
  satisfaction: number | null;
  satisfaction_comment: string | null;
  user_name: string;
  messageCount: number;
  created_at: string;
  updated_at: string;
}

interface AdminPageProps {
  onBack: () => void;
}

type AdminTab = 'overview' | 'sessions' | 'faq';

const SATISFACTION_EMOJIS = ['', '😞', '😕', '😐', '😊', '🤩'];

export function AdminPage({ onBack }: AdminPageProps) {
  const [activeTab, setActiveTab] = useState<AdminTab>('overview');
  const [stats, setStats] = useState<AdminStats | null>(null);
  const [sessions, setSessions] = useState<AdminSession[]>([]);
  const [faqs, setFaqs] = useState<FaqItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [selectedSession, setSelectedSession] = useState<AdminSession | null>(null);
  const [sessionMessages, setSessionMessages] = useState<any[]>([]);
  const [faqEditItem, setFaqEditItem] = useState<FaqItem | null>(null);
  const [faqForm, setFaqForm] = useState({ category: 'general', question: '', answer: '', keywords: '' });
  const [filterIntent, setFilterIntent] = useState<string>('all');
  const [filterStatus, setFilterStatus] = useState<string>('all');

  const fetchData = useCallback(async () => {
    setLoading(true);
    try {
      const [statsData, sessionsData, faqsData] = await Promise.all([
        adminApi.fetchAdminStats().catch(() => null),
        sessionApi.fetchSessions().catch(() => []),
        sessionApi.fetchFaqs().catch(() => []),
      ]);
      if (statsData) setStats(statsData);
      setSessions(Array.isArray(sessionsData) ? sessionsData : (sessionsData as any).sessions || []);
      setFaqs(Array.isArray(faqsData) ? faqsData : (faqsData as any).faqs || []);
    } catch (e) { console.error(e); }
    setLoading(false);
  }, []);

  useEffect(() => { fetchData(); }, [fetchData]);

  const loadSessionDetail = async (session: AdminSession) => {
    setSelectedSession(session);
    try {
      const data = await sessionApi.fetchSession(session.id);
      setSessionMessages((data as any).messages || []);
    } catch (e) { console.error(e); }
  };

  const handleDeleteSession = async (id: string) => {
    if (!confirm('确认删除此对话记录？')) return;
    try {
      await sessionApi.deleteSession(id);
      setSessions(prev => prev.filter(s => s.id !== id));
      if (selectedSession?.id === id) setSelectedSession(null);
    } catch (e) { console.error(e); }
  };

  const handleSaveFaq = async () => {
    if (!faqForm.question || !faqForm.answer) return alert('请填写问题和答案');
    try {
      if (faqEditItem) {
        await sessionApi.updateFaq(faqEditItem.id, faqForm);
      } else {
        await sessionApi.createFaq(faqForm);
      }
    } catch (e) { console.error(e); }
    setFaqEditItem(null);
    setFaqForm({ category: 'general', question: '', answer: '', keywords: '' });
    try {
      const faqsData = await sessionApi.fetchFaqs();
      setFaqs(Array.isArray(faqsData) ? faqsData : (faqsData as any).faqs || []);
    } catch (e) { console.error(e); }
  };

  const handleDeleteFaq = async (id: string) => {
    if (!confirm('确认删除此 FAQ？')) return;
    try {
      await sessionApi.deleteFaq(id);
      setFaqs(prev => prev.filter(f => f.id !== id));
    } catch (e) { console.error(e); }
  };

  const filteredSessions = sessions.filter(s => {
    if (filterIntent !== 'all' && s.intent !== filterIntent) return false;
    if (filterStatus !== 'all' && s.status !== filterStatus) return false;
    return true;
  });

  if (loading) {
    return (
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', height: '100%', gap: '12px' }}>
        <div style={{
          width: '24px', height: '24px',
          border: '3px solid var(--nova-border)',
          borderTopColor: 'var(--nova-accent)',
          borderRadius: '50%',
          animation: 'spin 0.8s linear infinite',
        }} />
        <span style={{ color: 'var(--nova-text-secondary)', fontSize: '14px' }}>加载数据中...</span>
      </div>
    );
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      {/* 顶部导航 — 玻璃效果 */}
      <div className="glass" style={{
        display: 'flex', alignItems: 'center', gap: '16px',
        padding: '14px 24px',
        borderBottom: '1px solid var(--nova-border)',
        flexShrink: 0,
        zIndex: 10,
      }}>
        <button
          onClick={onBack}
          style={{
            padding: '6px 14px', borderRadius: '8px',
            border: '1px solid var(--nova-border)',
            background: 'transparent',
            color: 'var(--nova-text-secondary)',
            fontSize: '13px', cursor: 'pointer',
            transition: 'all 0.2s',
            display: 'flex', alignItems: 'center', gap: '6px',
          }}
          onMouseEnter={e => {
            (e.currentTarget as HTMLElement).style.borderColor = 'var(--nova-accent-glow)';
            (e.currentTarget as HTMLElement).style.color = 'var(--nova-accent)';
          }}
          onMouseLeave={e => {
            (e.currentTarget as HTMLElement).style.borderColor = 'var(--nova-border)';
            (e.currentTarget as HTMLElement).style.color = 'var(--nova-text-secondary)';
          }}
        >
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none"
            stroke="currentColor" strokeWidth="2" strokeLinecap="round"
            strokeLinejoin="round">
            <line x1="19" y1="12" x2="5" y2="12" />
            <polyline points="12 19 5 12 12 5" />
          </svg>
          返回
        </button>
        <div style={{
          width: '32px', height: '32px', borderRadius: '10px',
          background: 'linear-gradient(135deg, var(--nova-accent), var(--nova-secondary))',
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          fontSize: '14px', fontWeight: 800, color: 'white',
          boxShadow: '0 0 16px var(--nova-accent-glow)',
        }}>
          N
        </div>
        <h1 style={{
          margin: 0, fontSize: '17px', fontWeight: 700,
          color: 'var(--nova-text-primary)',
        }}>
          管理后台
        </h1>
        <div style={{ marginLeft: 'auto', display: 'flex', gap: '4px' }}>
          {(['overview', 'sessions', 'faq'] as AdminTab[]).map(tab => (
            <button
              key={tab}
              onClick={() => setActiveTab(tab)}
              style={{
                padding: '7px 18px',
                borderRadius: '8px',
                border: activeTab === tab ? 'none' : '1px solid var(--nova-border)',
                background: activeTab === tab
                  ? 'linear-gradient(135deg, var(--nova-accent), var(--nova-secondary))'
                  : 'transparent',
                color: activeTab === tab ? '#fff' : 'var(--nova-text-secondary)',
                fontSize: '13px',
                fontWeight: activeTab === tab ? 600 : 400,
                cursor: 'pointer',
                transition: 'all 0.2s',
                boxShadow: activeTab === tab ? '0 0 16px var(--nova-accent-glow)' : 'none',
              }}
            >
              {tab === 'overview' ? '数据总览' : tab === 'sessions' ? '对话记录' : 'FAQ 管理'}
            </button>
          ))}
        </div>
        <button
          onClick={fetchData}
          style={{
            padding: '7px 14px', borderRadius: '8px',
            border: '1px solid var(--nova-border)',
            background: 'transparent',
            color: 'var(--nova-text-secondary)',
            fontSize: '13px', cursor: 'pointer',
            display: 'flex', alignItems: 'center', gap: '6px',
            transition: 'all 0.2s',
          }}
          onMouseEnter={e => {
            (e.currentTarget as HTMLElement).style.borderColor = 'var(--nova-accent-glow)';
            (e.currentTarget as HTMLElement).style.color = 'var(--nova-accent)';
          }}
          onMouseLeave={e => {
            (e.currentTarget as HTMLElement).style.borderColor = 'var(--nova-border)';
            (e.currentTarget as HTMLElement).style.color = 'var(--nova-text-secondary)';
          }}
        >
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none"
            stroke="currentColor" strokeWidth="2" strokeLinecap="round"
            strokeLinejoin="round">
            <polyline points="23 4 23 10 17 10" />
            <path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10" />
          </svg>
          刷新
        </button>
      </div>

      {/* 内容区 */}
      <div className="flex-1 overflow-auto" style={{ padding: '24px' }}>
        {activeTab === 'overview' && stats && (
          <OverviewTab stats={stats} sessions={sessions} />
        )}
        {activeTab === 'sessions' && (
          <SessionsTab
            sessions={filteredSessions}
            selectedSession={selectedSession}
            sessionMessages={sessionMessages}
            filterIntent={filterIntent}
            filterStatus={filterStatus}
            onFilterIntent={setFilterIntent}
            onFilterStatus={setFilterStatus}
            onSelectSession={loadSessionDetail}
            onDeleteSession={handleDeleteSession}
            onClose={() => setSelectedSession(null)}
          />
        )}
        {activeTab === 'faq' && (
          <FaqTab
            faqs={faqs}
            faqEditItem={faqEditItem}
            faqForm={faqForm}
            onSetFaqForm={setFaqForm}
            onEditFaq={faq => { setFaqEditItem(faq); setFaqForm({ category: faq.category, question: faq.question, answer: faq.answer, keywords: faq.keywords }); }}
            onCancelEdit={() => { setFaqEditItem(null); setFaqForm({ category: 'general', question: '', answer: '', keywords: '' }); }}
            onSaveFaq={handleSaveFaq}
            onDeleteFaq={handleDeleteFaq}
          />
        )}
      </div>
    </div>
  );
}

// ===================================================
// 📊 数据总览 — 看板风格
// ===================================================
function OverviewTab({ stats, sessions }: { stats: AdminStats; sessions: AdminSession[] }) {
  const totalSessions = stats.satisfaction.total;
  const ratedSessions = stats.satisfaction.rated;
  const ratePercent = totalSessions > 0 ? Math.round((ratedSessions / totalSessions) * 100) : 0;
  const humanTransfer = sessions.filter(s => s.status === 'human_transfer').length;

  // KPI 卡片数据
  const kpiCards = [
    {
      label: '总对话数',
      value: totalSessions,
      icon: (
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
          <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
        </svg>
      ),
      color: 'var(--nova-accent)',
      bgGradient: 'linear-gradient(135deg, rgba(99,102,241,0.1), rgba(99,102,241,0.02))',
      accentBar: 'var(--nova-accent)',
    },
    {
      label: '满意度均分',
      value: stats.satisfaction.avg_score ? `${stats.satisfaction.avg_score.toFixed(1)}` : '—',
      suffix: stats.satisfaction.avg_score ? '/ 5.0' : '',
      icon: (
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
          <polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2" />
        </svg>
      ),
      color: '#f59e0b',
      bgGradient: 'linear-gradient(135deg, rgba(245,158,11,0.1), rgba(245,158,11,0.02))',
      accentBar: '#f59e0b',
    },
    {
      label: '转人工率',
      value: `${stats.transferRate}%`,
      icon: (
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
          <path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" />
          <circle cx="9" cy="7" r="4" />
          <path d="M23 21v-2a4 4 0 0 0-3-3.87" />
          <path d="M16 3.13a4 4 0 0 1 0 7.75" />
        </svg>
      ),
      color: '#ef4444',
      bgGradient: 'linear-gradient(135deg, rgba(239,68,68,0.1), rgba(239,68,68,0.02))',
      accentBar: '#ef4444',
    },
    {
      label: '评价率',
      value: `${ratePercent}%`,
      icon: (
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
          <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
          <polyline points="14 2 14 8 20 8" />
          <line x1="16" y1="13" x2="8" y2="13" />
          <line x1="16" y1="17" x2="8" y2="17" />
        </svg>
      ),
      color: '#10b981',
      bgGradient: 'linear-gradient(135deg, rgba(16,185,129,0.1), rgba(16,185,129,0.02))',
      accentBar: '#10b981',
    },
  ];

  return (
    <div className="animate-fade-in-up">
      {/* KPI 卡片网格 */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: '16px', marginBottom: '24px' }}>
        {kpiCards.map(item => (
          <div key={item.label} className="glass-card" style={{
            borderRadius: '14px',
            padding: '0',
            overflow: 'hidden',
          }}>
            {/* 顶部装饰条 */}
            <div style={{
              height: '3px',
              background: `linear-gradient(90deg, ${item.accentBar}, transparent)`,
            }} />
            <div style={{
              padding: '20px',
              background: item.bgGradient,
            }}>
              <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between' }}>
                <div>
                  <div style={{
                    fontSize: '28px', fontWeight: 700,
                    color: item.color,
                    display: 'flex', alignItems: 'baseline', gap: '4px',
                  }}>
                    {item.value}
                    {item.suffix && (
                      <span style={{ fontSize: '14px', fontWeight: 400, color: 'var(--nova-text-tertiary)' }}>
                        {item.suffix}
                      </span>
                    )}
                  </div>
                  <div style={{
                    fontSize: '13px',
                    color: 'var(--nova-text-secondary)',
                    marginTop: '4px',
                  }}>
                    {item.label}
                  </div>
                </div>
                <div style={{
                  width: '44px', height: '44px', borderRadius: '12px',
                  background: `${item.color}15`,
                  display: 'flex', alignItems: 'center', justifyContent: 'center',
                  color: item.color,
                  flexShrink: 0,
                }}>
                  {item.icon}
                </div>
              </div>
            </div>
          </div>
        ))}
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '16px' }}>
        {/* 满意度分布 */}
        <div className="glass-card" style={{ padding: '20px', borderRadius: '14px' }}>
          <h3 style={{
            margin: '0 0 16px', fontSize: '15px', fontWeight: 600,
            color: 'var(--nova-text-primary)',
            display: 'flex', alignItems: 'center', gap: '8px',
          }}>
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none"
              stroke="#f59e0b" strokeWidth="2" strokeLinecap="round"
              strokeLinejoin="round">
              <polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2" />
            </svg>
            满意度分布
          </h3>
          {[5, 4, 3, 2, 1].map(score => {
            const count = (stats.satisfaction as any)[`score_${score}`] || 0;
            const percent = ratedSessions > 0 ? Math.round((count / ratedSessions) * 100) : 0;
            const barColor = score >= 4 ? '#10b981' : score === 3 ? '#f59e0b' : '#ef4444';
            return (
              <div key={score} style={{ display: 'flex', alignItems: 'center', gap: '10px', marginBottom: '8px' }}>
                <span style={{ fontSize: '14px', width: '24px', textAlign: 'center' }}>{SATISFACTION_EMOJIS[score]}</span>
                <span style={{ fontSize: '11px', color: 'var(--nova-text-tertiary)', width: '28px' }}>{score}分</span>
                <div style={{
                  flex: 1,
                  background: 'var(--nova-bg-component)',
                  borderRadius: '100px', height: '8px',
                  overflow: 'hidden',
                  position: 'relative',
                }}>
                  <div style={{
                    height: '100%',
                    width: `${percent}%`,
                    borderRadius: '100px',
                    background: `linear-gradient(90deg, ${barColor}, ${barColor}80)`,
                    transition: 'width 0.8s cubic-bezier(0.4, 0, 0.2, 1)',
                    boxShadow: `0 0 8px ${barColor}40`,
                  }} />
                </div>
                <span style={{
                  fontSize: '11px',
                  color: 'var(--nova-text-tertiary)',
                  width: '60px', textAlign: 'right',
                  fontVariantNumeric: 'tabular-nums',
                }}>
                  {count} ({percent}%)
                </span>
              </div>
            );
          })}
        </div>

        {/* 意图分布 */}
        <div className="glass-card" style={{ padding: '20px', borderRadius: '14px' }}>
          <h3 style={{
            margin: '0 0 16px', fontSize: '15px', fontWeight: 600,
            color: 'var(--nova-text-primary)',
            display: 'flex', alignItems: 'center', gap: '8px',
          }}>
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none"
              stroke="currentColor" strokeWidth="2" strokeLinecap="round"
              strokeLinejoin="round">
              <circle cx="12" cy="12" r="10" />
              <polyline points="12 6 12 12 16 14" />
            </svg>
            意图分布
          </h3>
          {stats.intents.length === 0 ? (
            <div style={{ color: 'var(--nova-text-tertiary)', fontSize: '13px', textAlign: 'center', padding: '20px' }}>
              暂无数据
            </div>
          ) : stats.intents.map((item, idx) => {
            const percent = totalSessions > 0 ? Math.round((item.count / totalSessions) * 100) : 0;
            const color = INTENT_COLORS[item.intent] || '#888';
            return (
              <div key={item.intent} style={{ display: 'flex', alignItems: 'center', gap: '10px', marginBottom: '10px' }}>
                <div style={{
                  width: '6px', height: '6px', borderRadius: '50%',
                  background: color,
                  boxShadow: `0 0 6px ${color}60`,
                }} />
                <span style={{
                  fontSize: '12px', color,
                  width: '72px', fontWeight: 500,
                  overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
                }}>
                  {INTENT_LABELS[item.intent] || item.intent}
                </span>
                <div style={{
                  flex: 1,
                  background: 'var(--nova-bg-component)',
                  borderRadius: '100px', height: '8px',
                  overflow: 'hidden',
                }}>
                  <div style={{
                    height: '100%',
                    width: `${percent}%`,
                    borderRadius: '100px',
                    background: `linear-gradient(90deg, ${color}, ${color}80)`,
                    transition: 'width 0.8s cubic-bezier(0.4, 0, 0.2, 1)',
                    animation: `slide-in-${idx} 0.6s ease-out`,
                  }} />
                </div>
                <span style={{
                  fontSize: '11px',
                  color: 'var(--nova-text-tertiary)',
                  width: '70px', textAlign: 'right',
                  fontVariantNumeric: 'tabular-nums',
                }}>
                  {item.count}次 ({percent}%)
                </span>
              </div>
            );
          })}
        </div>

        {/* 近7日趋势 */}
        <div className="glass-card" style={{ padding: '20px', borderRadius: '14px', gridColumn: '1 / -1' }}>
          <h3 style={{
            margin: '0 0 20px', fontSize: '15px', fontWeight: 600,
            color: 'var(--nova-text-primary)',
            display: 'flex', alignItems: 'center', gap: '8px',
          }}>
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none"
              stroke="currentColor" strokeWidth="2" strokeLinecap="round"
              strokeLinejoin="round">
              <rect x="3" y="4" width="18" height="18" rx="2" ry="2" />
              <line x1="16" y1="2" x2="16" y2="6" />
              <line x1="8" y1="2" x2="8" y2="6" />
              <line x1="3" y1="10" x2="21" y2="10" />
            </svg>
            近7日对话趋势
          </h3>
          {stats.daily.length === 0 ? (
            <div style={{ color: 'var(--nova-text-tertiary)', fontSize: '13px', textAlign: 'center', padding: '20px' }}>
              暂无数据
            </div>
          ) : (
            <div style={{ display: 'flex', alignItems: 'flex-end', gap: '12px', height: '140px', padding: '0 8px' }}>
              {stats.daily.map((d, idx) => {
                const maxCount = Math.max(...stats.daily.map(x => x.session_count), 1);
                const barHeight = Math.max((d.session_count / maxCount) * 100, 4);
                return (
                  <div key={d.date} style={{
                    flex: 1, display: 'flex', flexDirection: 'column',
                    alignItems: 'center', gap: '8px',
                    height: '100%', justifyContent: 'flex-end',
                  }}>
                    <span style={{
                      fontSize: '11px',
                      color: 'var(--nova-text-secondary)',
                      fontWeight: 500,
                      fontVariantNumeric: 'tabular-nums',
                    }}>
                      {d.session_count}
                    </span>
                    <div style={{
                      width: '100%', borderRadius: '6px 6px 2px 2px',
                      background: `linear-gradient(180deg, var(--nova-accent), var(--nova-secondary))`,
                      height: `${barHeight}%`,
                      minHeight: '6px',
                      transition: 'height 0.8s cubic-bezier(0.4, 0, 0.2, 1)',
                      position: 'relative',
                      cursor: 'default',
                      boxShadow: '0 0 12px var(--nova-accent-glow)',
                      opacity: 0.8 + (d.session_count / maxCount) * 0.2,
                    }}
                      title={`${d.date}: ${d.session_count}次对话${d.avg_satisfaction ? `，平均满意度 ${d.avg_satisfaction}` : ''}`}
                    />
                    <span style={{
                      fontSize: '10px',
                      color: 'var(--nova-text-tertiary)',
                      whiteSpace: 'nowrap',
                    }}>
                      {d.date.slice(5)}
                    </span>
                  </div>
                );
              })}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

// ===================================================
// 💬 对话记录 Tab
// ===================================================
function SessionsTab({ sessions, selectedSession, sessionMessages, filterIntent, filterStatus, onFilterIntent, onFilterStatus, onSelectSession, onDeleteSession, onClose }: any) {
  const statusLabels: Record<string, string> = { active: '进行中', human_transfer: '已转人工', closed: '已结束' };
  const statusColors: Record<string, string> = { active: 'var(--nova-success)', human_transfer: 'var(--nova-warm)', closed: 'var(--nova-text-tertiary)' };

  const selectStyle: React.CSSProperties = {
    flex: 1, padding: '8px 12px', borderRadius: '8px',
    border: '1px solid var(--nova-border)',
    background: 'var(--nova-bg-glass)',
    fontSize: '13px',
    color: 'var(--nova-text-primary)',
    cursor: 'pointer',
    outline: 'none',
    backdropFilter: 'blur(8px)',
    transition: 'all 0.2s',
  };

  return (
    <div className="animate-fade-in-up" style={{ display: 'flex', gap: '16px', height: 'calc(100vh - 140px)' }}>
      {/* 左侧列表 */}
      <div style={{
        width: selectedSession ? '340px' : '100%',
        flexShrink: 0, display: 'flex', flexDirection: 'column', gap: '12px',
      }}>
        <div style={{ display: 'flex', gap: '8px' }}>
          <select value={filterIntent} onChange={e => onFilterIntent(e.target.value)} style={selectStyle}
            onFocus={e => { e.target.style.borderColor = 'var(--nova-accent)'; e.target.style.boxShadow = '0 0 12px var(--nova-accent-glow)'; }}
            onBlur={e => { e.target.style.borderColor = 'var(--nova-border)'; e.target.style.boxShadow = 'none'; }}
          >
            <option value="all">全部意图</option>
            {Object.entries(INTENT_LABELS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
          </select>
          <select value={filterStatus} onChange={e => onFilterStatus(e.target.value)} style={selectStyle}
            onFocus={e => { e.target.style.borderColor = 'var(--nova-accent)'; e.target.style.boxShadow = '0 0 12px var(--nova-accent-glow)'; }}
            onBlur={e => { e.target.style.borderColor = 'var(--nova-border)'; e.target.style.boxShadow = 'none'; }}
          >
            <option value="all">全部状态</option>
            <option value="active">进行中</option>
            <option value="human_transfer">已转人工</option>
            <option value="closed">已结束</option>
          </select>
        </div>

        <div style={{ flex: 1, overflow: 'auto', display: 'flex', flexDirection: 'column', gap: '8px' }}>
          {sessions.length === 0 ? (
            <div style={{ textAlign: 'center', color: 'var(--nova-text-tertiary)', padding: '40px', fontSize: '14px' }}>
              暂无对话记录
            </div>
          ) : sessions.map((s: AdminSession, idx: number) => (
            <div
              key={s.id}
              onClick={() => onSelectSession(s)}
              className="glass-card animate-fade-in-up"
              style={{
                padding: '14px 16px',
                borderRadius: '12px',
                border: `1px solid ${selectedSession?.id === s.id ? 'var(--nova-accent)' : 'var(--nova-border)'}`,
                background: selectedSession?.id === s.id ? 'var(--nova-accent-light)' : 'var(--nova-bg-glass)',
                cursor: 'pointer',
                animationDelay: `${idx * 0.03}s`,
                animationFillMode: 'both',
              }}
            >
              <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: '8px' }}>
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{
                    fontSize: '13px', fontWeight: 600,
                    color: 'var(--nova-text-primary)',
                    overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
                  }}>
                    {s.title}
                  </div>
                  <div style={{ display: 'flex', alignItems: 'center', gap: '6px', marginTop: '6px', flexWrap: 'wrap' }}>
                    {s.intent !== 'unknown' && (
                      <span style={{
                        fontSize: '10px', padding: '1px 6px', borderRadius: '100px',
                        background: (INTENT_COLORS[s.intent] || '#888') + '18',
                        color: INTENT_COLORS[s.intent] || '#888',
                        border: `1px solid ${(INTENT_COLORS[s.intent] || '#888')}30`,
                        fontWeight: 500,
                      }}>
                        {INTENT_LABELS[s.intent] || s.intent}
                      </span>
                    )}
                    <span style={{ fontSize: '10px', color: statusColors[s.status] || '#888', fontWeight: 500 }}>
                      ● {statusLabels[s.status] || s.status}
                    </span>
                    {s.satisfaction && (
                      <span style={{ fontSize: '10px', color: '#f59e0b' }}>
                        ★ {s.satisfaction}
                      </span>
                    )}
                  </div>
                </div>
                <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-end', gap: '4px', flexShrink: 0 }}>
                  <span style={{ fontSize: '11px', color: 'var(--nova-text-tertiary)' }}>
                    {new Date(s.created_at).toLocaleDateString()}
                  </span>
                  <span style={{ fontSize: '10px', color: 'var(--nova-text-tertiary)' }}>
                    {s.messageCount || 0} 条
                  </span>
                </div>
              </div>
              <button
                onClick={e => { e.stopPropagation(); onDeleteSession(s.id); }}
                style={{
                  marginTop: '8px', padding: '2px 10px', borderRadius: '6px',
                  border: '1px solid rgba(239,68,68,0.3)',
                  background: 'transparent',
                  color: 'var(--nova-error)', fontSize: '11px',
                  cursor: 'pointer',
                  transition: 'all 0.2s',
                }}
                onMouseEnter={e => {
                  (e.currentTarget as HTMLElement).style.background = 'rgba(239,68,68,0.1)';
                  (e.currentTarget as HTMLElement).style.borderColor = 'var(--nova-error)';
                }}
                onMouseLeave={e => {
                  (e.currentTarget as HTMLElement).style.background = 'transparent';
                  (e.currentTarget as HTMLElement).style.borderColor = 'rgba(239,68,68,0.3)';
                }}
              >
                删除
              </button>
            </div>
          ))}
        </div>
      </div>

      {/* 右侧详情 */}
      {selectedSession && (
        <div className="glass-card animate-fade-in-up" style={{
          flex: 1, borderRadius: '14px',
          border: '1px solid var(--nova-border)',
          display: 'flex', flexDirection: 'column', overflow: 'hidden',
        }}>
          <div style={{
            padding: '14px 18px',
            borderBottom: '1px solid var(--nova-border)',
            display: 'flex', alignItems: 'center', gap: '10px',
          }}>
            <span style={{ flex: 1, fontSize: '14px', fontWeight: 600, color: 'var(--nova-text-primary)' }}>
              {selectedSession.title}
            </span>
            {selectedSession.satisfaction && (
              <span style={{
                fontSize: '12px', padding: '2px 10px', borderRadius: '100px',
                background: 'rgba(245,158,11,0.1)',
                color: '#f59e0b',
              }}>
                {SATISFACTION_EMOJIS[selectedSession.satisfaction]} {selectedSession.satisfaction}分
                {selectedSession.satisfaction_comment && (
                  <span style={{ color: 'var(--nova-text-tertiary)', fontSize: '11px', marginLeft: '4px' }}>
                    · {selectedSession.satisfaction_comment}
                  </span>
                )}
              </span>
            )}
            <button onClick={onClose} style={{
              background: 'none', border: 'none', cursor: 'pointer',
              fontSize: '20px', color: 'var(--nova-text-tertiary)',
              padding: '0 4px', lineHeight: 1,
            }}>×</button>
          </div>
          <div style={{
            flex: 1, overflow: 'auto', padding: '16px',
            display: 'flex', flexDirection: 'column', gap: '12px',
          }}>
            {sessionMessages.map((msg: any, idx: number) => (
              <div key={msg.id} className="animate-fade-in-up" style={{
                display: 'flex', gap: '8px',
                flexDirection: msg.role === 'user' ? 'row-reverse' : 'row',
                animationDelay: `${idx * 0.02}s`,
                animationFillMode: 'both',
              }}>
                <div style={{
                  width: '28px', height: '28px', borderRadius: '10px', flexShrink: 0,
                  background: msg.role === 'user'
                    ? 'linear-gradient(135deg, var(--nova-accent), var(--nova-secondary))'
                    : 'var(--nova-bg-component)',
                  display: 'flex', alignItems: 'center', justifyContent: 'center',
                  fontSize: '12px', color: msg.role === 'user' ? '#fff' : 'var(--nova-text-primary)',
                  boxShadow: msg.role === 'user' ? '0 0 8px var(--nova-accent-glow)' : 'none',
                }}>
                  {msg.role === 'user' ? '👤' : msg.role === 'system' ? '⚙️' : '🤖'}
                </div>
                <div style={{
                  maxWidth: '75%',
                  padding: '10px 14px',
                  borderRadius: msg.role === 'user'
                    ? '14px 14px 4px 14px'
                    : '14px 14px 14px 4px',
                  background: msg.role === 'user'
                    ? 'linear-gradient(135deg, var(--nova-accent), var(--nova-secondary))'
                    : 'var(--nova-bg-glass)',
                  color: msg.role === 'user' ? '#fff' : 'var(--nova-text-primary)',
                  fontSize: '13px',
                  lineHeight: 1.5,
                  whiteSpace: 'pre-wrap',
                  wordBreak: 'break-word',
                  border: msg.role !== 'user' ? '1px solid var(--nova-border)' : 'none',
                  boxShadow: msg.role === 'user' ? '0 2px 12px var(--nova-accent-glow)' : 'none',
                }}>
                  {msg.content}
                  <div style={{
                    fontSize: '10px', opacity: 0.6, marginTop: '4px',
                    textAlign: msg.role === 'user' ? 'right' : 'left',
                  }}>
                    {new Date(msg.created_at).toLocaleTimeString()}
                  </div>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

// ===================================================
// 📚 FAQ 管理 Tab
// ===================================================
function FaqTab({ faqs, faqEditItem, faqForm, onSetFaqForm, onEditFaq, onCancelEdit, onSaveFaq, onDeleteFaq }: any) {
  const categoryLabels: Record<string, string> = { refund: '美食推荐', order: '目的地', tech: '行程规划', general: '出行指南' };
  const categoryColors: Record<string, string> = { refund: '#f59e0b', order: '#6366f1', tech: '#10b981', general: '#06b6d4' };
  const groupedFaqs = faqs.reduce((acc: any, faq: FaqItem) => {
    if (!acc[faq.category]) acc[faq.category] = [];
    acc[faq.category].push(faq);
    return acc;
  }, {});

  return (
    <div className="animate-fade-in-up" style={{ display: 'grid', gridTemplateColumns: '1fr 380px', gap: '16px' }}>
      {/* 左侧 FAQ 列表 */}
      <div>
        {Object.entries(groupedFaqs).length === 0 ? (
          <div style={{ textAlign: 'center', color: 'var(--nova-text-tertiary)', padding: '60px' }}>
            <svg width="48" height="48" viewBox="0 0 24 24" fill="none"
              stroke="currentColor" strokeWidth="1.5" strokeLinecap="round"
              strokeLinejoin="round" style={{ margin: '0 auto 12px', display: 'block', opacity: 0.3 }}>
              <circle cx="12" cy="12" r="10" />
              <line x1="12" y1="16" x2="12" y2="12" />
              <line x1="12" y1="8" x2="12.01" y2="8" />
            </svg>
            暂无 FAQ
          </div>
        ) : Object.entries(groupedFaqs).map(([category, items]: [string, any], catIdx: number) => (
          <div key={category} className="animate-fade-in-up" style={{ marginBottom: '20px', animationDelay: `${catIdx * 0.05}s`, animationFillMode: 'both' }}>
            <h3 style={{
              margin: '0 0 10px', fontSize: '13px', fontWeight: 600,
              color: categoryColors[category] || 'var(--nova-text-secondary)',
              display: 'flex', alignItems: 'center', gap: '8px',
              letterSpacing: '0.03em',
            }}>
              <span style={{
                width: '8px', height: '8px', borderRadius: '2px',
                background: categoryColors[category] || 'var(--nova-text-tertiary)',
                display: 'inline-block',
              }} />
              {categoryLabels[category] || category}
              <span style={{
                padding: '1px 8px', borderRadius: '100px',
                background: `${categoryColors[category] || 'var(--nova-text-tertiary)'}15`,
                fontSize: '10px',
                fontWeight: 400,
                color: categoryColors[category] || 'var(--nova-text-tertiary)',
              }}>
                {items.length}
              </span>
            </h3>
            <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
              {items.map((faq: FaqItem, idx: number) => (
                <div key={faq.id} className="glass-card" style={{
                  padding: '14px 16px',
                  borderRadius: '12px',
                  border: `1px solid ${faqEditItem?.id === faq.id ? 'var(--nova-accent)' : 'var(--nova-border)'}`,
                  background: faqEditItem?.id === faq.id ? 'var(--nova-accent-light)' : 'var(--nova-bg-glass)',
                }}>
                  <div style={{ display: 'flex', alignItems: 'flex-start', gap: '8px' }}>
                    <div style={{ flex: 1 }}>
                      <div style={{
                        fontSize: '13px', fontWeight: 600,
                        color: 'var(--nova-text-primary)', marginBottom: '4px',
                      }}>
                        {faq.question}
                      </div>
                      <div style={{
                        fontSize: '12px', color: 'var(--nova-text-secondary)',
                        marginBottom: '8px', lineHeight: 1.5,
                      }}>
                        {faq.answer}
                      </div>
                      <div style={{ display: 'flex', gap: '6px', flexWrap: 'wrap' }}>
                        {faq.keywords.split(',').map((k: string) => k.trim()).filter(Boolean).map((k: string) => (
                          <span key={k} style={{
                            padding: '1px 8px', borderRadius: '100px',
                            background: 'var(--nova-bg-component)',
                            fontSize: '10px', color: 'var(--nova-text-tertiary)',
                          }}>
                            {k}
                          </span>
                        ))}
                        <span style={{
                          padding: '1px 8px', borderRadius: '100px',
                          background: 'rgba(245,158,11,0.1)',
                          fontSize: '10px', color: '#f59e0b',
                        }}>
                          🔥 {faq.hit_count}次
                        </span>
                      </div>
                    </div>
                    <div style={{ display: 'flex', gap: '6px', flexShrink: 0 }}>
                      <button onClick={() => onEditFaq(faq)} style={{
                        padding: '4px 10px', borderRadius: '6px',
                        border: '1px solid var(--nova-border)',
                        background: 'transparent',
                        color: 'var(--nova-text-secondary)',
                        fontSize: '12px', cursor: 'pointer',
                        transition: 'all 0.2s',
                      }}
                        onMouseEnter={e => {
                          (e.currentTarget as HTMLElement).style.borderColor = 'var(--nova-accent-glow)';
                          (e.currentTarget as HTMLElement).style.color = 'var(--nova-accent)';
                        }}
                        onMouseLeave={e => {
                          (e.currentTarget as HTMLElement).style.borderColor = 'var(--nova-border)';
                          (e.currentTarget as HTMLElement).style.color = 'var(--nova-text-secondary)';
                        }}
                      >
                        编辑
                      </button>
                      <button onClick={() => onDeleteFaq(faq.id)} style={{
                        padding: '4px 10px', borderRadius: '6px',
                        border: '1px solid rgba(239,68,68,0.3)',
                        background: 'transparent',
                        color: 'var(--nova-error)',
                        fontSize: '12px', cursor: 'pointer',
                        transition: 'all 0.2s',
                      }}
                        onMouseEnter={e => {
                          (e.currentTarget as HTMLElement).style.background = 'rgba(239,68,68,0.1)';
                        }}
                        onMouseLeave={e => {
                          (e.currentTarget as HTMLElement).style.background = 'transparent';
                        }}
                      >
                        删除
                      </button>
                    </div>
                  </div>
                </div>
              ))}
            </div>
          </div>
        ))}
      </div>

      {/* 右侧编辑表单 — 玻璃拟态卡片 */}
      <div className="glass-card" style={{
        padding: '24px',
        borderRadius: '14px',
        height: 'fit-content',
        position: 'sticky', top: 0,
      }}>
        <h3 style={{
          margin: '0 0 20px', fontSize: '15px', fontWeight: 600,
          color: 'var(--nova-text-primary)',
          display: 'flex', alignItems: 'center', gap: '8px',
        }}>
          {faqEditItem ? (
            <>
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none"
                stroke="currentColor" strokeWidth="2" strokeLinecap="round"
                strokeLinejoin="round">
                <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7" />
                <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z" />
              </svg>
              编辑 FAQ
            </>
          ) : (
            <>
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none"
                stroke="currentColor" strokeWidth="2" strokeLinecap="round"
                strokeLinejoin="round">
                <circle cx="12" cy="12" r="10" />
                <line x1="12" y1="8" x2="12" y2="16" />
                <line x1="8" y1="12" x2="16" y2="12" />
              </svg>
              新增 FAQ
            </>
          )}
        </h3>
        <div style={{ display: 'flex', flexDirection: 'column', gap: '14px' }}>
          <div>
            <label style={{
              fontSize: '12px', color: 'var(--nova-text-secondary)',
              display: 'block', marginBottom: '6px', fontWeight: 500,
            }}>
              分类
            </label>
            <select value={faqForm.category} onChange={e => onSetFaqForm({ ...faqForm, category: e.target.value })}
              style={{
                width: '100%', padding: '9px 12px', borderRadius: '8px',
                border: '1px solid var(--nova-border)',
                background: 'var(--nova-bg-component)',
                fontSize: '13px', color: 'var(--nova-text-primary)',
                cursor: 'pointer', outline: 'none', boxSizing: 'border-box',
              }}
            >
              <option value="refund">美食推荐</option>
              <option value="order">目的地</option>
              <option value="tech">行程规划</option>
              <option value="general">出行指南</option>
            </select>
          </div>
          {[
            { key: 'question', label: '问题', placeholder: '常见旅游问题描述' },
            { key: 'keywords', label: '关键词（逗号分隔）', placeholder: '川菜,火锅,成都美食' },
          ].map(field => (
            <div key={field.key}>
              <label style={{
                fontSize: '12px', color: 'var(--nova-text-secondary)',
                display: 'block', marginBottom: '6px', fontWeight: 500,
              }}>
                {field.label}
              </label>
              <input
                value={(faqForm as any)[field.key]}
                onChange={e => onSetFaqForm({ ...faqForm, [field.key]: e.target.value })}
                placeholder={field.placeholder}
                style={{
                  width: '100%', padding: '9px 12px', borderRadius: '8px',
                  border: '1px solid var(--nova-border)',
                  background: 'var(--nova-bg-component)',
                  fontSize: '13px', color: 'var(--nova-text-primary)',
                  outline: 'none', boxSizing: 'border-box',
                  transition: 'all 0.2s',
                }}
                onFocus={e => {
                  e.target.style.borderColor = 'var(--nova-accent)';
                  e.target.style.boxShadow = '0 0 12px var(--nova-accent-glow)';
                }}
                onBlur={e => {
                  e.target.style.borderColor = 'var(--nova-border)';
                  e.target.style.boxShadow = 'none';
                }}
              />
            </div>
          ))}
          <div>
            <label style={{
              fontSize: '12px', color: 'var(--nova-text-secondary)',
              display: 'block', marginBottom: '6px', fontWeight: 500,
            }}>
              标准答案
            </label>
            <textarea
              value={faqForm.answer}
              onChange={e => onSetFaqForm({ ...faqForm, answer: e.target.value })}
              placeholder="标准回复内容..."
              rows={4}
              style={{
                width: '100%', padding: '9px 12px', borderRadius: '8px',
                border: '1px solid var(--nova-border)',
                background: 'var(--nova-bg-component)',
                fontSize: '13px', color: 'var(--nova-text-primary)',
                outline: 'none', resize: 'none',
                fontFamily: 'inherit', boxSizing: 'border-box',
                transition: 'all 0.2s',
              }}
              onFocus={e => {
                e.target.style.borderColor = 'var(--nova-accent)';
                e.target.style.boxShadow = '0 0 12px var(--nova-accent-glow)';
              }}
              onBlur={e => {
                e.target.style.borderColor = 'var(--nova-border)';
                e.target.style.boxShadow = 'none';
              }}
            />
          </div>
          <div style={{ display: 'flex', gap: '8px', marginTop: '4px' }}>
            {faqEditItem && (
              <button onClick={onCancelEdit} style={{
                flex: 1, padding: '9px', borderRadius: '8px',
                border: '1px solid var(--nova-border)',
                background: 'transparent',
                color: 'var(--nova-text-secondary)',
                fontSize: '13px', cursor: 'pointer',
                transition: 'all 0.2s',
              }}
                onMouseEnter={e => {
                  (e.currentTarget as HTMLElement).style.borderColor = 'var(--nova-accent-glow)';
                }}
                onMouseLeave={e => {
                  (e.currentTarget as HTMLElement).style.borderColor = 'var(--nova-border)';
                }}
              >
                取消
              </button>
            )}
            <button onClick={onSaveFaq}
              className={faqForm.question && faqForm.answer ? 'neon-btn' : ''}
              style={{
                flex: 2, padding: '9px', borderRadius: '8px',
                border: 'none',
                background: faqForm.question && faqForm.answer
                  ? undefined
                  : 'var(--nova-bg-component)',
                color: faqForm.question && faqForm.answer ? '#fff' : 'var(--nova-text-tertiary)',
                fontSize: '13px',
                fontWeight: 600,
                cursor: faqForm.question && faqForm.answer ? 'pointer' : 'not-allowed',
                fontFamily: 'inherit',
              } as React.CSSProperties}
            >
              {faqEditItem ? '保存修改' : '添加 FAQ'}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
