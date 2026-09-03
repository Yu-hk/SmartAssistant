import { lazy, Suspense, useState, useEffect, useCallback } from 'react';
import { Routes, Route, Navigate, useNavigate, useParams, useLocation } from 'react-router-dom';

import { useTheme } from './hooks/useTheme';
import { useSessions } from './hooks/useSessions';
import { useChat } from './hooks/useChat';
import { useNotifications } from './hooks/useNotifications';

import { CustomerSidebar } from './components/CustomerSidebar';
import { SessionInsightPanel } from './components/SessionInsightPanel';
import { CustomerChatPage } from './pages/CustomerChatPage';
import { RecoveryNotificationCenter } from './components/RecoveryNotificationCenter';
import { LoginPage } from './pages/LoginPage';
import {
  clearAuth,
  getAuthToken,
  getAuthUser,
  getCurrentUser,
  logout,
} from './api/auth';
import { LogOut, Menu, MessageSquareText, ShieldCheck, UserRound } from 'lucide-react';

const AdminApp = lazy(() => import('./admin/AdminApp').then(module => ({
  default: module.AdminApp,
})));

// ===================================================
// 主应用
// ===================================================
function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginRoute />} />
      <Route path="/" element={<AuthRoute audience="customer"><CustomerApp /></AuthRoute>} />
      <Route path="/chat/:sessionId" element={<AuthRoute audience="customer"><CustomerApp /></AuthRoute>} />
      <Route path="/admin/*" element={
        <AuthRoute audience="admin">
          <Suspense fallback={<div className="auth-loading" role="status">正在加载管理页面…</div>}>
            <AdminApp />
          </Suspense>
        </AuthRoute>
      } />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}

function LoginRoute() {
  const [state, setState] = useState<'checking' | 'login' | 'authenticated' | 'verificationFailed'>(
    () => getAuthToken() ? 'checking' : 'login',
  );
  const [authenticatedRole, setAuthenticatedRole] = useState(getAuthUser()?.role);
  const [verificationAttempt, setVerificationAttempt] = useState(0);

  useEffect(() => {
    let active = true;
    if (!getAuthToken()) {
      setState('login');
      return () => { active = false; };
    }

    setState('checking');
    getCurrentUser()
      .then(profile => {
        if (!active) return;
        setAuthenticatedRole(profile.role);
        setState('authenticated');
      })
      .catch(() => {
        if (!active) return;
        // 401/刷新失败时 apiClient 已清理失效凭据，此时直接展示登录页。
        // 网络或服务暂时异常时保留有效凭据，让用户选择重试或切换账号。
        setState(getAuthToken() ? 'verificationFailed' : 'login');
      });

    return () => { active = false; };
  }, [verificationAttempt]);

  if (state === 'checking') {
    return (
      <div className="auth-loading" role="status" aria-live="polite">
        <img className="auth-loading-mark" src="/icons/app-icon.svg" alt="" />
        <span>正在验证登录状态…</span>
      </div>
    );
  }
  if (state === 'authenticated') {
    return <Navigate to={authenticatedRole === 'ROLE_ADMIN' ? '/admin/overview' : '/'} replace />;
  }
  if (state === 'verificationFailed') {
    return (
      <div className="auth-loading" role="alert">
        <span>暂时无法验证已有登录状态，请重试或使用其他账号登录。</span>
        <button
          type="button"
          className="header-text-button"
          onClick={() => setVerificationAttempt(value => value + 1)}
        >
          重新验证
        </button>
        <button
          type="button"
          className="header-text-button"
          onClick={() => { clearAuth(); setState('login'); }}
        >
          使用其他账号
        </button>
      </div>
    );
  }
  return <LoginPage />;
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
        <img className="auth-loading-mark" src="/icons/app-icon.svg" alt="" />
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
    sessions, setSessions, sessionActionError, setSessionActionError,
    currentSessionId, setCurrentSessionId,
    currentSession,
    fetchSessions, deleteSession, createSession, closeSession, resumeSession, rateSession,
  } = useSessions();

  const { notifications, markRead: markNotificationRead } = useNotifications({ setSessions });

  const {
    isLoading, inputValue, setInputValue,
    permissionRequest, faqSuggestions,
    sendMessage, handleStop,
    handlePermissionAllow, handlePermissionDeny, handleRecoverMessage,
    queuePosition, queueEstimatedWait, progressMessage,
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

  const handleSelectAgent = useCallback((serviceName: string) => {
    const sessionId = createSession(`${serviceName}咨询`);
    setInputValue(`我需要${serviceName}：`);
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

  const handleResumeSession = useCallback(async (sessionId: string) => {
    const resumed = await resumeSession(sessionId);
    if (!resumed) return;
    setCurrentSessionId(sessionId);
    setInputValue('');
    setSidebarOpen(false);
    navigate(`/chat/${sessionId}`);
  }, [navigate, resumeSession, setCurrentSessionId, setInputValue]);

  return (
    <div className="workbench-shell relative z-10">
      <CustomerSidebar
        sessions={sessions}
        currentSessionId={currentSessionId}
        theme={theme}
        onNewChat={handleNewChat}
        onSelectSession={handleSelectSession}
        onDeleteSession={handleDeleteSession}
        onResumeSession={sessionId => { void handleResumeSession(sessionId); }}
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
                <strong>{currentSession ? currentSession.title : '智能服务助手'}</strong>
                <span>{currentSession ? '当前服务会话' : '描述需要处理的事情，系统会安排合适的服务能力'}</span>
              </div>
              <div className="header-capability"><ShieldCheck size={14} /> 安全协同处理</div>
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
              progressMessage={progressMessage}
              onSendMessage={sendMessage}
              onStop={handleStop}
              onInputChange={setInputValue}
              onPermissionAllow={handlePermissionAllow}
              onPermissionDeny={handlePermissionDeny}
              onRecoverMessage={handleRecoverMessage}
              onRateSession={handleRateSession}
              userName={authUser?.username}
            />
            {sessionActionError && (
              <div className="session-action-error" role="alert">
                <span>{sessionActionError}</span>
                <button type="button" onClick={() => setSessionActionError(null)}>关闭</button>
              </div>
            )}
        </>
      </main>

      <SessionInsightPanel
        sessions={sessions}
        currentSession={currentSession}
        onCloseSession={handleCloseSession}
        onRateSession={handleRateSession}
      />

      <RecoveryNotificationCenter
        notifications={notifications}
        onOpen={notification => {
          void markNotificationRead(notification.id);
          if (notification.sessionId) navigate(`/chat/${notification.sessionId}`);
        }}
        onDismiss={notificationId => { void markNotificationRead(notificationId); }}
      />

    </div>
  );
}

export default App;
