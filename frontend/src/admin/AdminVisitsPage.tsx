import { FormEvent, useEffect, useState } from 'react';
import { Search, UsersRound, Eye, Layers3, ArrowRight, ChevronLeft, ChevronRight } from 'lucide-react';
import { fetchVisits, visitModules, type VisitModule, type VisitResult } from '../api/visits';
import { AdminEmptyState, AdminErrorState, AdminLoadingState, AdminPageIntro } from './AdminState';
import { formatDateTime } from './adminFormat';
import './adminVisits.css';

const initialFilters = { from: '', to: '', module: '', identity: '', query: '' };
export function AdminVisitsPage({ refreshVersion }: { refreshVersion: number }) {
  const [filters, setFilters] = useState(initialFilters);
  const [applied, setApplied] = useState(initialFilters);
  const [view, setView] = useState<'visitors' | 'events'>('visitors');
  const [visitor, setVisitor] = useState('');
  const [page, setPage] = useState(0);
  const [result, setResult] = useState<VisitResult | null>(null);
  const [modules, setModules] = useState<VisitModule[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [retry, setRetry] = useState(0);
  useEffect(() => { void visitModules().then(setModules).catch(() => {}); }, []);
  useEffect(() => {
    let active = true;
    setLoading(true); setError('');
    void fetchVisits({ ...applied, view, visitor, page, size: 20 }).then(data => {
      if (active) setResult(data);
    }).catch(reason => { if (active) setError(reason instanceof Error ? reason.message : '访问记录加载失败'); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [applied, view, visitor, page, refreshVersion, retry]);
  const apply = (event: FormEvent) => { event.preventDefault(); setApplied({ ...filters }); setVisitor(''); setPage(0); };
  const tabs = (next: 'visitors' | 'events') => { setView(next); setVisitor(''); setPage(0); };
  const pages = Math.max(1, Math.ceil((result?.total ?? 0) / 20));
  return <div className="admin-page">
    <AdminPageIntro eyebrow="VISITS" title="访问记录" description="查看访客及功能模块的浏览情况，了解用户从哪里开始使用服务。" />
    <p className="visit-note">记录从功能上线后开始采集，默认查看最近 7 天，保留 {result?.retentionDays ?? 90} 天。访客数按浏览会话和登录身份统计，不代表实际人数；服务入口点击与页面浏览分别标注。</p>
    <form className="admin-filter-bar" onSubmit={apply} role="search">
      <label className="admin-filter-field"><span>开始日期</span><input aria-label="开始日期" type="date" value={filters.from} onChange={e => setFilters({ ...filters, from: e.target.value })} /></label>
      <label className="admin-filter-field"><span>结束日期</span><input aria-label="结束日期" type="date" value={filters.to} onChange={e => setFilters({ ...filters, to: e.target.value })} /></label>
      <label className="admin-filter-field"><span>功能模块</span><select value={filters.module} onChange={e => setFilters({ ...filters, module: e.target.value })}><option value="">全部模块</option>{modules.map(module => <option key={module.code} value={module.code}>{module.label}</option>)}</select></label>
      <label className="admin-filter-field"><span>访客身份</span><select value={filters.identity} onChange={e => setFilters({ ...filters, identity: e.target.value })}><option value="">全部身份</option><option value="guest">未登录访客</option><option value="user">普通用户</option><option value="admin">管理员</option></select></label>
      <label className="admin-search-field"><span className="sr-only">搜索用户名或访客标识</span><Search size={16} /><input maxLength={80} placeholder="用户名 / 访客标识" value={filters.query} onChange={e => setFilters({ ...filters, query: e.target.value })} /></label>
      <button className="admin-button primary" type="submit">查询</button>
    </form>
    {error ? <AdminErrorState message={error} onRetry={() => setRetry(n => n + 1)} /> : loading ? <AdminLoadingState /> : result && <>
      <div className="visit-metrics">
        {[{ label: '访客会话', value: result.summary.visitors, Icon: UsersRound }, { label: '浏览与入口访问', value: result.summary.views, Icon: Eye }, { label: '已访问模块', value: result.summary.modules, Icon: Layers3 }].map(({ label, value, Icon }) => <div className="admin-panel visit-metric" key={label}><Icon size={20} /><span>{label}<strong>{value.toLocaleString()}</strong></span></div>)}
      </div>
      <section className="admin-panel visit-modules" aria-label="模块浏览统计"><h2>模块浏览概览</h2>{result.modules.length ? <div className="visit-module-grid">{result.modules.map(module => <button type="button" key={module.code} onClick={() => { setFilters({ ...filters, module: module.code }); setApplied({ ...applied, module: module.code }); tabs('events'); }}><span>{module.label}</span><strong>{module.views.toLocaleString()} 次</strong><small>{module.visitors} 个浏览会话</small></button>)}</div> : <p>所选范围内还没有浏览记录。</p>}</section>
      <section className="admin-panel admin-table-panel" aria-label="访问明细">
        <div className="admin-table-toolbar"><div className="visit-tabs" role="group" aria-label="记录类型"><button className={`admin-button ${view === 'visitors' ? 'primary' : 'ghost'}`} onClick={() => tabs('visitors')}>访客记录</button><button className={`admin-button ${view === 'events' ? 'primary' : 'ghost'}`} onClick={() => tabs('events')}>模块浏览明细</button></div><span>共 {result.total} 条</span></div>
        {visitor && <div className="visit-selection">正在查看访客 {visitor.slice(0, 8)} 的浏览轨迹 <button className="admin-button ghost" onClick={() => { setVisitor(''); setPage(0); }}>取消筛选</button></div>}
        {!result.items.length ? <AdminEmptyState title="暂无访问记录" description="调整筛选条件，或在页面产生新的访问后刷新。" /> : <div className="admin-table-scroll"><table className="admin-table"><thead><tr><th>访客 / 用户</th>{view === 'visitors' ? <><th>首次访问</th><th>最近访问</th><th>访问次数</th><th>模块数</th><th>操作</th></> : <><th>功能模块</th><th>访问类型</th><th>浏览器 / 设备</th><th>访问时间</th></>}</tr></thead><tbody>{result.items.map(row => <tr key={row.id || `${row.visitorId}:${row.userId}`}><td><strong>{row.username || (row.userId ? `用户 ${row.userId}` : '未登录访客')}</strong><small className="visit-secondary">{row.role === 'ROLE_ADMIN' ? '管理员' : row.role === 'ROLE_USER' ? '普通用户' : '访客'} · {row.visitorId.slice(0, 8)}</small></td>{view === 'visitors' ? <><td>{formatDateTime(row.firstSeen || '')}</td><td>{formatDateTime(row.lastSeen || '')}</td><td>{row.views}</td><td>{row.modules}</td><td><button className="admin-button ghost" onClick={() => { setVisitor(row.visitorId); setView('events'); setPage(0); }}>浏览轨迹 <ArrowRight size={14} /></button></td></> : <><td>{row.module}</td><td>{row.kind === 'ENTRY_OPEN' ? '服务入口' : '页面浏览'}</td><td>{row.browser} / {row.device}</td><td>{formatDateTime(row.createdAt || '')}</td></>}</tr>)}</tbody></table></div>}
        <div className="admin-pagination"><span>第 {page + 1} / {pages} 页</span><button className="admin-button ghost" disabled={page === 0} onClick={() => setPage(n => n - 1)} aria-label="上一页"><ChevronLeft size={16} /></button><button className="admin-button ghost" disabled={page + 1 >= pages} onClick={() => setPage(n => n + 1)} aria-label="下一页"><ChevronRight size={16} /></button></div>
      </section>
    </>}
  </div>;
}
