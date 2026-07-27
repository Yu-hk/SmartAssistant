import { FormEvent, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { login, register, saveAuth } from '../api/auth';
import { Users, Activity, Wifi, Eye, EyeOff } from 'lucide-react';

export function LoginPage() {
  const navigate = useNavigate();
  const [mode, setMode] = useState<'login' | 'register'>('login');

  // 登录字段
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [showPwd, setShowPwd] = useState(false);
  const [remember, setRemember] = useState(true);

  // 注册字段
  const [email, setEmail] = useState('');
  const [confirm, setConfirm] = useState('');
  const [showPwd2, setShowPwd2] = useState(false);
  const [agreed, setAgreed] = useState(true);

  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

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
    setLoading(true);
    try {
      const user = mode === 'login'
        ? await login(username.trim(), password)
        : await register(username.trim(), password, email.trim());
      saveAuth(user);
      navigate('/', { replace: true });
    } catch (err) {
      setError(err instanceof Error ? err.message : '操作失败，请稍后重试');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="login-page">
      {/* ===== 左侧品牌叙事 ===== */}
      <section className="login-intro">
        <div className="login-brand"><span>智</span> 智服 SmartAssistant</div>
        <div className="login-kicker">MULTI-AGENT CUSTOMER SERVICE</div>
        <h1>让每个客户问题，<br />都有 <span className="accent">智能体接力</span> 解决</h1>
        <p>
          售前、订单、技术支持、投诉处理多智能体协同作业，实时洞察客户情绪与意图，
          全渠道接入，让服务又快又准。
        </p>

        <div className="login-values">
          <div className="login-value">
            <div className="lv-icon lv1"><Users size={18} /></div>
            <div>
              <div className="lv-t">智能体团队协同</div>
              <div className="lv-d">按意图自动路由，多智能体并行处理复杂工单</div>
            </div>
          </div>
          <div className="login-value">
            <div className="lv-icon lv2"><Activity size={18} /></div>
            <div>
              <div className="lv-t">实时会话洞察</div>
              <div className="lv-d">客户画像、情绪、意图与知识命中一目了然</div>
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
            <div className="ls-num">12,800<span>+</span></div>
            <div className="ls-label">已服务企业</div>
          </div>
          <div>
            <div className="ls-num">96.4%</div>
            <div className="ls-label">一次解决率</div>
          </div>
          <div>
            <div className="ls-num">1.8s</div>
            <div className="ls-label">平均响应</div>
          </div>
        </div>
      </section>

      {/* ===== 右侧登录 / 注册卡片 ===== */}
      <section className="login-panel glass">
        <div className="login-card">
          <div className="login-mobile-brand"><span>智</span> 智服 SmartAssistant</div>

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
                  <span className="li-pre">@</span>
                  <input
                    value={username}
                    onChange={e => setUsername(e.target.value)}
                    autoComplete="username"
                    placeholder="手机号 / 邮箱 / 工号"
                    required
                  />
                </div>

                <label>密码</label>
                <div className="login-input-wrap">
                  <span className="li-pre">🔒</span>
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
                  <a className="login-forgot" href="#">忘记密码？</a>
                </div>

                {error && <div className="login-error" role="alert">{error}</div>}

                <button className="login-submit" type="submit" disabled={loading}>
                  {loading ? '正在处理…' : '登 录'}
                </button>

                <div className="login-divider"><span>或</span></div>

                <div className="login-channels">
                  <button type="button" className="login-channel">
                    <span className="lc-dot" style={{ background: '#2ecc71' }}></span>企业微信
                  </button>
                  <button type="button" className="login-channel">
                    <span className="lc-dot" style={{ background: '#3370ff' }}></span>钉钉
                  </button>
                  <button type="button" className="login-channel">
                    <span className="lc-dot" style={{ background: '#00d6b9' }}></span>飞书
                  </button>
                </div>

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
                  <span className="li-pre">@</span>
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
                  <span className="li-pre">✉</span>
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
                  <span className="li-pre">🔒</span>
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
                  <span className="li-pre">🔒</span>
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
                  我已阅读并同意 <a href="#">服务协议与隐私政策</a>
                </label>

                {error && <div className="login-error" role="alert">{error}</div>}

                <button className="login-submit" type="submit" disabled={loading}>
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
    </div>
  );
}
