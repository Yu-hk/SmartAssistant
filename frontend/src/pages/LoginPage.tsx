import { FormEvent, useEffect, useState } from 'react';
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import {
  exchangeOAuthTicket,
  getOAuthAuthorizeUrl,
  getOAuthProviders,
  login,
  register,
  saveAuth,
  type OAuthProviderId,
  type OAuthProviderStatus,
} from '../api/auth';
import { DingTalkQrLoginDialog } from '../components/DingTalkQrLoginDialog';
import {
  Activity,
  AtSign,
  Building2,
  KeyRound,
  Mail,
  ShieldCheck,
  Users,
  Wifi,
} from 'lucide-react';

export function LoginPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const [searchParams] = useSearchParams();
  const oauthTicket = searchParams.get('oauth_ticket');
  const oauthError = searchParams.get('oauth_error');
  const env = (import.meta as ImportMeta & { env?: Record<string, string | undefined> }).env ?? {};
  const [mode, setMode] = useState<'login' | 'register'>('login');

  // 登录字段
  const [username, setUsername] = useState(
    () => localStorage.getItem('smart-assistant-remembered-username') || '',
  );
  const [password, setPassword] = useState('');
  const [showPwd, setShowPwd] = useState(false);
  const [remember, setRemember] = useState(true);

  // 注册字段
  const [email, setEmail] = useState('');
  const [confirm, setConfirm] = useState('');
  const [showPwd2, setShowPwd2] = useState(false);
  const [agreed, setAgreed] = useState(false);

  const [error, setError] = useState(
    () => searchParams.get('expired') === '1' ? '登录状态已过期，请重新登录' : '',
  );
  const [loading, setLoading] = useState(false);
  const [helpDialog, setHelpDialog] = useState<'forgot' | 'terms' | null>(null);
  const [dingtalkQrOpen, setDingtalkQrOpen] = useState(false);
  const [oauthProviders, setOAuthProviders] = useState<OAuthProviderStatus[]>([
    { id: 'wechat', name: '微信', enabled: false },
    { id: 'dingtalk', name: '钉钉', enabled: false },
    { id: 'feishu', name: '飞书', enabled: false },
  ]);

  useEffect(() => {
    let active = true;
    getOAuthProviders()
      .then(providers => { if (active) setOAuthProviders(providers); })
      .catch(() => undefined);
    return () => { active = false; };
  }, []);

  useEffect(() => {
    if (oauthError) {
      setError(oauthError);
      return;
    }
    if (!oauthTicket) return;
    let active = true;
    setLoading(true);
    exchangeOAuthTicket(oauthTicket)
      .then(({ auth, remember: shouldRemember, returnTo }) => {
        if (!active) return;
        saveAuth(auth, shouldRemember);
        const isAdmin = auth.role === 'ROLE_ADMIN';
        const permittedReturnTo = returnTo
          && (isAdmin ? returnTo.startsWith('/admin') : !returnTo.startsWith('/admin'))
          ? returnTo
          : undefined;
        navigate(permittedReturnTo || (isAdmin ? '/admin/overview' : '/'), { replace: true });
      })
      .catch(err => {
        if (active) {
          window.history.replaceState({}, '', '/login');
          setError(err instanceof Error ? err.message : '第三方登录失败，请重试');
        }
      })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [navigate, oauthError, oauthTicket]);

  const switchMode = (next: 'login' | 'register') => {
    setMode(next);
    setError('');
  };

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setError('');
    if (mode === 'register' && password !== confirm) {
      setError('两次输入的密码不一致');
      return;
    }
    if (mode === 'register' && !agreed) {
      setError('请先阅读并同意服务协议与隐私政策');
      return;
    }
    setLoading(true);
    try {
      const user = mode === 'login'
        ? await login(username.trim(), password)
        : await register(username.trim(), password, email.trim());
      const shouldRemember = mode === 'register' || remember;
      saveAuth(user, shouldRemember);
      if (mode === 'login' && remember) {
        localStorage.setItem('smart-assistant-remembered-username', username.trim());
      } else {
        localStorage.removeItem('smart-assistant-remembered-username');
      }
      const requestedPath = (location.state as { from?: string } | null)?.from;
      const isAdmin = user.role === 'ROLE_ADMIN';
      const permittedRequestedPath = requestedPath
        && (isAdmin ? requestedPath.startsWith('/admin') : !requestedPath.startsWith('/admin'))
        ? requestedPath
        : undefined;
      navigate(permittedRequestedPath || (isAdmin ? '/admin/overview' : '/'), { replace: true });
    } catch (err) {
      setError(err instanceof Error ? err.message : '操作失败，请稍后重试');
    } finally {
      setLoading(false);
    }
  };

  const openPasswordRecovery = () => {
    if (env.VITE_PASSWORD_RESET_URL) {
      window.location.assign(env.VITE_PASSWORD_RESET_URL);
      return;
    }
    setHelpDialog('forgot');
  };

  const beginSso = (provider: OAuthProviderStatus) => {
    setError('');
    if (!provider.enabled) {
      setError(`${provider.name}登录尚未配置，请联系系统管理员`);
      return;
    }
    const requestedPath = (location.state as { from?: string } | null)?.from || '/';
    if (provider.id === 'dingtalk') {
      setDingtalkQrOpen(true);
      return;
    }
    window.location.assign(getOAuthAuthorizeUrl(provider.id, requestedPath, remember));
  };

  const requestedPath = (location.state as { from?: string } | null)?.from || '/';

  const channelColors: Record<OAuthProviderId, string> = {
    wechat: '#2ecc71', dingtalk: '#3370ff', feishu: '#00d6b9',
  };

  return (
    <div className="login-page">
      {/* ===== 左侧品牌叙事 ===== */}
      <section className="login-intro">
        <div className="login-brand"><img src="/icons/app-icon.svg" alt="" /> 智服 SmartAssistant</div>
        <div className="login-kicker">INTELLIGENT CUSTOMER SERVICE</div>
        <h1>让每个服务问题，<br />都有 <span className="accent">清晰步骤</span> 解决</h1>
        <p>
          覆盖售前、订单、技术支持和投诉处理，自动理解需求并协同完成，
          全渠道接入，让服务更快、更准确。
        </p>

        <div className="login-values">
          <div className="login-value">
            <div className="lv-icon lv1"><Users size={18} /></div>
            <div>
              <div className="lv-t">专业能力协同</div>
              <div className="lv-d">自动拆分复杂问题，并行完成可独立处理的步骤</div>
            </div>
          </div>
          <div className="login-value">
            <div className="lv-icon lv2"><Activity size={18} /></div>
            <div>
              <div className="lv-t">全链路服务追踪</div>
              <div className="lv-d">处理状态、服务能力与知识命中清晰可见</div>
            </div>
          </div>
          <div className="login-value">
            <div className="lv-icon lv3"><Wifi size={18} /></div>
            <div>
              <div className="lv-t">全渠道接入</div>
              <div className="lv-d">微信 / App / 网页 / 企业微信统一工作台</div>
            </div>
          </div>
        </div>

        <div className="login-stats">
          <div>
            <div className="ls-num">3<span> 层</span></div>
            <div className="ls-label">意图识别（规则 + 小模型 + LLM）</div>
          </div>
          <div>
            <div className="ls-num">RAG</div>
            <div className="ls-label">知识库检索增强问答</div>
          </div>
          <div>
            <div className="ls-num">360°</div>
            <div className="ls-label">客户画像与偏好学习</div>
          </div>
        </div>
      </section>

      {/* ===== 右侧登录 / 注册卡片 ===== */}
      <section className="login-panel glass">
        <div className="login-card">
          <div className="login-mobile-brand"><img src="/icons/app-icon.svg" alt="" /> 智服 SmartAssistant</div>

          <div className="login-tabs">
            <button
              type="button"
              className={`login-tab ${mode === 'login' ? 'active' : ''}`}
              onClick={() => switchMode('login')}
            >
              登录
            </button>
            <button
              type="button"
              className={`login-tab ${mode === 'register' ? 'active' : ''}`}
              onClick={() => switchMode('register')}
            >
              注册
            </button>
          </div>

          <form onSubmit={submit}>
            {mode === 'login' ? (
              <>
                <h2>登录工作台</h2>
                <p className="login-subtitle">欢迎回来，请使用账号登录</p>

                <label>账号</label>
                <div className="login-input-wrap">
                  <span className="li-pre"><AtSign size={16} /></span>
                  <input
                    value={username}
                    onChange={e => setUsername(e.target.value)}
                    autoComplete="username"
                    placeholder="请输入用户名"
                    required
                  />
                </div>

                <label>密码</label>
                <div className="login-input-wrap">
                  <span className="li-pre"><KeyRound size={16} /></span>
                  <input
                    type={showPwd ? 'text' : 'password'}
                    value={password}
                    onChange={e => setPassword(e.target.value)}
                    autoComplete="current-password"
                    placeholder="请输入登录密码"
                    required
                  />
                  <button
                    type="button"
                    className="li-toggle"
                    onClick={() => setShowPwd(v => !v)}
                  >
                    {showPwd ? '隐藏' : '显示'}
                  </button>
                </div>

                <div className="login-row">
                  <label className="login-remember">
                    <input
                      type="checkbox"
                      checked={remember}
                      onChange={e => setRemember(e.target.checked)}
                    />
                    记住我
                  </label>
                  <button type="button" className="login-forgot" onClick={openPasswordRecovery}>
                    忘记密码？
                  </button>
                </div>

                {error && <div className="login-error" role="alert">{error}</div>}

                <button className="login-submit" type="submit" disabled={loading}>
                  {loading ? '正在处理…' : '登 录'}
                </button>

                <div className="login-divider"><span>第三方账号登录</span></div>

                <div className="login-channels">
                  {oauthProviders.map(channel => (
                    <button
                      type="button"
                      className="login-channel"
                      key={channel.name}
                      disabled={!channel.enabled || loading}
                      title={channel.enabled ? `使用${channel.name}登录` : `${channel.name}尚未开通`}
                      onClick={() => beginSso(channel)}
                    >
                      <span className="lc-dot" style={{ background: channelColors[channel.id] }} />
                      <span>{channel.name}</span>
                      {!channel.enabled && <small>未开通</small>}
                    </button>
                  ))}
                </div>
                <p className="login-channel-note">
                  <Building2 size={13} /> 企业登录需管理员配置平台凭据与安全回调。
                </p>

                <button type="button" className="login-switch" onClick={() => switchMode('register')}>
                  还没有账号？立即注册
                </button>
              </>
            ) : (
              <>
                <h2>创建账号</h2>
                <p className="login-subtitle">填写信息，开通你的工作台</p>

                <label>用户名</label>
                <div className="login-input-wrap">
                  <span className="li-pre"><AtSign size={16} /></span>
                  <input
                    value={username}
                    onChange={e => setUsername(e.target.value)}
                    minLength={3}
                    maxLength={50}
                    autoComplete="username"
                    placeholder="请输入用户名（3-50 位）"
                    required
                  />
                </div>

                <label>邮箱（可选）</label>
                <div className="login-input-wrap">
                  <span className="li-pre"><Mail size={16} /></span>
                  <input
                    type="email"
                    value={email}
                    onChange={e => setEmail(e.target.value)}
                    autoComplete="email"
                    placeholder="name@example.com"
                  />
                </div>

                <label>设置密码</label>
                <div className="login-input-wrap">
                  <span className="li-pre"><KeyRound size={16} /></span>
                  <input
                    type={showPwd2 ? 'text' : 'password'}
                    value={password}
                    onChange={e => setPassword(e.target.value)}
                    minLength={6}
                    maxLength={100}
                    autoComplete="new-password"
                    placeholder="至少 6 位字符"
                    required
                  />
                  <button
                    type="button"
                    className="li-toggle"
                    onClick={() => setShowPwd2(v => !v)}
                  >
                    {showPwd2 ? '隐藏' : '显示'}
                  </button>
                </div>

                <label>确认密码</label>
                <div className="login-input-wrap">
                  <span className="li-pre"><ShieldCheck size={16} /></span>
                  <input
                    type={showPwd2 ? 'text' : 'password'}
                    value={confirm}
                    onChange={e => setConfirm(e.target.value)}
                    autoComplete="new-password"
                    placeholder="再次输入密码"
                    required
                  />
                </div>

                <label className="login-agree">
                  <input
                    type="checkbox"
                    checked={agreed}
                    onChange={e => setAgreed(e.target.checked)}
                  />
                  我已阅读并同意{' '}
                  <button type="button" className="login-inline-link" onClick={() => setHelpDialog('terms')}>
                    服务协议与隐私政策
                  </button>
                </label>

                {error && <div className="login-error" role="alert">{error}</div>}

                <button className="login-submit" type="submit" disabled={loading || !agreed}>
                  {loading ? '正在处理…' : '注 册'}
                </button>

                <button type="button" className="login-switch" onClick={() => switchMode('login')}>
                  已有账号？返回登录
                </button>
              </>
            )}
          </form>
        </div>
      </section>

      {helpDialog && (
        <div className="login-dialog-backdrop" role="presentation" onMouseDown={() => setHelpDialog(null)}>
          <section
            className="login-dialog glass-card"
            role="dialog"
            aria-modal="true"
            aria-labelledby="login-dialog-title"
            onMouseDown={event => event.stopPropagation()}
          >
            <button type="button" className="login-dialog-close" aria-label="关闭" onClick={() => setHelpDialog(null)}>×</button>
            {helpDialog === 'forgot' ? (
              <>
                <h3 id="login-dialog-title">找回登录密码</h3>
                <p>当前环境尚未配置自助重置地址。请联系系统管理员核验账号并重置密码。</p>
                <a className="login-dialog-action" href={`mailto:${env.VITE_SUPPORT_EMAIL || 'support@example.com'}?subject=SmartAssistant 密码重置申请`}>
                  联系管理员
                </a>
              </>
            ) : (
              <>
                <h3 id="login-dialog-title">服务协议与隐私政策</h3>
                <div className="login-dialog-copy">
                  <h4>服务协议</h4>
                  <p>请合法使用本工作台，不得利用智能体能力从事违法、侵权或破坏系统安全的活动。涉及订单、金额和关键业务操作时，应由用户再次确认。</p>
                  <h4>隐私政策</h4>
                  <p>系统仅为提供登录、会话与业务协同功能处理必要的账号和会话数据。请勿在对话中提交密码、验证码等高度敏感信息。</p>
                </div>
                <button type="button" className="login-dialog-action" onClick={() => { setAgreed(true); setHelpDialog(null); }}>
                  已阅读并同意
                </button>
              </>
            )}
          </section>
        </div>
      )}

      <DingTalkQrLoginDialog
        open={dingtalkQrOpen}
        returnTo={requestedPath}
        remember={remember}
        onClose={() => setDingtalkQrOpen(false)}
      />
    </div>
  );
}
