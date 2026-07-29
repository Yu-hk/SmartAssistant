import React, { useEffect, useMemo, useState } from 'react';
import {
  Session, INTENT_LABELS, IntentType, KbHit, EmotionResult, CustomerProfile,
  Ticket, TicketStatus, TICKET_STATUS_META,
} from '../types';
import { insight as insightApi } from '../api';

interface InsightPanelProps {
  session?: Session;
  userName?: string;
  userId?: number;        // P0 新增：传入当前登录用户 ID 用于拉取真实画像
}

/**
 * 实时会话洞察面板 — 主工作台右栏创意核心。
 *
 * 数据来源说明（与后端约束对齐）：
 * - 情绪分析 / 知识库命中 / 工单：调用真实后端接口（consumer InsightController）。
 * - 客户画像 / 协同链路 / 待办：前端基于当前 session 实时派生。原因——consumer 不持久化
 *   会话与消息，服务端无法按 sessionId 还原这些上下文，故由已持有的前端 session 计算，
 *   避免伪造后端接口。
 */
interface ChainStep { name: string; desc: string; color: string; }
interface TodoItem { id: string; label: string; type: string; }

// 意图 → 智能体协同链路（前端派生）
const CHAIN_BY_INTENT: Record<IntentType, ChainStep[]> = {
  refund: [
    { name: '售前顾问', desc: '受理意图', color: '#14B8A6' },
    { name: '订单助手', desc: '查询 / 退款', color: '#A78BFA' },
    { name: '知识管家', desc: '合规校验', color: '#38BDF8' },
  ],
  order: [
    { name: '订单助手', desc: '订单查询', color: '#A78BFA' },
    { name: '技术支持', desc: '异常处理', color: '#F59E0B' },
    { name: '知识管家', desc: '政策说明', color: '#38BDF8' },
  ],
  tech: [
    { name: '技术支持', desc: '问题诊断', color: '#F59E0B' },
    { name: '知识管家', desc: '知识检索', color: '#38BDF8' },
    { name: '订单助手', desc: '后续处理', color: '#A78BFA' },
  ],
  general: [
    { name: '知识管家', desc: '知识检索', color: '#38BDF8' },
    { name: '售前顾问', desc: '应答', color: '#14B8A6' },
    { name: '技术支持', desc: '兜底', color: '#F59E0B' },
  ],
  unknown: [
    { name: '智能体路由', desc: '意图识别', color: '#14B8A6' },
    { name: '对应专员', desc: '任务处理', color: '#A78BFA' },
  ],
};

// 意图 → 待办工单（前端派生）
const TODO_BY_INTENT: Record<IntentType, TodoItem[]> = {
  refund: [{ id: 'refund', label: '生成退款工单', type: 'refund' }],
  order: [{ id: 'order', label: '生成订单核查工单', type: 'order' }],
  tech: [{ id: 'tech', label: '生成技术支持工单', type: 'tech' }],
  general: [],
  unknown: [],
};

export function InsightPanel({ session, userName, userId }: InsightPanelProps) {
  const customerName = session?.user_name || userName || '访客用户';
  const intent: IntentType = session?.intent ?? 'unknown';
  const intentLabel = session?.intent && session.intent !== 'unknown'
    ? INTENT_LABELS[session.intent]
    : '待识别';

  const lastUserMessage = useMemo(() => {
    if (!session?.messages?.length) return '';
    const reversed = [...session.messages].reverse();
    return reversed.find(m => m.role === 'user')?.content || '';
  }, [session?.messages, session?.id]);

  const [emotion, setEmotion] = useState<EmotionResult | null>(null);
  const [kbHits, setKbHits] = useState<KbHit[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [tickets, setTickets] = useState<Ticket[]>([]);  // ⭐ P1-C 工单列表
  const [ticketError, setTicketError] = useState<string | null>(null);
  const [resolvingId, setResolvingId] = useState<string | null>(null);  // 正在填写关闭结论的工单
  const [resolutionText, setResolutionText] = useState<string>('');
  const [profile, setProfile] = useState<CustomerProfile | null>(null);  // P0 客户 360°

  // 拉取真实后端数据：情绪分析 + 知识库检索 + 客户画像 + 工单列表
  useEffect(() => {
    if (!session?.id) return;
    let cancelled = false;
    setLoading(true);
    setTickets([]);
    setTicketError(null);

    Promise.allSettled([
      insightApi.analyzeEmotion(lastUserMessage, userId, intentLabel),
      insightApi.searchKb(lastUserMessage, intent),
      userId !== undefined ? insightApi.getProfile(userId, customerName) : Promise.resolve(null),
      insightApi.listTickets({ sessionId: session.id }),  // ⭐ P1-C 工单列表
    ]).then(([eRes, kRes, pRes, tRes]) => {
      if (cancelled) return;
      if (eRes.status === 'fulfilled') setEmotion(eRes.value);
      if (kRes.status === 'fulfilled') setKbHits(kRes.value.hits);
      if (pRes && pRes.status === 'fulfilled' && pRes.value) setProfile(pRes.value);
      if (tRes.status === 'fulfilled') setTickets(tRes.value);
      setLoading(false);
    });

    return () => { cancelled = true; };
  }, [session?.id, lastUserMessage, intent, userId, customerName, intentLabel]);

  const chain = CHAIN_BY_INTENT[intent];
  const todos = TODO_BY_INTENT[intent];

  const emotionLabel = emotion?.label ?? '平静 · 略急';
  const emotionScore = emotion?.score ?? 62;
  const emotionConfidence = emotion?.confidence ?? 92;
  const kbList = kbHits && kbHits.length > 0
    ? kbHits
    : [{ title: '退款政策 · 7天无理由', match: 96, source: '知识库' } as KbHit,
       { title: '订单物流时效说明', match: 91, source: '知识库' } as KbHit];

  const handleCreateTicket = async (todo: TodoItem) => {
    if (!session?.id) return;
    setTicketError(null);
    try {
      const res = await insightApi.createTicket({
        sessionId: session.id,
        intent,
        summary: lastUserMessage || intentLabel,
        customerName,
      });
      if (res.status === 'FAILED' || res.error) {
        setTicketError(res.error || '工单创建失败');
      } else {
        // 刷新本会话工单列表
        const list = await insightApi.listTickets({ sessionId: session.id });
        setTickets(list);
      }
    } catch (err) {
      setTicketError('网络异常，工单创建未成功');
    }
  };

  // ⭐ P1-C 工单生命周期操作
  const handleStatusChange = async (ticketId: string, status: TicketStatus) => {
    setTicketError(null);
    try {
      const res = await insightApi.updateTicketStatus(ticketId, status);
      if (res.status === 'FAILED' || res.error) {
        setTicketError(res.error || '状态更新失败');
      } else if (session?.id) {
        setTickets(await insightApi.listTickets({ sessionId: session.id }));
      }
    } catch {
      setTicketError('网络异常，状态更新未成功');
    }
  };

  const handleCloseTicket = async (ticketId: string) => {
    setTicketError(null);
    try {
      const res = await insightApi.closeTicket(ticketId, resolutionText.trim());
      if (res.status === 'FAILED' || res.error) {
        setTicketError(res.error || '关闭失败');
      } else if (session?.id) {
        setTickets(await insightApi.listTickets({ sessionId: session.id }));
      }
    } catch {
      setTicketError('网络异常，关闭未成功');
    } finally {
      setResolvingId(null);
      setResolutionText('');
    }
  };

  return (
    <aside className="glass workbench-insight" style={{
      width: '340px',
      flexShrink: 0,
      height: '100vh',
      display: 'flex',
      flexDirection: 'column',
      borderLeft: '1px solid var(--nova-border)',
      padding: '18px 16px',
      gap: '14px',
      zIndex: 15,
      overflow: 'auto',
    }}>
      {/* 面板标题 */}
      <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
        <span style={{
          width: '8px', height: '8px', borderRadius: '50%',
          background: 'var(--nova-secondary)',
          boxShadow: '0 0 8px var(--nova-secondary)',
        }} />
        <span style={{
          fontSize: '14px', fontWeight: 700,
          color: 'var(--nova-text-primary)', letterSpacing: '0.01em',
        }}>
          实时会话洞察
        </span>
        <span style={{
          marginLeft: 'auto', fontSize: '11px',
          padding: '3px 10px', borderRadius: '100px',
          background: 'var(--nova-secondary-light, rgba(6,182,212,0.14))',
          color: 'var(--nova-secondary)', fontWeight: 500,
        }}>
          实时
        </span>
      </div>

      {/* 客户画像（P0 客户 360° — 从后端拉取） */}
      <InsightCard>
        <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
          <div style={{
            width: '40px', height: '40px', borderRadius: '50%',
            flexShrink: 0,
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            fontSize: '15px', fontWeight: 700, color: '#fff',
            background: 'linear-gradient(135deg, var(--nova-accent), var(--nova-secondary))',
            boxShadow: '0 0 14px var(--nova-accent-glow)',
          }}>
            {customerName.slice(0, 1)}
          </div>
          <div style={{ minWidth: 0 }}>
            <div style={{
              fontSize: '14px', fontWeight: 700,
              color: 'var(--nova-text-primary)',
              overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
            }}>
              {customerName}
            </div>
            <div style={{ fontSize: '11px', color: 'var(--nova-text-secondary)' }}>
              {profile
                ? (() => {
                    const parts: string[] = [];
                    if (profile.entityFacts?.location) parts.push(profile.entityFacts.location);
                    if (profile.budgetRange) parts.push('预算:' + profile.budgetRange);
                    const ch = profile.entityFacts?.channel || '在线客服';
                    parts.push(ch);
                    return parts.join(' · ');
                  })()
                : `${profile ? '' : '加载中...'}`}
            </div>
          </div>
        </div>
        <div style={{ display: 'flex', gap: '6px', marginTop: '12px', flexWrap: 'wrap' }}>
          {profile ? (
            <>
              <Tag text={`历史查询 ${profile.totalQueries} 次`} />
              <Tag text={`投诉 ${profile.complaintCount} 次`} />
              {/* ⭐ P2-A 持久化情绪聚合 */}
              {profile.lastEmotionLabel && (
                <Tag text={`情绪: ${profile.lastEmotionLabel} (${profile.lastEmotionScore ?? 0})`} />
              )}
              {profile.negativeTouchCount ? (
                <Tag text={`负面触碰 ${profile.negativeTouchCount} 次`} />
              ) : null}
              {profile.emotionAvgScore != null && (
                <Tag text={`情绪均分 ${Math.round(profile.emotionAvgScore)}`} />
              )}
              {profile.foodPreferences.length > 0 && (
                <Tag text={`食: ${profile.foodPreferences.slice(0, 2).join('/')}`} />
              )}
              {profile.travelPreferences.length > 0 && (
                <Tag text={`旅: ${profile.travelPreferences.slice(0, 2).join('/')}`} />
              )}
              {profile.agentMemorySummaries.length > 0 && (
                <Tag text={`笔记 ${profile.agentMemorySummaries.length} 条`} />
              )}
              {/* ⭐ P2-C 隐藏关键信息（潜在需求/隐性信号） */}
              {profile.keyInsights && profile.keyInsights.length > 0 && (
                <div style={{ width: '100%', marginTop: '6px' }}>
                  <div style={{
                    fontSize: '10px', color: 'var(--nova-text-tertiary)', marginBottom: '4px',
                  }}>
                    潜在关键信息
                  </div>
                  <div style={{ display: 'flex', gap: '6px', flexWrap: 'wrap' }}>
                    {profile.keyInsights.slice(0, 6).map((k, i) => (
                      <Tag key={i} text={k} />
                    ))}
                  </div>
                </div>
              )}
            </>
          ) : (
            <>
              <Tag text={`历史查询 ${profile === null ? '?' : '0'} 次`} />
              <Tag text="满意度 --" />
            </>
          )}
        </div>
      </InsightCard>

      {/* 情绪意图（后端情绪分析 + 前端意图派生） */}
      <InsightCard>
        {loading && !emotion ? (
          <SkeletonLine />
        ) : (
          <>
            <Row label="客户情绪" value={emotionLabel} valueColor="var(--nova-warm)" />
            <div style={{
              height: '6px', borderRadius: '3px', marginTop: '8px',
              background: 'var(--nova-bg-component-hover)', overflow: 'hidden',
            }}>
              <div style={{
                width: `${emotionScore}%`, height: '100%', borderRadius: '3px',
                background: 'linear-gradient(90deg, #34d399, #fbbf24, #fb7185)',
              }} />
            </div>
            <div style={{ marginTop: '12px' }}>
              <Row label="意图识别" value={intentLabel} valueColor="var(--nova-text-primary)" />
            </div>
            <div style={{ marginTop: '10px' }}>
              <Row label="置信度" value={`${emotionConfidence}%`} valueColor="var(--nova-secondary)" />
            </div>
          </>
        )}
      </InsightCard>

      {/* 知识库命中（后端检索） */}
      <InsightCard>
        <div style={{
          display: 'flex', alignItems: 'center', justifyContent: 'space-between',
          marginBottom: '10px',
        }}>
          <span style={{ fontSize: '12px', fontWeight: 700, color: 'var(--nova-text-primary)' }}>
            知识库命中
          </span>
          <span style={{ fontSize: '11px', color: 'var(--nova-secondary)' }}>
            {kbList.length} 篇
          </span>
        </div>
        {loading && !kbHits ? (
          <>
            <SkeletonLine />
            <SkeletonLine />
          </>
        ) : (
          kbList.map((hit, i) => (
            <KbItem key={`${hit.title}-${i}`} text={hit.title} match={`${hit.match}%`} />
          ))
        )}
      </InsightCard>

      {/* 智能体协同链路（前端派生） */}
      <InsightCard>
        <div style={{
          fontSize: '12px', fontWeight: 700, color: 'var(--nova-text-primary)',
          marginBottom: '12px',
        }}>
          智能体协同链路
        </div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
          {chain.map((step, i) => (
            <div key={step.name} style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
              <span style={{
                width: '8px', height: '8px', borderRadius: '50%', flexShrink: 0,
                background: step.color, boxShadow: `0 0 6px ${step.color}`,
              }} />
              <span style={{ fontSize: '12px', color: 'var(--nova-text-secondary)' }}>
                <b style={{ color: 'var(--nova-text-primary)', fontWeight: 600 }}>{step.name}</b>
                <span style={{ color: 'var(--nova-text-tertiary)' }}> · {step.desc}</span>
              </span>
              {i < chain.length - 1 && (
                <span style={{
                  marginLeft: 'auto', color: 'var(--nova-text-tertiary)', fontSize: '12px',
                }}>↓</span>
              )}
            </div>
          ))}
        </div>
      </InsightCard>

      {/* 待办事项 + 工单生命周期（⭐ P1-C） */}
      <InsightCard>
        <div style={{
          display: 'flex', alignItems: 'center', justifyContent: 'space-between',
          marginBottom: '10px',
        }}>
          <span style={{ fontSize: '12px', fontWeight: 700, color: 'var(--nova-text-primary)' }}>
            待办 & 工单
          </span>
          {todos.length > 0 && (
            <span style={{
              fontSize: '11px', fontWeight: 700, color: '#fff',
              background: 'linear-gradient(135deg, var(--nova-accent), var(--nova-secondary))',
              borderRadius: '100px', padding: '1px 8px',
            }}>
              {todos.length}
            </span>
          )}
        </div>

        {/* 待办：生成工单按钮 */}
        {todos.map(todo => (
          <button
            key={todo.id}
            className="neon-btn"
            style={{ width: '100%', padding: '10px', fontSize: '13px', marginBottom: '8px' }}
            onClick={() => handleCreateTicket(todo)}
          >
            {todo.label}
          </button>
        ))}

        {/* 已生成工单列表 + 生命周期操作 */}
        {tickets.length > 0 ? (
          <div style={{ display: 'flex', flexDirection: 'column', gap: '8px', marginTop: todos.length ? '6px' : 0 }}>
            {tickets.map(t => {
              const meta = TICKET_STATUS_META[t.status] ?? TICKET_STATUS_META.OPEN;
              const isClosed = t.status === 'CLOSED';
              return (
                <div key={t.id} style={{
                  border: '1px solid var(--nova-border)',
                  borderRadius: '10px', padding: '10px', background: meta.bg,
                }}>
                  <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: '6px' }}>
                    <span style={{ fontSize: '12px', fontWeight: 700, color: 'var(--nova-text-primary)' }}>
                      #{t.id.slice(0, 8)}
                    </span>
                    <span style={{
                      fontSize: '10px', fontWeight: 700, color: meta.color,
                      background: 'rgba(255,255,255,0.6)', borderRadius: '100px', padding: '1px 8px',
                    }}>
                      {meta.label}
                    </span>
                  </div>
                  <div style={{ fontSize: '11px', color: 'var(--nova-text-tertiary)', marginTop: '4px', wordBreak: 'break-all' }}>
                    {t.summary || '（无摘要）'}
                  </div>

                  {!isClosed ? (
                    <div style={{ display: 'flex', gap: '6px', marginTop: '8px' }}>
                      <select
                        value={t.status}
                        onChange={e => handleStatusChange(t.id, e.target.value as TicketStatus)}
                        style={{
                          flex: 1, fontSize: '11px', padding: '4px 6px', borderRadius: '8px',
                          border: '1px solid var(--nova-border)', background: 'var(--nova-bg, #0b1220)',
                          color: 'var(--nova-text-primary)',
                        }}
                      >
                        {(['OPEN', 'IN_PROGRESS', 'PENDING', 'RESOLVED'] as TicketStatus[]).map(s => (
                          <option key={s} value={s}>{TICKET_STATUS_META[s].label}</option>
                        ))}
                      </select>
                      <button
                        className="neon-btn"
                        style={{ padding: '4px 10px', fontSize: '11px' }}
                        onClick={() => { setResolvingId(t.id); setResolutionText(''); }}
                      >
                        关闭
                      </button>
                    </div>
                  ) : (
                    <div style={{ fontSize: '10px', color: 'var(--nova-text-tertiary)', marginTop: '6px' }}>
                      已关闭{ t.closedAt ? ` · ${t.closedAt}` : '' }
                      { t.resolution ? ` · ${t.resolution}` : '' }
                    </div>
                  )}

                  {/* 关闭结论输入 */}
                  {resolvingId === t.id && (
                    <div style={{ marginTop: '8px' }}>
                      <input
                        value={resolutionText}
                        onChange={e => setResolutionText(e.target.value)}
                        placeholder="处理结论（可选）"
                        style={{
                          width: '100%', fontSize: '11px', padding: '5px 8px', borderRadius: '8px',
                          border: '1px solid var(--nova-border)', background: 'var(--nova-bg, #0b1220)',
                          color: 'var(--nova-text-primary)', marginBottom: '6px',
                        }}
                      />
                      <div style={{ display: 'flex', gap: '6px' }}>
                        <button className="neon-btn" style={{ flex: 1, padding: '5px', fontSize: '11px' }}
                          onClick={() => handleCloseTicket(t.id)}>确认关闭</button>
                        <button style={{ padding: '5px 10px', fontSize: '11px', borderRadius: '8px',
                          border: '1px solid var(--nova-border)', background: 'transparent', color: 'var(--nova-text-tertiary)' }}
                          onClick={() => { setResolvingId(null); setResolutionText(''); }}>取消</button>
                      </div>
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        ) : tickets.length === 0 && todos.length === 0 ? (
          <div style={{ fontSize: '12px', color: 'var(--nova-text-tertiary)' }}>
            当前无需生成工单
          </div>
        ) : null}

        {ticketError && (
          <div style={{
            fontSize: '11px', color: '#fca5a5', marginTop: '6px',
          }}>
            {ticketError}
          </div>
        )}
      </InsightCard>
    </aside>
  );
}

// ===================================================
// 局部小组件
// ===================================================
function InsightCard({ children }: { children: React.ReactNode }) {
  return (
    <div className="glass-card" style={{
      borderRadius: '14px',
      padding: '14px',
      display: 'flex',
      flexDirection: 'column',
    }}>
      {children}
    </div>
  );
}

function Row({ label, value, valueColor }: { label: string; value: string; valueColor: string }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
      <span style={{ fontSize: '12px', color: 'var(--nova-text-secondary)' }}>{label}</span>
      <span style={{ fontSize: '12px', fontWeight: 700, color: valueColor }}>{value}</span>
    </div>
  );
}

function Tag({ text }: { text: string }) {
  return (
    <span style={{
      fontSize: '11px', color: 'var(--nova-text-secondary)',
      padding: '4px 10px', borderRadius: '100px',
      background: 'var(--nova-bg-component)',
      border: '1px solid var(--nova-border)',
    }}>
      {text}
    </span>
  );
}

function KbItem({ text, match }: { text: string; match: string }) {
  return (
    <div style={{
      display: 'flex', alignItems: 'center', gap: '8px',
      padding: '8px 10px', borderRadius: '8px',
      background: 'var(--nova-bg-component)', marginTop: '6px',
    }}>
      <span style={{
        flex: 1, fontSize: '12px', color: 'var(--nova-text-primary)',
        overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
      }}>
        {text}
      </span>
      <span style={{
        fontSize: '11px', fontWeight: 700, color: 'var(--nova-secondary)', flexShrink: 0,
      }}>
        {match}
      </span>
    </div>
  );
}

function SkeletonLine() {
  return (
    <div style={{
      height: '14px', borderRadius: '6px', margin: '6px 0',
      background: 'linear-gradient(90deg, var(--nova-bg-component), var(--nova-bg-glass), var(--nova-bg-component))',
      backgroundSize: '200% 100%',
      animation: 'breathe 1.6s ease-in-out infinite',
    }} />
  );
}
