import { apiClient } from './client';

export interface AuthUser {
  token: string;
  refreshToken: string;
  tokenType: string;
  userId: number;
  username: string;
  email?: string;
}

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

export function saveAuth(user: AuthUser) {
  localStorage.setItem('smart-assistant-token', user.token);
  localStorage.setItem('smart-assistant-user', JSON.stringify(user));
}

export function clearAuth() {
  localStorage.removeItem('smart-assistant-token');
  localStorage.removeItem('smart-assistant-user');
}

export function getAuthUser(): AuthUser | null {
  try {
    const raw = localStorage.getItem('smart-assistant-user');
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}
