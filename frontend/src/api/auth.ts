import { apiClient } from './client';
import {
  clearAuth,
  getAuthToken,
  getAuthUser,
  saveAuth,
  updateAuthProfile,
  type AuthProfile,
  type AuthUser,
} from './authStorage';

export type { AuthProfile, AuthRole, AuthUser } from './authStorage';
export { clearAuth, getAuthToken, getAuthUser, saveAuth } from './authStorage';

interface ApiEnvelope<T> {
  code: number;
  message: string;
  data: T;
  error?: { detail?: string };
}

async function authenticate(
  endpoint: '/auth/login' | '/auth/register',
  body: Record<string, string | undefined>,
): Promise<AuthUser> {
  const response = await apiClient.post<ApiEnvelope<AuthUser>>(endpoint, body);
  if (response.code !== 0 || !response.data?.token) {
    throw new Error(response.error?.detail || response.message || '认证失败');
  }
  return response.data;
}

export const login = (username: string, password: string) =>
  authenticate('/auth/login', { username, password });

export const register = (username: string, password: string, email: string) =>
  authenticate('/auth/register', { username, password, email: email || undefined });

export async function getCurrentUser(): Promise<AuthProfile> {
  const response = await apiClient.get<ApiEnvelope<AuthProfile>>('/auth/me');
  if (response.code !== 0 || !response.data?.userId) {
    throw new Error(response.error?.detail || response.message || '登录状态校验失败');
  }
  updateAuthProfile(response.data);
  return response.data;
}

export async function logout(): Promise<void> {
  const current = getAuthUser();
  const accessToken = getAuthToken();
  // 先清理本地状态，阻止在途 refresh 响应把已退出会话重新写回。
  clearAuth();
  try {
    if (accessToken && current) {
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), 5000);
      try {
        await apiClient.request<ApiEnvelope<void>>('/auth/logout', {
          method: 'POST',
          headers: { Authorization: `Bearer ${accessToken}` },
          body: JSON.stringify({ refreshToken: current.refreshToken }),
          signal: controller.signal,
        }, false);
      } finally {
        clearTimeout(timer);
      }
    }
  } catch {
    // 本地退出已经完成；服务端不可用不应阻止用户离开当前账号。
  }
}
