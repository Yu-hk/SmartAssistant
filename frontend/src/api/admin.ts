/**
 * 管理后台相关 API
 */
import { apiClient } from './client';
import type { AdminStats } from '../types';

/** 获取管理后台统计信息 */
export async function fetchAdminStats(): Promise<AdminStats> {
  return apiClient.get<AdminStats>('/stats');
}

/** 检查登录状态 */
export async function checkLogin(): Promise<{ loggedIn: boolean }> {
  return apiClient.get<{ loggedIn: boolean }>('/check-login');
}

/** 保存环境变量配置 */
export async function saveEnvConfig(config: Record<string, string>): Promise<{ success: boolean }> {
  return apiClient.post<{ success: boolean }>('/save-env-config', config);
}
