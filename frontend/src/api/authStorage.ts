export type AuthRole = 'ROLE_USER' | 'ROLE_ADMIN';

export interface AuthUser {
  token: string;
  refreshToken: string;
  tokenType: string;
  userId: number;
  username: string;
  email?: string;
  role: AuthRole;
}

export interface AuthProfile {
  userId: number;
  username: string;
  email?: string;
  role: AuthRole;
}

const TOKEN_KEY = 'smart-assistant-token';
const USER_KEY = 'smart-assistant-user';

function storageWithSession(): Storage | null {
  if (localStorage.getItem(TOKEN_KEY)) return localStorage;
  if (sessionStorage.getItem(TOKEN_KEY)) return sessionStorage;
  return null;
}

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
  return storageWithSession()?.getItem(TOKEN_KEY) ?? null;
}

export function getAuthUser(): AuthUser | null {
  try {
    const raw = storageWithSession()?.getItem(USER_KEY);
    return raw ? JSON.parse(raw) as AuthUser : null;
  } catch {
    return null;
  }
}

export function getRefreshToken(): string | null {
  return getAuthUser()?.refreshToken ?? null;
}

export function isRememberedAuth(): boolean {
  return Boolean(localStorage.getItem(TOKEN_KEY));
}

export function updateAuth(user: AuthUser) {
  saveAuth(user, isRememberedAuth());
}

export function updateAuthProfile(profile: AuthProfile) {
  const current = getAuthUser();
  if (!current) return;
  saveAuth({ ...current, ...profile }, isRememberedAuth());
}
