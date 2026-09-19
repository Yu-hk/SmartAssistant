import { useEffect, useRef, useState } from 'react';
import { ShieldCheck } from 'lucide-react';
import { apiClient } from '../api/client';
import { getAuthUser } from '../api/authStorage';
import './profile-privacy.css';

interface Job { job_id: string; state: string; targets: { target: string; state: string }[] }
interface Overview { available: boolean; analysisEnabled: boolean; job?: Job }
const TARGETS: Record<string, string> = {
  POSTGRES_PROFILE: '在线画像', REDIS_INDEXED: '画像与答案缓存',
  LEGACY_STORAGE: '在线记忆文件', DERIVED_COPIES: '派生诊断信息', BACKUP_RESTORE: '恢复后防止画像重新启用',
};
const STATES: Record<string, string> = { SUCCEEDED: '已完成', PENDING: '等待处理', RETRY: '正在重试', BLOCKED: '待核验' };

export function ProfilePrivacy() {
  const [open, setOpen] = useState(false);
  const owner = getAuthUser()?.userId;
  return <>
    <button type="button" onClick={() => setOpen(true)}><ShieldCheck size={16} />画像与隐私</button>
    {open && owner && <PrivacyDialog key={owner} owner={owner} onClose={() => setOpen(false)} />}
  </>;
}

function PrivacyDialog({ owner, onClose }: { owner: number; onClose: () => void }) {
  const dialog = useRef<HTMLDialogElement>(null);
  const alive = useRef(true);
  const busy = useRef(false);
  const key = useRef<string>();
  const [overview, setOverview] = useState<Overview>();
  const [confirmed, setConfirmed] = useState(false);
  const [sending, setSending] = useState(false);
  const [error, setError] = useState('');
  const [refresh, setRefresh] = useState(0);
  const current = () => alive.current && getAuthUser()?.userId === owner;

  useEffect(() => {
    alive.current = true;
    const previous = document.activeElement as HTMLElement | null;
    dialog.current?.showModal();
    return () => { alive.current = false; previous?.focus(); };
  }, []);

  useEffect(() => {
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let attempts = 0;
    async function load() {
      try {
        const result = await apiClient.get<Overview>('/privacy/profile');
        if (cancelled || !current()) return;
        setOverview(result);
        setError('');
        // Bounded, only while this dialog is open. Reopen/check manually later.
        if (result.job && !['ONLINE_CLEANED', 'STALE'].includes(result.job.state) && ++attempts < 20)
          timer = setTimeout(() => { void load(); }, 5000);
      } catch {
        if (!cancelled && current()) setError('暂时无法获取处理状态。请稍后刷新查看，不必重复提交。');
      }
    }
    void load();
    return () => { cancelled = true; if (timer) clearTimeout(timer); };
  }, [owner, refresh]);

  async function submit() {
    if (busy.current || !current() || !confirmed || !overview?.available || overview.job) return;
    busy.current = true; setSending(true); setError('');
    key.current ??= crypto.randomUUID();
    try {
      const accepted = await apiClient.post<{ jobId: string }>('/privacy/profile/deletions', {
        idempotencyKey: key.current, confirmation: 'DELETE_PROFILE_PAUSE_ANALYSIS',
      });
      if (current()) {
        setOverview(previous => previous && ({ ...previous, analysisEnabled: false,
          job: { job_id: accepted.jobId, state: 'PAUSED', targets: [] } }));
        setConfirmed(false); setRefresh(value => value + 1);
      }
    } catch {
      if (current()) {
        // A timeout may follow a committed request. Same key on retry; GET first.
        setError('提交结果暂未确认。请刷新状态；如需重试，本次请求不会重复清理。');
        setRefresh(value => value + 1);
      }
    } finally {
      busy.current = false;
      if (current()) setSending(false);
    }
  }

  const job = overview?.job;
  return <dialog ref={dialog} className="profile-privacy-dialog" aria-labelledby="profile-privacy-title"
    onCancel={onClose} onClose={onClose}>
    <div className="profile-privacy-content">
      <header><h2 id="profile-privacy-title">画像与隐私</h2><button type="button" onClick={onClose} aria-label="关闭画像与隐私">关闭</button></header>
      <p>清除为您生成的偏好、画像和在线记忆，并暂停后续画像分析。您仍可正常咨询，系统不再使用这些画像进行个性化处理。</p>
      <p>账号、订单、聊天原文、评价和您导入的文档会保留。这不是注销账号，也不会删除上述业务记录。</p>
      <p className="profile-privacy-notice">历史备份和旧日志仍会保留。恢复备份时，系统通过独立控制记录阻止已清除画像重新启用；该记录保存在同一服务器，不覆盖整机丢失。</p>
      <section aria-live="polite">
        {!overview && !error && <p>正在获取状态…</p>}
        {overview && <p>后续画像分析：{overview.analysisEnabled ? '开启' : '已暂停'}</p>}
        {overview && !overview.available && <p>清理服务暂不可用，请稍后再试。正常咨询不受影响。</p>}
        {job && <>
          <h3>{job.state === 'ONLINE_CLEANED' ? '在线画像清理完成' : job.state === 'STALE' ? '画像状态发生变化，请联系管理员核验' : '分析已暂停，清理仍在进行'}</h3>
          <ul>{job.targets.map(target => <li key={target.target}><span>{TARGETS[target.target] || '清理检查'}</span><span>{STATES[target.state] || '待核验'}</span></li>)}</ul>
          {job.state !== 'ONLINE_CLEANED' && <p>清理会在后台继续，关闭此窗口不会取消。部分诊断副本需要等待到期，稍后可再次查看。</p>}
        </>}
        {error && <p role="alert">{error}</p>}
      </section>
      {!job && <label className="profile-privacy-confirm"><input type="checkbox" checked={confirmed} disabled={sending || !overview?.available}
        onChange={event => setConfirmed(event.target.checked)} />我已了解保留范围，确认清除画像并暂停分析（暂不支持自行恢复分析）。</label>}
      <footer>
        <button type="button" disabled={sending} onClick={() => setRefresh(value => value + 1)}>刷新状态</button>
        {!job && <button type="button" className="profile-privacy-delete" disabled={sending || !confirmed || !overview?.available}
          onClick={() => { void submit(); }}>{sending ? '正在提交…' : '清除画像并暂停分析'}</button>}
      </footer>
    </div>
  </dialog>;
}
