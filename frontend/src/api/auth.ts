import { apiClient } from './client';
import type { AuthUser } from '../authStorage';

interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
}

export interface LoginPayload {
  username: string;
  password: string;
}

export interface LoginResult extends AuthUser {
  token: string;
  refreshToken?: string;
  tokenType?: string;
}

export async function login(payload: LoginPayload): Promise<LoginResult> {
  const response = await apiClient.post<ApiResponse<LoginResult>>('/auth/login', payload);
  if (response.code !== 0 || !response.data?.token) {
    throw new Error(response.message || '登录失败');
  }
  return response.data;
}

export async function me(): Promise<AuthUser> {
  const response = await apiClient.get<ApiResponse<AuthUser>>('/auth/me');
  if (response.code !== 0 || !response.data) {
    throw new Error(response.message || '登录状态无效');
  }
  return response.data;
}
