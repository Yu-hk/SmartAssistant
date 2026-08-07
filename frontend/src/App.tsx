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
import { clearAuth, getAuthToken, getAuthUser } from './api/auth';

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
        <Route path="/" element={<ProtectedRoute><AppContent /></ProtectedRoute>} />
        <Route path="/chat/:sessionId" element={<ProtectedRoute><AppContent /></ProtectedRoute>} />
        <Route path="/admin" element={<AdminRoute><AppContent /></AdminRoute>} />
      </Routes>
    </>
  );
}

function ProtectedRoute({ children }: { children: React.ReactNode }) {
  return getAuthToken()
    ? <>{children}</>
    : <Navigate to="/login" replace />;
}

function AdminRoute({ children }: { children: React.ReactNode }) {
  if (!getAuthToken()) return <Navigate to="/login" replace />;
  return getAuthUser()?.role === 'ROLE_ADMIN'
    ? <>{children}</>
    : <Navigate to="/" replace />;
}

function AppContent() {
  const navigate = useNavigate();
  const { sessionId: urlSessionId } = useParams<{ sessionId: string }>();
  const location = useLocation();
  const isAdmin = location.pathname === '/admin';

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
    navigate(`/chat/${sessionId}`);
  }, [createSession, navigate, setInputValue]);

  const handleSelectAgent = useCallback((agentName: string) => {
    const sessionId = createSession(`与${agentName}的新对话`);
    setInputValue(`${agentName}，请协助我处理：`);
    navigate(`/chat/${sessionId}`);
  }, [createSession, navigate, setInputValue]);

  const handleSelectSession = useCallback((sessionId: string) => {
    setCurrentSessionId(sessionId);
    setInputValue('');
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
    <div className="relative z-10" style={{
      display: 'flex',
      height: '100vh',
      width: '100vw',
      overflow: 'hidden',
    }}>
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
        />
      )}

      <main className="flex-1 flex flex-col min-w-0 relative">
        {isAdmin ? (
          <AdminPage onBack={() => navigate('/')} />
        ) : (
          <>
            {/* 顶部栏 — 玻璃效果 */}
            <div className="glass" style={{
              display: 'flex',
              alignItems: 'center',
              padding: '0 20px',
              height: '60px',
              borderBottom: '1px solid var(--nova-border)',
              flexShrink: 0,
              gap: '12px',
              zIndex: 10,
            }}>
              {/* 品牌标识 */}
              <div style={{
                width: '32px', height: '32px', borderRadius: '10px',
                background: 'linear-gradient(135deg, var(--nova-accent), var(--nova-secondary))',
                display: 'flex', alignItems: 'center', justifyContent: 'center',
                fontSize: '14px', fontWeight: 800, color: 'white',
                flexShrink: 0,
                boxShadow: '0 0 16px var(--nova-accent-glow)',
              }}>
                  智
              </div>
              <div>
                <div style={{
                  fontSize: '15px', fontWeight: 700,
                  color: 'var(--nova-text-primary)', lineHeight: 1.2,
                  letterSpacing: '0.02em',
                }}>
                  智服 SmartAssistant
                </div>
                <div style={{
                  fontSize: '11px', color: 'var(--nova-secondary)',
                  display: 'flex', alignItems: 'center', gap: '5px',
                }}>
                  <span style={{
                    width: '6px', height: '6px', borderRadius: '50%',
                    background: 'var(--nova-secondary)', display: 'inline-block',
                    boxShadow: '0 0 8px var(--nova-secondary)',
                  }}></span>
                  多智能体客服工作台
                </div>
              </div>

              {/* 协同状态药丸 */}
              <div style={{
                marginLeft: '8px',
                display: 'flex', alignItems: 'center', gap: '6px',
                padding: '5px 12px', borderRadius: '100px',
                background: 'var(--nova-secondary-light, rgba(6,182,212,0.12))',
                fontSize: '12px', color: 'var(--nova-secondary)',
              }}>
                <span style={{
                  width: '7px', height: '7px', borderRadius: '50%',
                  background: 'var(--nova-secondary)',
                  boxShadow: '0 0 8px var(--nova-secondary)',
                  animation: 'breathe 3s ease-in-out infinite',
                }}></span>
                智能体协同处理中
              </div>
              {currentSession && (
                <div style={{
                  marginLeft: '12px',
                  padding: '4px 12px',
                  borderRadius: '100px',
                  background: 'var(--nova-accent-light)',
                  fontSize: '12px',
                  color: 'var(--nova-accent)',
                  fontWeight: 500,
                  maxWidth: '240px',
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                  whiteSpace: 'nowrap',
                }}>
                  {currentSession.title.slice(0, 24)}
                </div>
              )}
              <div style={{ marginLeft: 'auto', display: 'flex', alignItems: 'center', gap: '10px' }}>
                {currentSession && currentSession.status !== 'closed' && (
                  <button
                    type="button"
                    onClick={handleCloseSession}
                    style={{
                      padding: '6px 10px', borderRadius: '8px',
                      border: '1px solid var(--nova-border)',
                      background: 'var(--nova-bg-component)',
                      color: 'var(--nova-text-secondary)', cursor: 'pointer',
                      fontSize: '12px',
                    }}
                  >
                    结束对话
                  </button>
                )}
                <span style={{ fontSize: '12px', color: 'var(--nova-text-secondary)' }}>
                  {getAuthUser()?.username || '用户'}
                </span>
                <button
                  onClick={() => {
                    clearAuth();
                    window.location.replace('/login');
                  }}
                  style={{
                    padding: '6px 10px', borderRadius: '8px',
                    border: '1px solid var(--nova-border)',
                    background: 'var(--nova-bg-component)',
                    color: 'var(--nova-text-secondary)', cursor: 'pointer',
                    fontSize: '12px',
                  }}
                >
                  退出登录
                </button>
              </div>
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
              onRateSession={handleRateSession}
            />
          </>
        )}
      </main>

      {/* 右栏 — 实时会话洞察 */}
      {!isAdmin && (
        <InsightPanel
          session={currentSession}
          userName={getAuthUser()?.username}
          userId={getAuthUser()?.userId}
        />
      )}
    </div>
  );
}

export default App;
