/**
 * 智能客服 Agent 类型定义
 */

export type PermissionMode = 'default' | 'acceptEdits' | 'plan' | 'bypassPermissions';

export type IntentType = 'refund' | 'order' | 'product' | 'tech' | 'general' | 'unknown';
export type SessionStatus = 'active' | 'human_transfer' | 'closed';

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
      || intent.includes('天气') || intent.includes('新闻') || intent.includes('计算')
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
  hit_count: number;
  created_at: string;
  updated_at: string;
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

export interface SatisfactionStats {
  total: number;
  rated: number;
  avg_score: number | null;
  score_1: number;
  score_2: number;
  score_3: number;
  score_4: number;
  score_5: number;
}

export interface IntentStats {
  intent: IntentType;
  count: number;
  transfer_count: number;
}

export interface DailyStats {
  date: string;
  session_count: number;
  avg_satisfaction: number | null;
}

export interface AdminStats {
  satisfaction: SatisfactionStats;
  intents: IntentStats[];
  daily: DailyStats[];
  transferRate: number;
}

// 实时会话洞察相关类型
export interface KbHit {
  title: string;
  match: number;
  source?: string;
}

export interface EmotionResult {
  label: string;
  score: number;
  confidence: number;
}

export interface TicketResult {
  id: string;
  status: string;
  error?: string;
}

/** ⭐ P1-C 工单生命周期状态 */
export type TicketStatus = 'OPEN' | 'IN_PROGRESS' | 'PENDING' | 'RESOLVED' | 'CLOSED';

/** ⭐ P1-C 工单面板展示模型（与后端 TicketView 对齐） */
export interface Ticket {
  id: string;
  sessionId: string | null;
  intent: string | null;
  summary: string | null;
  customerName: string | null;
  status: TicketStatus;
  createdAt: string | null;
  updatedAt: string | null;
  closedAt: string | null;
  resolution: string | null;
}

/** 工单状态中文标签与配色（前端展示用） */
export const TICKET_STATUS_META: Record<TicketStatus, { label: string; color: string; bg: string }> = {
  OPEN: { label: '待处理', color: '#f59e0b', bg: 'rgba(245,158,11,0.14)' },
  IN_PROGRESS: { label: '处理中', color: '#3b82f6', bg: 'rgba(59,130,246,0.14)' },
  PENDING: { label: '挂起', color: '#a78bfa', bg: 'rgba(167,139,250,0.14)' },
  RESOLVED: { label: '已解决', color: '#14b8a6', bg: 'rgba(20,184,166,0.14)' },
  CLOSED: { label: '已关闭', color: '#94a3b8', bg: 'rgba(148,163,184,0.14)' },
};

// 客户 360° 画像 (P0 新增)
export interface CustomerProfile {
  userName: string;
  totalQueries: number;
  intentDistribution: Record<string, number>;
  entityFacts: Record<string, string>;
  foodPreferences: string[];
  travelPreferences: string[];
  budgetRange: string;
  dietaryRestrictions: string[];
  preferenceWeights: Record<string, number>;
  escalationCount: number;
  complaintCount: number;
  // ⭐ P2-A 持久化情绪聚合
  lastEmotionLabel?: string | null;
  lastEmotionScore?: number;
  negativeTouchCount?: number;
  positiveTouchCount?: number;
  emotionAvgScore?: number | null;
  agentMemorySummaries: string[];
  emotionHistory: Array<{ timestamp: string; score: number; label: string; triggerTopic: string }>;
  // ⭐ P2-C 隐藏关键信息（潜在需求/隐性信号）
  keyInsights?: string[];
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
