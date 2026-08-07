/**
 * 实时会话洞察 API — 情绪分析 / 知识库检索 / 工单
 */
import { apiClient } from './client';
import type { CustomerProfile, EmotionResult, KbHit, Ticket, TicketResult, TicketStatus } from '../types';

/** 情绪分析：传入最新用户文本，返回情绪标签 / 分数 / 置信度 */
export async function analyzeEmotion(text: string, triggerTopic?: string): Promise<EmotionResult> {
  return apiClient.post<EmotionResult>('/insight/emotion', { text, triggerTopic });
}

/** 知识库检索：按查询文本或意图匹配，返回高匹配知识条目 */
export async function searchKb(
  query?: string,
  intent?: string,
): Promise<{ hits: KbHit[]; count: number }> {
  const params = new URLSearchParams();
  if (query) params.set('query', query);
  if (intent) params.set('intent', intent);
  const qs = params.toString();
  return apiClient.get<{ hits: KbHit[]; count: number }>(
    `/insight/kb-search${qs ? `?${qs}` : ''}`,
  );
}

/** 创建工单：持久化并返回工单号 */
export async function createTicket(payload: {
  sessionId: string;
  intent: string;
  summary: string;
  customerName: string;
}): Promise<TicketResult> {
  return apiClient.post<TicketResult>('/insight/ticket', payload);
}

/** ⭐ P1-C 推进工单状态（生命周期） */
export async function updateTicketStatus(ticketId: string, status: TicketStatus): Promise<TicketResult> {
  return apiClient.post<TicketResult>('/insight/ticket/status', { ticketId, status });
}

/** ⭐ P1-C 关闭工单（终态，可附处理结论） */
export async function closeTicket(ticketId: string, resolution?: string): Promise<TicketResult> {
  return apiClient.post<TicketResult>('/insight/ticket/close', { ticketId, resolution });
}

/** ⭐ P1-C 工单列表（按 sessionId / customerName 过滤） */
export async function listTickets(params?: {
  sessionId?: string;
  customerName?: string;
}): Promise<Ticket[]> {
  const sp = new URLSearchParams();
  if (params?.sessionId) sp.set('sessionId', params.sessionId);
  if (params?.customerName) sp.set('customerName', params.customerName);
  const qs = sp.toString();
  return apiClient.get<Ticket[]>(`/insight/tickets${qs ? `?${qs}` : ''}`);
}

/** 客户 360° 画像：聚合偏好/事实/记忆/情绪 */
export async function getProfile(): Promise<CustomerProfile> {
  return apiClient.get<CustomerProfile>('/insight/profile');
}
