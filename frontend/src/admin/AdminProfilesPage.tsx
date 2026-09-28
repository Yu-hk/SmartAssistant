import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { getAdminProfile, listAdminProfiles, requestAdminCleanup, type ProfileDetail, type ProfilePage } from '../api/adminProfiles';
import { AdminEmptyState, AdminErrorState, AdminLoadingState, AdminPageIntro } from './AdminState';
import { getErrorMessage } from './adminFormat';

const reasons = [
  ['USER_REQUEST', '用户请求'], ['SECURITY_INCIDENT', '安全事件'],
  ['DATA_CORRECTION', '数据纠正'], ['OTHER', '其他合规原因'],
] as const;

export function AdminProfilesPage({ refreshVersion }: { refreshVersion: number }) {
  const [search, setSearch] = useState('');
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(0);
  const [list, setList] = useState<ProfilePage | null>(null);
  const [selected, setSelected] = useState<ProfileDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [actionError, setActionError] = useState('');
  const [typedName, setTypedName] = useState('');
  const [reason, setReason] = useState('');
  const [acknowledged, setAcknowledged] = useState(false);
  const [busy, setBusy] = useState(false);
  const [actionId, setActionId] = useState(() => crypto.randomUUID());

  const load = useCallback(async () => {
    setLoading(true);setError('');
    try { setList(await listAdminProfiles(query, page)); }
    catch (failure) { setError(getErrorMessage(failure, '无法获取画像管理列表')); }
    finally { setLoading(false); }
  }, [query, page]);
  useEffect(() => { void load(); }, [load, refreshVersion]);

  const select = async (id: number) => {
    setSelected(null);setTypedName('');setReason('');setAcknowledged(false);setActionError('');
    setActionId(crypto.randomUUID());
    try { setSelected(await getAdminProfile(id)); }
    catch (failure) { setActionError(getErrorMessage(failure, '无法读取目标用户状态')); }
  };
  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (!selected || typedName !== selected.username || !reason || !acknowledged || busy) return;
    setBusy(true);setActionError('');
    try {
      await requestAdminCleanup(selected.id, selected.username, reason, actionId);
      setSelected(await getAdminProfile(selected.id));
      setList(await listAdminProfiles(query, page));
      setTypedName('');setReason('');setAcknowledged(false);setActionId(crypto.randomUUID());
    } catch (failure) {
      // Keep the same idempotency key on uncertain network failures.
      setActionError(getErrorMessage(failure, '操作未确认；请核对任务后用相同请求重试'));
    } finally { setBusy(false); }
  };

  return <div className="admin-page admin-profiles-page">
    <AdminPageIntro eyebrow="PRIVACY" title="画像管理" description="仅展示画像元数据、生命周期和清理审计；不展示画像原文。" />
    <form className="admin-panel admin-profile-search" onSubmit={event => { event.preventDefault();setPage(0);setQuery(search); }}>
      <label className="admin-form-field"><span>按用户名搜索</span><input value={search} onChange={event => setSearch(event.target.value)} maxLength={100} /></label>
      <button className="admin-button primary" type="submit">查询</button>
    </form>
    {loading ? <AdminLoadingState /> : error ? <AdminErrorState message={error} onRetry={() => void load()} /> : !list?.items.length ?
      <AdminEmptyState title="没有匹配的用户" description="请调整用户名后重试。" /> :
      <section className="admin-panel admin-table-panel" aria-label="用户画像列表">
        <div className="admin-table-toolbar"><strong>用户画像</strong><span>共 {list.total} 个账号</span></div>
        <div className="admin-table-scroll"><table className="admin-table"><thead><tr><th>用户</th><th>分析状态</th><th>画像版本</th><th>最近清理</th><th>操作</th></tr></thead><tbody>
          {list.items.map(row => <tr key={row.id}>
            <td data-label="用户">{row.username}（#{row.id}）</td>
            <td data-label="分析状态">{row.analysis_enabled ? '已启用' : '已暂停'}</td>
            <td data-label="画像版本">{row.profile_version ?? '无'}</td>
            <td data-label="最近清理">{row.cleanup_state ?? '无'}</td>
            <td data-label="操作"><button type="button" className="admin-button secondary" onClick={() => void select(row.id)}>查看与管理</button></td>
          </tr>)}</tbody></table></div>
        <div className="admin-profile-pager"><button type="button" className="admin-button secondary" disabled={page === 0} onClick={() => setPage(page - 1)}>上一页</button><span>第 {page + 1} 页</span><button type="button" className="admin-button secondary" disabled={(page + 1) * 20 >= list.total} onClick={() => setPage(page + 1)}>下一页</button></div>
      </section>}
    {actionError && <div role="alert" className="admin-notice is-error">{actionError}</div>}
    {selected && <section className="admin-panel admin-profile-detail" aria-label="画像详情与清理">
      <h2>{selected.username}（#{selected.id}）</h2>
      <p>分析：{selected.analysis_enabled ? '已启用' : '已暂停'} · 画像版本：{selected.profile_version ?? '无'} · 可靠性：{selected.reliable == null ? '无' : selected.reliable ? '可靠' : '待核实'} · 购买阶段：{selected.purchase_stage ?? '未知'}</p>
      <h3>最近清理任务</h3>
      {selected.jobs.length ? <ul>{selected.jobs.map(job => <li key={job.job_id}>{job.job_id} · {job.state} · {job.created_at}</li>)}</ul> : <p>无</p>}
      <h3>清理回执</h3>
      {selected.receipts.length ? <ul>{selected.receipts.map(receipt => <li key={`${receipt.job_id}-${receipt.target}`}>{receipt.target} · {receipt.state} · {receipt.error_code ?? '无错误'}</li>)}</ul> : <p>无</p>}
      <h3>管理员审计</h3>
      {selected.audit.length ? <ul>{selected.audit.map(entry => <li key={entry.action_id}>管理员 #{entry.actor_user_id} · {entry.reason_code} · {entry.created_at} · 任务 {entry.job_id}</li>)}</ul> : <p>无管理员代清理记录</p>}
      <form onSubmit={event => void submit(event)} className="admin-profile-cleanup">
        <h3>代用户清理画像</h3>
        <p role="note">此操作不可自行恢复：立即暂停该用户的画像分析，并启动异步清理。账号、对话与订单仍保留。</p>
        <label className="admin-form-field"><span>原因 *</span><select required value={reason} onChange={event => setReason(event.target.value)}><option value="">请选择原因</option>{reasons.map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>
        <label className="admin-form-field"><span>输入目标用户名“{selected.username}”确认 *</span><input required autoComplete="off" value={typedName} onChange={event => setTypedName(event.target.value)} /></label>
        <label><input type="checkbox" checked={acknowledged} onChange={event => setAcknowledged(event.target.checked)} /> 我确认目标账号和不可逆影响</label>
        <button type="submit" className="admin-button danger" disabled={busy || typedName !== selected.username || !reason || !acknowledged || !selected.analysis_enabled}>{busy ? '提交中…' : '清理画像并暂停分析'}</button>
      </form>
    </section>}
  </div>;
}
