import { lazy, Suspense, useState } from 'react';
import { Navigate, Route, Routes } from 'react-router-dom';
import { getAuthUser } from '../api/auth';
import { useTheme } from '../hooks/useTheme';
import { AdminLayout } from './AdminLayout';

const AdminOverviewPage = lazy(() => import('./AdminOverviewPage').then(module => ({
  default: module.AdminOverviewPage,
})));
const AdminConversationsPage = lazy(() => import('./AdminConversationsPage').then(module => ({
  default: module.AdminConversationsPage,
})));
const AdminKnowledgePage = lazy(() => import('./AdminKnowledgePage').then(module => ({
  default: module.AdminKnowledgePage,
})));

/**
 * Independent administrator application.
 *
 * Keeping this component outside the customer workbench prevents admin routes
 * from initializing customer session and chat state.
 */
export function AdminApp() {
  const { theme, toggleTheme } = useTheme();
  const [refreshVersion, setRefreshVersion] = useState(0);

  return (
    <AdminLayout
      user={getAuthUser()}
      theme={theme}
      refreshVersion={refreshVersion}
      onRefresh={() => setRefreshVersion(version => version + 1)}
      onToggleTheme={toggleTheme}
    >
      <Suspense fallback={<div className="admin-loading" role="status">正在加载管理数据…</div>}>
        <Routes>
          <Route index element={<Navigate to="overview" replace />} />
          <Route path="overview" element={<AdminOverviewPage refreshVersion={refreshVersion} />} />
        <Route path="conversations" element={<AdminConversationsPage refreshVersion={refreshVersion} />} />
        <Route path="knowledge" element={<AdminKnowledgePage refreshVersion={refreshVersion} />} />
        <Route path="*" element={<Navigate to="overview" replace />} />
        </Routes>
      </Suspense>
    </AdminLayout>
  );
}
