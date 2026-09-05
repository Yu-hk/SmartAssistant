import React from 'react';
import {
  Activity,
  CircleCheck,
  ClipboardList,
  Headset,
  MessagesSquare,
  PhoneForwarded,
  Star,
  UserRound,
  Wrench,
} from 'lucide-react';
import { Session, INTENT_LABELS, INTENT_COLORS } from '../types';
import { summarizeTelemetry } from '../utils/sessionTelemetry';
import { getAgentCapabilityLabel } from '../utils/toolDisplay';
import { SessionExecutionSteps } from './SessionExecutionSteps';

interface SessionInsightPanelProps {
  sessions: Session[];
  currentSession: Session | undefined;
  onCloseSession: () => void;
  onRateSession: (score: number) => void;
}

const STATUS_LABELS: Record<string, string> = {
  active: '进行中',
  human_transfer: '转人工',
  closed: '已结束',
};

const STATUS_TONES: Record<string, { color: string; bg: string }> = {
  active: { color: 'var(--nova-success)', bg: 'var(--nova-success-bg)' },
  human_transfer: { color: 'var(--nova-warning)', bg: 'var(--nova-warning-bg)' },
  closed: { color: 'var(--nova-text-tertiary)', bg: 'var(--nova-bg-component)' },
};

function formatTime(value: Date | string | undefined) {
  if (!value) return '—';
  const date = value instanceof Date ? value : new Date(value);
  if (Number.isNaN(date.getTime())) return '—';
  return date.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
}

/**
 * 右栏坐席洞察面板 —— 全部数据来自真实会话状态，无占位假数据。
 * 有会话时展示会话详情与坐席操作；无会话时展示今日接待概览。
 */
export function SessionInsightPanel({
  sessions,
  currentSession,
  onCloseSession,
  onRateSession,
}: SessionInsightPanelProps) {
  const telemetry = summarizeTelemetry(currentSession?.messages ?? []);
  const formatTokens = (value: number | null) => value === null ? '未采集' : value.toLocaleString('zh-CN');
  const stats = {
    total: sessions.length,
    active: sessions.filter(s => s.status === 'active').length,
    human: sessions.filter(s => s.status === 'human_transfer').length,
    closed: sessions.filter(s => s.status === 'closed').length,
  };

  return (
    <aside className="workbench-insight" aria-label="坐席洞察面板">
      {currentSession ? (
        <>
          <section className="insight-section">
            <div className="insight-heading"><ClipboardList size={13} /> 当前会话</div>
            <div className="insight-kv">
              <span>状态</span>
              <strong>
                <span
                  className="insight-tag"
                  style={{
                    color: STATUS_TONES[currentSession.status]?.color,
                    background: STATUS_TONES[currentSession.status]?.bg,
                  }}
                >
                  {STATUS_LABELS[currentSession.status] || currentSession.status}
                </span>
              </strong>
            </div>
            {currentSession.intent !== 'unknown' && (
              <div className="insight-kv">
                <span>咨询分类</span>
                <strong>
                  <span
                    className="insight-tag"
                    style={{
                      color: INTENT_COLORS[currentSession.intent],
                      background: `color-mix(in srgb, ${INTENT_COLORS[currentSession.intent]} 10%, transparent)`,
                    }}
                  >
                    {INTENT_LABELS[currentSession.intent]}
                  </span>
                </strong>
              </div>
            )}
            <div className="insight-kv">
              <span>处理能力</span>
              <strong>{currentSession.agent_name ? getAgentCapabilityLabel(currentSession.agent_name)
                : telemetry.streaming ? '正在识别需求' : '智能助手'}</strong>
            </div>
            <div className="insight-kv">
              <span>本轮回复</span>
              <strong>{telemetry.streaming ? '处理中' : currentSession.messages.some(m => m.role === 'assistant')
                ? '已结束' : '等待提问'}</strong>
            </div>
            <div className="insight-kv">
              <span>创建时间</span>
              <strong>{formatTime(currentSession.createdAt)}</strong>
            </div>
            <div className="insight-kv">
              <span>消息数</span>
              <strong>{currentSession.messages.length}</strong>
            </div>
            <div className="insight-kv">
              <span>工具调用</span>
              <strong>
                {telemetry.calls.length || telemetry.toolsComplete
                  ? `${telemetry.calls.length} 次${telemetry.toolsComplete ? '' : ' · 部分采集'}`
                  : telemetry.streaming ? '等待调用记录' : '未采集'}
              </strong>
            </div>
            <div className="insight-telemetry" aria-live="polite" aria-atomic="true">
              <div className="insight-kv">
                <span>累计 Token</span>
                <strong>{formatTokens(telemetry.totalTokens)}</strong>
              </div>
              <div className="insight-token-breakdown">
                <span>输入 <b>{formatTokens(telemetry.promptTokens)}</b></span>
                <span>输出 <b>{formatTokens(telemetry.completionTokens)}</b></span>
              </div>
              <p className="insight-telemetry-note">
                {telemetry.streaming ? '执行中 · 随服务端回传更新'
                  : telemetry.totalTokens === null ? '服务端尚未提供用量，不代表消耗为 0'
                  : telemetry.tokensComplete ? '已采集各轮用量' : '部分轮次未采集，当前为已知用量'}
              </p>
            </div>
            <SessionExecutionSteps key={currentSession.id} messages={currentSession.messages} />
          </section>

          <section className="insight-section">
            <div className="insight-heading"><Star size={13} /> 服务评价</div>
            {currentSession.satisfaction ? (
              <div className="insight-kv">
                <span>客户评分</span>
                <strong>{currentSession.satisfaction} / 5 分</strong>
              </div>
            ) : (
              <>
                <p style={{ margin: '0 0 8px', color: 'var(--nova-text-tertiary)', fontSize: 12 }}>
                  为本次服务打分，将用于服务质量统计
                </p>
                <div className="insight-rate-row" role="radiogroup" aria-label="会话满意度评分">
                  {[1, 2, 3, 4, 5].map(score => (
                    <button
                      key={score}
                      type="button"
                      role="radio"
                      aria-checked={false}
                      aria-label={`${score} 分`}
                      title={`${score} 分`}
                      onClick={() => onRateSession(score)}
                    >
                      <Star size={17} />
                    </button>
                  ))}
                </div>
              </>
            )}
          </section>

          <section className="insight-section">
            <div className="insight-heading"><Headset size={13} /> 坐席操作</div>
            <div className="insight-actions">
              <button
                type="button"
                className="insight-action is-danger"
                disabled={currentSession.status === 'closed'}
                onClick={onCloseSession}
              >
                <CircleCheck size={15} /> 结束会话
              </button>
            </div>
          </section>
        </>
      ) : (
        <>
          <section className="insight-section">
            <div className="insight-heading"><Activity size={13} /> 接待概览</div>
            <div className="insight-stats">
              <div className="insight-stat">
                <strong>{stats.total}</strong>
                <span>全部会话</span>
              </div>
              <div className="insight-stat">
                <strong>{stats.active}</strong>
                <span>进行中</span>
              </div>
              <div className="insight-stat">
                <strong>{stats.human}</strong>
                <span>转人工</span>
              </div>
              <div className="insight-stat">
                <strong>{stats.closed}</strong>
                <span>已结束</span>
              </div>
            </div>
          </section>

          <section className="insight-section">
            <div className="insight-heading"><MessagesSquare size={13} /> 工作提示</div>
            {stats.total === 0 ? (
              <div className="insight-empty">
                今日还没有接待记录<br />从左侧「新建会话」或下方能力入口开始
              </div>
            ) : (
              <>
                <div className="insight-kv">
                  <span><PhoneForwarded size={12} style={{ verticalAlign: '-2px' }} /> 待跟进</span>
                  <strong>{stats.human > 0 ? `${stats.human} 个转人工会话` : '无转人工会话'}</strong>
                </div>
                <div className="insight-kv">
                  <span><Wrench size={12} style={{ verticalAlign: '-2px' }} /> 进行中</span>
                  <strong>{stats.active > 0 ? `${stats.active} 个会话处理中` : '暂无处理中会话'}</strong>
                </div>
                <div className="insight-kv">
                  <span><UserRound size={12} style={{ verticalAlign: '-2px' }} /> 接待人</span>
                  <strong>智能服务助手</strong>
                </div>
              </>
            )}
          </section>
        </>
      )}
    </aside>
  );
}
