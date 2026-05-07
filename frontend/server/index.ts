import express from "express";
import { query, unstable_v2_authenticate, PermissionResult } from "@tencent-ai/agent-sdk";
import { v4 as uuidv4 } from "uuid";
import path from "path";
import { fileURLToPath } from "url";
import * as db from "./db.js";

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const app = express();
const PORT = process.env.PORT || 3001;

app.use(express.json());

// ============= 意图识别逻辑 =============

const INTENT_PATTERNS: Record<string, string[]> = {
  refund: ['退款', '退货', '退钱', '退回', '不想要', '申请退', '退一下', '退掉', '赔偿', '赔款'],
  order: ['订单', '快递', '物流', '发货', '配送', '查询', '查一下', '什么时候到', '到哪了', '下单', '取消订单', '修改订单', '收货', '签收'],
  tech: ['登录', '登不上', '无法登录', '密码', '支付', '付款', '卡顿', '崩溃', '报错', 'APP', '安装', '更新', '闪退', 'bug', 'BUG', '出错', '故障', '技术'],
  general: ['发票', '联系', '客服', '时间', '营业', '售后', '保修', '积分', '优惠', '活动'],
};

function detectIntent(text: string): db.IntentType {
  const lower = text.toLowerCase();
  for (const [intent, keywords] of Object.entries(INTENT_PATTERNS)) {
    if (keywords.some(k => lower.includes(k.toLowerCase()))) {
      return intent as db.IntentType;
    }
  }
  return 'unknown';
}

// 判断是否需要转人工
function shouldTransferToHuman(text: string, intent: db.IntentType): boolean {
  const transferKeywords = ['人工', '转人工', '真人', '客服', '要找人', '不要机器人', '小姐姐', '帮我'];
  const urgentKeywords = ['投诉', '举报', '起诉', '律师', '骗', '欺诈', '假冒', '维权'];
  return transferKeywords.some(k => text.includes(k)) || urgentKeywords.some(k => text.includes(k));
}

// ============= 智能客服系统提示词 =============

const CUSTOMER_SERVICE_SYSTEM_PROMPT = `你是一个专业、友好的智能客服助手。你的职责是帮助用户解决以下类型的问题：

1. **退款/退货问题** (意图: refund)：帮助用户了解退款流程、查询退款状态
2. **订单查询** (意图: order)：帮助用户查询订单状态、物流信息
3. **技术支持** (意图: tech)：解决登录、支付、APP使用等技术问题
4. **通用咨询** (意图: general)：回答发票、客服时间等通用问题

**重要指导原则：**
- 始终保持专业、耐心、友好的态度
- 优先使用FAQ知识库中的标准答案
- 对于复杂问题，引导用户提供更多信息
- 如果无法解决问题，主动建议转接人工客服
- 回答要简洁明了，避免冗长
- 在中文环境下，使用中文回答

**转人工场景：**
当以下情况发生时，请在回复末尾加上 "[TRANSFER_TO_HUMAN]" 标记：
- 用户明确要求人工客服
- 问题涉及金额超过500元的退款
- 涉及投诉、举报、维权等敏感情况
- 连续3次未能解决用户问题
- 账号安全相关的紧急问题

请始终以用户满意为目标，提供高质量的客服服务。`;

// ============= 健康检查 =============

app.get("/api/health", (_req, res) => {
  res.json({ status: "ok", timestamp: new Date().toISOString() });
});

// ============= 登录检查 =============

app.get("/api/check-login", async (_req, res) => {
  const apiKey = process.env.CODEBUDDY_API_KEY;
  const authToken = process.env.CODEBUDDY_AUTH_TOKEN;
  
  if (apiKey || authToken) {
    res.json({
      isLoggedIn: true,
      method: 'env',
      envConfigured: true,
      apiKey: apiKey ? apiKey.slice(0, 8) + '****' + apiKey.slice(-4) : undefined
    });
    return;
  }

  try {
    let needsLogin = false;
    const result = await unstable_v2_authenticate({
      environment: 'external',
      onAuthUrl: async () => { needsLogin = true; }
    });
    if (!needsLogin && result?.userinfo) {
      res.json({ isLoggedIn: true, method: 'cli', cliConfigured: true });
    } else {
      res.json({ isLoggedIn: false, method: 'none', error: '未登录，请配置 CODEBUDDY_API_KEY' });
    }
  } catch (e: any) {
    res.json({ isLoggedIn: false, method: 'none', error: e?.message });
  }
});

// 保存环境变量
app.post("/api/save-env-config", (req, res) => {
  const { apiKey, authToken } = req.body;
  if (!apiKey && !authToken) {
    return res.status(400).json({ error: '请配置 API Key' });
  }
  if (apiKey) process.env.CODEBUDDY_API_KEY = apiKey;
  if (authToken) process.env.CODEBUDDY_AUTH_TOKEN = authToken;
  res.json({ success: true, message: '配置已保存' });
});

// 获取模型列表（简化版）
app.get("/api/models", (_req, res) => {
  res.json({
    models: [
      { modelId: "claude-sonnet-4", name: "Claude Sonnet 4（推荐）" },
      { modelId: "claude-opus-4", name: "Claude Opus 4（高质量）" },
    ],
    defaultModel: "claude-sonnet-4"
  });
});

// ============= 会话 API =============

app.get("/api/sessions", (_req, res) => {
  try {
    const sessions = db.getAllSessions();
    const sessionsWithMessages = sessions.map(session => ({
      ...session,
      messageCount: db.getMessagesBySession(session.id).length
    }));
    res.json({ sessions: sessionsWithMessages });
  } catch (e: any) {
    res.status(500).json({ error: e?.message });
  }
});

app.get("/api/sessions/:sessionId", (req, res) => {
  try {
    const session = db.getSession(req.params.sessionId);
    if (!session) return res.status(404).json({ error: "会话不存在" });
    const messages = db.getMessagesBySession(req.params.sessionId).map(msg => ({
      ...msg,
      tool_calls: msg.tool_calls ? JSON.parse(msg.tool_calls) : null
    }));
    res.json({ session, messages });
  } catch (e: any) {
    res.status(500).json({ error: e?.message });
  }
});

app.post("/api/sessions", (req, res) => {
  try {
    const { model = "claude-sonnet-4", title = "新对话", user_name = "访客" } = req.body;
    const now = new Date().toISOString();
    const session = db.createSession({
      id: uuidv4(), title, model, user_name,
      created_at: now, updated_at: now
    });
    res.json({ session });
  } catch (e: any) {
    res.status(500).json({ error: e?.message });
  }
});

app.patch("/api/sessions/:sessionId", (req, res) => {
  try {
    const success = db.updateSession(req.params.sessionId, req.body);
    if (!success) return res.status(404).json({ error: "会话不存在" });
    res.json({ success: true });
  } catch (e: any) {
    res.status(500).json({ error: e?.message });
  }
});

app.delete("/api/sessions/:sessionId", (req, res) => {
  try {
    const success = db.deleteSession(req.params.sessionId);
    if (!success) return res.status(404).json({ error: "会话不存在" });
    res.json({ success: true });
  } catch (e: any) {
    res.status(500).json({ error: e?.message });
  }
});

// ============= 满意度评分 API =============

app.post("/api/sessions/:sessionId/satisfaction", (req, res) => {
  try {
    const { score, comment } = req.body;
    if (!score || score < 1 || score > 5) {
      return res.status(400).json({ error: "评分必须在1-5之间" });
    }
    const success = db.updateSession(req.params.sessionId, {
      satisfaction: score,
      satisfaction_comment: comment || null,
      status: 'closed'
    });
    if (!success) return res.status(404).json({ error: "会话不存在" });
    res.json({ success: true, message: "感谢您的评价！" });
  } catch (e: any) {
    res.status(500).json({ error: e?.message });
  }
});

// ============= 转人工 API =============

app.post("/api/sessions/:sessionId/transfer", (req, res) => {
  try {
    const { reason = "用户请求" } = req.body;
    db.updateSession(req.params.sessionId, { status: 'human_transfer' });
    // 添加系统消息
    db.createMessage({
      id: uuidv4(),
      session_id: req.params.sessionId,
      role: 'system',
      content: `[系统] 会话已转接人工客服。原因：${reason}`,
      created_at: new Date().toISOString()
    });
    res.json({ success: true, message: "已转接人工客服，请稍候..." });
  } catch (e: any) {
    res.status(500).json({ error: e?.message });
  }
});

// ============= FAQ API =============

app.get("/api/faq", (_req, res) => {
  try {
    res.json({ faqs: db.getAllFaqs() });
  } catch (e: any) {
    res.status(500).json({ error: e?.message });
  }
});

app.get("/api/faq/search", (req, res) => {
  try {
    const { q = '' } = req.query as { q: string };
    if (!q.trim()) return res.json({ faqs: [] });
    const faqs = db.searchFaqs(q);
    res.json({ faqs });
  } catch (e: any) {
    res.status(500).json({ error: e?.message });
  }
});

app.post("/api/faq/:id/hit", (req, res) => {
  db.incrementFaqHit(req.params.id);
  res.json({ success: true });
});

app.post("/api/faq", (req, res) => {
  try {
    const { category, question, answer, keywords } = req.body;
    if (!category || !question || !answer || !keywords) {
      return res.status(400).json({ error: "缺少必要字段" });
    }
    const faq = db.createFaq({ id: uuidv4(), category, question, answer, keywords });
    res.json({ faq });
  } catch (e: any) {
    res.status(500).json({ error: e?.message });
  }
});

app.patch("/api/faq/:id", (req, res) => {
  try {
    const success = db.updateFaq(req.params.id, req.body);
    res.json({ success });
  } catch (e: any) {
    res.status(500).json({ error: e?.message });
  }
});

app.delete("/api/faq/:id", (req, res) => {
  try {
    const success = db.deleteFaq(req.params.id);
    res.json({ success });
  } catch (e: any) {
    res.status(500).json({ error: e?.message });
  }
});

// ============= 统计 API =============

app.get("/api/stats", (_req, res) => {
  try {
    const satisfaction = db.getSatisfactionStats();
    const intents = db.getIntentStats();
    const daily = db.getDailyStats(7);
    const transferRate = db.getTransferRate();
    res.json({ satisfaction, intents, daily, transferRate });
  } catch (e: any) {
    res.status(500).json({ error: e?.message });
  }
});

// ============= 权限管理 =============

interface PendingPermission {
  resolve: (result: PermissionResult) => void;
  toolName: string;
  input: Record<string, unknown>;
  sessionId: string;
}

const pendingPermissions = new Map<string, PendingPermission>();

app.post("/api/permission-response", (req, res) => {
  const { requestId, behavior, message } = req.body;
  const pending = pendingPermissions.get(requestId);
  if (!pending) return res.status(404).json({ error: "请求不存在或已超时" });
  pendingPermissions.delete(requestId);
  pending.resolve(behavior === 'allow'
    ? { behavior: 'allow', updatedInput: pending.input }
    : { behavior: 'deny', message: message || '用户拒绝' }
  );
  res.json({ success: true });
});

// ============= 聊天 API（核心） =============

app.post("/api/chat", async (req, res) => {
  const { sessionId, message, model, userName = "访客" } = req.body;

  if (!message?.trim()) {
    return res.status(400).json({ error: "消息不能为空" });
  }

  // 意图识别
  const detectedIntent = detectIntent(message);
  const needsTransfer = shouldTransferToHuman(message, detectedIntent);

  // FAQ 检索
  const faqResults = db.searchFaqs(message);

  // 获取/创建会话
  let session = sessionId ? db.getSession(sessionId) : null;
  const now = new Date().toISOString();

  if (!session) {
    session = db.createSession({
      id: sessionId || uuidv4(),
      title: message.slice(0, 30) + (message.length > 30 ? '...' : ''),
      model: model || "claude-sonnet-4",
      user_name: userName,
      intent: detectedIntent,
      created_at: now, updated_at: now
    });
  } else if (session.intent === 'unknown' && detectedIntent !== 'unknown') {
    db.updateSession(session.id, { intent: detectedIntent });
  }

  const selectedModel = model || session.model;
  const userMessageId = uuidv4();
  const assistantMessageId = uuidv4();

  // 保存用户消息
  db.createMessage({
    id: userMessageId, session_id: session.id,
    role: 'user', content: message,
    intent: detectedIntent, created_at: now
  });

  // 设置 SSE
  res.setHeader("Content-Type", "text/event-stream");
  res.setHeader("Cache-Control", "no-cache");
  res.setHeader("Connection", "keep-alive");

  // 发送初始化事件（包含意图和FAQ信息）
  res.write(`data: ${JSON.stringify({
    type: "init",
    sessionId: session.id,
    userMessageId, assistantMessageId,
    intent: detectedIntent,
    faqSuggestions: faqResults.slice(0, 3),
    needsTransfer,
    model: selectedModel
  })}\n\n`);

  // 如果需要立即转人工
  if (needsTransfer && session.status !== 'human_transfer') {
    db.updateSession(session.id, { status: 'human_transfer' });
    res.write(`data: ${JSON.stringify({ type: "transfer_to_human", reason: "用户请求人工客服" })}\n\n`);
  }

  // 构建增强的系统提示（含 FAQ 上下文）
  let systemPromptWithFaq = CUSTOMER_SERVICE_SYSTEM_PROMPT;
  if (faqResults.length > 0) {
    systemPromptWithFaq += `\n\n**当前问题相关知识库参考：**\n`;
    faqResults.slice(0, 3).forEach((faq, i) => {
      systemPromptWithFaq += `\n${i + 1}. Q: ${faq.question}\n   A: ${faq.answer}\n`;
      db.incrementFaqHit(faq.id);
    });
  }

  // 调用 Agent SDK
  try {
    const stream = query({
      prompt: message,
      options: {
        cwd: process.cwd(),
        model: selectedModel,
        maxTurns: 5,
        systemPrompt: systemPromptWithFaq,
        permissionMode: 'bypassPermissions',
        ...(session.sdk_session_id ? { resume: session.sdk_session_id } : {})
      }
    });

    let fullResponse = "";
    let newSdkSessionId: string | null = null;

    for await (const msg of stream) {
      if (msg.type === "system" && (msg as any).subtype === "init") {
        newSdkSessionId = (msg as any).session_id;
        if (newSdkSessionId && newSdkSessionId !== session.sdk_session_id) {
          db.updateSession(session.id, { sdk_session_id: newSdkSessionId });
        }
      } else if (msg.type === "assistant") {
        const content = msg.message.content;
        if (typeof content === "string") {
          fullResponse += content;
          res.write(`data: ${JSON.stringify({ type: "text", content })}\n\n`);
        } else if (Array.isArray(content)) {
          for (const block of content) {
            if (block.type === "text") {
              fullResponse += block.text;
              res.write(`data: ${JSON.stringify({ type: "text", content: block.text })}\n\n`);
            }
          }
        }
      } else if (msg.type === "result") {
        res.write(`data: ${JSON.stringify({ type: "done" })}\n\n`);
      }
    }

    // 检测响应中的转人工标记
    if (fullResponse.includes('[TRANSFER_TO_HUMAN]') && session.status !== 'human_transfer') {
      db.updateSession(session.id, { status: 'human_transfer' });
      res.write(`data: ${JSON.stringify({ type: "transfer_to_human", reason: "AI 判断需要人工处理" })}\n\n`);
      fullResponse = fullResponse.replace('[TRANSFER_TO_HUMAN]', '').trim();
    }

    // 保存助手消息
    db.createMessage({
      id: assistantMessageId, session_id: session.id,
      role: 'assistant', content: fullResponse,
      model: selectedModel, created_at: new Date().toISOString()
    });

    // 更新会话信息
    const messages = db.getMessagesBySession(session.id);
    if (messages.length <= 2) {
      db.updateSession(session.id, {
        title: message.slice(0, 30) + (message.length > 30 ? '...' : ''),
        model: selectedModel
      });
    }

    res.end();
  } catch (error: any) {
    console.error("[Chat] Error:", error?.message);
    const errorMsg = error?.message || "处理请求时发生错误";
    res.write(`data: ${JSON.stringify({ type: "error", message: errorMsg })}\n\n`);
    res.end();
  }
});

// ============= 启动服务器 =============

app.listen(PORT, () => {
  console.log(`
╔══════════════════════════════════════════════════╗
║                                                  ║
║     🤖 智能客服 Agent 后端已启动                    ║
║                                                  ║
║     地址:   http://localhost:${PORT}                ║
║     数据库: SQLite (data/chat.db)                 ║
║     功能:   FAQ检索 | 意图识别 | 转人工 | 统计       ║
║                                                  ║
╚══════════════════════════════════════════════════╝
  `);
});
