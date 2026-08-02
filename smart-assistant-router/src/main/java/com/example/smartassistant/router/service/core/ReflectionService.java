/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.core;

import com.example.smartassistant.common.rag.advisor.AiChatService;
import com.example.smartassistant.router.model.ReflectionResult;
import com.example.smartassistant.router.model.SubTaskResult.ErrorType;
import com.example.smartassistant.router.service.agent.AgentCallerService;
import com.example.smartassistant.router.service.agent.AgentDiscoveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 反思器服务——对 Agent 回复进行质量评估，不通过则触发重试。
 *
 * <p>五维评分（总分 0.0 ~ 1.0，阈值默认 0.6）：</p>
 * <ul>
 *     <li>长度检查（0.20）：回复过短（&lt;20字）疑似异常</li>
 *     <li>错误标记检测（0.25）：含 ❌/⚠️/服务异常 等错误特征</li>
 *     <li>关键词覆盖（0.25）：问题中的命名实体（地点/菜品等）是否在回复中出现</li>
 *     <li>Agent 健康状态（0.15）：目标 Agent 是否通过健康检查</li>
 *     <li>意图匹配（0.15）：intentTag 与 agentName 是否匹配</li>
 * </ul>
 *
 * <p>设计原则：</p>
 * <ul>
 *     <li>纯规则评分，不调 LLM（避免增加延迟）</li>
 *     <li>最多重试 1 次，换 fallback Agent</li>
 *     <li>低质量回复不写语义缓存（防污染）</li>
 * </ul>
 *
 * @author SmartAssistant
 * @since 2026-05-18
 */
@Service
public class ReflectionService {

    private static final Logger log = LoggerFactory.getLogger(ReflectionService.class);

    // ==================== 评分维度权重 ====================
    private static final double LENGTH_WEIGHT      = 0.20;
    private static final double ERROR_MARKER_WEIGHT = 0.25;
    private static final double KEYWORD_WEIGHT     = 0.25;
    private static final double HEALTH_WEIGHT      = 0.15;
    private static final double INTENT_WEIGHT      = 0.15;

    // ==================== 停用词（不计入关键词覆盖） ====================
    private static final Set<String> STOP_WORDS = Set.of(
            "的", "了", "是", "在", "我", "有", "和", "就", "不", "人",
            "都", "一", "一个", "上", "也", "很", "到", "说", "要", "去", "你",
            "会", "着", "没有", "看", "好", "自己", "这", "他", "她", "它"
    );

    // ==================== 错误标记正则 ====================
    private static final Pattern ERROR_PATTERN = Pattern.compile(
            "[❌❎⚠️❗❕][\\s]*[A-Za-z]|" +
            "服务异常|服务不可用|暂时无法|系统繁忙|ERROR|error|Exception|exception"
    );

    // ==================== 意图→Agent 匹配规则 ====================
    private static final List<IntentAgentRule> INTENT_AGENT_RULES = List.of(
            new IntentAgentRule("订单",    List.of("order_agent")),
            new IntentAgentRule("物流",    List.of("order_agent")),
            new IntentAgentRule("退款",    List.of("order_agent")),
            new IntentAgentRule("取消",    List.of("order_agent")),
            new IntentAgentRule("商品",    List.of("product_agent")),
            new IntentAgentRule("旅游",    List.of("travel", "order")),
            new IntentAgentRule("美食",    List.of("food", "product")),
            new IntentAgentRule("天气",    List.of("general")),
            new IntentAgentRule("图片",    List.of("general")),
            new IntentAgentRule("计算",    List.of("general")),
            new IntentAgentRule("新闻",    List.of("general"))
    );

    // ==================== 依赖 ====================
    private final AgentCallerService agentCallerService;
    private final AgentDiscoveryService discoveryService;
    private final StringRedisTemplate redisTemplate;
    private final ChatClient fallbackChatClient;

    @Value("${router.reflection.enabled:true}")
    private boolean reflectionEnabled;

    @Value("${router.reflection.threshold:0.60}")
    private double threshold;

    @Value("${router.reflection.max-retry:1}")
    private int maxRetry;

    public ReflectionService(AgentCallerService agentCallerService,
                             @Autowired(required = false) AgentDiscoveryService discoveryService,
                             @Autowired(required = false) StringRedisTemplate redisTemplate,
                             ChatClient.Builder chatClientBuilder,
                             AiChatService aiChatService) {
        this.agentCallerService = agentCallerService;
        this.discoveryService = discoveryService;
        this.redisTemplate = redisTemplate;
        this.fallbackChatClient = aiChatService.applyAdvisors(chatClientBuilder).build();
    }

    // ========================================================================
    // 公开 API
    // ========================================================================

    /**
     * 对 Agent 回复进行质量评估。
     *
     * @param question   用户原始问题
     * @param result     Agent 回复内容
     * @param agentName  执行 Agent 名称
     * @param intentTag  意图标签
     * @param userId     用户 ID
     * @return 反思结果（含是否通过、评分、原因）
     */
    public ReflectionResult evaluate(String question, String result,
                                    String agentName, String intentTag,
                                    Long userId) {
        if (!reflectionEnabled) {
            log.debug("[Reflect] 反思器已关闭，跳过评估");
            return new ReflectionResult(true, 1.0, "reflection.disabled");
        }

        if (result == null || result.isBlank()) {
            return new ReflectionResult(false, 0.0, "result.isBlank()");
        }

        // 权限拒绝和无证据拒答是安全边界的正确输出，不能因为缺少订单详情或带有警示符号
        // 而重试到通用 Agent。后者既无法获得授权数据，也可能触发慢模型并扩大 IDOR 风险面。
        if (isSafeDenial(result)) {
            String reason = "安全拒答，无需 fallback Agent";
            log.info("[Reflect] 安全拒答直接通过: agent={}, reason={}", agentName, reason);
            saveReflectionLog(userId, question, agentName, 1.0, true, reason);
            return new ReflectionResult(true, 1.0, reason);
        }

        double score = 0.0;
        StringBuilder reasonBuilder = new StringBuilder();

        // 维度1：长度检查
        double lengthScore = checkLength(result);
        score += lengthScore * LENGTH_WEIGHT;
        if (lengthScore < 0.5) {
            reasonBuilder.append("长度异常(").append(result.length()).append("字); ");
        }

        // 维度2：错误标记检测
        double errorScore = checkErrorMarkers(result);
        score += errorScore * ERROR_MARKER_WEIGHT;
        if (errorScore < 0.5) {
            reasonBuilder.append("含错误标记; ");
        }

        // 维度3：关键词覆盖
        CoverageAssessment coverage = assessRequirementCoverage(question, result);
        double keywordScore = coverage.score();
        score += keywordScore * KEYWORD_WEIGHT;
        if (!coverage.missingRequirements().isEmpty()) {
            reasonBuilder.append("关键要求未覆盖(")
                    .append(String.join("、", coverage.missingRequirements()))
                    .append("); ");
        }

        // 维度4：Agent 健康状态
        double healthScore = checkAgentHealth(agentName);
        score += healthScore * HEALTH_WEIGHT;
        if (healthScore < 0.5) {
            reasonBuilder.append("Agent 健康状态异常; ");
        }

        // 维度5：意图匹配
        double intentScore = checkIntentMatch(intentTag, agentName);
        score += intentScore * INTENT_WEIGHT;
        if (intentScore < 0.5) {
            reasonBuilder.append("意图与 Agent 不匹配; ");
        }

        boolean hasCriticalFailure = errorScore < 0.5
                || !coverage.missingRequirements().isEmpty();
        boolean acceptable = score >= threshold && !hasCriticalFailure;
        String reason = reasonBuilder.length() > 0
                ? reasonBuilder.toString()
                : "质量合格(score=" + String.format("%.2f", score) + ")";

        log.info("[Reflect] 评估结果: acceptable={}, score={}, agent={}, reason={}",
                acceptable, String.format("%.2f", score), agentName, reason);

        // 写入 Redis 日志（供调试）
        saveReflectionLog(userId, question, agentName, score, acceptable, reason);

        return new ReflectionResult(acceptable, score, reason);
    }

    /**
     * 重试：当质量不通过时，换一个 Agent 重新调用（最多 1 次）。
     *
     * @param question       用户原始问题
     * @param originalResult 原始（低质量）回复
     * @param originalAgent  原始 Agent 名称
     * @param intentTag      意图标签
     * @param userId         用户 ID
     * @param requestId      请求 ID
     * @return 重试后的结果（如重试失败返回原结果）
     */
    public String retry(String question, String originalResult,
                        String originalAgent, String intentTag,
                        Long userId, String requestId) {
        if (!reflectionEnabled) {
            return originalResult;
        }

        // 选择 fallback Agent（排除当前 Agent）
        String fallbackAgent = selectFallbackAgent(originalAgent, intentTag);
        if (fallbackAgent == null) {
            log.warn("[Reflect] 无可用 fallback Agent，返回原结果");
            return originalResult;
        }

        log.info("[Reflect] 质量不通过，重试: originalAgent={}, fallbackAgent={}",
                originalAgent, fallbackAgent);

        try {
            String retryResult = agentCallerService.callAgent(fallbackAgent, question, userId);
            if (retryResult != null && !retryResult.isBlank()
                    && !retryResult.startsWith("❌") && !retryResult.startsWith("⚠️")) {
                // 对重试结果再评估一次
                ReflectionResult recheck = evaluate(question, retryResult,
                        fallbackAgent, intentTag, userId);
                if (recheck.isAcceptable()) {
                    log.info("[Reflect] 重试成功: agent={}, newScore={}",
                            fallbackAgent, String.format("%.2f", recheck.getScore()));
                    return retryResult;
                } else {
                    log.warn("[Reflect] 重试结果仍不通过: score={}, 使用原结果",
                            String.format("%.2f", recheck.getScore()));
                }
            }
        } catch (Exception e) {
            log.warn("[Reflect] 重试调用失败: agent={}, error={}",
                    fallbackAgent, e.getMessage());
        }

        // 重试失败，返回原结果
        return originalResult;
    }

    // ========================================================================
    // 评分维度实现
    // ========================================================================

    /**
     * 维度1：长度检查
     * - &gt;= 100 字：1.0 分
     * - 50~99 字：0.6 分
     * - 20~49 字：0.3 分
     * - &lt; 20 字：0.0 分（疑似异常回复）
     */
    private double checkLength(String result) {
        int len = result.length();
        if (len >= 100) return 1.0;
        if (len >= 50)  return 0.6;
        if (len >= 20)  return 0.3;
        return 0.0;
    }

    /**
     * 维度2：错误标记检测
     * - 不含错误标记：1.0 分
     * - 含错误标记：0.0 分
     */
    private double checkErrorMarkers(String result) {
        if (ERROR_PATTERN.matcher(result).find()) {
            return 0.0;
        }
        // 检查开头是否是错误提示
        String first20 = result.substring(0, Math.min(20, result.length())).trim();
        if (first20.startsWith("❌") || first20.startsWith("⚠️")
                || first20.startsWith("错误") || first20.startsWith("失败")) {
            return 0.0;
        }
        return 1.0;
    }

    /**
     * 维度3：关键词覆盖
     * - 提取问题中的命名实体（简单 heuristic：连续2+个中文字符或英文单词）
     * - 检查这些词是否在回复中出现
     * - 覆盖率 &gt;= 50%：1.0 分；30%~50%：0.6 分；&lt; 30%：0.3 分
     */
    private double checkKeywordCoverage(String question, String result) {
        if (question == null || result == null) return 0.5;

        Set<String> keywords = extractKeywords(question);
        if (keywords.isEmpty()) return 0.5;  // 无关键词，无法判断，给中性分

        long covered = keywords.stream()
                .filter(kw -> result.contains(kw))
                .count();

        double coverage = (double) covered / keywords.size();
        if (coverage >= 0.5) return 1.0;
        if (coverage >= 0.3) return 0.6;
        return 0.3;
    }

    private boolean isSafeDenial(String result) {
        return containsAny(result, List.of(
                "无权查看", "无权访问", "没有权限", "权限不足",
                "未找到该订单", "未找到订单", "没有查询到订单", "订单不存在",
                "请核对订单号", "无法核验", "不会编造"));
    }

    /**
     * 对可确定的用户要求做硬性覆盖检查，避免总分加权掩盖关键项缺失。
     */
    private CoverageAssessment assessRequirementCoverage(String question, String result) {
        if (question == null || result == null) {
            return new CoverageAssessment(0.5, List.of());
        }

        List<String> required = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        checkRequirement(question, result, required, missing, "订单状态",
                List.of("当前状态", "订单状态", "状态"),
                List.of("当前状态", "订单状态", "状态"));
        checkRequirement(question, result, required, missing, "支付方式",
                List.of("支付方式", "付款方式"),
                List.of("支付方式", "付款方式"));
        checkRequirement(question, result, required, missing, "物流公司",
                List.of("物流公司", "快递公司", "承运公司"),
                List.of("物流公司", "快递公司", "承运公司"));
        checkRequirement(question, result, required, missing, "物流轨迹",
                List.of("物流轨迹", "最新轨迹", "最新两条", "最新2条"),
                List.of("物流轨迹", "最新轨迹", "条轨迹"));
        checkRequirement(question, result, required, missing, "取消条件判断",
                List.of("取消条件", "能否取消", "是否可以取消", "满足取消"),
                List.of("取消条件", "可直接取消", "不满足直接取消", "满足直接取消"));

        Matcher orderIdMatcher = Pattern.compile("ORD-[A-Za-z0-9_-]+").matcher(question);
        while (orderIdMatcher.find()) {
            String orderId = orderIdMatcher.group();
            required.add("订单号");
            if (!result.contains(orderId)) {
                missing.add("订单号");
            }
        }

        Matcher markerMatcher = Pattern
                .compile("核验标记\\s*[:：]?\\s*([A-Za-z0-9][A-Za-z0-9_-]{2,64})")
                .matcher(question);
        if (markerMatcher.find()) {
            required.add("核验标记");
            if (!result.contains(markerMatcher.group(1))) {
                missing.add("核验标记");
            }
        }

        if (required.isEmpty()) {
            return new CoverageAssessment(checkKeywordCoverage(question, result), List.of());
        }
        double ratio = (double) (required.size() - missing.size()) / required.size();
        double score = ratio >= 0.8 ? 1.0 : ratio >= 0.5 ? 0.6 : 0.3;
        return new CoverageAssessment(score, List.copyOf(missing));
    }

    private void checkRequirement(String question, String result,
                                  List<String> required, List<String> missing,
                                  String label, List<String> questionTerms,
                                  List<String> resultTerms) {
        if (containsAny(question, questionTerms)) {
            required.add(label);
            if (!containsAny(result, resultTerms)) {
                missing.add(label);
            }
        }
    }

    private boolean containsAny(String text, List<String> terms) {
        return terms.stream().anyMatch(text::contains);
    }

    /**
     * 维度4：Agent 健康状态
     * - 健康（在发现列表中存在）：1.0 分
     * - 不健康或无法检测：0.5 分（降级处理，不直接判死刑）
     */
    private double checkAgentHealth(String agentName) {
        if (discoveryService == null) return 0.7;  // 无发现服务，给默认分
        try {
            var agent = discoveryService.getCachedAgents().stream()
                    .filter(candidate -> agentName.equals(candidate.getAgentName())
                            || agentName.equals(candidate.getServiceName()))
                    .findFirst()
                    .orElse(null);
            return agent != null ? 1.0 : 0.5;
        } catch (Exception e) {
            log.warn("[Reflect] 健康检查失败: agent={}, error={}", agentName, e.getMessage());
            return 0.5;
        }
    }

    /**
     * 维度5：意图匹配
     * - intentTag 与 agentName 匹配：1.0 分
     * - 部分匹配（如 intent=旅游 且 agent=general）：0.6 分
     * - 完全不匹配：0.3 分
     */
    private double checkIntentMatch(String intentTag, String agentName) {
        if (intentTag == null || agentName == null) return 0.5;

        for (IntentAgentRule rule : INTENT_AGENT_RULES) {
            if (intentTag.contains(rule.intentKeyword)) {
                if (rule.matchedAgents.stream().anyMatch(agentName::contains)) {
                    return 1.0;
                }
            }
        }

        // 通用 Agent（general/builtin）处理所有意图，给基础分
        if (agentName.contains("general") || agentName.contains("builtin")
                || agentName.contains("fallback")) {
            return 0.6;
        }

        return 0.3;  // 不匹配
    }

    // ========================================================================
    // 辅助方法
    // ========================================================================

    // ========================================================================
    // 验收标准检查（P1 新增）
    // ========================================================================

    /**
     * 基于 successCriteria 对 Agent 输出进行目标达成度校验。
     * <p>
     * 调用 LLM 轻量 prompt 判断回复是否满足验收标准：
     * <ul>
     *   <li>返回 {@link ErrorType#NONE}：满足标准，可继续</li>
     *   <li>返回 {@link ErrorType#RETRYABLE_FAILED}：结果为空/不可用，可重试同节点</li>
     *   <li>返回 {@link ErrorType#NEED_REPLAN}：不满足标准，需重新规划</li>
     * </ul>
     * </p>
     *
     * @param result          Agent 输出
     * @param successCriteria 验收标准（可空，空则跳过检查）
     * @return 错误类型
     */
    public ErrorType checkCriteria(String result, String successCriteria) {
        if (successCriteria == null || successCriteria.isBlank()) {
            return ErrorType.NONE;  // 无验收标准，跳过检查
        }
        if (result == null || result.isBlank()) {
            log.warn("[Reflect] 结果为空，验收标准: {}", truncate(successCriteria, 80));
            return ErrorType.RETRYABLE_FAILED;
        }

        try {
            String prompt = String.format("""
                    判断以下 AI 回复是否满足验收标准。

                    验收标准：%s

                    回复内容：
                    %s

                    只回答一个词：PASS 或 FAIL。不要解释。
                    """, successCriteria, truncate(result, 2000));

            String llmResponse = fallbackChatClient.prompt().user(prompt).call().content();
            if (llmResponse == null || llmResponse.isBlank()) {
                log.warn("[Reflect] LLM 验收检查返回空，默认通过");
                return ErrorType.NONE;
            }

            boolean passed = llmResponse.toUpperCase().contains("PASS") && !llmResponse.toUpperCase().contains("FAIL");
            if (passed) {
                log.debug("[Reflect] 验收通过: criteria={}", truncate(successCriteria, 80));
                return ErrorType.NONE;
            } else {
                log.warn("[Reflect] ⚠️ 验收不通过: criteria={}, llmResponse={}",
                        truncate(successCriteria, 80), llmResponse);
                return ErrorType.NEED_REPLAN;
            }
        } catch (Exception e) {
            log.warn("[Reflect] 验收检查异常: criteria={}, error={}",
                    truncate(successCriteria, 80), e.getMessage());
            return ErrorType.NONE;  // 检查失败默认放行，避免误杀
        }
    }

    // ========================================================================
    // 辅助方法
    // ========================================================================

    /** 截断文本 */
    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    /**
     * 选择 fallback Agent（排除当前 Agent）
     */
    private String selectFallbackAgent(String originalAgent, String intentTag) {
        // 根据意图选择 fallback
        if (intentTag != null) {
            for (IntentAgentRule rule : INTENT_AGENT_RULES) {
                if (intentTag.contains(rule.intentKeyword)) {
                    return rule.matchedAgents.stream()
                            .filter(a -> !a.equals(originalAgent))
                            .findFirst()
                            .orElse("general");
                }
            }
        }
        // 默认 fallback
        return "general".equals(originalAgent) ? null : "general";
    }

    /**
     * 简单关键词提取（heuristic）
     * - 连续 2+ 个中文字符
     * - 连续 2+ 个英文字母组成的单词
     * - 过滤停用词
     */
    private Set<String> extractKeywords(String text) {
        Set<String> keywords = new HashSet<>();
        if (text == null) return keywords;

        // 匹配连续中文字符（2+）
        Pattern chinesePattern = Pattern.compile("[\\u4e00-\\u9fa5]{2,}");
        var chineseMatcher = chinesePattern.matcher(text);
        while (chineseMatcher.find()) {
            String word = chineseMatcher.group().trim();
            if (!STOP_WORDS.contains(word)) {
                keywords.add(word);
            }
        }

        // 匹配英文单词（2+ 字母）
        Pattern englishPattern = Pattern.compile("\\b[a-zA-Z]{2,}\\b");
        var englishMatcher = englishPattern.matcher(text);
        while (englishMatcher.find()) {
            String word = englishMatcher.group().toLowerCase();
            if (!STOP_WORDS.contains(word)) {
                keywords.add(word);
            }
        }

        return keywords;
    }

    private record CoverageAssessment(double score, List<String> missingRequirements) {
    }

    /**
     * 将反思日志写入 Redis（供调试/监控）
     * Key: a2a:reflect:{userId}:{timestamp}
     * TTL: 7 天
     */
    private void saveReflectionLog(Long userId, String question,
                                   String agentName, double score,
                                   boolean acceptable, String reason) {
        if (redisTemplate == null || userId == null) return;
        try {
            String logKey = "a2a:reflect:" + userId + ":" + System.currentTimeMillis();
            String logValue = String.format(
                    "question=%s|agent=%s|score=%.2f|acceptable=%s|reason=%s",
                    question.substring(0, Math.min(50, question.length())),
                    agentName, score, acceptable, reason
            );
            redisTemplate.opsForValue().set(logKey, logValue, 7, java.util.concurrent.TimeUnit.DAYS);
        } catch (Exception e) {
            log.warn("[Reflect] 写入 Redis 日志失败: {}", e.getMessage());
        }
    }

    // ========================================================================
    // 内部类
    // ========================================================================

    /**
     * 意图→Agent 匹配规则
     */
    private static class IntentAgentRule {
        final String intentKeyword;
        final List<String> matchedAgents;

        IntentAgentRule(String intentKeyword, List<String> matchedAgents) {
            this.intentKeyword = intentKeyword;
            this.matchedAgents = matchedAgents;
        }
    }
}
