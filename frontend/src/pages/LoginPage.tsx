import { FormEvent, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { login, register, saveAuth } from '../api/auth';

export function LoginPage() {
  const navigate = useNavigate();
  const [mode, setMode] = useState<'login' | 'register'>('login');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [email, setEmail] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setError('');
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
      <section className="login-intro">
        <div className="login-brand"><span>S</span> SmartAssistant</div>
        <div className="login-kicker">MULTI-AGENT BUSINESS COPILOT</div>
        <h1>让多个智能体协同完成<br />真实业务任务</h1>
        <p>统一接入商品、订单、知识检索、推荐与通用问答能力，让复杂请求被理解、路由并执行。</p>
        <div className="login-capabilities">
          <span>订单服务</span><span>商品咨询</span><span>知识检索</span>
          <span>智能推荐</span><span>工具调用</span><span>通用问答</span>
        </div>
      </section>

      <section className="login-panel glass">
        <div className="login-card">
          <div className="login-mobile-brand"><span>S</span> SmartAssistant</div>
          <h2>{mode === 'login' ? '欢迎回来' : '创建账号'}</h2>
          <p className="login-subtitle">
            {mode === 'login' ? '登录后进入多智能体工作台' : '注册后即可开始使用智能助手'}
          </p>
          <form onSubmit={submit}>
            <label>用户名</label>
            <input value={username} onChange={e => setUsername(e.target.value)}
              minLength={3} maxLength={50} autoComplete="username"
              placeholder="请输入用户名" required />
            {mode === 'register' && (
              <>
                <label>邮箱（可选）</label>
                <input type="email" value={email} onChange={e => setEmail(e.target.value)}
                  autoComplete="email" placeholder="name@example.com" />
              </>
            )}
            <label>密码</label>
            <input type="password" value={password} onChange={e => setPassword(e.target.value)}
              minLength={6} maxLength={100}
              autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
              placeholder="至少 6 位字符" required />
            {error && <div className="login-error" role="alert">{error}</div>}
            <button className="login-submit" type="submit" disabled={loading}>
              {loading ? '正在处理…' : mode === 'login' ? '登录并进入工作台' : '注册并进入工作台'}
            </button>
          </form>
          <button className="login-switch" onClick={() => {
            setMode(mode === 'login' ? 'register' : 'login');
            setError('');
          }}>
            {mode === 'login' ? '没有账号？立即注册' : '已有账号？返回登录'}
          </button>
        </div>
      </section>
    </div>
  );
}
