export const AUTH_CHANGED_EVENT = 'smart-assistant:auth-changed';

const ACCESS_TOKEN_KEY = 'accessToken';
const REFRESH_TOKEN_KEY = 'refreshToken';
const USER_KEY = 'authUser';

export interface AuthUser {
  userId?: number;
  id?: number;
  username: string;
  email?: string | null;
}

export interface StoredAuthSession {
  token: string;
  refreshToken?: string;
  user: AuthUser;
}

export function readAccessToken(): string | null {
  if (typeof window === 'undefined') return null;
  for (const key of [ACCESS_TOKEN_KEY, 'token', 'auth_token']) {
    const value = localStorage.getItem(key);
    if (value) return value.replace(/^"|"$/g, '');
  }
  const persisted = localStorage.getItem('auth-storage');
  if (!persisted) return null;
  try {
    const parsed = JSON.parse(persisted);
    return parsed?.state?.token || parsed?.state?.accessToken || parsed?.token || null;
  } catch {
    return null;
  }
}

export function readStoredUser(): AuthUser | null {
  if (typeof window === 'undefined') return null;
  const value = localStorage.getItem(USER_KEY);
  if (!value) return null;
  try {
    return JSON.parse(value) as AuthUser;
  } catch {
    return null;
  }
}

export function saveAuthSession(session: StoredAuthSession): void {
  localStorage.setItem(ACCESS_TOKEN_KEY, session.token);
  localStorage.setItem(USER_KEY, JSON.stringify(session.user));
  if (session.refreshToken) localStorage.setItem(REFRESH_TOKEN_KEY, session.refreshToken);
  window.dispatchEvent(new CustomEvent(AUTH_CHANGED_EVENT));
}

export function clearAuthSession(): void {
  for (const key of [ACCESS_TOKEN_KEY, REFRESH_TOKEN_KEY, USER_KEY, 'token', 'auth_token', 'auth-storage']) {
    localStorage.removeItem(key);
  }
  window.dispatchEvent(new CustomEvent(AUTH_CHANGED_EVENT));
}
