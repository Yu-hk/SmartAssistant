import type { Message, ToolCall } from '../types';

type Payload = Record<string, any>;
export function tokenNumber(value: unknown): number | null {
  if (value == null || value === '' || typeof value === 'boolean') return null;
  const number = Number(value);
  return Number.isSafeInteger(number) && number >= 0 ? number : null;
}

export function normalizeTools(value: unknown): ToolCall[] {
  if (!Array.isArray(value)) return [];
  return value.filter(tool => tool && typeof tool.name === 'string').map((tool, index) => ({
    id: String(tool.id ?? `audit-${index}`), name: tool.name,
    status: ['SUCCESS', 'COMPLETED'].includes(String(tool.status).toUpperCase()) ? 'completed'
      : ['FAILED', 'ERROR'].includes(String(tool.status).toUpperCase()) ? 'error' : 'running',
    durationMs: tokenNumber(tool.durationMs ?? tool.duration_ms) ?? undefined,
  }));
}

export function normalizeTelemetry(raw: Payload): Partial<Message> {
  const promptTokens = tokenNumber(raw.promptTokens ?? raw.prompt_tokens);
  const completionTokens = tokenNumber(raw.completionTokens ?? raw.completion_tokens);
  return {
    promptTokens, completionTokens,
    tokenUsageComplete: typeof (raw.tokenUsageComplete ?? raw.token_usage_complete) === 'boolean'
      ? raw.tokenUsageComplete ?? raw.token_usage_complete : null,
    totalTokens: tokenNumber(raw.totalTokens ?? raw.total_tokens)
      ?? (promptTokens !== null && completionTokens !== null ? promptTokens + completionTokens : null),
    toolUsageComplete: typeof (raw.toolUsageComplete ?? raw.tool_usage_complete) === 'boolean'
      ? raw.toolUsageComplete ?? raw.tool_usage_complete : null,
    toolCalls: normalizeTools(raw.toolCalls ?? raw.tool_calls),
  };
}

/** Usage events are per-turn snapshots, never deltas; replacing prevents replay double counts. */
export function applyTelemetryEvent(message: Pick<Message, 'toolCalls'>, event: Payload): Partial<Message> | null {
  if (event.type === 'token_usage') {
    const values = normalizeTelemetry(event.usage ?? event.tokenUsage ?? event);
    if (values.totalTokens == null && values.promptTokens == null && values.completionTokens == null) {
      return { tokenUsageComplete: false };
    }
    return { promptTokens: values.promptTokens, completionTokens: values.completionTokens,
      totalTokens: values.totalTokens, tokenUsageComplete: values.tokenUsageComplete };
  }
  if (event.type === 'tool_usage') {
    return { toolCalls: normalizeTools(event.toolCalls),
      toolUsageComplete: typeof event.toolUsageComplete === 'boolean' ? event.toolUsageComplete : null };
  }
  if (!['tool', 'tool_call', 'tool_result'].includes(event.type)) return null;
  const tools = [...(message.toolCalls ?? [])];
  const name = event.name ?? event.toolName;
  const id = event.toolId ?? event.id ?? (event.step != null ? `step-${event.step}` : undefined);
  if (event.type !== 'tool_result') {
    if (typeof name !== 'string' || !name) return null;
    const toolId = String(id ?? `stream-${tools.length}`);
    if (tools.some(tool => tool.id === toolId)) return { toolCalls: tools };
    tools.push({ id: toolId, name, status: 'running',
      input: event.input && typeof event.input === 'object' ? event.input : undefined });
  } else {
    const index = id != null ? tools.findIndex(tool => tool.id === String(id))
      : tools.findIndex(tool => tool.status === 'running' && (!name || name === tool.name));
    if (index < 0) return null;
    const failed = event.isError === true || ['ERROR', 'FAILED'].includes(String(event.status).toUpperCase());
    tools[index] = { ...tools[index], status: failed ? 'error' : 'completed', isError: failed,
      result: typeof event.content === 'string' ? event.content : undefined,
      durationMs: tokenNumber(event.durationMs ?? event.duration_ms) ?? tools[index].durationMs };
  }
  return { toolCalls: tools, toolUsageComplete: false };
}

export function summarizeTelemetry(messages: Message[]) {
  const turns = messages.filter(message => message.role === 'assistant');
  const sum = (key: 'promptTokens' | 'completionTokens' | 'totalTokens') => {
    const values = turns.map(turn => tokenNumber(turn[key])).filter((value): value is number => value !== null);
    return values.length ? values.reduce((total, value) => total + value, 0) : null;
  };
  const calls = turns.flatMap((turn, index) => (turn.toolCalls ?? []).map(tool => ({
    ...tool, key: `${turn.id}:${tool.id}`, turn: index + 1,
  })));
  return { calls, totalTokens: sum('totalTokens'), promptTokens: sum('promptTokens'),
    completionTokens: sum('completionTokens'), streaming: turns.some(turn => turn.isStreaming),
    tokensComplete: turns.length > 0 && turns.every(turn => tokenNumber(turn.totalTokens) !== null
      && turn.tokenUsageComplete !== false),
    toolsComplete: turns.length > 0 && turns.every(turn => turn.toolUsageComplete === true) };
}
