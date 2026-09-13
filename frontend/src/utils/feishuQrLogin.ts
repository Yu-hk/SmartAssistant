export const FEISHU_QR_ORIGIN = 'https://passport.feishu.cn';
export const FEISHU_LOGIN_SDK =
  'https://lf-package-cn.feishucdn.com/obj/feishu-static/lark/passport/qrcode/LarkSSOSDKWebQRCode-1.0.3.js';

export interface FeishuQrLoginOptions {
  id: string;
  goto: string;
  width: string;
  height: string;
  style: string;
}

export interface FeishuQrLoginInstance {
  matchOrigin: (origin: string) => boolean;
  matchData: (data: unknown) => boolean;
}

declare global {
  interface Window {
    QRLogin?: (options: FeishuQrLoginOptions) => FeishuQrLoginInstance;
  }
}

export type FeishuQrStatus = 'loading' | 'ready' | 'error' | 'redirecting';

/** Reject incompatible authorization endpoints before passing a URL to the SDK. */
export function validateFeishuAuthorizationUri(config: { authorizationUri: string; state: string }): URL {
  const uri = new URL(config.authorizationUri);
  if (uri.origin !== FEISHU_QR_ORIGIN || uri.pathname !== '/suite/passport/oauth/authorize'
    || uri.username || uri.password || uri.hash
    || uri.searchParams.get('response_type') !== 'code'
    || !uri.searchParams.get('client_id')?.trim()
    || !uri.searchParams.get('redirect_uri')?.trim()) {
    throw new Error('飞书扫码配置不正确，请联系管理员');
  }
  if (!config.state || uri.searchParams.getAll('state').length !== 1
    || uri.searchParams.get('state') !== config.state) {
    throw new Error('飞书登录状态校验失败，请重新加载');
  }
  return uri;
}

/** A shared request, with failed/stale script elements removed before retry. */
export function createFeishuSdkLoader(
  browser: Pick<Window, 'QRLogin'>,
  doc: Pick<Document, 'querySelector' | 'createElement' | 'head'>,
  timeoutMs = 15_000,
): () => Promise<void> {
  let pending: Promise<void> | null = null;
  return () => {
    if (pending) return pending;
    if (browser.QRLogin) return Promise.resolve();
    // An old load event will not fire again. Never wait on an orphaned script.
    doc.querySelector<HTMLScriptElement>(`script[src="${FEISHU_LOGIN_SDK}"]`)?.remove();
    const script = doc.createElement('script');
    const request = new Promise<void>((resolve, reject) => {
      let settled = false;
      let timer: ReturnType<typeof setTimeout>;
      const finish = (error?: Error) => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        script.removeEventListener('load', handleLoad);
        script.removeEventListener('error', handleError);
        if (error) {
          script.remove();
          reject(error);
        } else resolve();
      };
      const handleLoad = () => finish(browser.QRLogin ? undefined : new Error('飞书登录组件加载失败'));
      const handleError = () => finish(new Error('无法加载飞书登录组件，请检查网络后重试'));
      timer = setTimeout(() => finish(new Error('飞书登录组件加载超时，请重试')), timeoutMs);
      script.addEventListener('load', handleLoad);
      script.addEventListener('error', handleError);
      script.src = FEISHU_LOGIN_SDK;
      script.async = true;
      script.referrerPolicy = 'no-referrer';
      doc.head.appendChild(script);
    });
    pending = request.catch(error => {
      pending = null;
      throw error;
    });
    return pending;
  };
}

let loadSdk: (() => Promise<void>) | undefined;
export function loadFeishuSdk(): Promise<void> {
  loadSdk ??= createFeishuSdkLoader(window, document);
  return loadSdk();
}

export function mountFeishuQrCode({
  container,
  authorizationUri,
  onStatus,
  onAuthorized,
  browser = window,
  timeoutMs = 15_000,
}: {
  container: HTMLElement;
  authorizationUri: URL;
  onStatus: (status: FeishuQrStatus, message: string) => void;
  onAuthorized: (uri: string) => void;
  browser?: Window;
  timeoutMs?: number;
}): () => void {
  if (!browser.QRLogin) throw new Error('飞书登录组件初始化失败');
  container.replaceChildren();
  const login = browser.QRLogin({
    id: container.id,
    goto: authorizationUri.toString(),
    width: '300', height: '300', style: 'width:300px;height:300px;border:0;',
  });
  const frame = container.querySelector('iframe');
  if (!frame || new URL(frame.src).origin !== FEISHU_QR_ORIGIN
    || typeof login.matchData !== 'function' || typeof login.matchOrigin !== 'function') {
    container.replaceChildren();
    throw new Error('飞书二维码组件版本或配置不正确，请刷新页面');
  }
  frame.title = '飞书扫码登录二维码';
  let active = true;
  let authorized = false;
  const timer = setTimeout(() => {
    if (active && !authorized) onStatus('error', '飞书二维码页面加载超时，请重新加载或打开官方登录页');
  }, timeoutMs);
  const onLoad = () => {
    if (!active || authorized) return;
    clearTimeout(timer);
    // Cross-origin load also fires for provider error pages; do not claim QR success.
    onStatus('ready', '请使用飞书扫码；若页面显示错误，请重新加载或打开官方登录页');
  };
  const onError = () => {
    if (!active || authorized) return;
    clearTimeout(timer);
    onStatus('error', '飞书二维码页面加载失败，请重新加载或打开官方登录页');
  };
  const onMessage = (event: MessageEvent) => {
    if (!active || authorized || event.origin !== FEISHU_QR_ORIGIN
      || !frame.contentWindow || event.source !== frame.contentWindow
      || !login.matchOrigin(event.origin) || !login.matchData(event.data)) return;
    const data = event.data;
    // SDK 1.0.3 sends { source: 'qrcode', tmp_code }. Ignore layout/status/error messages.
    if (!data || typeof data !== 'object' || data.source !== 'qrcode'
      || typeof data.tmp_code !== 'string' || !data.tmp_code
      || data.tmp_code.length > 4096 || /[\u0000-\u0020\u007f]/.test(data.tmp_code)) return;
    const uri = new URL(authorizationUri);
    uri.searchParams.set('tmp_code', data.tmp_code);
    authorized = true;
    clearTimeout(timer);
    onStatus('redirecting', '扫码确认成功，正在完成登录…');
    onAuthorized(uri.toString());
  };
  frame.addEventListener('load', onLoad);
  frame.addEventListener('error', onError);
  browser.addEventListener('message', onMessage);
  return () => {
    active = false;
    clearTimeout(timer);
    frame.removeEventListener('load', onLoad);
    frame.removeEventListener('error', onError);
    browser.removeEventListener('message', onMessage);
    container.replaceChildren();
  };
}
