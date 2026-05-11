/**
 * API 基础客户端 — 统一管理所有后端调用路径
 * 所有 API 调用统一经过此层，便于修改 base URL、错误处理、鉴权注入
 */

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
): Promise<T> {
  const url = `${BASE_URL}${endpoint}`;
  const res = await fetch(url, {
    ...options,
    headers: {
      ...DEFAULT_HEADERS,
      ...options.headers,
    },
  });

  if (!res.ok) {
    const errorBody = await res.text().catch(() => '');
    throw new ApiError(res.status, `请求失败: ${res.statusText}`, errorBody);
  }

  // 204 No Content（DELETE 等）
  if (res.status === 204) return undefined as T;

  return res.json();
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
  return request<T>(endpoint, {
    method: 'POST',
    body: body ? JSON.stringify(body) : undefined,
  });
}

/** PUT 请求 */
function put<T>(endpoint: string, body: unknown): Promise<T> {
  return request<T>(endpoint, {
    method: 'PUT',
    body: JSON.stringify(body),
  });
}

/** DELETE 请求 */
function del<T = void>(endpoint: string): Promise<T> {
  return request<T>(endpoint, { method: 'DELETE' });
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
