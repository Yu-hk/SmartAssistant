import { useEffect, useId, useState } from 'react';
import { getDingTalkFrameConfig } from '../api/auth';

const DINGTALK_LOGIN_SDK = 'https://g.alicdn.com/dingding/h5-dingtalk-login/0.21.0/ddlogin.js';

interface DingTalkLoginResult {
  authCode?: string;
  state?: string;
}

interface DingTalkLoginError {
  message?: string;
  errorMessage?: string;
}

type DingTalkFrameLogin = (
  frame: { id: string; width: number; height: number },
  login: {
    redirect_uri: string;
    client_id: string;
    scope: string;
    response_type: string;
    state: string;
    prompt: string;
  },
  onSuccess: (result: DingTalkLoginResult) => void,
  onError: (error: DingTalkLoginError | string) => void,
) => void;

declare global {
  interface Window {
    DTFrameLogin?: DingTalkFrameLogin;
  }
}

let sdkPromise: Promise<void> | null = null;

function loadDingTalkSdk(): Promise<void> {
  if (window.DTFrameLogin) return Promise.resolve();
  if (sdkPromise) return sdkPromise;
  const pending = new Promise<void>((resolve, reject) => {
    const existing = document.querySelector<HTMLScriptElement>(`script[src="${DINGTALK_LOGIN_SDK}"]`);
    const script = existing || document.createElement('script');
    const handleLoad = () => window.DTFrameLogin
      ? resolve()
      : reject(new Error('钉钉登录组件加载失败'));
    const handleError = () => {
      script.remove();
      reject(new Error('无法加载钉钉登录组件，请检查网络后重试'));
    };
    script.addEventListener('load', handleLoad, { once: true });
    script.addEventListener('error', handleError, { once: true });
    if (!existing) {
      script.src = DINGTALK_LOGIN_SDK;
      script.async = true;
      script.referrerPolicy = 'no-referrer';
      document.head.appendChild(script);
    }
  }).catch(error => {
    sdkPromise = null;
    throw error;
  });
  sdkPromise = pending;
  return pending;
}

interface DingTalkQrLoginDialogProps {
  open: boolean;
  returnTo: string;
  remember: boolean;
  onClose: () => void;
}

export function DingTalkQrLoginDialog({
  open,
  returnTo,
  remember,
  onClose,
}: DingTalkQrLoginDialogProps) {
  const reactId = useId();
  const containerId = `dingtalk-qr-${reactId.replace(/:/g, '')}`;
  const [status, setStatus] = useState<'loading' | 'ready' | 'error'>('loading');
  const [message, setMessage] = useState('正在加载钉钉二维码…');
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
    setStatus('loading');
    setMessage('正在加载钉钉二维码…');

    Promise.all([getDingTalkFrameConfig(returnTo, remember), loadDingTalkSdk()])
      .then(([config]) => {
        if (!active) return;
        const target = document.getElementById(containerId);
        if (!target || !window.DTFrameLogin) throw new Error('钉钉登录组件初始化失败');
        target.replaceChildren();
        window.DTFrameLogin(
          { id: containerId, width: 300, height: 300 },
          {
            redirect_uri: encodeURIComponent(config.redirectUri),
            client_id: config.clientId,
            scope: config.scope,
            response_type: config.responseType,
            state: config.state,
            prompt: config.prompt,
          },
          result => {
            if (!active) return;
            if (!result.authCode || result.state !== config.state) {
              setStatus('error');
              setMessage('钉钉授权结果校验失败，请重新扫码');
              return;
            }
            const callback = new URL(config.redirectUri);
            if (callback.origin !== window.location.origin) {
              setStatus('error');
              setMessage('钉钉回调地址与当前站点不一致，请联系管理员');
              return;
            }
            callback.searchParams.set('code', result.authCode);
            callback.searchParams.set('state', result.state);
            window.location.assign(callback.toString());
          },
          error => {
            if (!active) return;
            const detail = typeof error === 'string'
              ? error
              : error.errorMessage || error.message;
            setStatus('error');
            setMessage(detail || '钉钉扫码登录失败，请重试');
          },
        );
        setStatus('ready');
        setMessage('请使用钉钉扫描二维码并确认登录');
      })
      .catch(error => {
        if (!active) return;
        setStatus('error');
        setMessage(error instanceof Error ? error.message : '无法初始化钉钉扫码登录');
      });

    return () => {
      active = false;
      document.getElementById(containerId)?.replaceChildren();
    };
  }, [attempt, containerId, open, remember, returnTo]);

  if (!open) return null;

  return (
    <div className="login-dialog-backdrop" role="presentation" onMouseDown={onClose}>
      <section
        className="login-dialog dingtalk-qr-dialog glass-card"
        role="dialog"
        aria-modal="true"
        aria-labelledby="dingtalk-qr-title"
        onMouseDown={event => event.stopPropagation()}
      >
        <button type="button" className="login-dialog-close" aria-label="关闭" onClick={onClose}>×</button>
        <h3 id="dingtalk-qr-title">钉钉扫码登录</h3>
        <p className="dingtalk-qr-description">扫码后在钉钉中确认，本页面会自动完成登录。</p>
        <div className={`dingtalk-qr-frame ${status}`} aria-busy={status === 'loading'}>
          <div id={containerId} className="dingtalk-qr-container" />
          {status === 'loading' && <div className="dingtalk-qr-placeholder">二维码加载中…</div>}
        </div>
        <p className={`dingtalk-qr-status ${status}`} role={status === 'error' ? 'alert' : 'status'}>
          {message}
        </p>
        {status === 'error' && (
          <button type="button" className="login-dialog-action" onClick={() => setAttempt(value => value + 1)}>
            重新加载
          </button>
        )}
      </section>
    </div>
  );
}
