/** Administrative API client. All routes in this module require ROLE_ADMIN. */
import { apiClient } from './client';
import type {
  AdminDailyStats,
  AdminIntentBreakdown,
  AdminSessionDetail,
  AdminSessionMessage,
  AdminSessionPage,
  AdminSessionSummary,
  AdminToolCall,
  AdminStats,
  AdminStatusBreakdown,
  FaqItem,
} from '../types';

export interface AdminSessionQuery {
  query?: string;
  userId?: string | number;
  status?: string;
  intent?: string;
  page?: number;
  size?: number;
}

export interface AdminFaqPayload {
  category: string;
  question: string;
  answer: string;
  keywords: string;
}

export interface AdminFaqImportPayload {
  sourceName: string;
  sourceType: 'json' | 'csv' | 'markdown';
  overwrite: boolean;
  items: AdminFaqPayload[];
}

export interface AdminFaqImportResult {
  total: number;
  created: number;
  updated: number;
  skipped: number;
}

type UnknownRecord = Record<string, unknown>;

function record(value: unknown): UnknownRecord {
  return value && typeof value === 'object' ? value as UnknownRecord : {};
}

function text(value: unknown, fallback = ''): string {
  return typeof value === 'string' ? value : fallback;
}

function number(value: unknown, fallback = 0): number {
  const parsed = typeof value === 'number' ? value : Number(value);
  return Number.isFinite(parsed) ? parsed : fallback;
}

function nullableNumber(value: unknown): number | null {
  return value === null || value === undefined || value === '' ? null : number(value);
}

function tokenNumber(value: unknown): number | null {
  if (value === null || value === undefined || value === '') return null;
  const parsed = typeof value === 'number' ? value : Number(value);
  return Number.isFinite(parsed) && parsed >= 0 ? parsed : null;
}

function nullableBoolean(value: unknown): boolean | null {
  if (value === null || value === undefined || value === '') return null;
  if (typeof value === 'boolean') return value;
  if (value === 1 || value === '1' || value === 'true') return true;
  if (value === 0 || value === '0' || value === 'false') return false;
  return null;
}

function unwrap(value: unknown): unknown {
  const body = record(value);
  return body.data !== undefined && ('code' in body || 'message' in body) ? body.data : value;
}

function normalizeStatusBreakdown(value: unknown): AdminStatusBreakdown[] {
  if (Array.isArray(value)) {
    return value.map(item => {
      const row = record(item);
      return { status: text(row.status ?? row.key), count: number(row.count ?? row.value) };
    }).filter(item => item.status);
  }
  return Object.entries(record(value)).map(([status, count]) => ({ status, count: number(count) }));
}

function normalizeIntentBreakdown(value: unknown): AdminIntentBreakdown[] {
  if (Array.isArray(value)) {
    return value.map(item => {
      const row = record(item);
      return { intent: text(row.intent ?? row.key), count: number(row.count ?? row.value) };
    }).filter(item => item.intent);
  }
  return Object.entries(record(value)).map(([intent, count]) => ({ intent, count: number(count) }));
}

function normalizeDaily(value: unknown): AdminDailyStats[] {
  if (!Array.isArray(value)) return [];
  return value.map(item => {
    const row = record(item);
    return {
      date: text(row.date ?? row.day),
      sessionCount: number(row.sessionCount ?? row.session_count ?? row.count),
      avgSatisfaction: nullableNumber(
        row.avgSatisfaction ?? row.averageSatisfaction ?? row.avg_satisfaction,
      ),
    };
  }).filter(item => item.date);
}

function normalizeSession(value: unknown): AdminSessionSummary {
  const row = record(value);
  return {
    sessionId: text(row.sessionId ?? row.session_id ?? row.id),
    userId: nullableNumber(row.userId ?? row.user_id),
    username: text(row.username ?? row.userName ?? row.user_name, '未知用户'),
    title: text(row.title ?? row.userInput ?? row.user_input, '未命名对话'),
    agentName: text(row.agentName ?? row.agent_name ?? row.routedAgent ?? row.routed_agent) || null,
    intent: text(row.intent) || null,
    status: text(row.status, 'unknown'),
    satisfaction: nullableNumber(row.satisfaction ?? row.rating),
    satisfactionComment: text(
      row.satisfactionComment ?? row.satisfaction_comment ?? row.feedbackText ?? row.feedback_text,
    ) || null,
    messageCount: number(row.messageCount ?? row.message_count),
    promptTokens: tokenNumber(row.promptTokens ?? row.prompt_tokens),
    completionTokens: tokenNumber(row.completionTokens ?? row.completion_tokens),
    totalTokens: tokenNumber(row.totalTokens ?? row.total_tokens),
    tokenTrackedTurns: tokenNumber(row.tokenTrackedTurns ?? row.token_tracked_turns),
    totalTurns: tokenNumber(row.totalTurns ?? row.total_turns),
    tokenUsageComplete: nullableBoolean(row.tokenUsageComplete ?? row.token_usage_complete),
    createdAt: text(row.createdAt ?? row.created_at),
    updatedAt: text(row.updatedAt ?? row.updated_at ?? row.createdAt ?? row.created_at),
  };
}

function normalizeMessage(value: unknown, index: number): AdminSessionMessage {
  const row = record(value);
  return {
    id: text(row.id ?? row.messageId ?? row.message_id, `message-${index}`),
    role: text(row.role, index % 2 === 0 ? 'user' : 'assistant'),
    content: text(row.content ?? row.message ?? row.userInput ?? row.user_input ?? row.response),
    createdAt: text(row.createdAt ?? row.created_at),
    agentName: text(row.agentName ?? row.agent_name ?? row.routedAgent ?? row.routed_agent) || null,
    status: text(row.status) || null,
    latencyMs: nullableNumber(row.latencyMs ?? row.latency_ms),
    promptTokens: tokenNumber(row.promptTokens ?? row.prompt_tokens),
    completionTokens: tokenNumber(row.completionTokens ?? row.completion_tokens),
    totalTokens: tokenNumber(row.totalTokens ?? row.total_tokens),
    promptSnapshot: text(row.promptSnapshot ?? row.prompt_snapshot) || null,
    toolUsageComplete: nullableBoolean(row.toolUsageComplete ?? row.tool_usage_complete),
    toolCalls: normalizeToolCalls(row.toolCalls ?? row.tool_calls),
  };
}

function normalizeToolCalls(value: unknown): AdminToolCall[] {
  if (!Array.isArray(value)) return [];
  return value.map(item => {
    const row = record(item);
    return {
      name: text(row.name),
      status: text(row.status, 'UNKNOWN'),
      durationMs: Math.max(0, number(row.durationMs ?? row.duration_ms)),
    };
  }).filter(item => item.name);
}

function normalizeFaq(value: unknown): FaqItem {
  const row = record(value);
  return {
    id: text(row.id),
    category: text(row.category, 'general'),
    question: text(row.question),
    answer: text(row.answer),
    keywords: text(row.keywords),
    sourceName: text(row.sourceName ?? row.source_name) || undefined,
    sourceType: text(row.sourceType ?? row.source_type, 'manual'),
    hitCount: number(row.hitCount ?? row.hit_count),
    createdAt: text(row.createdAt ?? row.created_at),
    updatedAt: text(row.updatedAt ?? row.updated_at),
  };
}

export async function fetchAdminStats(): Promise<AdminStats> {
  const raw = record(unwrap(await apiClient.get<unknown>('/admin/stats')));
  return {
    totalSessions: number(raw.totalSessions ?? raw.total_sessions),
    totalUsers: number(raw.totalUsers ?? raw.total_users),
    totalTokens: tokenNumber(raw.totalTokens ?? raw.total_tokens),
    avgTokensPerSession: tokenNumber(raw.avgTokensPerSession ?? raw.avg_tokens_per_session),
    tokenTrackedSessions: tokenNumber(
      raw.tokenTrackedSessions ?? raw.token_tracked_sessions ?? raw.trackedSessions ?? raw.tracked_sessions,
    ),
    tokenTrackedTurns: tokenNumber(raw.tokenTrackedTurns ?? raw.token_tracked_turns),
    totalTurns: tokenNumber(raw.totalTurns ?? raw.total_turns),
    tokenCoverageRate: tokenNumber(raw.tokenCoverageRate ?? raw.token_coverage_rate),
    ratedSessions: number(raw.ratedSessions ?? raw.rated_sessions),
    averageSatisfaction: nullableNumber(raw.averageSatisfaction ?? raw.average_satisfaction),
    successRate: number(raw.successRate ?? raw.success_rate),
    handoffRate: number(raw.handoffRate ?? raw.handoff_rate),
    avgLatencyMs: number(raw.avgLatencyMs ?? raw.avg_latency_ms),
    p95LatencyMs: number(raw.p95LatencyMs ?? raw.p95_latency_ms),
    statusBreakdown: normalizeStatusBreakdown(raw.statusBreakdown ?? raw.status_breakdown),
    intentBreakdown: normalizeIntentBreakdown(raw.intentBreakdown ?? raw.intent_breakdown),
    daily: normalizeDaily(raw.daily),
  };
}

export async function fetchAdminSessions(params: AdminSessionQuery): Promise<AdminSessionPage> {
  const query = new URLSearchParams();
  if (params.query?.trim()) query.set('query', params.query.trim());
  if (params.userId !== undefined && `${params.userId}`.trim()) query.set('userId', `${params.userId}`.trim());
  if (params.status) query.set('status', params.status);
  if (params.intent) query.set('intent', params.intent);
  query.set('page', String(params.page ?? 0));
  query.set('size', String(params.size ?? 20));
  const payload = record(unwrap(await apiClient.get<unknown>(`/admin/sessions?${query}`)));
  const source = Array.isArray(payload.items) ? payload.items : [];
  return {
    items: source.map(normalizeSession),
    total: number(payload.total, source.length),
    page: number(payload.page, params.page ?? 0),
    size: number(payload.size, params.size ?? 20),
  };
}

export async function fetchAdminSession(sessionId: string, userId: number | null): Promise<AdminSessionDetail> {
  const query = userId === null ? '' : `?userId=${encodeURIComponent(String(userId))}`;
  const payload = record(unwrap(await apiClient.get<unknown>(
    `/admin/sessions/${encodeURIComponent(sessionId)}${query}`,
  )));
  const messages = Array.isArray(payload.messages) ? payload.messages : [];
  return {
    session: normalizeSession(payload.session ?? payload),
    messages: messages.map(normalizeMessage),
  };
}

export async function deleteAdminSession(sessionId: string, userId: number | null): Promise<void> {
  const query = userId === null ? '' : `?userId=${encodeURIComponent(String(userId))}`;
  await apiClient.del(`/admin/sessions/${encodeURIComponent(sessionId)}${query}`);
}

export async function fetchAdminFaqs(): Promise<FaqItem[]> {
  const payload = unwrap(await apiClient.get<unknown>('/admin/faqs'));
  const body = record(payload);
  const list = Array.isArray(payload)
    ? payload
    : Array.isArray(body.items) ? body.items : Array.isArray(body.faqs) ? body.faqs : [];
  return list.map(normalizeFaq);
}

export async function createAdminFaq(payload: AdminFaqPayload): Promise<FaqItem> {
  return normalizeFaq(unwrap(await apiClient.post<unknown>('/admin/faqs', payload)));
}

export async function updateAdminFaq(id: string, payload: AdminFaqPayload): Promise<FaqItem> {
  return normalizeFaq(unwrap(await apiClient.put<unknown>(
    `/admin/faqs/${encodeURIComponent(id)}`,
    payload,
  )));
}

export async function deleteAdminFaq(id: string): Promise<void> {
  await apiClient.del(`/admin/faqs/${encodeURIComponent(id)}`);
}

export async function importAdminFaqs(payload: AdminFaqImportPayload): Promise<AdminFaqImportResult> {
  const result = record(unwrap(await apiClient.post<unknown>('/admin/faqs/import', payload)));
  return {
    total: number(result.total),
    created: number(result.created),
    updated: number(result.updated),
    skipped: number(result.skipped),
  };
}

/** Retained for the existing environment configuration screen. */
export async function checkLogin(): Promise<{
  loggedIn: boolean;
  envConfigured?: boolean;
  cliConfigured?: boolean;
  error?: string;
  apiKey?: string;
  envVars?: { apiKey?: string; authToken?: string; internetEnv?: string; baseUrl?: string };
}> {
  return apiClient.get('/check-login');
}

/** Retained for the existing environment configuration screen. */
export async function saveEnvConfig(config: Record<string, string>): Promise<{ success: boolean }> {
  return apiClient.post<{ success: boolean }>('/save-env-config', config);
}
