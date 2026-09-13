import { useEffect, useId, useState } from 'react';
import { getFeishuFrameConfig, getOAuthAuthorizeUrl } from '../api/auth';
import {
  loadFeishuSdk, mountFeishuQrCode, validateFeishuAuthorizationUri, type FeishuQrStatus,
} from '../utils/feishuQrLogin';

interface FeishuQrLoginDialogProps {
  open: boolean;
  returnTo: string;
  remember: boolean;
  onClose: () => void;
}

export function FeishuQrLoginDialog({
  open,
  returnTo,
  remember,
  onClose,
}: FeishuQrLoginDialogProps) {
  const reactId = useId();
  const containerId = `feishu-qr-${reactId.replace(/:/g, '')}`;
  const [status, setStatus] = useState<FeishuQrStatus>('loading');
  const [message, setMessage] = useState('正在加载飞书二维码…');
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    if (!open) return undefined;
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [onClose, open]);

  useEffect(() => {
    if (!open) return undefined;
    let active = true;
    let disposeQr: (() => void) | undefined;
    setStatus('loading');
    setMessage('正在加载飞书二维码…');

    Promise.all([getFeishuFrameConfig(returnTo, remember), loadFeishuSdk()])
      .then(([config]) => {
        if (!active) return;
        const target = document.getElementById(containerId);
        if (!target) throw new Error('飞书登录组件初始化失败');
        const authorizationUri = validateFeishuAuthorizationUri(config);
        disposeQr = mountFeishuQrCode({
          container: target, authorizationUri,
          onStatus: (nextStatus, nextMessage) => {
            if (!active) return;
            setStatus(nextStatus);
            setMessage(nextMessage);
          },
          onAuthorized: uri => { if (active) window.location.assign(uri); },
        });
      })
      .catch(error => {
        if (!active) return;
        setStatus('error');
        setMessage(error instanceof Error ? error.message : '无法初始化飞书扫码登录');
      });

    return () => {
      active = false;
      disposeQr?.();
      document.getElementById(containerId)?.replaceChildren();
    };
  }, [attempt, containerId, open, remember, returnTo]);

  if (!open) return null;

  return (
    <div className="login-dialog-backdrop" role="presentation" onMouseDown={onClose}>
      <section
        className="login-dialog oauth-qr-dialog glass-card"
        role="dialog"
        aria-modal="true"
        aria-labelledby="feishu-qr-title"
        onMouseDown={event => event.stopPropagation()}
      >
        <button type="button" className="login-dialog-close" aria-label="关闭" onClick={onClose}>×</button>
        <h3 id="feishu-qr-title">飞书扫码登录</h3>
        <p className="oauth-qr-description">扫码后在飞书中确认，本页面会自动完成登录。</p>
        <div className={`oauth-qr-frame ${status}`} aria-busy={status === 'loading'}>
          <div id={containerId} className="oauth-qr-container" />
          {status === 'loading' && <div className="oauth-qr-placeholder">二维码加载中…</div>}
        </div>
        <p className={`oauth-qr-status ${status}`} role={status === 'error' ? 'alert' : 'status'}>
          {message}
        </p>
        {status !== 'loading' && status !== 'redirecting' && (
          <button type="button" className="login-dialog-action" onClick={() => setAttempt(value => value + 1)}>
            重新加载
          </button>
        )}
        <p className="oauth-qr-fallback">
          <a href={getOAuthAuthorizeUrl('feishu', returnTo, remember)}>在飞书官方页面登录</a>
        </p>
      </section>
    </div>
  );
}
