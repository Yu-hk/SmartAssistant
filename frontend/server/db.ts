import Database from 'better-sqlite3';
import path from 'path';
import { fileURLToPath } from 'url';
import fs from 'fs';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

// 数据库文件路径
const dbPath = path.join(__dirname, '..', 'data', 'chat.db');

// 确保 data 目录存在
const dataDir = path.dirname(dbPath);
if (!fs.existsSync(dataDir)) {
  fs.mkdirSync(dataDir, { recursive: true });
}

// 创建数据库连接
const db = new Database(dbPath);

// 启用 WAL 模式以提高性能
db.pragma('journal_mode = WAL');

// 初始化数据库表
db.exec(`
  -- 会话表（客服专用扩展）
  CREATE TABLE IF NOT EXISTS sessions (
    id TEXT PRIMARY KEY,
    title TEXT NOT NULL,
    model TEXT NOT NULL,
    sdk_session_id TEXT,
    intent TEXT DEFAULT 'unknown',
    status TEXT DEFAULT 'active' CHECK (status IN ('active', 'human_transfer', 'closed')),
    satisfaction INTEGER DEFAULT NULL,
    satisfaction_comment TEXT DEFAULT NULL,
    user_name TEXT DEFAULT '访客',
    agent_name TEXT DEFAULT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
  );

  -- 消息表（客服专用扩展）
  CREATE TABLE IF NOT EXISTS messages (
    id TEXT PRIMARY KEY,
    session_id TEXT NOT NULL,
    role TEXT NOT NULL CHECK (role IN ('user', 'assistant', 'system')),
    content TEXT NOT NULL,
    model TEXT,
    intent TEXT DEFAULT NULL,
    created_at TEXT NOT NULL,
    tool_calls TEXT,
    FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
  );

  -- FAQ 知识库表
  CREATE TABLE IF NOT EXISTS faq (
    id TEXT PRIMARY KEY,
    category TEXT NOT NULL,
    question TEXT NOT NULL,
    answer TEXT NOT NULL,
    keywords TEXT NOT NULL,
    hit_count INTEGER DEFAULT 0,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
  );

  -- 索引
  CREATE INDEX IF NOT EXISTS idx_messages_session_id ON messages(session_id);
  CREATE INDEX IF NOT EXISTS idx_sessions_status ON sessions(status);
  CREATE INDEX IF NOT EXISTS idx_sessions_intent ON sessions(intent);
  CREATE INDEX IF NOT EXISTS idx_sessions_created_at ON sessions(created_at);
  CREATE INDEX IF NOT EXISTS idx_faq_category ON faq(category);
`);

// 数据库迁移：兼容旧版结构
const tryAddColumn = (table: string, column: string, definition: string) => {
  try {
    const info = db.prepare(`PRAGMA table_info(${table})`).all() as Array<{ name: string }>;
    if (!info.some(col => col.name === column)) {
      db.exec(`ALTER TABLE ${table} ADD COLUMN ${column} ${definition}`);
    }
  } catch (e) { /* ignore */ }
};

tryAddColumn('sessions', 'sdk_session_id', 'TEXT');
tryAddColumn('sessions', 'intent', "TEXT DEFAULT 'unknown'");
tryAddColumn('sessions', 'status', "TEXT DEFAULT 'active'");
tryAddColumn('sessions', 'satisfaction', 'INTEGER DEFAULT NULL');
tryAddColumn('sessions', 'satisfaction_comment', 'TEXT DEFAULT NULL');
tryAddColumn('sessions', 'user_name', "TEXT DEFAULT '访客'");
tryAddColumn('sessions', 'agent_name', 'TEXT DEFAULT NULL');
tryAddColumn('messages', 'intent', 'TEXT DEFAULT NULL');

// 插入默认 FAQ 数据（如果为空）
const faqCount = (db.prepare('SELECT COUNT(*) as cnt FROM faq').get() as { cnt: number }).cnt;
if (faqCount === 0) {
  const insertFaq = db.prepare(`
    INSERT INTO faq (id, category, question, answer, keywords, hit_count, created_at, updated_at)
    VALUES (?, ?, ?, ?, ?, 0, ?, ?)
  `);
  const now = new Date().toISOString();
  const faqs = [
    // 退款相关
    ['faq-001', 'refund', '如何申请退款？', '您可以在订单页面点击"申请退款"按钮，填写退款原因后提交。退款将在3-5个工作日内原路返回。如需加急处理，请联系人工客服。', '退款,退钱,退货,申请退款', now, now],
    ['faq-002', 'refund', '退款多久到账？', '退款审核通过后，一般3-5个工作日内到账。微信/支付宝退款最快1个工作日，银行卡退款需3-5个工作日。如超时未到账，请联系客服。', '退款时间,几天到账,退款到账', now, now],
    ['faq-003', 'refund', '商品已使用可以退款吗？', '已使用的商品一般不支持退款，但如果商品存在质量问题，可以申请售后处理。请提供相关照片或视频证明，我们会为您处理。', '已使用退款,质量问题,售后', now, now],
    // 订单相关
    ['faq-004', 'order', '如何查询订单状态？', '您可以在"我的订单"页面查看所有订单状态。订单状态包括：待支付、已支付、配送中、已完成、已取消。也可以联系客服提供订单号查询。', '查询订单,订单状态,我的订单', now, now],
    ['faq-005', 'order', '订单可以修改吗？', '订单在未发货前可以修改收货地址。已发货的订单无法修改，如需更改请联系快递公司。如需修改商品，需要取消原订单重新下单。', '修改订单,修改地址,改地址', now, now],
    ['faq-006', 'order', '如何取消订单？', '待支付订单可直接取消。已支付未发货的订单，请在订单详情页申请取消，通常24小时内处理。已发货的订单需申请退货。', '取消订单,取消,不要了', now, now],
    // 技术支持
    ['faq-007', 'tech', 'APP 登录不了怎么办？', '请尝试以下步骤：1. 检查网络连接；2. 清除 APP 缓存；3. 卸载重新安装；4. 检查账号是否被封禁。如仍无法解决，请联系技术支持。', '登录失败,登录不了,无法登录', now, now],
    ['faq-008', 'tech', '支付失败怎么处理？', '支付失败可能原因：1. 银行卡余额不足；2. 网络不稳定；3. 银行限制。建议：更换支付方式，或联系发卡行确认是否有限制。如问题持续，请联系客服。', '支付失败,付款失败,支付不了', now, now],
    ['faq-009', 'tech', '账号被封怎么办？', '如您的账号被封禁，请通过申诉渠道提交身份证明和申诉理由。我们会在3个工作日内审核处理。如账号违规，封禁决定可能无法撤销。', '账号封禁,被封,申诉', now, now],
    // 通用
    ['faq-010', 'general', '客服工作时间是什么？', '人工客服服务时间：工作日 9:00-21:00，节假日 10:00-18:00。AI 智能客服 24 小时全天候为您服务。', '客服时间,工作时间,什么时候有客服', now, now],
    ['faq-011', 'general', '如何联系人工客服？', '您可以通过以下方式联系人工客服：1. 点击对话框的"转人工"按钮；2. 拨打客服热线 400-XXX-XXXX；3. 发送邮件至 support@example.com。', '人工客服,转人工,联系客服', now, now],
    ['faq-012', 'general', '发票如何开具？', '请在订单完成后，在"我的订单"中找到对应订单，点击"申请发票"按钮，填写发票信息（个人/企业），7个工作日内可收到电子发票。', '发票,开发票,电子发票', now, now],
  ];
  const insertMany = db.transaction(() => {
    for (const faq of faqs) {
      insertFaq.run(...faq);
    }
  });
  insertMany();
  console.log('[DB] 已插入默认 FAQ 数据');
}

// ============= 类型定义 =============

export type IntentType = 'refund' | 'order' | 'tech' | 'general' | 'unknown';
export type SessionStatus = 'active' | 'human_transfer' | 'closed';

export interface DbSession {
  id: string;
  title: string;
  model: string;
  sdk_session_id: string | null;
  intent: IntentType;
  status: SessionStatus;
  satisfaction: number | null;
  satisfaction_comment: string | null;
  user_name: string;
  agent_name: string | null;
  created_at: string;
  updated_at: string;
}

export interface DbMessage {
  id: string;
  session_id: string;
  role: 'user' | 'assistant' | 'system';
  content: string;
  model: string | null;
  intent: string | null;
  created_at: string;
  tool_calls: string | null;
}

export interface DbFaq {
  id: string;
  category: string;
  question: string;
  answer: string;
  keywords: string;
  hit_count: number;
  created_at: string;
  updated_at: string;
}

// ============= 会话操作 =============

export function getAllSessions(): DbSession[] {
  const stmt = db.prepare('SELECT * FROM sessions ORDER BY updated_at DESC');
  return stmt.all() as DbSession[];
}

export function getSession(id: string): DbSession | undefined {
  const stmt = db.prepare('SELECT * FROM sessions WHERE id = ?');
  return stmt.get(id) as DbSession | undefined;
}

export function createSession(session: Partial<DbSession> & { id: string; title: string; model: string; created_at: string; updated_at: string }): DbSession {
  const stmt = db.prepare(`
    INSERT INTO sessions (id, title, model, sdk_session_id, intent, status, satisfaction, satisfaction_comment, user_name, agent_name, created_at, updated_at)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
  `);
  stmt.run(
    session.id, session.title, session.model,
    session.sdk_session_id || null,
    session.intent || 'unknown',
    session.status || 'active',
    session.satisfaction || null,
    session.satisfaction_comment || null,
    session.user_name || '访客',
    session.agent_name || null,
    session.created_at, session.updated_at
  );
  return getSession(session.id)!;
}

export function updateSession(id: string, updates: Partial<Omit<DbSession, 'id' | 'created_at'>>): boolean {
  const fields: string[] = [];
  const values: any[] = [];
  const allowed = ['title', 'model', 'sdk_session_id', 'intent', 'status', 'satisfaction', 'satisfaction_comment', 'user_name', 'agent_name'];
  for (const key of allowed) {
    if (key in updates) {
      fields.push(`${key} = ?`);
      values.push((updates as any)[key]);
    }
  }
  if (fields.length === 0) return false;
  fields.push('updated_at = ?');
  values.push(new Date().toISOString());
  values.push(id);
  const stmt = db.prepare(`UPDATE sessions SET ${fields.join(', ')} WHERE id = ?`);
  const result = stmt.run(...values);
  return result.changes > 0;
}

export function deleteSession(id: string): boolean {
  const stmt = db.prepare('DELETE FROM sessions WHERE id = ?');
  const result = stmt.run(id);
  return result.changes > 0;
}

// ============= 消息操作 =============

export function getMessagesBySession(sessionId: string): DbMessage[] {
  const stmt = db.prepare('SELECT * FROM messages WHERE session_id = ? ORDER BY created_at ASC');
  return stmt.all(sessionId) as DbMessage[];
}

export function createMessage(message: Partial<DbMessage> & { id: string; session_id: string; role: string; content: string; created_at: string }): DbMessage {
  const stmt = db.prepare(`
    INSERT INTO messages (id, session_id, role, content, model, intent, created_at, tool_calls)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
  `);
  stmt.run(
    message.id, message.session_id, message.role, message.content,
    message.model || null, message.intent || null,
    message.created_at, message.tool_calls || null
  );
  const updateStmt = db.prepare('UPDATE sessions SET updated_at = ? WHERE id = ?');
  updateStmt.run(new Date().toISOString(), message.session_id);
  return message as DbMessage;
}

export function updateMessage(id: string, updates: Partial<Pick<DbMessage, 'content' | 'tool_calls'>>): boolean {
  const fields: string[] = [];
  const values: any[] = [];
  if (updates.content !== undefined) { fields.push('content = ?'); values.push(updates.content); }
  if (updates.tool_calls !== undefined) { fields.push('tool_calls = ?'); values.push(updates.tool_calls); }
  if (fields.length === 0) return false;
  values.push(id);
  const stmt = db.prepare(`UPDATE messages SET ${fields.join(', ')} WHERE id = ?`);
  const result = stmt.run(...values);
  return result.changes > 0;
}

export function deleteMessage(id: string): boolean {
  const stmt = db.prepare('DELETE FROM messages WHERE id = ?');
  return stmt.run(id).changes > 0;
}

// ============= FAQ 操作 =============

export function getAllFaqs(): DbFaq[] {
  return db.prepare('SELECT * FROM faq ORDER BY category, question').all() as DbFaq[];
}

export function getFaqsByCategory(category: string): DbFaq[] {
  return db.prepare('SELECT * FROM faq WHERE category = ? ORDER BY hit_count DESC').all(category) as DbFaq[];
}

export function searchFaqs(query: string): DbFaq[] {
  const likeQuery = `%${query}%`;
  return db.prepare(
    'SELECT * FROM faq WHERE question LIKE ? OR keywords LIKE ? OR answer LIKE ? ORDER BY hit_count DESC LIMIT 5'
  ).all(likeQuery, likeQuery, likeQuery) as DbFaq[];
}

export function incrementFaqHit(id: string): void {
  db.prepare('UPDATE faq SET hit_count = hit_count + 1, updated_at = ? WHERE id = ?')
    .run(new Date().toISOString(), id);
}

export function createFaq(faq: Omit<DbFaq, 'hit_count' | 'created_at' | 'updated_at'>): DbFaq {
  const now = new Date().toISOString();
  db.prepare(`
    INSERT INTO faq (id, category, question, answer, keywords, hit_count, created_at, updated_at)
    VALUES (?, ?, ?, ?, ?, 0, ?, ?)
  `).run(faq.id, faq.category, faq.question, faq.answer, faq.keywords, now, now);
  return db.prepare('SELECT * FROM faq WHERE id = ?').get(faq.id) as DbFaq;
}

export function updateFaq(id: string, updates: Partial<Pick<DbFaq, 'question' | 'answer' | 'keywords' | 'category'>>): boolean {
  const fields: string[] = [];
  const values: any[] = [];
  const allowed = ['question', 'answer', 'keywords', 'category'];
  for (const key of allowed) {
    if (key in updates) { fields.push(`${key} = ?`); values.push((updates as any)[key]); }
  }
  if (fields.length === 0) return false;
  fields.push('updated_at = ?');
  values.push(new Date().toISOString());
  values.push(id);
  return db.prepare(`UPDATE faq SET ${fields.join(', ')} WHERE id = ?`).run(...values).changes > 0;
}

export function deleteFaq(id: string): boolean {
  return db.prepare('DELETE FROM faq WHERE id = ?').run(id).changes > 0;
}

// ============= 统计分析 =============

export interface SatisfactionStats {
  total: number;
  rated: number;
  avg_score: number | null;
  score_1: number; score_2: number; score_3: number; score_4: number; score_5: number;
}

export interface IntentStats {
  intent: string;
  count: number;
  transfer_count: number;
}

export interface DailyStats {
  date: string;
  session_count: number;
  avg_satisfaction: number | null;
}

export function getSatisfactionStats(): SatisfactionStats {
  const total = (db.prepare('SELECT COUNT(*) as cnt FROM sessions').get() as any).cnt;
  const rated = (db.prepare('SELECT COUNT(*) as cnt FROM sessions WHERE satisfaction IS NOT NULL').get() as any).cnt;
  const avgRow = db.prepare('SELECT AVG(satisfaction) as avg FROM sessions WHERE satisfaction IS NOT NULL').get() as any;
  const scores: any = {};
  for (let i = 1; i <= 5; i++) {
    scores[`score_${i}`] = (db.prepare('SELECT COUNT(*) as cnt FROM sessions WHERE satisfaction = ?').get(i) as any).cnt;
  }
  return { total, rated, avg_score: avgRow.avg ? Math.round(avgRow.avg * 100) / 100 : null, ...scores };
}

export function getIntentStats(): IntentStats[] {
  return db.prepare(`
    SELECT intent, COUNT(*) as count,
           SUM(CASE WHEN status = 'human_transfer' THEN 1 ELSE 0 END) as transfer_count
    FROM sessions GROUP BY intent ORDER BY count DESC
  `).all() as IntentStats[];
}

export function getDailyStats(days: number = 7): DailyStats[] {
  return db.prepare(`
    SELECT DATE(created_at) as date, COUNT(*) as session_count,
           ROUND(AVG(satisfaction), 2) as avg_satisfaction
    FROM sessions WHERE created_at >= DATE('now', '-' || ? || ' days')
    GROUP BY DATE(created_at) ORDER BY date ASC
  `).all(days) as DailyStats[];
}

export function getTransferRate(): number {
  const total = (db.prepare('SELECT COUNT(*) as cnt FROM sessions').get() as any).cnt;
  if (total === 0) return 0;
  const transfers = (db.prepare("SELECT COUNT(*) as cnt FROM sessions WHERE status = 'human_transfer'").get() as any).cnt;
  return Math.round((transfers / total) * 100);
}

export function clearAllData(): void {
  db.exec('DELETE FROM messages');
  db.exec('DELETE FROM sessions');
}

export default db;
