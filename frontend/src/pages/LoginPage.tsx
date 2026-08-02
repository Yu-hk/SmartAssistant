import { FormEvent, useState } from 'react';
import {
  ArrowRight,
  Eye,
  EyeOff,
  Headphones,
  LockKeyhole,
  ShieldCheck,
  UserRound,
} from 'lucide-react';
import { ApiError, auth } from '../api';
import { AuthUser, saveAuthSession } from '../authStorage';

interface LoginPageProps {
  onAuthenticated: (user: AuthUser) => void;
}

function getLoginError(error: unknown): string {
  if (error instanceof ApiError && error.body) {
    try {
      const payload = JSON.parse(error.body);
      return payload.message || payload.error || '用户名或密码错误';
    } catch {
      return '用户名或密码错误';
    }
  }
  return error instanceof Error ? error.message : '登录失败，请稍后重试';
}

export function LoginPage({ onAuthenticated }: LoginPageProps) {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [error, setError] = useState('');

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!username.trim() || !password) return;
    setError('');
    setIsSubmitting(true);
    try {
      const result = await auth.login({ username: username.trim(), password });
      const user: AuthUser = {
        userId: result.userId,
        username: result.username || username.trim(),
        email: result.email,
      };
      saveAuthSession({ token: result.token, refreshToken: result.refreshToken, user });
      onAuthenticated(user);
    } catch (loginError) {
      setError(getLoginError(loginError));
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <main className="login-page">
      <section className="login-shell" aria-label="智服中心登录">
        <div className="login-brand-panel">
          <div className="login-brand-mark"><Headphones size={28} /></div>
          <div>
            <span className="login-eyebrow">SMART CUSTOMER SERVICE</span>
            <h1>让每一次咨询<br />都有清晰回应</h1>
            <p>统一连接订单、物流、商品与售后服务，为客户提供安全、及时、连续的智能服务体验。</p>
          </div>
          <div className="login-trust-list">
            <span><ShieldCheck size={16} /> 登录后访问专属订单与会话</span>
            <span><Headphones size={16} /> 支持智能客服与人工协同</span>
          </div>
        </div>

        <div className="login-form-panel">
          <div className="login-mobile-brand">
            <span className="login-brand-mark"><Headphones size={22} /></span>
            <strong>智服中心</strong>
          </div>
          <div className="login-form-heading">
            <span>欢迎回来</span>
            <h2>登录客户服务中心</h2>
            <p>请使用您的服务账号继续访问</p>
          </div>

          <form onSubmit={handleSubmit} className="login-form">
            <label htmlFor="login-username">用户名</label>
            <div className="login-field">
              <UserRound size={18} />
              <input
                id="login-username"
                name="username"
                autoComplete="username"
                value={username}
                onChange={(event) => setUsername(event.target.value)}
                placeholder="请输入用户名"
                disabled={isSubmitting}
                autoFocus
              />
            </div>

            <label htmlFor="login-password">密码</label>
            <div className="login-field">
              <LockKeyhole size={18} />
              <input
                id="login-password"
                name="password"
                type={showPassword ? 'text' : 'password'}
                autoComplete="current-password"
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                placeholder="请输入密码"
                disabled={isSubmitting}
              />
              <button
                type="button"
                className="login-password-toggle"
                onClick={() => setShowPassword((visible) => !visible)}
                aria-label={showPassword ? '隐藏密码' : '显示密码'}
              >
                {showPassword ? <EyeOff size={18} /> : <Eye size={18} />}
              </button>
            </div>

            {error && <div className="login-error" role="alert">{error}</div>}

            <button
              type="submit"
              className="login-submit"
              disabled={!username.trim() || !password || isSubmitting}
            >
              {isSubmitting ? '正在登录…' : '登录并开始咨询'}
              {!isSubmitting && <ArrowRight size={18} />}
            </button>
          </form>

          <p className="login-help">没有账号或无法登录？请联系系统管理员</p>
        </div>
      </section>
    </main>
  );
}
