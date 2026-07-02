var __assign = (this && this.__assign) || function () {
    __assign = Object.assign || function(t) {
        for (var s, i = 1, n = arguments.length; i < n; i++) {
            s = arguments[i];
            for (var p in s) if (Object.prototype.hasOwnProperty.call(s, p))
                t[p] = s[p];
        }
        return t;
    };
    return __assign.apply(this, arguments);
};
var __awaiter = (this && this.__awaiter) || function (thisArg, _arguments, P, generator) {
    function adopt(value) { return value instanceof P ? value : new P(function (resolve) { resolve(value); }); }
    return new (P || (P = Promise))(function (resolve, reject) {
        function fulfilled(value) { try { step(generator.next(value)); } catch (e) { reject(e); } }
        function rejected(value) { try { step(generator["throw"](value)); } catch (e) { reject(e); } }
        function step(result) { result.done ? resolve(result.value) : adopt(result.value).then(fulfilled, rejected); }
        step((generator = generator.apply(thisArg, _arguments || [])).next());
    });
};
var __generator = (this && this.__generator) || function (thisArg, body) {
    var _ = { label: 0, sent: function() { if (t[0] & 1) throw t[1]; return t[1]; }, trys: [], ops: [] }, f, y, t, g = Object.create((typeof Iterator === "function" ? Iterator : Object).prototype);
    return g.next = verb(0), g["throw"] = verb(1), g["return"] = verb(2), typeof Symbol === "function" && (g[Symbol.iterator] = function() { return this; }), g;
    function verb(n) { return function (v) { return step([n, v]); }; }
    function step(op) {
        if (f) throw new TypeError("Generator is already executing.");
        while (g && (g = 0, op[0] && (_ = 0)), _) try {
            if (f = 1, y && (t = op[0] & 2 ? y["return"] : op[0] ? y["throw"] || ((t = y["return"]) && t.call(y), 0) : y.next) && !(t = t.call(y, op[1])).done) return t;
            if (y = 0, t) op = [op[0] & 2, t.value];
            switch (op[0]) {
                case 0: case 1: t = op; break;
                case 4: _.label++; return { value: op[1], done: false };
                case 5: _.label++; y = op[1]; op = [0]; continue;
                case 7: op = _.ops.pop(); _.trys.pop(); continue;
                default:
                    if (!(t = _.trys, t = t.length > 0 && t[t.length - 1]) && (op[0] === 6 || op[0] === 2)) { _ = 0; continue; }
                    if (op[0] === 3 && (!t || (op[1] > t[0] && op[1] < t[3]))) { _.label = op[1]; break; }
                    if (op[0] === 6 && _.label < t[1]) { _.label = t[1]; t = op; break; }
                    if (t && _.label < t[2]) { _.label = t[2]; _.ops.push(op); break; }
                    if (t[2]) _.ops.pop();
                    _.trys.pop(); continue;
            }
            op = body.call(thisArg, _);
        } catch (e) { op = [6, e]; y = 0; } finally { f = t = 0; }
        if (op[0] & 5) throw op[1]; return { value: op[0] ? op[1] : void 0, done: true };
    }
};
var __asyncValues = (this && this.__asyncValues) || function (o) {
    if (!Symbol.asyncIterator) throw new TypeError("Symbol.asyncIterator is not defined.");
    var m = o[Symbol.asyncIterator], i;
    return m ? m.call(o) : (o = typeof __values === "function" ? __values(o) : o[Symbol.iterator](), i = {}, verb("next"), verb("throw"), verb("return"), i[Symbol.asyncIterator] = function () { return this; }, i);
    function verb(n) { i[n] = o[n] && function (v) { return new Promise(function (resolve, reject) { v = o[n](v), settle(resolve, reject, v.done, v.value); }); }; }
    function settle(resolve, reject, d, v) { Promise.resolve(v).then(function(v) { resolve({ value: v, done: d }); }, reject); }
};
import express from "express";
import { query, unstable_v2_authenticate } from "@tencent-ai/agent-sdk";
import { v4 as uuidv4 } from "uuid";
import path from "path";
import { fileURLToPath } from "url";
import * as db from "./db.js";
var __filename = fileURLToPath(import.meta.url);
var __dirname = path.dirname(__filename);
var app = express();
var PORT = process.env.PORT || 3001;
app.use(express.json());
// ============= 意图识别逻辑 =============
var INTENT_PATTERNS = {
    refund: ['退款', '退货', '退钱', '退回', '不想要', '申请退', '退一下', '退掉', '赔偿', '赔款'],
    order: ['订单', '快递', '物流', '发货', '配送', '查询', '查一下', '什么时候到', '到哪了', '下单', '取消订单', '修改订单', '收货', '签收'],
    tech: ['登录', '登不上', '无法登录', '密码', '支付', '付款', '卡顿', '崩溃', '报错', 'APP', '安装', '更新', '闪退', 'bug', 'BUG', '出错', '故障', '技术'],
    general: ['发票', '联系', '客服', '时间', '营业', '售后', '保修', '积分', '优惠', '活动'],
};
function detectIntent(text) {
    var lower = text.toLowerCase();
    for (var _i = 0, _a = Object.entries(INTENT_PATTERNS); _i < _a.length; _i++) {
        var _b = _a[_i], intent = _b[0], keywords = _b[1];
        if (keywords.some(function (k) { return lower.includes(k.toLowerCase()); })) {
            return intent;
        }
    }
    return 'unknown';
}
// 判断是否需要转人工
function shouldTransferToHuman(text, intent) {
    var transferKeywords = ['人工', '转人工', '真人', '客服', '要找人', '不要机器人', '小姐姐', '帮我'];
    var urgentKeywords = ['投诉', '举报', '起诉', '律师', '骗', '欺诈', '假冒', '维权'];
    return transferKeywords.some(function (k) { return text.includes(k); }) || urgentKeywords.some(function (k) { return text.includes(k); });
}
// ============= 智能客服系统提示词 =============
var CUSTOMER_SERVICE_SYSTEM_PROMPT = "\u4F60\u662F\u4E00\u4E2A\u4E13\u4E1A\u3001\u53CB\u597D\u7684\u667A\u80FD\u5BA2\u670D\u52A9\u624B\u3002\u4F60\u7684\u804C\u8D23\u662F\u5E2E\u52A9\u7528\u6237\u89E3\u51B3\u4EE5\u4E0B\u7C7B\u578B\u7684\u95EE\u9898\uFF1A\n\n1. **\u9000\u6B3E/\u9000\u8D27\u95EE\u9898** (\u610F\u56FE: refund)\uFF1A\u5E2E\u52A9\u7528\u6237\u4E86\u89E3\u9000\u6B3E\u6D41\u7A0B\u3001\u67E5\u8BE2\u9000\u6B3E\u72B6\u6001\n2. **\u8BA2\u5355\u67E5\u8BE2** (\u610F\u56FE: order)\uFF1A\u5E2E\u52A9\u7528\u6237\u67E5\u8BE2\u8BA2\u5355\u72B6\u6001\u3001\u7269\u6D41\u4FE1\u606F\n3. **\u6280\u672F\u652F\u6301** (\u610F\u56FE: tech)\uFF1A\u89E3\u51B3\u767B\u5F55\u3001\u652F\u4ED8\u3001APP\u4F7F\u7528\u7B49\u6280\u672F\u95EE\u9898\n4. **\u901A\u7528\u54A8\u8BE2** (\u610F\u56FE: general)\uFF1A\u56DE\u7B54\u53D1\u7968\u3001\u5BA2\u670D\u65F6\u95F4\u7B49\u901A\u7528\u95EE\u9898\n\n**\u91CD\u8981\u6307\u5BFC\u539F\u5219\uFF1A**\n- \u59CB\u7EC8\u4FDD\u6301\u4E13\u4E1A\u3001\u8010\u5FC3\u3001\u53CB\u597D\u7684\u6001\u5EA6\n- \u4F18\u5148\u4F7F\u7528FAQ\u77E5\u8BC6\u5E93\u4E2D\u7684\u6807\u51C6\u7B54\u6848\n- \u5BF9\u4E8E\u590D\u6742\u95EE\u9898\uFF0C\u5F15\u5BFC\u7528\u6237\u63D0\u4F9B\u66F4\u591A\u4FE1\u606F\n- \u5982\u679C\u65E0\u6CD5\u89E3\u51B3\u95EE\u9898\uFF0C\u4E3B\u52A8\u5EFA\u8BAE\u8F6C\u63A5\u4EBA\u5DE5\u5BA2\u670D\n- \u56DE\u7B54\u8981\u7B80\u6D01\u660E\u4E86\uFF0C\u907F\u514D\u5197\u957F\n- \u5728\u4E2D\u6587\u73AF\u5883\u4E0B\uFF0C\u4F7F\u7528\u4E2D\u6587\u56DE\u7B54\n\n**\u8F6C\u4EBA\u5DE5\u573A\u666F\uFF1A**\n\u5F53\u4EE5\u4E0B\u60C5\u51B5\u53D1\u751F\u65F6\uFF0C\u8BF7\u5728\u56DE\u590D\u672B\u5C3E\u52A0\u4E0A \"[TRANSFER_TO_HUMAN]\" \u6807\u8BB0\uFF1A\n- \u7528\u6237\u660E\u786E\u8981\u6C42\u4EBA\u5DE5\u5BA2\u670D\n- \u95EE\u9898\u6D89\u53CA\u91D1\u989D\u8D85\u8FC7500\u5143\u7684\u9000\u6B3E\n- \u6D89\u53CA\u6295\u8BC9\u3001\u4E3E\u62A5\u3001\u7EF4\u6743\u7B49\u654F\u611F\u60C5\u51B5\n- \u8FDE\u7EED3\u6B21\u672A\u80FD\u89E3\u51B3\u7528\u6237\u95EE\u9898\n- \u8D26\u53F7\u5B89\u5168\u76F8\u5173\u7684\u7D27\u6025\u95EE\u9898\n\n\u8BF7\u59CB\u7EC8\u4EE5\u7528\u6237\u6EE1\u610F\u4E3A\u76EE\u6807\uFF0C\u63D0\u4F9B\u9AD8\u8D28\u91CF\u7684\u5BA2\u670D\u670D\u52A1\u3002";
// ============= 健康检查 =============
app.get("/api/health", function (_req, res) {
    res.json({ status: "ok", timestamp: new Date().toISOString() });
});
// ============= 登录检查 =============
app.get("/api/check-login", function (_req, res) { return __awaiter(void 0, void 0, void 0, function () {
    var apiKey, authToken, needsLogin_1, result, e_1;
    return __generator(this, function (_a) {
        switch (_a.label) {
            case 0:
                apiKey = process.env.CODEBUDDY_API_KEY;
                authToken = process.env.CODEBUDDY_AUTH_TOKEN;
                if (apiKey || authToken) {
                    res.json({
                        isLoggedIn: true,
                        method: 'env',
                        envConfigured: true,
                        apiKey: apiKey ? apiKey.slice(0, 8) + '****' + apiKey.slice(-4) : undefined
                    });
                    return [2 /*return*/];
                }
                _a.label = 1;
            case 1:
                _a.trys.push([1, 3, , 4]);
                needsLogin_1 = false;
                return [4 /*yield*/, unstable_v2_authenticate({
                        environment: 'external',
                        onAuthUrl: function () { return __awaiter(void 0, void 0, void 0, function () { return __generator(this, function (_a) {
                            needsLogin_1 = true;
                            return [2 /*return*/];
                        }); }); }
                    })];
            case 2:
                result = _a.sent();
                if (!needsLogin_1 && (result === null || result === void 0 ? void 0 : result.userinfo)) {
                    res.json({ isLoggedIn: true, method: 'cli', cliConfigured: true });
                }
                else {
                    res.json({ isLoggedIn: false, method: 'none', error: '未登录，请配置 CODEBUDDY_API_KEY' });
                }
                return [3 /*break*/, 4];
            case 3:
                e_1 = _a.sent();
                res.json({ isLoggedIn: false, method: 'none', error: e_1 === null || e_1 === void 0 ? void 0 : e_1.message });
                return [3 /*break*/, 4];
            case 4: return [2 /*return*/];
        }
    });
}); });
// 保存环境变量
app.post("/api/save-env-config", function (req, res) {
    var _a = req.body, apiKey = _a.apiKey, authToken = _a.authToken;
    if (!apiKey && !authToken) {
        return res.status(400).json({ error: '请配置 API Key' });
    }
    if (apiKey)
        process.env.CODEBUDDY_API_KEY = apiKey;
    if (authToken)
        process.env.CODEBUDDY_AUTH_TOKEN = authToken;
    res.json({ success: true, message: '配置已保存' });
});
// 获取模型列表（简化版）
app.get("/api/models", function (_req, res) {
    res.json({
        models: [
            { modelId: "claude-sonnet-4", name: "Claude Sonnet 4（推荐）" },
            { modelId: "claude-opus-4", name: "Claude Opus 4（高质量）" },
        ],
        defaultModel: "claude-sonnet-4"
    });
});
// ============= 会话 API =============
app.get("/api/sessions", function (_req, res) {
    try {
        var sessions = db.getAllSessions();
        var sessionsWithMessages = sessions.map(function (session) { return (__assign(__assign({}, session), { messageCount: db.getMessagesBySession(session.id).length })); });
        res.json({ sessions: sessionsWithMessages });
    }
    catch (e) {
        res.status(500).json({ error: e === null || e === void 0 ? void 0 : e.message });
    }
});
app.get("/api/sessions/:sessionId", function (req, res) {
    try {
        var session = db.getSession(req.params.sessionId);
        if (!session)
            return res.status(404).json({ error: "会话不存在" });
        var messages = db.getMessagesBySession(req.params.sessionId).map(function (msg) { return (__assign(__assign({}, msg), { tool_calls: msg.tool_calls ? JSON.parse(msg.tool_calls) : null })); });
        res.json({ session: session, messages: messages });
    }
    catch (e) {
        res.status(500).json({ error: e === null || e === void 0 ? void 0 : e.message });
    }
});
app.post("/api/sessions", function (req, res) {
    try {
        var _a = req.body, _b = _a.model, model = _b === void 0 ? "claude-sonnet-4" : _b, _c = _a.title, title = _c === void 0 ? "新对话" : _c, _d = _a.user_name, user_name = _d === void 0 ? "访客" : _d;
        var now = new Date().toISOString();
        var session = db.createSession({
            id: uuidv4(),
            title: title,
            model: model,
            user_name: user_name,
            created_at: now, updated_at: now
        });
        res.json({ session: session });
    }
    catch (e) {
        res.status(500).json({ error: e === null || e === void 0 ? void 0 : e.message });
    }
});
app.patch("/api/sessions/:sessionId", function (req, res) {
    try {
        var success = db.updateSession(req.params.sessionId, req.body);
        if (!success)
            return res.status(404).json({ error: "会话不存在" });
        res.json({ success: true });
    }
    catch (e) {
        res.status(500).json({ error: e === null || e === void 0 ? void 0 : e.message });
    }
});
app.delete("/api/sessions/:sessionId", function (req, res) {
    try {
        var success = db.deleteSession(req.params.sessionId);
        if (!success)
            return res.status(404).json({ error: "会话不存在" });
        res.json({ success: true });
    }
    catch (e) {
        res.status(500).json({ error: e === null || e === void 0 ? void 0 : e.message });
    }
});
// ============= 满意度评分 API =============
app.post("/api/sessions/:sessionId/satisfaction", function (req, res) {
    try {
        var _a = req.body, score = _a.score, comment = _a.comment;
        if (!score || score < 1 || score > 5) {
            return res.status(400).json({ error: "评分必须在1-5之间" });
        }
        var success = db.updateSession(req.params.sessionId, {
            satisfaction: score,
            satisfaction_comment: comment || null,
            status: 'closed'
        });
        if (!success)
            return res.status(404).json({ error: "会话不存在" });
        res.json({ success: true, message: "感谢您的评价！" });
    }
    catch (e) {
        res.status(500).json({ error: e === null || e === void 0 ? void 0 : e.message });
    }
});
// ============= 转人工 API =============
app.post("/api/sessions/:sessionId/transfer", function (req, res) {
    try {
        var _a = req.body.reason, reason = _a === void 0 ? "用户请求" : _a;
        db.updateSession(req.params.sessionId, { status: 'human_transfer' });
        // 添加系统消息
        db.createMessage({
            id: uuidv4(),
            session_id: req.params.sessionId,
            role: 'system',
            content: "[\u7CFB\u7EDF] \u4F1A\u8BDD\u5DF2\u8F6C\u63A5\u4EBA\u5DE5\u5BA2\u670D\u3002\u539F\u56E0\uFF1A".concat(reason),
            created_at: new Date().toISOString()
        });
        res.json({ success: true, message: "已转接人工客服，请稍候..." });
    }
    catch (e) {
        res.status(500).json({ error: e === null || e === void 0 ? void 0 : e.message });
    }
});
// ============= FAQ API =============
app.get("/api/faq", function (_req, res) {
    try {
        res.json({ faqs: db.getAllFaqs() });
    }
    catch (e) {
        res.status(500).json({ error: e === null || e === void 0 ? void 0 : e.message });
    }
});
app.get("/api/faq/search", function (req, res) {
    try {
        var _a = req.query.q, q = _a === void 0 ? '' : _a;
        if (!q.trim())
            return res.json({ faqs: [] });
        var faqs = db.searchFaqs(q);
        res.json({ faqs: faqs });
    }
    catch (e) {
        res.status(500).json({ error: e === null || e === void 0 ? void 0 : e.message });
    }
});
app.post("/api/faq/:id/hit", function (req, res) {
    db.incrementFaqHit(req.params.id);
    res.json({ success: true });
});
app.post("/api/faq", function (req, res) {
    try {
        var _a = req.body, category = _a.category, question = _a.question, answer = _a.answer, keywords = _a.keywords;
        if (!category || !question || !answer || !keywords) {
            return res.status(400).json({ error: "缺少必要字段" });
        }
        var faq = db.createFaq({ id: uuidv4(), category: category, question: question, answer: answer, keywords: keywords });
        res.json({ faq: faq });
    }
    catch (e) {
        res.status(500).json({ error: e === null || e === void 0 ? void 0 : e.message });
    }
});
app.patch("/api/faq/:id", function (req, res) {
    try {
        var success = db.updateFaq(req.params.id, req.body);
        res.json({ success: success });
    }
    catch (e) {
        res.status(500).json({ error: e === null || e === void 0 ? void 0 : e.message });
    }
});
app.delete("/api/faq/:id", function (req, res) {
    try {
        var success = db.deleteFaq(req.params.id);
        res.json({ success: success });
    }
    catch (e) {
        res.status(500).json({ error: e === null || e === void 0 ? void 0 : e.message });
    }
});
// ============= 统计 API =============
app.get("/api/stats", function (_req, res) {
    try {
        var satisfaction = db.getSatisfactionStats();
        var intents = db.getIntentStats();
        var daily = db.getDailyStats(7);
        var transferRate = db.getTransferRate();
        res.json({ satisfaction: satisfaction, intents: intents, daily: daily, transferRate: transferRate });
    }
    catch (e) {
        res.status(500).json({ error: e === null || e === void 0 ? void 0 : e.message });
    }
});
var pendingPermissions = new Map();
app.post("/api/permission-response", function (req, res) {
    var _a = req.body, requestId = _a.requestId, behavior = _a.behavior, message = _a.message;
    var pending = pendingPermissions.get(requestId);
    if (!pending)
        return res.status(404).json({ error: "请求不存在或已超时" });
    pendingPermissions.delete(requestId);
    pending.resolve(behavior === 'allow'
        ? { behavior: 'allow', updatedInput: pending.input }
        : { behavior: 'deny', message: message || '用户拒绝' });
    res.json({ success: true });
});
// ============= 聊天 API（核心） =============
app.post("/api/chat", function (req, res) { return __awaiter(void 0, void 0, void 0, function () {
    var _a, sessionId, message, model, _b, userName, detectedIntent, needsTransfer, faqResults, session, now, selectedModel, userMessageId, assistantMessageId, systemPromptWithFaq, stream, fullResponse, newSdkSessionId, _c, stream_1, stream_1_1, msg, content, _i, content_1, block, e_2_1, messages, error_1, errorMsg;
    var _d, e_2, _e, _f;
    return __generator(this, function (_g) {
        switch (_g.label) {
            case 0:
                _a = req.body, sessionId = _a.sessionId, message = _a.message, model = _a.model, _b = _a.userName, userName = _b === void 0 ? "访客" : _b;
                if (!(message === null || message === void 0 ? void 0 : message.trim())) {
                    return [2 /*return*/, res.status(400).json({ error: "消息不能为空" })];
                }
                detectedIntent = detectIntent(message);
                needsTransfer = shouldTransferToHuman(message, detectedIntent);
                faqResults = db.searchFaqs(message);
                session = sessionId ? db.getSession(sessionId) : null;
                now = new Date().toISOString();
                if (!session) {
                    session = db.createSession({
                        id: sessionId || uuidv4(),
                        title: message.slice(0, 30) + (message.length > 30 ? '...' : ''),
                        model: model || "claude-sonnet-4",
                        user_name: userName,
                        intent: detectedIntent,
                        created_at: now, updated_at: now
                    });
                }
                else if (session.intent === 'unknown' && detectedIntent !== 'unknown') {
                    db.updateSession(session.id, { intent: detectedIntent });
                }
                selectedModel = model || session.model;
                userMessageId = uuidv4();
                assistantMessageId = uuidv4();
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
                res.write("data: ".concat(JSON.stringify({
                    type: "init",
                    sessionId: session.id,
                    userMessageId: userMessageId,
                    assistantMessageId: assistantMessageId,
                    intent: detectedIntent,
                    faqSuggestions: faqResults.slice(0, 3),
                    needsTransfer: needsTransfer,
                    model: selectedModel
                }), "\n\n"));
                // 如果需要立即转人工
                if (needsTransfer && session.status !== 'human_transfer') {
                    db.updateSession(session.id, { status: 'human_transfer' });
                    res.write("data: ".concat(JSON.stringify({ type: "transfer_to_human", reason: "用户请求人工客服" }), "\n\n"));
                }
                systemPromptWithFaq = CUSTOMER_SERVICE_SYSTEM_PROMPT;
                if (faqResults.length > 0) {
                    systemPromptWithFaq += "\n\n**\u5F53\u524D\u95EE\u9898\u76F8\u5173\u77E5\u8BC6\u5E93\u53C2\u8003\uFF1A**\n";
                    faqResults.slice(0, 3).forEach(function (faq, i) {
                        systemPromptWithFaq += "\n".concat(i + 1, ". Q: ").concat(faq.question, "\n   A: ").concat(faq.answer, "\n");
                        db.incrementFaqHit(faq.id);
                    });
                }
                _g.label = 1;
            case 1:
                _g.trys.push([1, 14, , 15]);
                stream = query({
                    prompt: message,
                    options: __assign({ cwd: process.cwd(), model: selectedModel, maxTurns: 5, systemPrompt: systemPromptWithFaq, permissionMode: 'bypassPermissions' }, (session.sdk_session_id ? { resume: session.sdk_session_id } : {}))
                });
                fullResponse = "";
                newSdkSessionId = null;
                _g.label = 2;
            case 2:
                _g.trys.push([2, 7, 8, 13]);
                _c = true, stream_1 = __asyncValues(stream);
                _g.label = 3;
            case 3: return [4 /*yield*/, stream_1.next()];
            case 4:
                if (!(stream_1_1 = _g.sent(), _d = stream_1_1.done, !_d)) return [3 /*break*/, 6];
                _f = stream_1_1.value;
                _c = false;
                msg = _f;
                if (msg.type === "system" && msg.subtype === "init") {
                    newSdkSessionId = msg.session_id;
                    if (newSdkSessionId && newSdkSessionId !== session.sdk_session_id) {
                        db.updateSession(session.id, { sdk_session_id: newSdkSessionId });
                    }
                }
                else if (msg.type === "assistant") {
                    content = msg.message.content;
                    if (typeof content === "string") {
                        fullResponse += content;
                        res.write("data: ".concat(JSON.stringify({ type: "text", content: content }), "\n\n"));
                    }
                    else if (Array.isArray(content)) {
                        for (_i = 0, content_1 = content; _i < content_1.length; _i++) {
                            block = content_1[_i];
                            if (block.type === "text") {
                                fullResponse += block.text;
                                res.write("data: ".concat(JSON.stringify({ type: "text", content: block.text }), "\n\n"));
                            }
                        }
                    }
                }
                else if (msg.type === "result") {
                    res.write("data: ".concat(JSON.stringify({ type: "done" }), "\n\n"));
                }
                _g.label = 5;
            case 5:
                _c = true;
                return [3 /*break*/, 3];
            case 6: return [3 /*break*/, 13];
            case 7:
                e_2_1 = _g.sent();
                e_2 = { error: e_2_1 };
                return [3 /*break*/, 13];
            case 8:
                _g.trys.push([8, , 11, 12]);
                if (!(!_c && !_d && (_e = stream_1.return))) return [3 /*break*/, 10];
                return [4 /*yield*/, _e.call(stream_1)];
            case 9:
                _g.sent();
                _g.label = 10;
            case 10: return [3 /*break*/, 12];
            case 11:
                if (e_2) throw e_2.error;
                return [7 /*endfinally*/];
            case 12: return [7 /*endfinally*/];
            case 13:
                // 检测响应中的转人工标记
                if (fullResponse.includes('[TRANSFER_TO_HUMAN]') && session.status !== 'human_transfer') {
                    db.updateSession(session.id, { status: 'human_transfer' });
                    res.write("data: ".concat(JSON.stringify({ type: "transfer_to_human", reason: "AI 判断需要人工处理" }), "\n\n"));
                    fullResponse = fullResponse.replace('[TRANSFER_TO_HUMAN]', '').trim();
                }
                // 保存助手消息
                db.createMessage({
                    id: assistantMessageId, session_id: session.id,
                    role: 'assistant', content: fullResponse,
                    model: selectedModel, created_at: new Date().toISOString()
                });
                messages = db.getMessagesBySession(session.id);
                if (messages.length <= 2) {
                    db.updateSession(session.id, {
                        title: message.slice(0, 30) + (message.length > 30 ? '...' : ''),
                        model: selectedModel
                    });
                }
                res.end();
                return [3 /*break*/, 15];
            case 14:
                error_1 = _g.sent();
                console.error("[Chat] Error:", error_1 === null || error_1 === void 0 ? void 0 : error_1.message);
                errorMsg = (error_1 === null || error_1 === void 0 ? void 0 : error_1.message) || "处理请求时发生错误";
                res.write("data: ".concat(JSON.stringify({ type: "error", message: errorMsg }), "\n\n"));
                res.end();
                return [3 /*break*/, 15];
            case 15: return [2 /*return*/];
        }
    });
}); });
// ============= 启动服务器 =============
app.listen(PORT, function () {
    console.log("\n\u2554\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2557\n\u2551                                                  \u2551\n\u2551     \uD83E\uDD16 \u667A\u80FD\u5BA2\u670D Agent \u540E\u7AEF\u5DF2\u542F\u52A8                    \u2551\n\u2551                                                  \u2551\n\u2551     \u5730\u5740:   http://localhost:".concat(PORT, "                \u2551\n\u2551     \u6570\u636E\u5E93: SQLite (data/chat.db)                 \u2551\n\u2551     \u529F\u80FD:   FAQ\u68C0\u7D22 | \u610F\u56FE\u8BC6\u522B | \u8F6C\u4EBA\u5DE5 | \u7EDF\u8BA1       \u2551\n\u2551                                                  \u2551\n\u255A\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u255D\n  "));
});
