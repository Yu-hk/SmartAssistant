import { useState, useEffect, useCallback, useRef } from 'react';
import { Routes, Route, Navigate, useNavigate, useParams, useLocation } from 'react-router-dom';
import '@tdesign-react/chat/es/style/index.js';

import { useTheme } from './hooks/useTheme';
import { useSessions } from './hooks/useSessions';
import { useChat } from './hooks/useChat';

import { CustomerSidebar } from './components/CustomerSidebar';
import { InsightPanel } from './components/InsightPanel';
import { CustomerChatPage } from './pages/CustomerChatPage';
import { AdminPage } from './pages/AdminPage';
import { LoginPage } from './pages/LoginPage';
import {
  clearAuth,
  getAuthToken,
  getAuthUser,
  getCurrentUser,
  logout,
} from './api/auth';
import { LogOut, Menu, MessageSquareText, ShieldCheck, UserRound } from 'lucide-react';

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
  return (
    <>
      <ParticleBackground />
      <Routes>
        <Route path="/login" element={
          getAuthToken()
            ? <Navigate to="/" replace />
            : <LoginPage />
        } />
        <Route path="/" element={<AuthRoute><AppContent /></AuthRoute>} />
        <Route path="/chat/:sessionId" element={<AuthRoute><AppContent /></AuthRoute>} />
        <Route path="/admin" element={<AuthRoute requireAdmin><AppContent /></AuthRoute>} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </>
  );
}

function AuthRoute({
  children,
  requireAdmin = false,
}: {
  children: React.ReactNode;
  requireAdmin?: boolean;
}) {
  const location = useLocation();
  const [state, setState] = useState<'checking' | 'ready' | 'unauthenticated' | 'forbidden'>('checking');

  useEffect(() => {
    let active = true;
    if (!getAuthToken()) {
      setState('unauthenticated');
      return () => { active = false; };
    }

    getCurrentUser()
      .then(profile => {
        if (!active) return;
        setState(requireAdmin && profile.role !== 'ROLE_ADMIN' ? 'forbidden' : 'ready');
      })
      .catch(() => {
        if (!active) return;
        const cached = getAuthUser();
        if (!getAuthToken()) setState('unauthenticated');
        // 管理路由必须服务端校验成功，不能信任可被本地修改的角色缓存。
        else if (requireAdmin) setState('forbidden');
        else setState('ready');
      });

    return () => { active = false; };
  }, [requireAdmin]);

  if (state === 'checking') {
    return (
      <div className="auth-loading" role="status" aria-live="polite">
        <span className="auth-loading-mark">智</span>
        <span>正在验证登录状态…</span>
      </div>
    );
  }
  if (state === 'unauthenticated') {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  }
  if (state === 'forbidden') return <Navigate to="/" replace />;
  return <>{children}</>;
}

function AppContent() {
  const navigate = useNavigate();
  const { sessionId: urlSessionId } = useParams<{ sessionId: string }>();
  const location = useLocation();
  const isAdmin = location.pathname === '/admin';
  const authUser = getAuthUser();
  const [sidebarOpen, setSidebarOpen] = useState(false);

  const { theme, toggleTheme } = useTheme();
  const {
    sessions, setSessions,
    currentSessionId, setCurrentSessionId,
    currentSession,
    fetchSessions, deleteSession, createSession, closeSession, rateSession,
    updateSession,
  } = useSessions();

  const {
    isLoading, inputValue, setInputValue,
    permissionRequest, faqSuggestions,
    sendMessage, handleStop,
    handlePermissionAllow, handlePermissionDeny,
    queuePosition, queueEstimatedWait,
    locationEnabled, locationStatus, setLocationEnabled,
  } = useChat({
    currentSession,
    currentSessionId,
    selectedModel: 'deepseek-v4-flash',
    setSessions,
    setCurrentSessionId,
  });

  // URL 同步
  useEffect(() => {
    if (isAdmin) return;
    const routeSessionId = urlSessionId || null;
    setCurrentSessionId(previous =>
      previous === routeSessionId ? previous : routeSessionId
    );
  }, [urlSessionId, isAdmin, setCurrentSessionId]);

  // 初始加载
  useEffect(() => { fetchSessions(); }, [fetchSessions]);

  const handleNewChat = useCallback(() => {
    const sessionId = createSession();
    setInputValue('');
    setSidebarOpen(false);
    navigate(`/chat/${sessionId}`);
  }, [createSession, navigate, setInputValue]);

  const handleSelectAgent = useCallback((agentName: string) => {
    const sessionId = createSession(`与${agentName}的新对话`);
    setInputValue(`${agentName}，请协助我处理：`);
    setSidebarOpen(false);
    navigate(`/chat/${sessionId}`);
  }, [createSession, navigate, setInputValue]);

  const handleSelectSession = useCallback((sessionId: string) => {
    setCurrentSessionId(sessionId);
    setInputValue('');
    setSidebarOpen(false);
    navigate(`/chat/${sessionId}`);
  }, [navigate, setCurrentSessionId, setInputValue]);

  const handleDeleteSession = useCallback(async (sessionId: string) => {
    const navigateTo = await deleteSession(sessionId);
    if (navigateTo) navigate(navigateTo);
  }, [deleteSession, navigate]);

  const handleRateSession = useCallback((score: number) => {
    if (!currentSessionId) return;
    void rateSession(currentSessionId, score);
  }, [currentSessionId, rateSession]);

  const handleCloseSession = useCallback(() => {
    if (!currentSessionId || currentSession?.status === 'closed') return;
    void closeSession(currentSessionId);
  }, [closeSession, currentSession?.status, currentSessionId]);

  return (
    <div className="workbench-shell relative z-10">
      {!isAdmin && (
        <CustomerSidebar
          sessions={sessions}
          currentSessionId={currentSessionId}
          theme={theme}
          onNewChat={handleNewChat}
          onSelectSession={handleSelectSession}
          onDeleteSession={handleDeleteSession}
          onSelectAgent={handleSelectAgent}
          onOpenAdmin={getAuthUser()?.role === 'ROLE_ADMIN'
            ? () => navigate('/admin')
            : undefined}
          onToggleTheme={toggleTheme}
          isOpen={sidebarOpen}
          onClose={() => setSidebarOpen(false)}
        />
      )}

      {!isAdmin && sidebarOpen && (
        <button
          type="button"
          className="sidebar-backdrop"
          aria-label="关闭侧边栏"
          onClick={() => setSidebarOpen(false)}
        />
      )}

      <main className="workbench-main flex-1 flex flex-col min-w-0 relative">
        {isAdmin ? (
          <AdminPage onBack={() => navigate('/')} />
        ) : (
          <>
            <header className="workbench-header glass">
              <button
                type="button"
                className="header-icon-button mobile-menu-button"
                aria-label="打开侧边栏"
                onClick={() => setSidebarOpen(true)}
              >
                <Menu size={19} />
              </button>
              <div className="header-context-icon"><MessageSquareText size={18} /></div>
              <div className="header-context">
                <strong>{currentSession ? currentSession.title : '智能业务工作台'}</strong>
                <span>{currentSession ? '当前会话' : '新建任务并交给合适的能力处理'}</span>
              </div>
              <div className="header-capability"><ShieldCheck size={14} /> 多智能体协同</div>
              <div className="header-actions">
                {currentSession && currentSession.status !== 'closed' && (
                  <button
                    type="button"
                    onClick={handleCloseSession}
                    className="header-text-button"
                  >
                    结束对话
                  </button>
                )}
                <div className="header-user">
                  <span className="header-avatar"><UserRound size={15} /></span>
                  <span>
                    <strong>{authUser?.username || '用户'}</strong>
                    <small>{authUser?.role === 'ROLE_ADMIN' ? '管理员' : '普通用户'}</small>
                  </span>
                </div>
                <button
                  type="button"
                  className="header-icon-button"
                  title="退出登录"
                  aria-label="退出登录"
                  onClick={async () => {
                    try {
                      await logout();
                    } catch {
                      clearAuth();
                    } finally {
                      window.location.replace('/login');
                    }
                  }}
                >
                  <LogOut size={17} />
                </button>
              </div>
            </header>

            {/* 聊天区域 */}
            <CustomerChatPage
              currentSession={currentSession}
              isLoading={isLoading}
              inputValue={inputValue}
              permissionRequest={permissionRequest}
              faqSuggestions={faqSuggestions}
              queuePosition={queuePosition}
              queueEstimatedWait={queueEstimatedWait}
              locationEnabled={locationEnabled}
              locationStatus={locationStatus}
              onSendMessage={sendMessage}
              onStop={handleStop}
              onInputChange={setInputValue}
              onPermissionAllow={handlePermissionAllow}
              onPermissionDeny={handlePermissionDeny}
              onRateSession={handleRateSession}
              onLocationEnabledChange={setLocationEnabled}
              userName={authUser?.username}
            />
          </>
        )}
      </main>

      {/* 右栏 — 实时会话洞察 */}
      {!isAdmin && currentSession && currentSession.messages.length > 0 && (
        <InsightPanel
          session={currentSession}
          userName={authUser?.username}
          userId={authUser?.userId}
        />
      )}
    </div>
  );
}

export default App;
