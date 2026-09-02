/**
 * 智能客服 Agent 类型定义
 */

export type PermissionMode = 'default' | 'acceptEdits' | 'plan' | 'bypassPermissions';

export type IntentType = 'refund' | 'order' | 'product' | 'tech' | 'general' | 'unknown';
export type SessionStatus = 'active' | 'suspended' | 'human_transfer' | 'closed';

export type WorkflowRecoveryStatus =
  | 'REQUESTED'
  | 'QUEUED'
  | 'RECOVERING'
  | 'RETRY_SCHEDULED'
  | 'SUCCEEDED'
  | 'DEAD_LETTERED'
  | 'SKIPPED_ACTIVE'
  | 'SKIPPED_APPROVAL'
  | 'SKIPPED_SUPERSEDED'
  | 'SKIPPED_DUPLICATE'
  | 'REJECTED_INVALID_COMMAND';

export interface WorkflowRecoveryJob {
  recoveryId: string;
  requestId: string;
  checkpointUpdatedAtEpochMs: number;
  trigger: string;
  workflowOwnerId: number | null;
  requestedBy: number | null;
  reason: string;
  status: WorkflowRecoveryStatus;
  attempts: number;
  lastError: string;
  result: string;
  requestedAt: string;
  updatedAt: string;
}

export interface UserNotification {
  id: string;
  type: 'WORKFLOW_RECOVERY' | string;
  title: string;
  content: string;
  sessionId: string | null;
  requestId: string | null;
  status: 'UNREAD' | 'READ';
  createdAt: string;
}

export const INTENT_LABELS: Record<IntentType, string> = {
  refund: '退款/退货',
  order: '订单查询',
  product: '商品咨询',
  tech: '技术支持',
  general: '通用咨询',
  unknown: '未识别',
};

export const INTENT_COLORS: Record<IntentType, string> = {
  refund: '#e34d59',
  order: '#0052d9',
  product: '#f59e0b',
  tech: '#ed7b2f',
  general: '#00a870',
  unknown: '#8a8a8a',
};

/** Normalize Router intent tags (including Chinese operational tags) for customer-facing UI. */
export function normalizeIntentType(value: unknown): IntentType {
  if (typeof value !== 'string') return 'unknown';
  const intent = value.trim().toLowerCase().replace(/\s+/g, '');
  if (!intent || intent === 'unknown') return 'unknown';
  if (intent.includes('退款') || intent.includes('退货') || intent.includes('refund')) return 'refund';
  if (intent.includes('订单') || intent.includes('物流') || intent.includes('order')) return 'order';
  if (intent.includes('商品') || intent.includes('产品') || intent.includes('product')) return 'product';
  if (intent.includes('技术') || intent.includes('故障') || intent.includes('tech')) return 'tech';
  if (intent.includes('general') || intent.includes('通用') || intent.includes('问候')
      || intent.includes('新闻') || intent.includes('计算')
      || intent.includes('system_capabilities')) return 'general';
  return 'unknown';
}

export interface Model {
  modelId: string;
  name: string;
  description?: string;
}

export interface FaqItem {
  id: string;
  category: string;
  question: string;
  answer: string;
  keywords: string;
  sourceName?: string;
  sourceType?: string;
  hitCount?: number;
  createdAt?: string;
  updatedAt?: string;
  /** Legacy customer API fields kept for backwards compatibility. */
  hit_count?: number;
  created_at?: string;
  updated_at?: string;
  source_name?: string;
  source_type?: string;
}

export interface ToolCall {
  id: string;
  name: string;
  input?: Record<string, unknown>;
  status: 'running' | 'completed' | 'error';
  result?: string;
  isError?: boolean;
}

export type ContentBlock =
  | { type: 'text'; text: string }
  | { type: 'tool_use'; toolCall: ToolCall };

export interface Message {
  id: string;
  role: 'user' | 'assistant' | 'system';
  content: string;
  model?: string;
  intent?: IntentType;
  timestamp: Date;
  isStreaming?: boolean;
  toolCalls?: ToolCall[];
  contentBlocks?: ContentBlock[];
  /** Independent Router/LangGraph execution ID for this conversation turn. */
  requestId?: string;
  deliveryStatus?: 'streaming' | 'completed' | 'failed' | 'stopped';
  recoverable?: boolean;
  recoveryStatus?: WorkflowRecoveryStatus;
  recoveryError?: string;
}

export interface Session {
  id: string;
  title: string;
  model: string;
  agentId?: string;
  sdk_session_id?: string | null;
  intent: IntentType;
  status: SessionStatus;
  satisfaction: number | null;
  satisfaction_comment: string | null;
  user_name: string;
  agent_name: string | null;
  messageCount?: number;
  createdAt: Date;
  messages: Message[];
}

export interface AdminStatusBreakdown {
  status: string;
  count: number;
}

export interface AdminIntentBreakdown {
  intent: string;
  count: number;
}

export interface AdminDailyStats {
  date: string;
  sessionCount: number;
  avgSatisfaction: number | null;
}

export interface AdminStats {
  totalSessions: number;
  totalUsers: number;
  totalTokens: number | null;
  avgTokensPerSession: number | null;
  tokenTrackedSessions: number | null;
  tokenTrackedTurns: number | null;
  totalTurns: number | null;
  tokenCoverageRate: number | null;
  ratedSessions: number;
  averageSatisfaction: number | null;
  successRate: number;
  handoffRate: number;
  avgLatencyMs: number;
  p95LatencyMs: number;
  statusBreakdown: AdminStatusBreakdown[];
  intentBreakdown: AdminIntentBreakdown[];
  daily: AdminDailyStats[];
}

export interface AdminSessionSummary {
  sessionId: string;
  userId: number | null;
  username: string;
  title: string;
  agentName: string | null;
  intent: string | null;
  status: string;
  satisfaction: number | null;
  satisfactionComment: string | null;
  messageCount: number;
  promptTokens: number | null;
  completionTokens: number | null;
  totalTokens: number | null;
  tokenTrackedTurns: number | null;
  totalTurns: number | null;
  tokenUsageComplete: boolean | null;
  createdAt: string;
  updatedAt: string;
}

export interface AdminSessionMessage {
  id: string;
  role: 'user' | 'assistant' | 'system' | string;
  content: string;
  createdAt: string;
  requestId: string | null;
  agentName: string | null;
  status: string | null;
  latencyMs: number | null;
  promptTokens: number | null;
  completionTokens: number | null;
  totalTokens: number | null;
  promptSnapshot: string | null;
  toolUsageComplete: boolean | null;
  toolCalls: AdminToolCall[];
}

export interface AdminToolCall {
  name: string;
  status: string;
  durationMs: number;
}

export interface AdminSessionDetail {
  session: AdminSessionSummary;
  messages: AdminSessionMessage[];
}

export interface AdminAgentFlowNode {
  id: string;
  label: string;
  agent: string;
  type: 'planner' | 'agent' | 'merger' | string;
  status: 'pending' | 'running' | 'completed' | 'failed' | 'skipped' | string;
  summary: string;
  dependsOn: string[];
  elapsedMs: number | null;
}

export interface AdminAgentFlowEdge {
  from: string;
  to: string;
  label: string;
}

export interface AdminAgentFlow {
  requestId: string;
  question: string;
  modelName: string;
  modelTier: string;
  questionChars: number;
  status: string;
  startedAt: number;
  completedAt: number | null;
  nodes: AdminAgentFlowNode[];
  edges: AdminAgentFlowEdge[];
  message?: string;
}

export interface AdminSessionPage {
  items: AdminSessionSummary[];
  total: number;
  page: number;
  size: number;
}

export type Theme = 'light' | 'dark';

export interface Agent {
  id: string;
  name: string;
  description?: string;
  systemPrompt?: string;
  icon: string;
  color: string;
  permissionMode?: PermissionMode;
}

export interface CustomAgent {
  id: string;
  name: string;
  description: string;
  systemPrompt: string;
  icon: string;
  color: string;
  permissionMode: PermissionMode;
  createdAt: string;
  updatedAt: string;
}

export interface PermissionRequest {
  requestId: string;
  toolUseId: string;
  toolName: string;
  input: Record<string, unknown>;
  sessionId: string;
  timestamp: number;
}
