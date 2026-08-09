/**
 * API 基础客户端 — 统一管理所有后端调用路径
 * 所有 API 调用统一经过此层，便于修改 base URL、错误处理、鉴权注入
 */

import {
  clearAuth,
  getAuthToken,
  getRefreshToken,
  updateAuth,
  type AuthUser,
} from './authStorage';

const BASE_URL = '/api';

/** 请求头配置 */
const DEFAULT_HEADERS: Record<string, string> = {
  'Content-Type': 'application/json',
};

/**
 * 基础请求封装（统一错误处理）
 */
async function request<T>(
  endpoint: string,
  options: RequestInit = {},
  retryAfterRefresh = true,
): Promise<T> {
  const url = `${BASE_URL}${endpoint}`;
  const res = await authenticatedFetch(url, {
    ...options,
    headers: {
      ...DEFAULT_HEADERS,
      ...options.headers,
    },
  }, retryAfterRefresh && canRefresh(endpoint));

  if (!res.ok) {
    const errorBody = await res.text().catch(() => '');
    const message = readErrorMessage(errorBody)
      || (res.status === 401 ? '账号或密码错误' : `请求失败: ${res.statusText}`);
    throw new ApiError(res.status, message, errorBody);
  }

  // 204 No Content（DELETE 等）
  if (res.status === 204) return undefined as T;

  return res.json();
}

/**
 * 带统一 Bearer、单次刷新与失效跳转的 fetch。
 * SSE 等不能经过 JSON apiClient 的请求也必须复用此入口。
 */
export async function authenticatedFetch(
  input: RequestInfo | URL,
  options: RequestInit = {},
  retryAfterRefresh = true,
): Promise<Response> {
  const headers = new Headers(options.headers);
  const token = getAuthToken();
  if (token && !headers.has('Authorization')) {
    headers.set('Authorization', `Bearer ${token}`);
  }

  const response = await fetch(input, { ...options, headers });
  if (response.status !== 401 || !retryAfterRefresh) return response;

  const refreshed = await refreshAccessToken();
  if (refreshed) return authenticatedFetch(input, options, false);
  expireSession();
  return response;
}

interface ApiEnvelope<T> {
  code: number;
  message: string;
  data: T;
  error?: { detail?: string };
}

let refreshPromise: Promise<boolean> | null = null;

function canRefresh(endpoint: string) {
  return !['/auth/login', '/auth/register', '/auth/refresh'].includes(endpoint);
}

function readErrorMessage(body: string): string | null {
  if (!body) return null;
  try {
    const payload = JSON.parse(body) as {
      message?: string;
      error?: string | { detail?: string };
    };
    if (typeof payload.error === 'object' && payload.error?.detail) {
      return payload.error.detail;
    }
    if (payload.message) return payload.message;
    return typeof payload.error === 'string' ? payload.error : null;
  } catch {
    return null;
  }
}

function refreshAccessToken(): Promise<boolean> {
  if (refreshPromise) return refreshPromise;
  const refreshToken = getRefreshToken();
  if (!refreshToken) return Promise.resolve(false);

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 10000);
  refreshPromise = fetch(`${BASE_URL}/auth/refresh`, {
    method: 'POST',
    headers: DEFAULT_HEADERS,
    body: JSON.stringify({ refreshToken }),
    signal: controller.signal,
  })
    .then(async response => {
      if (!response.ok) return false;
      const payload = await response.json() as ApiEnvelope<AuthUser>;
      if (payload.code !== 0 || !payload.data?.token) return false;
      // 登出或切换账号后，迟到的刷新响应不得把旧会话重新写回。
      if (getRefreshToken() !== refreshToken) return false;
      updateAuth(payload.data);
      return true;
    })
    .catch(() => false)
    .finally(() => {
      clearTimeout(timer);
      refreshPromise = null;
    });

  return refreshPromise;
}

function expireSession() {
  clearAuth();
  if (typeof window !== 'undefined' && window.location.pathname !== '/login') {
    window.location.replace('/login?expired=1');
  }
}

/** 带超时的 GET 请求 */
function get<T>(endpoint: string, timeoutMs = 15000): Promise<T> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  return request<T>(endpoint, { signal: controller.signal }).finally(() =>
    clearTimeout(timer),
  );
}

/** POST 请求 */
function post<T>(endpoint: string, body?: unknown): Promise<T> {
  return requestWithTimeout<T>(endpoint, {
    method: 'POST',
    body: body ? JSON.stringify(body) : undefined,
  });
}

/** PUT 请求 */
function put<T>(endpoint: string, body: unknown): Promise<T> {
  return requestWithTimeout<T>(endpoint, {
    method: 'PUT',
    body: JSON.stringify(body),
  });
}

/** DELETE 请求 */
function del<T = void>(endpoint: string): Promise<T> {
  return requestWithTimeout<T>(endpoint, { method: 'DELETE' });
}

function requestWithTimeout<T>(
  endpoint: string,
  options: RequestInit,
  timeoutMs = 15000,
): Promise<T> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  return request<T>(endpoint, { ...options, signal: controller.signal })
    .finally(() => clearTimeout(timer));
}

export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
    public body?: string,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

export const apiClient = { get, post, put, del, request };
