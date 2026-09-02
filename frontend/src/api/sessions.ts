/**
 * 会话（Session）相关 API
 */
import { apiClient } from './client';
import type { Session, FaqItem, WorkflowRecoveryJob } from '../types';

export interface ChatRequest {
  message: string;
  sessionId?: string | null;
  requestId?: string;
}

export interface ChatResponse {
  reply: string;
  suggestions?: string[];
  sessionId?: string | null;
  agentName?: string;
  duration_ms?: number;
  error?: string;
}

/** 获取所有会话列表 */
export async function fetchSessions(): Promise<Session[]> {
  const resp = await apiClient.get<Session[] | { sessions: Session[] }>('/sessions');
  return Array.isArray(resp) ? resp : (resp.sessions ?? []);
}

/** 获取单个会话详情（含消息） */
export async function fetchSession(sessionId: string): Promise<{ session: Session; messages: any[] }> {
  return apiClient.get<{ session: Session; messages: any[] }>(`/sessions/${sessionId}`);
}

/** 删除会话 */
export async function deleteSession(sessionId: string): Promise<void> {
  return apiClient.del(`/sessions/${sessionId}`);
}

/** 主动结束会话（无需评分） */
export async function closeSession(sessionId: string): Promise<{
  success: boolean;
  status: string;
}> {
  return apiClient.post(`/sessions/${sessionId}/close`, {});
}

/** 由用户主动恢复一条已暂停的会话 */
export async function resumeSession(sessionId: string): Promise<{
  success: boolean;
  status: string;
  sessionId: string;
}> {
  return apiClient.post(`/sessions/${sessionId}/resume`, {});
}

/** 提交满意度并结束会话 */
export async function rateSession(
  sessionId: string,
  score: number,
  comment?: string,
): Promise<void> {
  return apiClient.post(`/sessions/${sessionId}/satisfaction`, { score, comment });
}

/** 发送聊天消息 */
export async function sendChatMessage(params: ChatRequest): Promise<ChatResponse> {
  return apiClient.post<ChatResponse>('/chat', params);
}

/** 权限确认（允许） */
export async function allowPermission(requestId: string): Promise<void> {
  return apiClient.post('/permission-response', { requestId, action: 'allow' });
}

/** 权限确认（拒绝） */
export async function denyPermission(requestId: string): Promise<void> {
  return apiClient.post('/permission-response', { requestId, action: 'deny' });
}

/** Submit recovery for one workflow owned by the authenticated user. */
export async function requestWorkflowRecovery(requestId: string): Promise<WorkflowRecoveryJob> {
  return apiClient.post<WorkflowRecoveryJob>(
    `/router/workflows/${encodeURIComponent(requestId)}/recovery-requests`,
  );
}

/** Query an owned asynchronous recovery request. */
export async function fetchWorkflowRecovery(recoveryId: string): Promise<WorkflowRecoveryJob> {
  return apiClient.get<WorkflowRecoveryJob>(
    `/router/workflows/recovery-requests/${encodeURIComponent(recoveryId)}`,
  );
}

/** 获取 FAQ 列表 */
export async function fetchFaqs(): Promise<FaqItem[]> {
  const resp = await apiClient.get<{ faqs: FaqItem[] }>('/faq');
  return resp.faqs;
}

/** 新增 FAQ */
export async function createFaq(faq: Partial<FaqItem>): Promise<FaqItem> {
  return apiClient.post<FaqItem>('/faq', faq);
}

/** 更新 FAQ */
export async function updateFaq(id: string, faq: Partial<FaqItem>): Promise<FaqItem> {
  return apiClient.put<FaqItem>(`/faq/${id}`, faq);
}

/** 删除 FAQ */
export async function deleteFaq(id: string): Promise<void> {
  return apiClient.del(`/faq/${id}`);
}

/** FAQ 点击计数 */
export async function hitFaq(id: string): Promise<void> {
  return apiClient.post(`/faq/${id}/hit`);
}

/** 获取可用模型列表 */
export async function fetchModels(): Promise<string[]> {
  return apiClient.get<string[]>('/models');
}
