import { useCallback, useEffect, useState } from 'react';
import {
  CheckCircle2,
  Clock3,
  Gauge,
  Hash,
  Headphones,
  MessageSquareText,
  Star,
  Users,
} from 'lucide-react';
import * as adminApi from '../api/admin';
import type { AdminStats } from '../types';
import {
  formatDay,
  formatDuration,
  formatIntent,
  formatPercent,
  formatStatus,
  formatTokenCount,
  getErrorMessage,
  statusTone,
} from './adminFormat';
import { AdminEmptyState, AdminErrorState, AdminLoadingState, AdminPageIntro } from './AdminState';

export function AdminOverviewPage({ refreshVersion }: { refreshVersion: number }) {
  const [stats, setStats] = useState<AdminStats | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const loadStats = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      setStats(await adminApi.fetchAdminStats());
    } catch (loadError) {
      setError(getErrorMessage(loadError, '无法获取管理指标'));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void loadStats(); }, [loadStats, refreshVersion]);

  return (
    <div className="admin-page admin-overview-page">
      <AdminPageIntro
        eyebrow="OVERVIEW"
        title="数据总览"
        description="集中查看全局对话规模、服务质量与最近 7 天运行趋势。"
      />

      {loading && !stats ? (
        <AdminLoadingState label="正在汇总全局数据…" />
      ) : error ? (
        <AdminErrorState message={error} onRetry={() => void loadStats()} />
      ) : stats ? (
        <OverviewContent stats={stats} />
      ) : null}
    </div>
  );
}

function OverviewContent({ stats }: { stats: AdminStats }) {
  const hasActivity = stats.totalSessions > 0
    || stats.statusBreakdown.some(item => item.count > 0)
    || stats.daily.some(item => item.sessionCount > 0);

  const kpis = [
    {
      label: '累计对话', value: stats.totalSessions.toLocaleString('zh-CN'),
      hint: '全局有效会话', icon: MessageSquareText, tone: 'indigo',
    },
    {
      label: '服务用户', value: stats.totalUsers.toLocaleString('zh-CN'),
      hint: '已识别独立用户', icon: Users, tone: 'cyan',
    },
    {
      label: '总 Token', value: formatTokenCount(stats.totalTokens),
      hint: stats.tokenTrackedSessions === null
        ? '输入与输出 Token 合计'
        : `完整采集 ${stats.tokenTrackedSessions.toLocaleString('zh-CN')} 个会话`,
      icon: Hash, tone: 'indigo',
    },
    {
      label: '单会话平均 Token', value: formatTokenCount(stats.avgTokensPerSession, 1),
      hint: stats.tokenCoverageRate === null
        ? '按已采集会话平均计算'
        : `轮次覆盖率 ${formatPercent(stats.tokenCoverageRate)}${formatTrackedTurns(stats)}`,
      icon: Gauge, tone: 'cyan',
    },
    {
      label: '平均满意度', value: stats.averageSatisfaction === null ? '—' : `${stats.averageSatisfaction.toFixed(2)} / 5`,
      hint: `${stats.ratedSessions.toLocaleString('zh-CN')} 条有效评价`, icon: Star, tone: 'amber',
    },
    {
      label: '处理成功率', value: formatPercent(stats.successRate),
      hint: '按已完成会话计算', icon: CheckCircle2, tone: 'green',
    },
    {
      label: '平均响应', value: formatDuration(stats.avgLatencyMs),
      hint: `P95 ${formatDuration(stats.p95LatencyMs)}`, icon: Gauge, tone: 'violet',
    },
    {
      label: '转人工率', value: formatPercent(stats.handoffRate),
      hint: '需人工继续处理', icon: Headphones, tone: 'rose',
    },
  ];

  return (
    <>
      <section className="admin-kpi-grid" aria-label="核心指标">
        {kpis.map(kpi => {
          const Icon = kpi.icon;
          return (
            <article className={`admin-kpi-card tone-${kpi.tone}`} key={kpi.label}>
              <span className="admin-kpi-icon"><Icon size={19} aria-hidden="true" /></span>
              <span className="admin-kpi-label">{kpi.label}</span>
              <strong>{kpi.value}</strong>
              <small>{kpi.hint}</small>
            </article>
          );
        })}
      </section>

      {!hasActivity && (
        <AdminEmptyState
          title="暂无可分析的对话数据"
          description="普通用户完成对话后，这里会自动展示服务质量和趋势。"
        />
      )}

      <section className="admin-dashboard-grid">
        <BreakdownPanel
          title="处理状态"
          subtitle="当前会话结果分布"
          items={mergeBreakdown(
            stats.statusBreakdown,
            item => formatStatus(item.status),
            item => statusTone(item.status),
          )}
        />
        <BreakdownPanel
          title="咨询类型"
          subtitle="用户问题意图分布"
          items={mergeBreakdown(
            stats.intentBreakdown,
            item => formatIntent(item.intent),
            (_, index) => ['info', 'success', 'warning', 'danger', 'neutral'][index % 5],
          )}
        />
      </section>

      <section className="admin-panel admin-trend-panel" aria-labelledby="daily-trend-title">
        <div className="admin-panel-heading">
          <div>
            <span className="admin-panel-icon"><Clock3 size={17} aria-hidden="true" /></span>
            <span><h2 id="daily-trend-title">近 7 天对话趋势</h2><p>每日有效会话量与平均满意度</p></span>
          </div>
          <span className="admin-panel-meta">最近更新 · 实时</span>
        </div>
        {stats.daily.length === 0 ? (
          <AdminEmptyState title="暂无趋势数据" description="近 7 天还没有可展示的有效会话。" />
        ) : (
          <DailyTrend stats={stats} />
        )}
      </section>
    </>
  );
}

function formatTrackedTurns(stats: AdminStats): string {
  if (stats.tokenTrackedTurns === null || stats.totalTurns === null) return '';
  return ` · ${stats.tokenTrackedTurns.toLocaleString('zh-CN')}/${stats.totalTurns.toLocaleString('zh-CN')} 轮`;
}

interface DisplayBreakdownItem {
  label: string;
  count: number;
  tone: string;
}

function mergeBreakdown<T extends { count: number }>(
  items: T[],
  getLabel: (item: T) => string,
  getTone: (item: T, index: number) => string,
): DisplayBreakdownItem[] {
  const merged = new Map<string, DisplayBreakdownItem>();
  items.forEach((item, index) => {
    const label = getLabel(item);
    const count = Number(item.count) || 0;
    const existing = merged.get(label);
    if (existing) existing.count += count;
    else merged.set(label, { label, count, tone: getTone(item, index) });
  });
  return [...merged.values()].sort((left, right) => right.count - left.count);
}

function BreakdownPanel({
  title,
  subtitle,
  items,
}: {
  title: string;
  subtitle: string;
  items: DisplayBreakdownItem[];
}) {
  const max = Math.max(1, ...items.map(item => item.count));
  const total = items.reduce((sum, item) => sum + item.count, 0);
  return (
    <section className="admin-panel admin-breakdown-panel">
      <div className="admin-panel-heading compact">
        <div><span><h2>{title}</h2><p>{subtitle}</p></span></div>
        <span className="admin-panel-total">{total.toLocaleString('zh-CN')}</span>
      </div>
      {items.length === 0 ? (
        <AdminEmptyState title={`暂无${title}数据`} description="有新会话后将自动生成分布。" />
      ) : (
        <ul className="admin-breakdown-list">
          {items.map(item => {
            const { label, tone } = item;
            return (
              <li key={label}>
                <div><span className={`admin-legend-dot tone-${tone}`} /> <span>{label}</span><strong>{item.count}</strong></div>
                <span className="admin-progress-track" aria-label={`${label} ${item.count} 条`}>
                  <span className={`admin-progress-value tone-${tone}`} style={{ width: `${Math.max(3, item.count / max * 100)}%` }} />
                </span>
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}

function DailyTrend({ stats }: { stats: AdminStats }) {
  const max = Math.max(1, ...stats.daily.map(item => item.sessionCount));
  return (
    <div className="admin-trend-chart" role="img" aria-label="近七天每日对话数量柱状图">
      {stats.daily.map(item => (
        <div className="admin-trend-column" key={item.date}>
          <div className="admin-trend-tooltip">
            <strong>{item.sessionCount} 条</strong>
            <span>{item.avgSatisfaction === null ? '暂无评分' : `满意度 ${item.avgSatisfaction.toFixed(1)}`}</span>
          </div>
          <div className="admin-trend-bar-area">
            <span className="admin-trend-bar" style={{ height: `${Math.max(5, item.sessionCount / max * 100)}%` }} />
          </div>
          <span className="admin-trend-count">{item.sessionCount}</span>
          <span className="admin-trend-date">{formatDay(item.date)}</span>
        </div>
      ))}
    </div>
  );
}
