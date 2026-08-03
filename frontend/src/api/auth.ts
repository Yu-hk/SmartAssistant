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

const TOKEN_KEY = 'smart-assistant-token';
const USER_KEY = 'smart-assistant-user';

export function saveAuth(user: AuthUser, remember = true) {
  clearAuth();
  const storage = remember ? localStorage : sessionStorage;
  storage.setItem(TOKEN_KEY, user.token);
  storage.setItem(USER_KEY, JSON.stringify(user));
}

export function clearAuth() {
  [localStorage, sessionStorage].forEach(storage => {
    storage.removeItem(TOKEN_KEY);
    storage.removeItem(USER_KEY);
  });
}

export function getAuthToken(): string | null {
  return localStorage.getItem(TOKEN_KEY) || sessionStorage.getItem(TOKEN_KEY);
}

export function getAuthUser(): AuthUser | null {
  try {
    const raw = localStorage.getItem(USER_KEY) || sessionStorage.getItem(USER_KEY);
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}
