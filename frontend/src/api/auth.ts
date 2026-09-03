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

export type OAuthProviderId = 'wechat' | 'dingtalk' | 'feishu';

export interface OAuthProviderStatus {
  id: OAuthProviderId;
  name: string;
  enabled: boolean;
}

export interface DingTalkFrameConfig {
  clientId: string;
  redirectUri: string;
  state: string;
  scope: string;
  responseType: string;
  prompt: string;
}

export interface FeishuFrameConfig {
  authorizationUri: string;
  state: string;
}

interface OAuthTicketPayload {
  auth: AuthUser;
  returnTo: string;
  remember: boolean;
}

// React StrictMode 会在开发环境重复执行 effect；同一票据必须复用同一个请求，
// 否则第二次请求会把“一次性票据已使用”误报成登录失败。
const oauthTicketExchanges = new Map<string, Promise<OAuthTicketPayload>>();

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

export async function getOAuthProviders(): Promise<OAuthProviderStatus[]> {
  const response = await apiClient.get<ApiEnvelope<OAuthProviderStatus[]>>('/auth/oauth/providers');
  if (response.code !== 0) {
    throw new Error(response.error?.detail || response.message || '无法获取第三方登录渠道状态');
  }
  if (!Array.isArray(response.data) || response.data.length === 0) {
    throw new Error('第三方登录渠道状态返回异常');
  }
  return response.data;
}

export function getOAuthAuthorizeUrl(
  provider: OAuthProviderId,
  returnTo: string,
  remember: boolean,
) {
  const query = new URLSearchParams({ returnTo, remember: String(remember) });
  return `/api/auth/oauth/${provider}/authorize?${query.toString()}`;
}

export async function getDingTalkFrameConfig(
  returnTo: string,
  remember: boolean,
): Promise<DingTalkFrameConfig> {
  const query = new URLSearchParams({ returnTo, remember: String(remember) });
  const response = await apiClient.get<ApiEnvelope<DingTalkFrameConfig>>(
    `/auth/oauth/dingtalk/frame-config?${query.toString()}`,
  );
  if (response.code !== 0 || !response.data?.clientId || !response.data?.state) {
    throw new Error(response.error?.detail || response.message || '无法初始化钉钉扫码登录');
  }
  return response.data;
}

export async function getFeishuFrameConfig(
  returnTo: string,
  remember: boolean,
): Promise<FeishuFrameConfig> {
  const query = new URLSearchParams({ returnTo, remember: String(remember) });
  const response = await apiClient.get<ApiEnvelope<FeishuFrameConfig>>(
    `/auth/oauth/feishu/frame-config?${query.toString()}`,
  );
  if (response.code !== 0 || !response.data?.authorizationUri || !response.data?.state) {
    throw new Error(response.error?.detail || response.message || '无法初始化飞书扫码登录');
  }
  return response.data;
}

export async function exchangeOAuthTicket(ticket: string): Promise<OAuthTicketPayload> {
  const active = oauthTicketExchanges.get(ticket);
  if (active) return active;
  const exchange = apiClient.post<ApiEnvelope<OAuthTicketPayload>>('/auth/oauth/exchange', { ticket })
    .then(response => {
      if (response.code !== 0 || !response.data?.auth?.token) {
        throw new Error(response.error?.detail || response.message || '第三方登录失败');
      }
      return response.data;
    });
  oauthTicketExchanges.set(ticket, exchange);
  exchange.then(
    () => oauthTicketExchanges.delete(ticket),
    () => oauthTicketExchanges.delete(ticket),
  );
  return exchange;
}

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
