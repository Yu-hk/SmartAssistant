/**
 * 实时会话洞察 API — 情绪分析 / 知识库检索 / 工单
 */
import { apiClient } from './client';
import type { EmotionResult, KbHit, TicketResult } from '../types';

/** 情绪分析：传入最新用户文本，返回情绪标签 / 分数 / 置信度 */
export async function analyzeEmotion(text: string): Promise<EmotionResult> {
  return apiClient.post<EmotionResult>('/insight/emotion', { text });
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
