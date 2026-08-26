import { useState, useEffect, useCallback } from 'react';
import { Routes, Route, Navigate, useNavigate, useParams, useLocation } from 'react-router-dom';
import '@tdesign-react/chat/es/style/index.js';

import { useTheme } from './hooks/useTheme';
import { useSessions } from './hooks/useSessions';
import { useChat } from './hooks/useChat';

import { CustomerSidebar } from './components/CustomerSidebar';
import { SessionInsightPanel } from './components/SessionInsightPanel';
import { CustomerChatPage } from './pages/CustomerChatPage';
import { AdminApp } from './admin/AdminApp';
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
// 主应用
// ===================================================
function App() {
  const location = useLocation();
  return (
    <Routes>
      <Route path="/login" element={
        getAuthToken()
          ? <Navigate to={getAuthUser()?.role === 'ROLE_ADMIN' ? '/admin/overview' : '/'} replace />
          : <LoginPage />
      } />
      <Route path="/" element={<AuthRoute audience="customer"><CustomerApp /></AuthRoute>} />
      <Route path="/chat/:sessionId" element={<AuthRoute audience="customer"><CustomerApp /></AuthRoute>} />
      <Route path="/admin/*" element={<AuthRoute audience="admin"><AdminApp /></AuthRoute>} />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}

function AuthRoute({
  children,
  audience,
}: {
  children: React.ReactNode;
  audience: 'customer' | 'admin';
}) {
  const location = useLocation();
  const [state, setState] = useState<
    'checking' | 'ready' | 'unauthenticated' | 'wrongAudience' | 'verificationFailed'
  >('checking');

  useEffect(() => {
    let active = true;
    if (!getAuthToken()) {
      setState('unauthenticated');
      return () => { active = false; };
    }

    getCurrentUser()
      .then(profile => {
        if (!active) return;
        const isAdmin = profile.role === 'ROLE_ADMIN';
        setState((audience === 'admin') === isAdmin ? 'ready' : 'wrongAudience');
      })
      .catch(() => {
        if (!active) return;
        const cached = getAuthUser();
        if (!getAuthToken()) setState('unauthenticated');
        // 管理路由必须服务端校验成功，不能信任可被本地修改的角色缓存。
        else if (audience === 'admin') setState('verificationFailed');
        // 管理员缓存也不能回落到普通工作台；普通用户在 /me 短暂失败时仍可继续使用。
        else setState(cached?.role === 'ROLE_ADMIN' ? 'verificationFailed' : 'ready');
      });

    return () => { active = false; };
  }, [audience]);

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
  if (state === 'wrongAudience') {
    return <Navigate to={audience === 'admin' ? '/' : '/admin/overview'} replace />;
  }
  if (state === 'verificationFailed') {
    return (
      <div className="auth-loading" role="alert">
        <span>暂时无法验证账号权限，请稍后刷新页面或重新登录。</span>
        <button
          type="button"
          className="header-text-button"
          onClick={() => { clearAuth(); window.location.replace('/login'); }}
        >
          重新登录
        </button>
      </div>
    );
  }
  return <>{children}</>;
}

function CustomerApp() {
  const navigate = useNavigate();
  const { sessionId: urlSessionId } = useParams<{ sessionId: string }>();
  const authUser = getAuthUser();
  const [sidebarOpen, setSidebarOpen] = useState(false);

  const { theme, toggleTheme } = useTheme();
  const {
    sessions, setSessions,
    currentSessionId, setCurrentSessionId,
    currentSession,
    fetchSessions, deleteSession, createSession, closeSession, rateSession,
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
    const routeSessionId = urlSessionId || null;
    setCurrentSessionId(previous =>
      previous === routeSessionId ? previous : routeSessionId
    );
  }, [urlSessionId, setCurrentSessionId]);

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
      <CustomerSidebar
        sessions={sessions}
        currentSessionId={currentSessionId}
        theme={theme}
        onNewChat={handleNewChat}
        onSelectSession={handleSelectSession}
        onDeleteSession={handleDeleteSession}
        onSelectAgent={handleSelectAgent}
        onToggleTheme={toggleTheme}
        isOpen={sidebarOpen}
        onClose={() => setSidebarOpen(false)}
      />

      {sidebarOpen && (
        <button
          type="button"
          className="sidebar-backdrop"
          aria-label="关闭侧边栏"
          onClick={() => setSidebarOpen(false)}
        />
      )}

      <main className="workbench-main flex-1 flex flex-col min-w-0 relative">
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
                <strong>{currentSession ? currentSession.title : '多智能体客服工作台'}</strong>
                <span>{currentSession ? '当前接待会话' : '描述客户问题，智能体团队自动分流处理'}</span>
              </div>
              <div className="header-capability"><ShieldCheck size={14} /> 多智能体协同</div>
              <div className="header-actions">
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
              sessions={sessions}
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
      </main>

      <SessionInsightPanel
        sessions={sessions}
        currentSession={currentSession}
        onCloseSession={handleCloseSession}
        onRateSession={handleRateSession}
      />

    </div>
  );
}

export default App;
