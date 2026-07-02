/**
 * 智能客服 Agent 类型定义
 */

export type PermissionMode = 'default' | 'acceptEdits' | 'plan' | 'bypassPermissions';

export type IntentType = 'refund' | 'order' | 'tech' | 'general' | 'unknown';
export type SessionStatus = 'active' | 'human_transfer' | 'closed';

export const INTENT_LABELS: Record<IntentType, string> = {
  refund: '退款/退货',
  order: '订单查询',
  tech: '技术支持',
  general: '通用咨询',
  unknown: '未识别',
};

export const INTENT_COLORS: Record<IntentType, string> = {
  refund: '#e34d59',
  order: '#0052d9',
  tech: '#ed7b2f',
  general: '#00a870',
  unknown: '#8a8a8a',
};

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
