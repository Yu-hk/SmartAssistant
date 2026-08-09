import { useState } from 'react';
import { Navigate, Route, Routes } from 'react-router-dom';
import { getAuthUser } from '../api/auth';
import { useTheme } from '../hooks/useTheme';
import { AdminConversationsPage } from './AdminConversationsPage';
import { AdminKnowledgePage } from './AdminKnowledgePage';
import { AdminLayout } from './AdminLayout';
import { AdminOverviewPage } from './AdminOverviewPage';

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
      <Routes>
        <Route index element={<Navigate to="overview" replace />} />
        <Route path="overview" element={<AdminOverviewPage refreshVersion={refreshVersion} />} />
        <Route path="conversations" element={<AdminConversationsPage refreshVersion={refreshVersion} />} />
        <Route path="knowledge" element={<AdminKnowledgePage refreshVersion={refreshVersion} />} />
        <Route path="*" element={<Navigate to="overview" replace />} />
      </Routes>
    </AdminLayout>
  );
}
