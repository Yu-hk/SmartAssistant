import { FormEvent, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  ChevronLeft,
  ChevronRight,
  FilterX,
  GitBranch,
  Hash,
  MessageCircle,
  RefreshCw,
  Search,
  Star,
  Trash2,
  UserRound,
  X,
} from 'lucide-react';
import * as adminApi from '../api/admin';
import type { WorkflowRecoveryJob, WorkflowRecoveryStatus } from '../api/admin';
import { ApiError } from '../api/client';
import type { AdminAgentFlow, AdminAgentFlowNode, AdminSessionDetail, AdminSessionPage, AdminSessionSummary } from '../types';
import {
  formatAgent,
  formatConversationTitle,
  formatDateTime,
  formatIntent,
  formatRole,
  formatStatus,
  formatTokenCount,
  formatToolCapability,
  getErrorMessage,
  sanitizeMessageContent,
  statusTone,
} from './adminFormat';
import { AdminEmptyState, AdminErrorState, AdminLoadingState, AdminPageIntro } from './AdminState';

interface ConversationFilters {
  query: string;
  userId: string;
  status: string;
  intent: string;
}

const EMPTY_FILTERS: ConversationFilters = { query: '', userId: '', status: '', intent: '' };

interface SelectedConversation {
  sessionId: string;
  userId: number | null;
}

export function AdminConversationsPage({ refreshVersion }: { refreshVersion: number }) {
  const [filters, setFilters] = useState<ConversationFilters>(EMPTY_FILTERS);
  const [appliedFilters, setAppliedFilters] = useState<ConversationFilters>(EMPTY_FILTERS);
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [result, setResult] = useState<AdminSessionPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [selectedSession, setSelectedSession] = useState<SelectedConversation | null>(null);

  const loadSessions = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      setResult(await adminApi.fetchAdminSessions({ ...appliedFilters, page, size }));
    } catch (loadError) {
      setError(getErrorMessage(loadError, '无法获取对话列表'));
    } finally {
      setLoading(false);
    }
  }, [appliedFilters, page, size]);

  useEffect(() => { void loadSessions(); }, [loadSessions, refreshVersion]);

  const applyFilters = (event: FormEvent) => {
    event.preventDefault();
    setPage(0);
    setAppliedFilters({ ...filters });
  };

  const clearFilters = () => {
    setFilters(EMPTY_FILTERS);
    setAppliedFilters(EMPTY_FILTERS);
    setPage(0);
  };

  const hasFilters = Object.values(appliedFilters).some(Boolean);
  const totalPages = Math.max(1, Math.ceil((result?.total ?? 0) / size));

  return (
    <div className="admin-page admin-conversations-page">
      <AdminPageIntro
        eyebrow="CONVERSATIONS"
        title="用户对话"
        description="按用户、咨询类型和处理结果检索全局会话，并查看完整对话链路。"
      />

      <form className="admin-filter-bar" onSubmit={applyFilters} role="search">
        <label className="admin-search-field">
          <span className="sr-only">搜索用户或对话标题</span>
          <Search size={16} aria-hidden="true" />
          <input
            value={filters.query}
            onChange={event => setFilters(current => ({ ...current, query: event.target.value }))}
            placeholder="搜索用户、标题或关键词"
          />
        </label>
        <label className="admin-filter-field user-id-field">
          <span>用户 ID</span>
          <input
            inputMode="numeric"
            value={filters.userId}
            onChange={event => setFilters(current => ({ ...current, userId: event.target.value.replace(/\D/g, '') }))}
            placeholder="全部"
          />
        </label>
        <label className="admin-filter-field">
          <span>处理状态</span>
          <select
            value={filters.status}
            onChange={event => setFilters(current => ({ ...current, status: event.target.value }))}
          >
            <option value="">全部状态</option>
            <option value="SUCCESS">处理成功</option>
            <option value="CLOSED">已结束</option>
            <option value="SUSPENDED">已暂停</option>
            <option value="PARTIAL_SUCCESS">已回复 · 待关注</option>
            <option value="FAILED">处理失败</option>
            <option value="TIMEOUT">处理超时</option>
            <option value="HUMAN_TRANSFER">已转人工</option>
          </select>
        </label>
        <label className="admin-filter-field">
          <span>咨询类型</span>
          <select
            value={filters.intent}
            onChange={event => setFilters(current => ({ ...current, intent: event.target.value }))}
          >
            <option value="">全部类型</option>
            <option value="general">通用咨询</option>
            <option value="product">商品咨询</option>
            <option value="order">订单与物流</option>
            <option value="refund">退款与售后</option>
            <option value="tech">技术支持</option>
            <option value="travel">出行规划</option>
          </select>
        </label>
        <div className="admin-filter-actions">
          <button type="submit" className="admin-button primary"><Search size={15} /> 查询</button>
          <button type="button" className="admin-button ghost" onClick={clearFilters} disabled={!hasFilters && !Object.values(filters).some(Boolean)}>
            <FilterX size={15} /> 清除
          </button>
        </div>
      </form>

      <section className="admin-panel admin-table-panel" aria-label="对话记录">
        <div className="admin-table-toolbar">
          <div><strong>会话记录</strong><span>共 {result?.total.toLocaleString('zh-CN') ?? 0} 条</span></div>
          <label className="admin-size-select">每页
            <select value={size} onChange={event => { setSize(Number(event.target.value)); setPage(0); }}>
              <option value={10}>10 条</option>
              <option value={20}>20 条</option>
              <option value={50}>50 条</option>
            </select>
          </label>
        </div>

        {loading && !result ? (
          <AdminLoadingState label="正在加载对话记录…" />
        ) : error ? (
          <AdminErrorState message={error} onRetry={() => void loadSessions()} />
        ) : result && result.items.length > 0 ? (
          <>
            <ConversationTable items={result.items} onOpen={setSelectedSession} />
            <div className="admin-pagination" aria-label="对话分页">
              <span>第 {page + 1} / {totalPages} 页</span>
              <div>
                <button type="button" className="admin-icon-button" disabled={page <= 0} aria-label="上一页" onClick={() => setPage(current => Math.max(0, current - 1))}>
                  <ChevronLeft size={17} />
                </button>
                <button type="button" className="admin-icon-button" disabled={page + 1 >= totalPages} aria-label="下一页" onClick={() => setPage(current => Math.min(totalPages - 1, current + 1))}>
                  <ChevronRight size={17} />
                </button>
              </div>
            </div>
          </>
        ) : (
          <AdminEmptyState
            title={hasFilters ? '没有匹配的对话' : '暂无对话记录'}
            description={hasFilters ? '请调整搜索词或筛选条件后重试。' : '用户完成对话后，记录会显示在这里。'}
            action={hasFilters ? <button type="button" className="admin-button secondary" onClick={clearFilters}>清除筛选</button> : undefined}
          />
        )}
      </section>

      {selectedSession && (
        <ConversationDrawer
          sessionId={selectedSession.sessionId}
          userId={selectedSession.userId}
          onClose={() => setSelectedSession(null)}
          onDeleted={async () => {
            setSelectedSession(null);
            if (result?.items.length === 1 && page > 0) setPage(current => current - 1);
            else await loadSessions();
          }}
        />
      )}
    </div>
  );
}

function ConversationTable({ items, onOpen }: { items: AdminSessionSummary[]; onOpen: (session: SelectedConversation) => void }) {
  return (
    <div className="admin-table-scroll">
      <table className="admin-table">
        <thead><tr>
          <th scope="col">用户与对话</th><th scope="col">咨询类型</th><th scope="col">处理状态</th>
          <th scope="col">评分</th><th scope="col">消息</th><th scope="col">Token</th><th scope="col">最近更新</th><th scope="col"><span className="sr-only">操作</span></th>
        </tr></thead>
        <tbody>
          {items.map(session => (
            <tr key={`${session.userId ?? 'legacy'}:${session.sessionId}`}>
              <td data-label="用户与对话">
                <div className="admin-session-primary">
                  <span className="admin-user-avatar"><UserRound size={15} /></span>
                  <span>
                    <button type="button" onClick={() => onOpen({ sessionId: session.sessionId, userId: session.userId })}>{formatConversationTitle(session.title)}</button>
                    <small>{session.username || '未知用户'}{session.userId !== null ? ` · ID ${session.userId}` : ''}</small>
                  </span>
                </div>
              </td>
              <td data-label="咨询类型"><span className="admin-soft-tag">{formatIntent(session.intent)}</span></td>
              <td data-label="处理状态"><span className={`admin-status-tag tone-${statusTone(session.status)}`}>{formatStatus(session.status)}</span></td>
              <td data-label="评分"><Satisfaction score={session.satisfaction} /></td>
              <td data-label="消息"><span className="admin-message-count"><MessageCircle size={14} /> {session.messageCount}</span></td>
              <td data-label="Token"><TokenSummary session={session} /></td>
              <td data-label="最近更新"><time dateTime={session.updatedAt}>{formatDateTime(session.updatedAt)}</time></td>
              <td data-label="操作"><button type="button" className="admin-row-action" onClick={() => onOpen({ sessionId: session.sessionId, userId: session.userId })}>查看详情 <ChevronRight size={14} /></button></td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function Satisfaction({ score }: { score: number | null }) {
  if (score === null || score <= 0) return <span className="admin-muted">未评价</span>;
  return <span className="admin-rating" aria-label={`满意度 ${score} 分`}><Star size={14} fill="currentColor" /> {score.toFixed(1)}</span>;
}

function TokenSummary({ session }: { session: AdminSessionSummary }) {
  const trackedTurns = session.tokenTrackedTurns;
  const totalTurns = session.totalTurns;
  const isPartial = trackedTurns !== null && totalTurns !== null
    && trackedTurns > 0 && trackedTurns < totalTurns;
  if (isPartial || (session.tokenUsageComplete === false && (trackedTurns ?? 0) > 0)) {
    const detail = trackedTurns !== null && totalTurns !== null ? `已采集 ${trackedTurns}/${totalTurns} 轮` : '仅部分轮次已采集';
    return <span className="admin-token-count is-partial" title={detail}><Hash size={13} /> 部分采集</span>;
  }
  if (session.tokenUsageComplete === false && (trackedTurns ?? 0) === 0) {
    return <span className="admin-muted">未采集</span>;
  }
  if (session.totalTokens === null) return <span className="admin-muted">—</span>;
  return <span className="admin-token-count"><Hash size={13} /> {formatTokenCount(session.totalTokens)}</span>;
}

function formatTokenTracking(session: AdminSessionSummary): string {
  const { tokenTrackedTurns: tracked, totalTurns: total, tokenUsageComplete: complete } = session;
  if (tracked !== null && total !== null) {
    if (complete === true || (tracked === total && total > 0)) return `完整采集 · ${tracked}/${total} 轮`;
    if (tracked > 0) return `部分采集 · ${tracked}/${total} 轮`;
    return '未采集';
  }
  if (complete === true || session.totalTokens !== null) return '已采集';
  if (complete === false) return '未采集';
  return '—';
}

function AgentFlowChain({ flow }: { flow: AdminAgentFlow | null }) {
  const layers = useMemo(() => {
    if (!flow?.nodes.length) return [];
    const byId = new Map(flow.nodes.map(node => [node.id, node]));
    const memo = new Map<string, number>();
    const layerOf = (node: AdminAgentFlowNode, visiting = new Set<string>()): number => {
      const cached = memo.get(node.id);
      if (cached !== undefined) return cached;
      if (visiting.has(node.id)) return 0;
      const nextVisiting = new Set(visiting).add(node.id);
      const parents = flow.edges.filter(edge => edge.to === node.id)
        .map(edge => byId.get(edge.from)).filter((parent): parent is AdminAgentFlowNode => Boolean(parent));
      const layer = parents.length ? Math.max(...parents.map(parent => layerOf(parent, nextVisiting))) + 1 : 0;
      memo.set(node.id, layer);
      return layer;
    };
    const grouped = new Map<number, AdminAgentFlowNode[]>();
    flow.nodes.forEach(node => {
      const layer = layerOf(node);
      grouped.set(layer, [...(grouped.get(layer) ?? []), node]);
    });
    return [...grouped.entries()].sort(([left], [right]) => left - right).map(([, nodes]) => nodes);
  }, [flow]);

  if (!flow || flow.nodes.length === 0) {
    return <div className="admin-agent-flow-empty">该会话暂无可视化 Agent 链路（旧会话不会补录）。</div>;
  }

  return (
    <section className="admin-agent-flow" aria-label="Agent 处理链路">
      <div className="admin-agent-flow-heading">
        <span><GitBranch size={14} /> Agent 处理链路</span>
        <small>{flow.modelName ? `${flow.modelName} · ${flow.questionChars} 字` : `${flow.nodes.length} 个节点`}</small>
      </div>
      <div className="admin-agent-flow-canvas">
        {layers.map((nodes, layerIndex) => (
          <div className="admin-agent-flow-stage-wrap" key={`layer-${layerIndex}`}>
            {layerIndex > 0 && <span className="admin-agent-flow-arrow" aria-hidden="true">→</span>}
            <div className="admin-agent-flow-stage">
              {nodes.map(node => (
                <article className={`admin-agent-flow-node is-${node.status}`} key={node.id}>
                  <div className="admin-agent-flow-node-top">
                    <span className="admin-agent-flow-dot" aria-hidden="true" />
                    <strong>{node.label || node.id}</strong>
                  </div>
                  <p>{node.agent || '未分配 Agent'}</p>
                  {node.summary && <small>{node.summary}</small>}
                  {node.dependsOn.length > 0 && <em>依赖：{node.dependsOn.join(' / ')}</em>}
                </article>
              ))}
            </div>
          </div>
        ))}
      </div>
    </section>
  );
}

const ACTIVE_RECOVERY_STATUSES = new Set<WorkflowRecoveryStatus>([
  'REQUESTED',
  'QUEUED',
  'RECOVERING',
  'RETRY_SCHEDULED',
]);

const RECOVERY_STATUS_LABELS: Record<WorkflowRecoveryStatus, string> = {
  REQUESTED: '已提交',
  QUEUED: '排队中',
  RECOVERING: '恢复中',
  RETRY_SCHEDULED: '等待重试',
  SUCCEEDED: '恢复成功',
  DEAD_LETTERED: '恢复失败',
  SKIPPED_ACTIVE: '任务仍在执行',
  SKIPPED_APPROVAL: '等待用户确认',
  SKIPPED_SUPERSEDED: '已被新检查点替代',
  SKIPPED_DUPLICATE: '重复恢复已跳过',
  REJECTED_INVALID_COMMAND: '恢复请求无效',
};

const RECOVERY_ERROR_LABELS: Record<string, string> = {
  CHECKPOINT_NOT_FOUND: '最近检查点不存在或已经过期，无法恢复。',
  FORBIDDEN: '当前账号没有恢复该工作流的权限。',
  APPROVAL_REQUIRED: '工作流正在等待用户确认，请先完成审批。',
  ACTIVE_EXECUTION: '工作流仍在正常执行，请稍后再试。',
  CHECKPOINT_VERSION_CONFLICT: '检查点已经更新，请刷新执行链路后重试。',
};

function getRecoveryErrorMessage(error: unknown, fallback: string): string {
  if (error instanceof ApiError && error.body) {
    try {
      const body = JSON.parse(error.body) as { code?: string };
      if (body.code && RECOVERY_ERROR_LABELS[body.code]) return RECOVERY_ERROR_LABELS[body.code];
    } catch {
      // 非 JSON 错误体继续使用统一错误信息。
    }
  }
  return getErrorMessage(error, fallback);
}

function RecoveryPanel({
  flow,
  job,
  reason,
  error,
  submitting,
  onReasonChange,
  onSubmit,
}: {
  flow: AdminAgentFlow | null;
  job: WorkflowRecoveryJob | null;
  reason: string;
  error: string;
  submitting: boolean;
  onReasonChange: (value: string) => void;
  onSubmit: () => void;
}) {
  if (!flow || !['running', 'failed'].includes(flow.status.toLowerCase())) return null;
  const active = job ? ACTIVE_RECOVERY_STATUSES.has(job.status) : false;

  return (
    <section className="admin-recovery-panel" aria-label="工作流恢复">
      <div className="admin-recovery-heading">
        <span><RefreshCw size={14} className={active ? 'admin-spin' : undefined} /> 工作流恢复</span>
        <small>执行 ID：{flow.requestId}</small>
      </div>
      <p>该执行处于{flow.status.toLowerCase() === 'running' ? '运行或异常中断' : '失败'}状态，可从最近检查点异步恢复。</p>
      <div className="admin-recovery-actions">
        <input
          value={reason}
          maxLength={200}
          onChange={event => onReasonChange(event.target.value)}
          placeholder="填写恢复原因（可选）"
          disabled={submitting || active}
          aria-label="恢复原因"
        />
        <button
          type="button"
          className="admin-button secondary"
          disabled={submitting || active}
          onClick={onSubmit}
        >
          <RefreshCw size={14} /> {submitting ? '正在提交…' : active ? '恢复处理中…' : '从检查点恢复'}
        </button>
      </div>
      {job && (
        <div className={`admin-recovery-status is-${job.status.toLowerCase()}`} aria-live="polite">
          <strong>{RECOVERY_STATUS_LABELS[job.status]}</strong>
          <span>尝试 {job.attempts} 次</span>
          {job.updatedAt && <span>更新于 {formatDateTime(job.updatedAt)}</span>}
          {job.lastError && <p>{job.lastError}</p>}
        </div>
      )}
      {error && <span className="admin-inline-error" role="alert">{error}</span>}
    </section>
  );
}

function ConversationDrawer({
  sessionId,
  userId,
  onClose,
  onDeleted,
}: {
  sessionId: string;
  userId: number | null;
  onClose: () => void;
  onDeleted: () => void | Promise<void>;
}) {
  const [detail, setDetail] = useState<AdminSessionDetail | null>(null);
  const [agentFlow, setAgentFlow] = useState<AdminAgentFlow | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [deleteError, setDeleteError] = useState('');
  const [deleting, setDeleting] = useState(false);
  const [recoveryJob, setRecoveryJob] = useState<WorkflowRecoveryJob | null>(null);
  const [recoveryReason, setRecoveryReason] = useState('');
  const [recoveryError, setRecoveryError] = useState('');
  const [submittingRecovery, setSubmittingRecovery] = useState(false);
  const closeButtonRef = useRef<HTMLButtonElement>(null);

  const loadAgentFlow = useCallback(async (requestId: string) => {
    setAgentFlow(await adminApi.fetchAdminAgentFlow(requestId).catch(() => null));
  }, []);

  const loadDetail = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const sessionDetail = await adminApi.fetchAdminSession(sessionId, userId);
      const latestRequestId = [...sessionDetail.messages].reverse()
        .find(message => message.role.toLowerCase() === 'assistant' && message.requestId)
        ?.requestId;
      setDetail(sessionDetail);
      await loadAgentFlow(latestRequestId || sessionId);
    } catch (loadError) {
      setError(getErrorMessage(loadError, '无法获取对话详情'));
    } finally {
      setLoading(false);
    }
  }, [loadAgentFlow, sessionId, userId]);

  useEffect(() => { void loadDetail(); }, [loadDetail]);
  useEffect(() => {
    setRecoveryJob(null);
    setRecoveryReason('');
    setRecoveryError('');
    setSubmittingRecovery(false);
  }, [sessionId]);
  useEffect(() => {
    closeButtonRef.current?.focus();
    const handleKey = (event: KeyboardEvent) => { if (event.key === 'Escape') onClose(); };
    window.addEventListener('keydown', handleKey);
    return () => window.removeEventListener('keydown', handleKey);
  }, [onClose]);

  useEffect(() => {
    if (!recoveryJob || !ACTIVE_RECOVERY_STATUSES.has(recoveryJob.status)) return;
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | undefined;

    const poll = async () => {
      try {
        const latest = await adminApi.fetchAdminWorkflowRecovery(recoveryJob.recoveryId);
        if (cancelled) return;
        setRecoveryError('');
        setRecoveryJob(latest);
        if (ACTIVE_RECOVERY_STATUSES.has(latest.status)) {
          timer = setTimeout(() => void poll(), 2000);
        } else if (latest.status === 'SUCCEEDED') {
          await loadDetail();
        }
      } catch (pollError) {
        if (!cancelled) {
          setRecoveryError(getRecoveryErrorMessage(pollError, '无法获取恢复状态，正在重试'));
          timer = setTimeout(() => void poll(), 4000);
        }
      }
    };

    timer = setTimeout(() => void poll(), 1500);
    return () => {
      cancelled = true;
      if (timer) clearTimeout(timer);
    };
  }, [loadDetail, recoveryJob?.recoveryId, recoveryJob?.status]);

  const requestRecovery = async () => {
    if (!agentFlow?.requestId) return;
    if (!window.confirm('确认从最近检查点恢复这次工作流执行？')) return;
    setSubmittingRecovery(true);
    setRecoveryError('');
    try {
      const job = await adminApi.requestAdminWorkflowRecovery(agentFlow.requestId, {
        reason: recoveryReason.trim() || '管理员从对话详情手动触发恢复',
      });
      setRecoveryJob(job);
    } catch (requestError) {
      setRecoveryError(getRecoveryErrorMessage(requestError, '恢复请求提交失败'));
    } finally {
      setSubmittingRecovery(false);
    }
  };

  const deleteSession = async () => {
    if (!window.confirm('确认永久删除这条对话及其关联记录？此操作无法撤销。')) return;
    setDeleting(true);
    setDeleteError('');
    try {
      await adminApi.deleteAdminSession(sessionId, userId);
      await onDeleted();
    } catch (deleteFailure) {
      setDeleteError(getErrorMessage(deleteFailure, '删除失败，请稍后重试'));
      setDeleting(false);
    }
  };

  const messages = useMemo(() => detail?.messages ?? [], [detail]);

  return (
    <div className="admin-drawer-backdrop" role="presentation" onMouseDown={onClose}>
      <aside
        className="admin-drawer"
        role="dialog"
        aria-modal="true"
        aria-labelledby="conversation-detail-title"
        onMouseDown={event => event.stopPropagation()}
      >
        <header className="admin-drawer-header">
          <div><span className="admin-eyebrow">CONVERSATION DETAIL</span><h2 id="conversation-detail-title">对话详情</h2></div>
          <button ref={closeButtonRef} type="button" className="admin-icon-button" aria-label="关闭对话详情" onClick={onClose}><X size={18} /></button>
        </header>

        {loading && !detail ? (
          <AdminLoadingState label="正在加载完整对话…" />
        ) : error ? (
          <AdminErrorState message={error} onRetry={() => void loadDetail()} />
        ) : detail ? (
          <>
            <div className="admin-drawer-summary">
              <div className="admin-drawer-title-row">
                <div><h3>{formatConversationTitle(detail.session.title)}</h3><p>{detail.session.username || '未知用户'}{detail.session.userId !== null ? ` · 用户 ID ${detail.session.userId}` : ''}</p></div>
                <span className={`admin-status-tag tone-${statusTone(detail.session.status)}`}>{formatStatus(detail.session.status)}</span>
              </div>
              <dl className="admin-detail-facts">
                <div><dt>咨询类型</dt><dd>{formatIntent(detail.session.intent)}</dd></div>
                <div><dt>服务能力</dt><dd>{formatAgent(detail.session.agentName)}</dd></div>
                <div><dt>消息数量</dt><dd>{detail.session.messageCount} 条</dd></div>
                <div><dt>Token 采集</dt><dd>{formatTokenTracking(detail.session)}</dd></div>
                <div><dt>Prompt Token</dt><dd>{formatTokenCount(detail.session.promptTokens)}</dd></div>
                <div><dt>Completion Token</dt><dd>{formatTokenCount(detail.session.completionTokens)}</dd></div>
                <div><dt>总 Token</dt><dd>{formatTokenCount(detail.session.totalTokens)}</dd></div>
                <div><dt>满意度</dt><dd><Satisfaction score={detail.session.satisfaction} /></dd></div>
                <div><dt>开始时间</dt><dd>{formatDateTime(detail.session.createdAt)}</dd></div>
                <div><dt>最近更新</dt><dd>{formatDateTime(detail.session.updatedAt)}</dd></div>
              </dl>
              {detail.session.satisfactionComment && (
                <div className="admin-feedback-note"><strong>用户反馈</strong><p>{sanitizeMessageContent(detail.session.satisfactionComment)}</p></div>
              )}
            </div>

            <div className="admin-message-section">
              <AgentFlowChain flow={agentFlow} />
              <RecoveryPanel
                flow={agentFlow}
                job={recoveryJob}
                reason={recoveryReason}
                error={recoveryError}
                submitting={submittingRecovery}
                onReasonChange={setRecoveryReason}
                onSubmit={() => void requestRecovery()}
              />
              <div className="admin-message-heading"><strong>完整对话</strong><span>{messages.length} 条消息</span></div>
              {messages.length === 0 ? (
                <AdminEmptyState title="暂无消息内容" description="该会话目前只有概要记录。" />
              ) : (
                <ol className="admin-message-list">
                  {messages.map(message => {
                    const normalizedRole = message.role.toLowerCase();
                    const isUser = normalizedRole === 'user';
                    const isAssistant = normalizedRole === 'assistant';
                    return (
                      <li key={message.id} className={isUser ? 'is-user' : 'is-assistant'}>
                        <div className="admin-message-meta">
                          <strong>{formatRole(message.role)}</strong>
                          <span>{formatDateTime(message.createdAt)}</span>
                        </div>
                        <p>{sanitizeMessageContent(message.content)}</p>
                        {!isUser && (
                          <div className="admin-message-details">
                            <span>{formatAgent(message.agentName)}</span>
                            {message.status && <span>{formatStatus(message.status)}</span>}
                            {message.latencyMs !== null && message.latencyMs > 0 && <span>{message.latencyMs} ms</span>}
                            {isAssistant && message.promptTokens !== null && <span>Prompt {formatTokenCount(message.promptTokens)}</span>}
                            {isAssistant && message.completionTokens !== null && <span>Completion {formatTokenCount(message.completionTokens)}</span>}
                            {isAssistant && message.totalTokens !== null && <span>总计 {formatTokenCount(message.totalTokens)} Token</span>}
                            {isAssistant && message.requestId && (
                              <button
                                type="button"
                                className="admin-message-flow-button"
                                disabled={agentFlow?.requestId === message.requestId}
                                onClick={() => void loadAgentFlow(message.requestId!)}
                              >
                                <GitBranch size={12} />
                                {agentFlow?.requestId === message.requestId ? '当前执行链路' : '查看本轮链路'}
                              </button>
                            )}
                          </div>
                        )}
                        {isAssistant && (message.promptSnapshot || message.toolUsageComplete !== null) && (
                          <details className="admin-invocation-audit">
                            <summary>查看本轮调用详情</summary>
                            <div className="admin-invocation-audit-body">
                              <section>
                                <h4>调用工具</h4>
                                {message.toolCalls.length > 0 ? (
                                  <ul className="admin-tool-call-list">
                                    {message.toolCalls.map((tool, toolIndex) => (
                                      <li key={`${message.id}-tool-${toolIndex}`}>
                                        <strong>{formatToolCapability(tool.name)}</strong>
                                        <span>{tool.status.toUpperCase() === 'SUCCESS' ? '成功' : '失败'}</span>
                                        <span>{tool.durationMs > 0 ? `${tool.durationMs} ms` : '—'}</span>
                                      </li>
                                    ))}
                                  </ul>
                                ) : (
                                  <p className="admin-audit-empty">
                                    {message.toolUsageComplete === true ? '本轮未调用工具' : '工具调用记录未完整采集'}
                                  </p>
                                )}
                              </section>
                              <section>
                                <h4>模型提示词（脱敏快照）</h4>
                                {message.promptSnapshot ? (
                                  <pre>{message.promptSnapshot}</pre>
                                ) : (
                                  <p className="admin-audit-empty">历史记录未采集提示词</p>
                                )}
                              </section>
                            </div>
                          </details>
                        )}
                      </li>
                    );
                  })}
                </ol>
              )}
            </div>

            <footer className="admin-drawer-footer">
              {deleteError && <span className="admin-inline-error" role="alert">{deleteError}</span>}
              <button type="button" className="admin-button danger" disabled={deleting} onClick={() => void deleteSession()}>
                <Trash2 size={15} /> {deleting ? '正在删除…' : '删除对话'}
              </button>
            </footer>
          </>
        ) : null}
      </aside>
    </div>
  );
}
