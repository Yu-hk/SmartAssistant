import { useEffect, useState } from 'react';
import { NavLink, useLocation } from 'react-router-dom';
import {
  BarChart3,
  BookOpenText,
  ChevronRight,
  Database,
  LogOut,
  Menu,
  MessageSquareText,
  Moon,
  RefreshCw,
  ShieldCheck,
  Sun,
  UserRound,
  X,
} from 'lucide-react';
import { clearAuth, logout, type AuthUser } from '../api/auth';
import type { Theme } from '../types';

interface AdminLayoutProps {
  user: AuthUser | null;
  theme: Theme;
  refreshVersion: number;
  onRefresh: () => void;
  onToggleTheme: () => void;
  children: React.ReactNode;
}

const NAV_ITEMS = [
  { to: '/admin/overview', label: '数据总览', description: '运营与质量指标', icon: BarChart3 },
  { to: '/admin/conversations', label: '用户对话', description: '全局会话与审计', icon: MessageSquareText },
  { to: '/admin/knowledge', label: '知识库', description: '问答内容维护', icon: BookOpenText },
];

const PAGE_TITLES: Record<string, string> = {
  '/admin/overview': '数据总览',
  '/admin/conversations': '用户对话',
  '/admin/knowledge': '知识库',
};

export function AdminLayout({
  user,
  theme,
  refreshVersion,
  onRefresh,
  onToggleTheme,
  children,
}: AdminLayoutProps) {
  const location = useLocation();
  const [mobileNavigationOpen, setMobileNavigationOpen] = useState(false);

  useEffect(() => setMobileNavigationOpen(false), [location.pathname]);

  const handleLogout = async () => {
    try {
      await logout();
    } catch {
      clearAuth();
    } finally {
      window.location.replace('/login');
    }
  };

  const sidebar = (
    <aside className={`admin-sidebar ${mobileNavigationOpen ? 'is-open' : ''}`} aria-label="管理后台导航">
      <div className="admin-brand">
        <span className="admin-brand-mark" aria-hidden="true">智</span>
        <span>
          <strong>SmartAssistant</strong>
          <small>管理控制台</small>
        </span>
        <button
          type="button"
          className="admin-icon-button admin-mobile-close"
          aria-label="关闭管理导航"
          onClick={() => setMobileNavigationOpen(false)}
        >
          <X size={18} />
        </button>
      </div>

      <div className="admin-environment-card">
        <span className="admin-live-dot" aria-hidden="true" />
        <span><strong>生产环境</strong><small>全局管理视图</small></span>
      </div>

      <nav className="admin-navigation">
        <span className="admin-nav-caption">控制台</span>
        {NAV_ITEMS.map(item => {
          const Icon = item.icon;
          return (
            <NavLink
              key={item.to}
              to={item.to}
              className={({ isActive }) => `admin-nav-item ${isActive ? 'active' : ''}`}
            >
              <span className="admin-nav-icon"><Icon size={18} aria-hidden="true" /></span>
              <span><strong>{item.label}</strong><small>{item.description}</small></span>
              <ChevronRight size={15} className="admin-nav-arrow" aria-hidden="true" />
            </NavLink>
          );
        })}
      </nav>

      <div className="admin-sidebar-footer">
        <div className="admin-identity-card">
          <span className="admin-avatar"><UserRound size={16} aria-hidden="true" /></span>
          <span><strong>{user?.username || '管理员'}</strong><small>系统管理员</small></span>
          <ShieldCheck size={16} className="admin-verified" aria-label="管理员身份已验证" />
        </div>
      </div>
    </aside>
  );

  return (
    <div className="admin-shell">
      <a className="admin-skip-link" href="#admin-main">跳到主要内容</a>
      {sidebar}
      {mobileNavigationOpen && (
        <button
          type="button"
          className="admin-sidebar-backdrop"
          aria-label="关闭管理导航"
          onClick={() => setMobileNavigationOpen(false)}
        />
      )}

      <div className="admin-content-shell">
        <header className="admin-topbar">
          <div className="admin-topbar-context">
            <button
              type="button"
              className="admin-icon-button admin-menu-button"
              aria-label="打开管理导航"
              aria-expanded={mobileNavigationOpen}
              onClick={() => setMobileNavigationOpen(true)}
            >
              <Menu size={19} />
            </button>
            <span className="admin-topbar-icon"><Database size={17} aria-hidden="true" /></span>
            <span><strong>{PAGE_TITLES[location.pathname] || '管理控制台'}</strong><small>仅管理员可见</small></span>
          </div>

          <div className="admin-topbar-actions">
            <span className="admin-scope-badge"><ShieldCheck size={13} /> 全局数据</span>
            <button
              key={refreshVersion}
              type="button"
              className="admin-icon-button"
              title="刷新当前页面数据"
              aria-label="刷新当前页面数据"
              onClick={onRefresh}
            >
              <RefreshCw size={17} />
            </button>
            <button
              type="button"
              className="admin-icon-button"
              title={theme === 'dark' ? '切换为浅色主题' : '切换为深色主题'}
              aria-label={theme === 'dark' ? '切换为浅色主题' : '切换为深色主题'}
              onClick={onToggleTheme}
            >
              {theme === 'dark' ? <Sun size={17} /> : <Moon size={17} />}
            </button>
            <button
              type="button"
              className="admin-icon-button"
              title="退出登录"
              aria-label="退出登录"
              onClick={handleLogout}
            >
              <LogOut size={17} />
            </button>
          </div>
        </header>

        <main id="admin-main" className="admin-main" tabIndex={-1}>{children}</main>
      </div>
    </div>
  );
}
