export const KNOWLEDGE_CATEGORIES = [
  { value: 'general', label: '通用知识' },
  { value: 'account', label: '账户与登录' },
  { value: 'order', label: '订单与物流' },
  { value: 'product', label: '商品服务' },
  { value: 'refund', label: '退款与售后' },
  { value: 'technical', label: '技术支持' },
  { value: 'weather', label: '天气服务' },
] as const;

export function formatStatus(value?: string | null): string {
  const status = (value || '').trim().toLowerCase();
  if (['success', 'completed', 'resolved'].includes(status)) return '处理成功';
  if (status === 'closed') return '已结束';
  if (['active', 'running', 'processing', 'pending'].includes(status)) return '处理中';
  if (status.includes('partial')) return '已回复 · 待关注';
  if (status === 'timeout' || status.includes('timedout')) return '处理超时';
  if (['failed', 'failure', 'error', 'rejected'].includes(status)) return '处理失败';
  if (status.includes('handoff') || status.includes('human') || status.includes('transfer')) return '已转人工';
  return '待确认';
}

export function statusTone(value?: string | null): 'success' | 'info' | 'warning' | 'danger' | 'neutral' {
  const status = (value || '').toLowerCase();
  if (['success', 'completed', 'resolved', 'closed'].includes(status)) return 'success';
  if (['active', 'running', 'processing', 'pending'].includes(status)) return 'info';
  if (status.includes('partial') || status.includes('handoff') || status.includes('human') || status.includes('transfer')) return 'warning';
  if (['failed', 'failure', 'error', 'rejected', 'timeout'].includes(status)) return 'danger';
  return 'neutral';
}

export function formatIntent(value?: string | null): string {
  const intent = (value || '').trim().toLowerCase().replace(/[\s_-]+/g, '');
  if (intent.includes('refund') || intent.includes('退款') || intent.includes('退货')) return '退款与售后';
  if (intent.includes('order') || intent.includes('订单') || intent.includes('物流')) return '订单与物流';
  if (intent.includes('product') || intent.includes('商品') || intent.includes('产品')) return '商品咨询';
  if (intent.includes('technical') || intent.includes('tech') || intent.includes('技术') || intent.includes('故障')) return '技术支持';
  if (intent.includes('weather') || intent.includes('天气')) return '天气查询';
  if (intent.includes('travel') || intent.includes('旅行') || intent.includes('出行')) return '出行规划';
  if (intent.includes('news') || intent.includes('新闻')) return '资讯查询';
  if (intent.includes('general') || intent.includes('通用') || intent.includes('问候')) return '通用咨询';
  return '其他咨询';
}

export function formatAgent(value?: string | null): string {
  const agent = (value || '').trim().toLowerCase().replace(/[\s_-]+/g, '');
  if (!agent) return '智能助手';
  if (agent.includes('weather')) return '天气查询';
  if (agent.includes('internet') || agent.includes('search') || agent.includes('web')) return '联网搜索';
  if (agent.includes('product') || agent.includes('recommend')) return '商品服务';
  if (agent.includes('order')) return '订单服务';
  if (agent.includes('refund')) return '售后服务';
  if (agent.includes('travel')) return '出行规划';
  if (agent.includes('human')) return '人工客服';
  if (agent.includes('router') || agent.includes('general')) return '智能协同';
  return '智能助手';
}

export function formatRole(value?: string | null): string {
  const role = (value || '').toLowerCase();
  if (role === 'user') return '用户';
  if (role === 'assistant') return '智能助手';
  if (role === 'system') return '系统事件';
  return '消息';
}

/** Keep internal function names out of the UI while preserving capability detail. */
export function formatToolCapability(value?: string | null): string {
  const tool = (value || '').trim().toLowerCase().replace(/[\s_.:-]+/g, '');
  if (!tool) return '智能工具';
  if (tool.includes('weather') || tool.includes('forecast')) return '天气查询';
  if (tool.includes('location') || tool.includes('geocode') || tool.includes('position')) return '定位解析';
  if (tool.includes('search') || tool.includes('internet') || tool.includes('web')) return '联网搜索';
  if (tool.includes('order') || tool.includes('logistics')) return '订单查询';
  if (tool.includes('knowledge') || tool.includes('rag')) return '知识库检索';
  if (tool.includes('product') || tool.includes('inventory') || tool.includes('recommend')
    || tool.includes('price') || tool.includes('stock') || tool.includes('catalog')) return '商品查询';
  if (tool.includes('refund') || tool.includes('return')) return '售后处理';
  if (tool.includes('database') || tool.includes('query') || tool.includes('sql')) return '数据查询';
  if (tool.includes('discover')) return '能力发现';
  return '智能工具';
}

function stripEmotionPrefix(value?: string | null): string {
  return (value || '')
    .replace(
      /^\s*(?:\[\[?(?:用户\s*)?(?:emotion|sentiment|情绪)\s*[:：=]\s*[^\]\n]+\]\]?|【(?:用户\s*)?(?:emotion|sentiment|情绪)\s*[:：=]\s*[^】\n]+】)\s*/gi,
      '',
    )
    .replace(
      /^\s*(?:用户\s*)?(?:emotion|sentiment|情绪)\s*[:：=]\s*(?:positive|negative|neutral|积极|消极|中性|正向|负向|轻微负面|轻微正面)(?:\s*\([^\n)]*\))?\s*(?:[-—:：]\s*)?/gi,
      '',
    )
    .trim();
}

export function sanitizeMessageContent(value?: string | null): string {
  return stripEmotionPrefix(value) || '（无文本内容）';
}

export function formatConversationTitle(value?: string | null): string {
  return stripEmotionPrefix(value) || '未命名对话';
}

export function formatDateTime(value?: string | null): string {
  if (!value) return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '—';
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
  }).format(date);
}

export function formatDay(value?: string | null): string {
  if (!value) return '—';
  const date = new Date(`${value.length === 10 ? `${value}T00:00:00` : value}`);
  if (Number.isNaN(date.getTime())) return value.slice(5);
  return `${date.getMonth() + 1}/${date.getDate()}`;
}

export function formatPercent(value: number): string {
  // The admin API contract returns percentage points in the 0..100 range.
  // Do not reinterpret values such as 1.0 as ratios: that is a valid 1% rate.
  const normalized = Number.isFinite(value) ? value : 0;
  return `${normalized.toFixed(normalized >= 10 ? 1 : 2)}%`;
}

export function formatDuration(value: number): string {
  if (!Number.isFinite(value) || value <= 0) return '—';
  if (value < 1000) return `${Math.round(value)} ms`;
  return `${(value / 1000).toFixed(value < 10000 ? 2 : 1)} s`;
}

export function formatTokenCount(value: number | null, maximumFractionDigits = 0): string {
  if (value === null || !Number.isFinite(value) || value < 0) return '—';
  return new Intl.NumberFormat('zh-CN', { maximumFractionDigits }).format(value);
}

export function formatKnowledgeCategory(value: string): string {
  return KNOWLEDGE_CATEGORIES.find(item => item.value === value)?.label || '其他知识';
}

export function getErrorMessage(error: unknown, fallback = '请求失败，请稍后重试'): string {
  if (error instanceof DOMException && error.name === 'AbortError') return '请求超时，请稍后重试';
  if (error instanceof Error && error.message) {
    if (/failed to fetch|networkerror|network request failed/i.test(error.message)) {
      return '网络连接异常，请检查服务后重试';
    }
    return error.message;
  }
  return fallback;
}
