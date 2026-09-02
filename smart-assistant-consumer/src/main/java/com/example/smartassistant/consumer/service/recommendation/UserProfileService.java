/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.service.recommendation;

import com.example.smartassistant.consumer.entity.RoutingCallLog;
import com.example.smartassistant.consumer.entity.UserProfile;
import com.example.smartassistant.consumer.mapper.RoutingCallLogMapper;
import com.example.smartassistant.routing.contract.RoutingKeys;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.Collectors;

/**
 * 电商用户画像服务。
 * PostgreSQL 保存版本化完整快照和不可变变更事件；Redis 承载请求级安全投影和短生命周期提交候选。
 */
@Service
public class UserProfileService {

    private static final Logger log = LoggerFactory.getLogger(UserProfileService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @Value("${preference.prefetch.ttl-seconds:120}")
    private long prefetchTtlSeconds;

    @Value("${preference.analysis.max-history-turns:20}")
    private int maxHistoryTurns;

    @Value("${preference.analysis.max-history-chars:12000}")
    private int maxHistoryChars;

    @Value("${preference.commit.wait-timeout-ms:15000}")
    private long commitWaitTimeoutMs;

    @Value("${preference.commit.poll-interval-ms:50}")
    private long commitPollIntervalMs;

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    @Autowired(required = false)
    private RoutingCallLogMapper routingCallLogMapper;

    @Autowired(required = false)
    @Qualifier("taskExecutor")
    private Executor profileExecutor;

    private final LLMPreferenceExtractor llmExtractor;
    private final UserProfileSnapshotStore profileStore;
    private final UserProfileCommitPublisher commitPublisher;

    public UserProfileService(LLMPreferenceExtractor llmExtractor,
                              UserProfileSnapshotStore profileStore,
                              UserProfileCommitPublisher commitPublisher) {
        this.llmExtractor = llmExtractor;
        this.profileStore = profileStore;
        this.commitPublisher = commitPublisher;
    }

    // ==================== 写入 ====================

    /**
     * 从问题中提取偏好信息并更新用户画像
     */
    public void extractAndUpdatePreferences(Long userId, String question, String extractedLocation) {
        if (userId == null || question == null) return;
        try {
            commitCandidate(analyzeCandidate(userId, question, null));
        } catch (Exception e) {
            log.error("[UserProfile] 数据库画像更新失败: userId={}, error={}", userId, e.getMessage());
            throw e instanceof RuntimeException runtime ? runtime
                    : new IllegalStateException("Unable to persist user profile", e);
        }
    }

    /**
     * 用户画像属于旁路增强，不能占用主对话链路的超时预算。
     */
    @Async("taskExecutor")
    public void extractAndUpdatePreferencesAsync(Long userId, String question, String extractedLocation) {
        extractAndUpdatePreferences(userId, question, extractedLocation);
    }

    /**
     * Starts request-scoped profile preparation without delaying Router planning.
     * Consumer writes {@code PENDING} before scheduling so Router can reliably
     * establish a barrier immediately before the first Product node.
     */
    public CompletableFuture<String> prefetchForRequest(
            Long userId, String question, String requestId) {
        if (userId == null || requestId == null || requestId.isBlank()) {
            return CompletableFuture.completedFuture("");
        }

        String key = RoutingKeys.userProfileContext(requestId);
        if (redisTemplate != null) {
            try {
                redisTemplate.opsForValue().set(key, RoutingKeys.USER_PROFILE_PENDING,
                        Duration.ofSeconds(Math.max(30L, prefetchTtlSeconds)));
            } catch (Exception error) {
                throw new IllegalStateException(
                        "Unable to establish the user-profile coordination barrier", error);
            }
        } else {
            throw new IllegalStateException(
                    "User-profile coordination requires Redis before routing request " + requestId);
        }

        Executor executor = profileExecutor != null ? profileExecutor : Runnable::run;
        try {
            return CompletableFuture
                    .supplyAsync(() -> prepareProfile(userId, question, requestId), executor)
                    .whenComplete((prepared, error) ->
                            publishPrefetchResult(requestId, key, prepared, error))
                    .thenApply(PreparedProfile::projection);
        } catch (RejectedExecutionException error) {
            publishPrefetchResult(requestId, key, null, error);
            return CompletableFuture.failedFuture(error);
        }
    }

    private PreparedProfile prepareProfile(Long userId, String question, String requestId) {
        if (question != null && !question.isBlank()) {
            PreparedProfileCandidate candidate = analyzeCandidate(userId, question, requestId);
            return new PreparedProfile(
                    buildUserProfilePrompt(writeJson(candidate.report())), candidate);
        }
        return new PreparedProfile(buildUserProfilePrompt(userId), null);
    }

    private PreparedProfileCandidate analyzeCandidate(
            Long userId, String question, String requestId) {
        ProfileConversationContext context = buildConversationContext(userId, question);
        Optional<UserProfileSnapshotStore.Snapshot> current = profileStore.load(userId);
        long expectedVersion = current.map(UserProfileSnapshotStore.Snapshot::profileVersion)
                .orElse(0L);
        String currentProfile = current.map(UserProfileSnapshotStore.Snapshot::reportJson)
                .orElse("当前没有已保存画像。");
        LLMPreferenceExtractor.UserInsightReport report =
                llmExtractor.extract(currentProfile, context.text(), question);
        return new PreparedProfileCandidate(
                userId, requestId, expectedVersion, report, question,
                context.sourceMaxMessageId(), context.messageIds());
    }

    private UserProfileSnapshotStore.Snapshot commitCandidate(
            PreparedProfileCandidate candidate) {
        LLMPreferenceExtractor.UserInsightReport report = candidate.report();
        long expectedVersion = candidate.expectedVersion();
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return profileStore.save(candidate.userId(), candidate.requestId(),
                        expectedVersion, report, candidate.sourceMaxMessageId(),
                        candidate.evidenceMessageIds());
            } catch (UserProfileSnapshotStore.OptimisticProfileUpdateException conflict) {
                if (attempt == 1) throw conflict;
                Optional<UserProfileSnapshotStore.Snapshot> current =
                        profileStore.load(candidate.userId());
                expectedVersion = current.map(UserProfileSnapshotStore.Snapshot::profileVersion)
                        .orElse(0L);
                String currentProfile = current
                        .map(UserProfileSnapshotStore.Snapshot::reportJson)
                        .orElse("当前没有已保存画像。");
                report = llmExtractor.extract(currentProfile,
                        "[当前用户消息]\n" + candidate.latestUserMessage(),
                        candidate.latestUserMessage());
                log.info("[UserProfile] 提交时画像版本冲突，已基于最新快照重新分析: userId={}",
                        candidate.userId());
            }
        }
        throw new IllegalStateException("User profile update exhausted retries");
    }

    private void publishPrefetchResult(String requestId, String key,
                                       PreparedProfile prepared, Throwable error) {
        if (redisTemplate == null) return;
        try {
            String state;
            if (error != null) {
                state = RoutingKeys.USER_PROFILE_FAILED;
            } else if (prepared == null || prepared.projection().isBlank()) {
                state = RoutingKeys.USER_PROFILE_EMPTY;
            } else {
                if (prepared.candidate() != null) {
                    redisTemplate.opsForValue().set(
                            RoutingKeys.userProfileCandidate(requestId),
                            writeJson(prepared.candidate()),
                            Duration.ofSeconds(Math.max(30L, prefetchTtlSeconds)));
                }
                state = RoutingKeys.USER_PROFILE_READY_PREFIX + prepared.projection();
            }
            redisTemplate.opsForValue().set(key, state,
                    Duration.ofSeconds(Math.max(30L, prefetchTtlSeconds)));
        } catch (Exception publishError) {
            log.error("[UserProfile] 发布画像预取结果失败: key={}, error={}",
                    key, publishError.getMessage());
            try {
                redisTemplate.opsForValue().set(key, RoutingKeys.USER_PROFILE_FAILED,
                        Duration.ofSeconds(Math.max(30L, prefetchTtlSeconds)));
            } catch (Exception ignored) {
            }
        }
    }

    /** Emits a durable commit command after a turn has completed successfully. */
    @Async("taskExecutor")
    public void commitAfterSuccessfulTurn(Long userId, String requestId) {
        if (userId == null || requestId == null || requestId.isBlank()) return;
        try {
            PreparedProfileCandidate candidate = awaitPreparedCandidate(userId, requestId);
            if (candidate == null) return;
            commitPublisher.publish(candidate);
            redisTemplate.delete(RoutingKeys.userProfileCandidate(requestId));
        } catch (Exception error) {
            log.error("[UserProfile] 发布画像提交事件失败: userId={}, requestId={}, error={}",
                    userId, requestId, error.getMessage());
        }
    }

    /** RabbitMQ entry point: persist the prepared candidate, never the assistant response. */
    public void commitPreparedProfile(PreparedProfileCandidate candidate) {
        if (candidate == null || candidate.userId() == null
                || candidate.requestId() == null || candidate.requestId().isBlank()) {
            throw new IllegalArgumentException("Prepared user profile is incomplete");
        }
        if (profileStore.isRequestApplied(candidate.userId(), candidate.requestId())) return;
        commitCandidate(candidate);
    }

    private PreparedProfileCandidate awaitPreparedCandidate(Long userId, String requestId) {
        if (redisTemplate == null) {
            throw new IllegalStateException("User-profile commit publishing requires Redis");
        }
        long deadline = System.currentTimeMillis() + Math.max(1L, commitWaitTimeoutMs);
        while (true) {
            String candidateJson = redisTemplate.opsForValue().get(
                    RoutingKeys.userProfileCandidate(requestId));
            if (candidateJson != null && !candidateJson.isBlank()) {
                PreparedProfileCandidate candidate = readCandidate(candidateJson);
                if (!Objects.equals(userId, candidate.userId())
                        || !Objects.equals(requestId, candidate.requestId())) {
                    throw new IllegalStateException("Prepared user profile identity mismatch");
                }
                return candidate;
            }
            String state = redisTemplate.opsForValue().get(
                    RoutingKeys.userProfileContext(requestId));
            if (RoutingKeys.USER_PROFILE_FAILED.equals(state)
                    || RoutingKeys.USER_PROFILE_EMPTY.equals(state)) {
                return null;
            }
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0L) {
                throw new IllegalStateException(
                        "Prepared user profile was not ready before commit timeout");
            }
            try {
                Thread.sleep(Math.min(Math.max(1L, commitPollIntervalMs), remaining));
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Interrupted while waiting to publish user profile", error);
            }
        }
    }

    private PreparedProfileCandidate readCandidate(String json) {
        try {
            return objectMapper.readValue(json, PreparedProfileCandidate.class);
        } catch (IOException error) {
            throw new IllegalStateException("Unable to read prepared user profile", error);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException error) {
            throw new IllegalStateException("Unable to serialize prepared user profile", error);
        }
    }

    /** Returns true only when the request contains durable preference evidence. */
    public static boolean isPreferenceWorthyRequest(String question) {
        if (question == null || question.isBlank()) return false;
        String lower = question.toLowerCase().trim();
        String[] greetingKeywords = {
                "你好", "hello", "hi ", "嗨", "谢谢", "感谢", "再见", "拜拜",
                "早上好", "晚上好", "下午好"
        };
        for (String keyword : greetingKeywords) {
            if (lower.contains(keyword)) return false;
        }
        String[] knowledgeKeywords = {
                "什么是", "怎么", "如何", "为什么", "是什么", "解释", "帮我查", "帮我搜"
        };
        for (String keyword : knowledgeKeywords) {
            if (lower.contains(keyword)) return false;
        }
        return true;
    }

    // ==================== 读取 ====================

    /**
     * 构建用户画像 Prompt。
     * 若用户首次使用（无历史画像），自动应用冷启动默认值，确保 Prompt 中始终有基础个性化上下文。
     */
    public String buildUserProfilePrompt(Long userId) {
        if (userId == null) return "";
        return profileStore.load(userId)
                .map(snapshot -> buildUserProfilePrompt(snapshot.reportJson()))
                .orElse("【电商用户洞察】\n- 当前没有可靠画像\n");
    }

    @SuppressWarnings("unchecked")
    private String buildUserProfilePrompt(String reportJson) {
        Map<String, Object> report;
        try {
            report = objectMapper.readValue(reportJson, LinkedHashMap.class);
        } catch (IOException error) {
            throw new IllegalStateException("Unable to read stored user profile", error);
        }
        StringBuilder prompt = new StringBuilder();
        prompt.append("【电商用户洞察】\n");
        appendAssessment(prompt, report.get("commerceAssessment"));
        appendList(prompt, "核心驱动", report.get("topDrivers"));
        appendList(prompt, "核心阻碍", report.get("topBarriers"));
        appendDimension(prompt, "购买动机", report.get("insightDimensions"), "purchaseMotivation");
        appendDimension(prompt, "价值偏好", report.get("insightDimensions"), "valuePreference");
        return prompt.toString();
    }

    /**
     * 获取用户画像（给其他服务使用）
     */
    public UserProfile getProfile(Long userId) {
        return profileStore.load(userId).map(snapshot -> {
            UserProfile profile = new UserProfile();
            profile.setUserId(userId);
            profile.setAdditionalPreferences("{\"insightAnalysis\":" + snapshot.reportJson() + "}");
            return profile;
        }).orElse(null);
    }

    /**
     * 更新意图分布
     */
    public void updateIntentDistribution(Long userId, String routedAgent) {
        // 电商画像只保存新 Prompt 的结构化结果，旧意图计数不再写入画像。
    }

    private void appendAssessment(StringBuilder prompt, Object rawAssessment) {
        if (!(rawAssessment instanceof Map<?, ?> assessment)) return;
        appendValue(prompt, "画像可靠", assessment.get("reliable"));
        appendValue(prompt, "购买阶段", assessment.get("purchaseStage"));
        appendValue(prompt, "决策风格", assessment.get("decisionStyle"));
        appendValue(prompt, "价格敏感度", assessment.get("priceSensitivity"));
        appendValue(prompt, "购买意愿", assessment.get("purchaseIntentScore"));
        appendValue(prompt, "流失风险", assessment.get("churnRisk"));
        appendList(prompt, "主要顾虑", assessment.get("primaryConcerns"));
    }

    private void appendDimension(StringBuilder prompt, String label,
                                 Object rawDimensions, String dimensionKey) {
        if (!(rawDimensions instanceof Map<?, ?> dimensions)) return;
        Object rawDimension = dimensions.get(dimensionKey);
        if (!(rawDimension instanceof Map<?, ?> dimension)) return;
        appendValue(prompt, label, dimension.get("summary"));
    }

    private void appendList(StringBuilder prompt, String label, Object rawValues) {
        if (!(rawValues instanceof Collection<?> values) || values.isEmpty()) return;
        String joined = values.stream().filter(Objects::nonNull).map(Object::toString)
                .filter(value -> !value.isBlank()).collect(Collectors.joining("、"));
        if (!joined.isBlank()) prompt.append("- ").append(label).append(": ").append(joined).append("\n");
    }

    private void appendValue(StringBuilder prompt, String label, Object value) {
        if (value == null || value.toString().isBlank()) return;
        prompt.append("- ").append(label).append(": ").append(value).append("\n");
    }

    private ProfileConversationContext buildConversationContext(Long userId, String currentQuestion) {
        List<RoutingCallLog> history = List.of();
        if (routingCallLogMapper != null) {
            try {
                history = routingCallLogMapper.findRecentByUserId(
                        userId, Math.max(1, Math.min(maxHistoryTurns, 100)));
            } catch (Exception error) {
                log.warn("[UserProfile] 读取历史对话失败，降级分析当前消息: userId={}, error={}",
                        userId, error.getMessage());
            }
        }

        List<RoutingCallLog> chronological = history == null
                ? new ArrayList<>() : new ArrayList<>(history);
        Collections.reverse(chronological);
        StringBuilder context = new StringBuilder();
        context.append("[对话统计]\n用户消息轮数: ")
                .append(chronological.size() + 1).append("\n\n");
        int turn = 1;
        for (RoutingCallLog item : chronological) {
            if (item == null || item.getUserInput() == null || item.getUserInput().isBlank()) continue;
            context.append("[历史用户消息 ").append(turn++).append("]\n")
                    .append(item.getUserInput().trim()).append("\n\n");
        }
        context.append("[当前用户消息]\n").append(currentQuestion.trim());

        int maxChars = Math.max(1000, maxHistoryChars);
        String text = context.length() <= maxChars ? context.toString()
                : "[较早历史已截断]\n" + context.substring(context.length() - maxChars);
        List<Long> messageIds = chronological.stream().map(RoutingCallLog::getId)
                .filter(Objects::nonNull).distinct().toList();
        Long sourceMaxMessageId = messageIds.stream().max(Long::compareTo).orElse(null);
        return new ProfileConversationContext(text, sourceMaxMessageId, messageIds);
    }

    private record ProfileConversationContext(String text, Long sourceMaxMessageId,
                                              List<Long> messageIds) {
    }

    private record PreparedProfile(String projection, PreparedProfileCandidate candidate) {
    }

    public record PreparedProfileCandidate(
            Long userId,
            String requestId,
            long expectedVersion,
            LLMPreferenceExtractor.UserInsightReport report,
            String latestUserMessage,
            Long sourceMaxMessageId,
            List<Long> evidenceMessageIds) {
    }

}
