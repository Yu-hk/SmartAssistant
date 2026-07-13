/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.evaluation;

import com.example.smartassistant.common.correction.CorrectionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * P1 Bad Case 自动挖掘服务。
 *
 * <p>监听路由决策事件，将低置信度（疑似错误）的路由决策记录为 Bad Case，
 * 供后续测试集补充和模型优化使用。</p>
 *
 * <p>挖掘策略（可组合）：
 * <ul>
 *   <li>① 低置信度：routingResult.confidence &lt; confidenceThreshold</li>
 *   <li>② 缓存未命中 + 低置信度（新意图未覆盖）</li>
 *   <li>③ 用户纠正信号：同一 session 内用户推翻 / 纠正了前次回答（P5-B 已实现，见 {@link #recordCorrection}）</li>
 * </ul>
 * </p>
 *
 * <p>Bad Case 写入 Redis List {@code a2a:bad-cases}，
 * 管理端点 {@code GET /api/router/admin/bad-cases} 可查看。</p>
 */
@Service
public class BadCaseMinerService {

    private static final Logger log = LoggerFactory.getLogger(BadCaseMinerService.class);
    private static final String BAD_CASE_KEY = "a2a:bad-cases";
    private static final int MAX_BAD_CASES = 500;

    @Value("${router.bad-case.enabled:true}")
    private boolean enabled;

    @Value("${router.bad-case.confidence-threshold:0.6}")
    private double confidenceThreshold;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * ⭐ P5-B 用户纠正信号闭环：命中纠正信号时，把纠正持久化到各 Agent 的纠错记录，
     * 供后续回答查询（GeneralTools 已接 queryCorrections），避免重复犯错。
     * 可选，null 时仅写 Bad Case，不持久化纠正。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private CorrectionService correctionService;

    public void setCorrectionService(CorrectionService correctionService) {
        this.correctionService = correctionService;
    }

    /** 测试 / 手动注入用 setter（默认由 @Value 注入） */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public BadCaseMinerService(StringRedisTemplate redisTemplate,
                               ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 记录一次路由决策（供 RouterService 调用）。
     * 满足 Bad Case 条件时写入 Redis。
     */
    public void record(RoutingDecision decision) {
        if (!enabled) return;
        if (decision == null || decision.question() == null) return;

        boolean isBadCase = decision.confidence() < confidenceThreshold;
        if (!isBadCase) return;

        try {
            BadCaseRecord record = BadCaseRecord.builder()
                    .question(decision.question())
                    .predictedIntent(decision.predictedIntent())
                    .confidence(decision.confidence())
                    .agentName(decision.agentName())
                    .sessionId(decision.sessionId())
                    .userId(decision.userId())
                    .reason("低置信度: " + decision.confidence())
                    .createdAt(LocalDateTime.now())
                    .resolved(false)
                    .build();

            String json = objectMapper.writeValueAsString(record);
            redisTemplate.opsForList().leftPush(BAD_CASE_KEY, json);

            // 裁剪列表长度
            Long size = redisTemplate.opsForList().size(BAD_CASE_KEY);
            if (size != null && size > MAX_BAD_CASES) {
                redisTemplate.opsForList().trim(BAD_CASE_KEY, 0, MAX_BAD_CASES - 1);
            }

            log.warn("[BadCase] 挖掘到疑似 Bad Case: question={}, intent={}, confidence={}",
                    truncate(decision.question(), 50),
                    decision.predictedIntent(),
                    decision.confidence());

        } catch (Exception e) {
            log.warn("[BadCase] 记录失败: {}", e.getMessage());
        }
    }

    /**
     * ⭐ P5-B 用户纠正信号挖掘（策略 ③）。
     *
     * <p>对每一轮路由决策，用确定性 {@link CorrectionSignalDetector} 检测本轮用户消息是否为
     * 「纠正信号」。命中时：
     * <ol>
     *   <li>写入一条 Bad Case，reason 标注「用户纠正信号 (③)」+ 命中的纠正关键词；</li>
     *   <li>若 {@link CorrectionService} 可用，把纠正持久化到对应 Agent 的纠错记录，
     *       后续回答前可经 GeneralTools.queryCorrections 查询，避免重复犯错。</li>
     * </ol>
     * 该方法是非阻断的：任何异常仅记日志，绝不影响主路由链路。
     */
    public void recordCorrection(RoutingDecision decision) {
        if (!enabled) return;
        if (decision == null || decision.question() == null) return;

        try {
            CorrectionSignalDetector.CorrectionSignal signal =
                    CorrectionSignalDetector.detect(decision.question());
            if (!signal.isCorrection()) return;

            // ① 写入 Bad Case（复用既有结构，reason 标注 ③）
            BadCaseRecord record = BadCaseRecord.builder()
                    .question(decision.question())
                    .predictedIntent(decision.predictedIntent())
                    .confidence(decision.confidence())
                    .agentName(decision.agentName())
                    .sessionId(decision.sessionId())
                    .userId(decision.userId())
                    .reason("用户纠正信号 (③): markers=" + signal.getMarkers())
                    .createdAt(LocalDateTime.now())
                    .resolved(false)
                    .build();

            String json = objectMapper.writeValueAsString(record);
            redisTemplate.opsForList().leftPush(BAD_CASE_KEY, json);
            Long size = redisTemplate.opsForList().size(BAD_CASE_KEY);
            if (size != null && size > MAX_BAD_CASES) {
                redisTemplate.opsForList().trim(BAD_CASE_KEY, 0, MAX_BAD_CASES - 1);
            }

            log.warn("[BadCase] ⭐ 挖掘到用户纠正信号(③): question={}, markers={}, agent={}",
                    truncate(decision.question(), 50), signal.getMarkers(), decision.agentName());

            // ② 持久化纠正到 CorrectionService（可选）
            if (correctionService != null && decision.agentName() != null) {
                long uid = decision.userId() != null ? decision.userId() : -1L;
                correctionService.appendCorrection(
                        decision.agentName(),
                        decision.question(),
                        "(前次回答被用户纠正)",
                        decision.question(),
                        uid);
            }

        } catch (Exception e) {
            log.warn("[BadCase] 纠正信号记录失败: {}", e.getMessage());
        }
    }

    /**
     * 获取最近的 Bad Case 列表（供管理端点调用）。
     */
    public List<BadCaseRecord> getRecent(int max) {
        try {
            List<String> raw = redisTemplate.opsForList().range(BAD_CASE_KEY, 0, max - 1);
            if (raw == null) return List.of();
            List<BadCaseRecord> result = new ArrayList<>();
            for (String json : raw) {
                try {
                    result.add(objectMapper.readValue(json, BadCaseRecord.class));
                } catch (Exception e) {
                    log.warn("[BadCaseMiner] 解析坏例记录失败: {}", e.getMessage());
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("[BadCase] 读取失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 每日凌晨清理 7 天前的 Bad Case（定时任务）。
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanupOldBadCases() {
        try {
            Long removed = redisTemplate.opsForList().size(BAD_CASE_KEY);
            // 简单策略：保留最近 200 条
            redisTemplate.opsForList().trim(BAD_CASE_KEY, 0, 199);
            log.info("[BadCase] 定时清理完成");
        } catch (Exception e) {
            log.warn("[BadCase] 清理失败: {}", e.getMessage());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    /**
     * 路由决策快照（Bad Case 挖掘输入）。
     *
     * @param question         用户原始问题
     * @param predictedIntent  预测的意图标签
     * @param confidence       置信度
     * @param agentName        路由到的 Agent
     * @param sessionId        Session ID
     * @param userId           User ID
     */
    public record RoutingDecision(
            String question,
            String predictedIntent,
            double confidence,
            String agentName,
            String sessionId,
            Long userId
    ) {}

    /**
     * Bad Case 记录（持久化到 Redis JSON）。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BadCaseRecord {
        private String question;
        private String predictedIntent;
        private double confidence;
        private String agentName;
        private String sessionId;
        private Long userId;
        private String reason;
        private LocalDateTime createdAt;
        private boolean resolved;
        private String correctIntent;   // 人工标注的正确意图
        private String note;            // 人工标注备注
    }
}
