import { useState, useEffect, useCallback, useRef } from 'react';
import { Routes, Route, useNavigate, useLocation } from 'react-router-dom';
import '@tdesign-react/chat/es/style/index.js';

import { useTheme } from './hooks/useTheme';
import { useSessions } from './hooks/useSessions';
import { useChat } from './hooks/useChat';

import { CustomerSidebar } from './components/CustomerSidebar';
import { CustomerChatPage } from './pages/CustomerChatPage';
import { AdminPage } from './pages/AdminPage';
import { LoginPage } from './pages/LoginPage';
import { auth } from './api';
import {
  AUTH_CHANGED_EVENT,
  AuthUser,
  clearAuthSession,
  readAccessToken,
  readStoredUser,
} from './authStorage';

// ===================================================
// 🌌 粒子背景系统 — Canvas 动态科技背景
// ===================================================
function ParticleBackground() {
  const canvasRef = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;

    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    let animId: number;
    let particles: Array<{
      x: number; y: number; vx: number; vy: number;
      radius: number; alpha: number; alphaSpeed: number;
    }> = [];

    const resize = () => {
      canvas.width = window.innerWidth;
      canvas.height = window.innerHeight;
    };
    resize();
    window.addEventListener('resize', resize);

    // 创建粒子
    const count = Math.min(40, Math.floor(window.innerWidth / 30));
    particles = Array.from({ length: count }, () => ({
      x: Math.random() * canvas.width,
      y: Math.random() * canvas.height,
      vx: (Math.random() - 0.5) * 0.3,
      vy: (Math.random() - 0.5) * 0.3,
      radius: Math.random() * 2 + 0.5,
      alpha: Math.random() * 0.5 + 0.1,
      alphaSpeed: (Math.random() - 0.5) * 0.005,
    }));

    const isDark = document.documentElement.classList.contains('dark');
    const particleColor = isDark ? '99, 102, 241' : '79, 70, 229';

    const animate = () => {
      ctx.clearRect(0, 0, canvas.width, canvas.height);

      const isDarkNow = document.documentElement.classList.contains('dark');
      const color = isDarkNow ? '99, 102, 241' : '79, 70, 229';

      // 更新和绘制粒子
      particles.forEach((p, i) => {
        p.x += p.vx;
        p.y += p.vy;
        p.alpha += p.alphaSpeed;
        if (p.alpha > 0.6 || p.alpha < 0.05) p.alphaSpeed *= -1;

        // 边界回弹
        if (p.x < 0 || p.x > canvas.width) p.vx *= -1;
        if (p.y < 0 || p.y > canvas.height) p.vy *= -1;

        // 绘制粒子
        ctx.beginPath();
        ctx.arc(p.x, p.y, p.radius, 0, Math.PI * 2);
        ctx.fillStyle = `rgba(${color}, ${p.alpha})`;
        ctx.fill();

        // 光晕
        const gradient = ctx.createRadialGradient(
          p.x, p.y, 0, p.x, p.y, p.radius * 6
        );
        gradient.addColorStop(0, `rgba(${color}, ${p.alpha * 0.3})`);
        gradient.addColorStop(1, `rgba(${color}, 0)`);
        ctx.beginPath();
        ctx.arc(p.x, p.y, p.radius * 6, 0, Math.PI * 2);
        ctx.fillStyle = gradient;
        ctx.fill();

        // 连接线
        for (let j = i + 1; j < particles.length; j++) {
          const dx = p.x - particles[j].x;
          const dy = p.y - particles[j].y;
          const dist = Math.sqrt(dx * dx + dy * dy);
          if (dist < 150) {
            ctx.beginPath();
            ctx.moveTo(p.x, p.y);
            ctx.lineTo(particles[j].x, particles[j].y);
            ctx.strokeStyle = `rgba(${color}, ${0.06 * (1 - dist / 150)})`;
            ctx.lineWidth = 0.5;
            ctx.stroke();
          }
        }
      });

      animId = requestAnimationFrame(animate);
    };

    animate();

    return () => {
      cancelAnimationFrame(animId);
      window.removeEventListener('resize', resize);
    };
  }, []);

  return (
    <canvas
      ref={canvasRef}
      className="particle-bg"
      style={{ position: 'fixed', inset: 0, pointerEvents: 'none', zIndex: 0 }}
    />
  );
}

// ===================================================
// 主应用
// ===================================================
function App() {
  const navigate = useNavigate();
  const [isCheckingAuth, setIsCheckingAuth] = useState(true);
  const [authenticatedUser, setAuthenticatedUser] = useState<AuthUser | null>(() => readStoredUser());

  useEffect(() => {
    let active = true;
    const verifySession = async () => {
      if (!readAccessToken()) {
        if (active) {
          setAuthenticatedUser(null);
          setIsCheckingAuth(false);
        }
        return;
      }
      try {
        const user = await auth.me();
        if (active) setAuthenticatedUser(user);
      } catch {
        clearAuthSession();
        if (active) setAuthenticatedUser(null);
      } finally {
        if (active) setIsCheckingAuth(false);
      }
    };

    const handleAuthChanged = () => {
      if (!readAccessToken()) setAuthenticatedUser(null);
    };

    window.addEventListener(AUTH_CHANGED_EVENT, handleAuthChanged);
    void verifySession();
    return () => {
      active = false;
      window.removeEventListener(AUTH_CHANGED_EVENT, handleAuthChanged);
    };
  }, []);

  if (isCheckingAuth) {
    return (
      <div className="auth-checking" role="status">
        <span className="auth-checking__mark">智</span>
        <span>正在验证登录状态…</span>
      </div>
    );
  }

  if (!authenticatedUser) {
    return <LoginPage onAuthenticated={(user) => {
      setAuthenticatedUser(user);
      navigate('/', { replace: true });
    }} />;
  }

  const handleLogout = () => {
    clearAuthSession();
    setAuthenticatedUser(null);
    navigate('/', { replace: true });
  };

  return (
    <Routes>
      <Route path="*" element={<AppContent user={authenticatedUser} onLogout={handleLogout} />} />
    </Routes>
  );
}

interface AppContentProps {
  user: AuthUser;
  onLogout: () => void;
}

function AppContent({ user, onLogout }: AppContentProps) {
  const navigate = useNavigate();
  const location = useLocation();
  const [mobileSidebarOpen, setMobileSidebarOpen] = useState(false);
  const chatPathMatch = location.pathname.match(/^\/chat\/([^/]+)$/);
  const urlSessionId = chatPathMatch ? decodeURIComponent(chatPathMatch[1]) : undefined;
  const isAdmin = location.pathname === '/admin';

  const { theme, toggleTheme } = useTheme();
  const {
    sessions, setSessions,
    currentSessionId, setCurrentSessionId,
    currentSession,
    fetchSessions, deleteSession,
  } = useSessions();

  const {
    isLoading, inputValue, setInputValue,
    permissionRequest, faqSuggestions,
    sendMessage, handleStop,
    handlePermissionAllow, handlePermissionDeny,
    queuePosition, queueEstimatedWait,
  } = useChat({
    currentSession,
    currentSessionId,
    selectedModel: 'claude-sonnet-4',
    setSessions,
    setCurrentSessionId,
  });

  // URL 同步
  useEffect(() => {
    if (urlSessionId && urlSessionId !== currentSessionId) {
      setCurrentSessionId(urlSessionId);
    } else if (!urlSessionId && !isAdmin && currentSessionId) {
      setCurrentSessionId(null);
    }
  }, [urlSessionId, isAdmin, currentSessionId, setCurrentSessionId]);

  // 初始加载
  useEffect(() => { fetchSessions(); }, [fetchSessions]);

  const handleNewChat = useCallback(() => {
    setMobileSidebarOpen(false);
    setCurrentSessionId(null);
    navigate('/');
  }, [navigate, setCurrentSessionId]);

  const handleSelectSession = useCallback((sessionId: string) => {
    setMobileSidebarOpen(false);
    setCurrentSessionId(sessionId);
    navigate(`/chat/${sessionId}`);
  }, [navigate, setCurrentSessionId]);

  const handleDeleteSession = useCallback(async (sessionId: string) => {
    const navigateTo = await deleteSession(sessionId);
    if (navigateTo) navigate(navigateTo);
  }, [deleteSession, navigate]);

  return (
    <div className="customer-app-shell relative z-10">
      {!isAdmin && (
        <>
          <button
            type="button"
            className={`customer-sidebar-backdrop${mobileSidebarOpen ? ' customer-sidebar-backdrop--visible' : ''}`}
            aria-label="关闭咨询记录"
            onClick={() => setMobileSidebarOpen(false)}
          />
          <CustomerSidebar
            sessions={sessions}
            currentSessionId={currentSessionId}
            theme={theme}
            mobileOpen={mobileSidebarOpen}
            onMobileClose={() => setMobileSidebarOpen(false)}
            onNewChat={handleNewChat}
            onSelectSession={handleSelectSession}
            onDeleteSession={handleDeleteSession}
            onOpenAdmin={() => navigate('/admin')}
            onToggleTheme={toggleTheme}
            username={user.username}
            onLogout={onLogout}
          />
        </>
      )}

      <main className="customer-main flex-1 flex flex-col min-w-0 relative">
        {isAdmin ? (
          <AdminPage onBack={() => navigate('/')} />
        ) : (
          <>
            {/* 客服状态栏 */}
            <div className="customer-topbar glass">
              <button
                type="button"
                className="customer-mobile-menu"
                aria-label="打开咨询记录"
                onClick={() => setMobileSidebarOpen(true)}
              >
                <svg width="19" height="19" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
                  <line x1="4" y1="7" x2="20" y2="7" />
                  <line x1="4" y1="12" x2="20" y2="12" />
                  <line x1="4" y1="17" x2="20" y2="17" />
                </svg>
              </button>
              {/* 客服标识 */}
              <div className="customer-topbar__avatar">
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                  <path d="M4 14a8 8 0 0 1 16 0" />
                  <path d="M18 19c0 1.1-.9 2-2 2h-3" />
                  <path d="M4 14v3a2 2 0 0 0 2 2h1v-7H6a2 2 0 0 0-2 2Z" />
                  <path d="M20 14v3a2 2 0 0 1-2 2h-1v-7h1a2 2 0 0 1 2 2Z" />
                </svg>
              </div>
              <div className="customer-topbar__identity">
                <strong>小智客服</strong>
                <span><i aria-hidden="true" /> 在线 · 通常几秒内回复</span>
              </div>
              {currentSession && (
                <div className="customer-topbar__session">
                  {currentSession.title.slice(0, 24)}
                </div>
              )}
              <button type="button" className="customer-topbar__new" onClick={handleNewChat}>
                <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round">
                  <line x1="12" y1="5" x2="12" y2="19" />
                  <line x1="5" y1="12" x2="19" y2="12" />
                </svg>
                新咨询
              </button>
            </div>

            {/* 聊天区域 */}
            <CustomerChatPage
              currentSession={currentSession}
              isLoading={isLoading}
              inputValue={inputValue}
              permissionRequest={permissionRequest}
              faqSuggestions={faqSuggestions}
              queuePosition={queuePosition}
              queueEstimatedWait={queueEstimatedWait}
              onSendMessage={sendMessage}
              onStop={handleStop}
              onInputChange={setInputValue}
              onPermissionAllow={handlePermissionAllow}
              onPermissionDeny={handlePermissionDeny}
            />
          </>
        )}
      </main>
    </div>
  );
}

export default App;
