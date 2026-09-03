import { useEffect, useId, useState } from 'react';
import { getFeishuFrameConfig } from '../api/auth';

const FEISHU_LOGIN_SDK =
  'https://sf3-cn.feishucdn.com/obj/feishu-static/lark/passport/qrcode/LarkSSOSDKWebQRCode-1.0.2.js';

interface FeishuQrLoginOptions {
  id: string;
  goto: string;
  width: string;
  height: string;
  style: string;
}

interface FeishuQrLoginInstance {
  matchOrigin: (origin: string) => boolean;
}

declare global {
  interface Window {
    QRLogin?: (options: FeishuQrLoginOptions) => FeishuQrLoginInstance;
  }
}

let sdkPromise: Promise<void> | null = null;

function loadFeishuSdk(): Promise<void> {
  if (window.QRLogin) return Promise.resolve();
  if (sdkPromise) return sdkPromise;
  const pending = new Promise<void>((resolve, reject) => {
    const existing = document.querySelector<HTMLScriptElement>(`script[src="${FEISHU_LOGIN_SDK}"]`);
    const script = existing || document.createElement('script');
    const handleLoad = () => window.QRLogin
      ? resolve()
      : reject(new Error('飞书登录组件加载失败'));
    const handleError = () => {
      script.remove();
      reject(new Error('无法加载飞书登录组件，请检查网络后重试'));
    };
    script.addEventListener('load', handleLoad, { once: true });
    script.addEventListener('error', handleError, { once: true });
    if (!existing) {
      script.src = FEISHU_LOGIN_SDK;
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
  const [status, setStatus] = useState<'loading' | 'ready' | 'error'>('loading');
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
    let removeMessageListener: (() => void) | undefined;
    setStatus('loading');
    setMessage('正在加载飞书二维码…');

    Promise.all([getFeishuFrameConfig(returnTo, remember), loadFeishuSdk()])
      .then(([config]) => {
        if (!active) return;
        const target = document.getElementById(containerId);
        if (!target || !window.QRLogin) throw new Error('飞书登录组件初始化失败');
        const authorizationUri = new URL(config.authorizationUri);
        if (authorizationUri.searchParams.get('state') !== config.state) {
          throw new Error('飞书登录状态校验失败，请重新加载');
        }
        target.replaceChildren();
        const login = window.QRLogin({
          id: containerId,
          goto: authorizationUri.toString(),
          width: '300',
          height: '300',
          style: 'width:300px;height:300px;border:0;',
        });
        const handleMessage = (event: MessageEvent) => {
          if (!active || !login.matchOrigin(event.origin)) return;
          const temporaryCode = typeof event.data === 'string' ? event.data.trim() : '';
          if (!temporaryCode) {
            setStatus('error');
            setMessage('飞书授权结果无效，请重新扫码');
            return;
          }
          const callbackUri = new URL(authorizationUri);
          callbackUri.searchParams.set('tmp_code', temporaryCode);
          window.location.assign(callbackUri.toString());
        };
        window.addEventListener('message', handleMessage);
        removeMessageListener = () => window.removeEventListener('message', handleMessage);
        setStatus('ready');
        setMessage('请使用飞书扫描二维码并确认登录');
      })
      .catch(error => {
        if (!active) return;
        setStatus('error');
        setMessage(error instanceof Error ? error.message : '无法初始化飞书扫码登录');
      });

    return () => {
      active = false;
      removeMessageListener?.();
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
        {status === 'error' && (
          <button type="button" className="login-dialog-action" onClick={() => setAttempt(value => value + 1)}>
            重新加载
          </button>
        )}
      </section>
    </div>
  );
}
