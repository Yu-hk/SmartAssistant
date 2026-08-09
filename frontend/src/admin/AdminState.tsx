import { AlertCircle, Inbox, LoaderCircle, RefreshCw } from 'lucide-react';

export function AdminPageIntro({
  eyebrow,
  title,
  description,
  actions,
}: {
  eyebrow: string;
  title: string;
  description: string;
  actions?: React.ReactNode;
}) {
  return (
    <div className="admin-page-intro">
      <div>
        <span className="admin-eyebrow">{eyebrow}</span>
        <h1>{title}</h1>
        <p>{description}</p>
      </div>
      {actions && <div className="admin-page-actions">{actions}</div>}
    </div>
  );
}

export function AdminLoadingState({ label = '正在加载数据…' }: { label?: string }) {
  return (
    <div className="admin-state admin-state-loading" role="status" aria-live="polite">
      <LoaderCircle className="admin-spin" size={25} aria-hidden="true" />
      <strong>{label}</strong>
      <span>正在连接管理服务，请稍候</span>
    </div>
  );
}

export function AdminErrorState({
  message,
  onRetry,
}: {
  message: string;
  onRetry: () => void;
}) {
  return (
    <div className="admin-state admin-state-error" role="alert">
      <span className="admin-state-icon"><AlertCircle size={24} aria-hidden="true" /></span>
      <strong>数据加载失败</strong>
      <span>{message}</span>
      <button type="button" className="admin-button secondary" onClick={onRetry}>
        <RefreshCw size={15} aria-hidden="true" /> 重新加载
      </button>
    </div>
  );
}

export function AdminEmptyState({
  title,
  description,
  action,
}: {
  title: string;
  description: string;
  action?: React.ReactNode;
}) {
  return (
    <div className="admin-state admin-state-empty">
      <span className="admin-state-icon"><Inbox size={24} aria-hidden="true" /></span>
      <strong>{title}</strong>
      <span>{description}</span>
      {action}
    </div>
  );
}
