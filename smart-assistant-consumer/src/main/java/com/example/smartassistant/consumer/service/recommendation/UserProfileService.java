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
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
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
    @Qualifier("profilePreparationExecutor")
    private Executor profileExecutor;

    @Autowired
    private ProfileAdmissionCoordinator admissionCoordinator;

    @Autowired
    private ProfileAdmissionStore admissionStore;

    @Autowired
    private ProfileGenerationFence publicationFence;

    @Autowired(required = false)
    @Qualifier("profileCommitExecutor")
    private Executor commitExecutor;

    private final Cache<ProfileRequest, CompletableFuture<String>> preparations = Caffeine.newBuilder()
            .maximumSize(10000).expireAfterWrite(Duration.ofMinutes(2)).build();

    private final LLMPreferenceExtractor llmExtractor;
    private final UserProfileSnapshotStore profileStore;
    private final UserProfileQueryService profileQueries;
    private final UserProfileCommitPublisher commitPublisher;

    public UserProfileService(LLMPreferenceExtractor llmExtractor,
                              UserProfileSnapshotStore profileStore,
                              UserProfileCommitPublisher commitPublisher) {
        this.llmExtractor = llmExtractor;
        this.profileStore = profileStore;
        this.profileQueries = new UserProfileQueryService(profileStore);
        this.commitPublisher = commitPublisher;
    }

    // ==================== 写入 ====================

    /**
     * 从问题中提取偏好信息并更新用户画像
     */
    public void extractAndUpdatePreferences(Long userId, String question, String extractedLocation) {
        throw new ProfileGenerationFence.Rejected(); // Legacy callers have no immutable request admission.
    }

    public void extractAdmittedPreferences(Long userId,String question,String requestId) {
        if (userId == null || question == null) return;
        try {
            if(admissionStore==null) throw new ProfileGenerationFence.Rejected();
            long generation=admissionStore.requireExisting(userId,requestId,question);
            commitCandidate(analyzeCandidate(userId,question,requestId,generation));
        } catch (Exception e) {
            log.error("[UserProfile] 数据库画像更新失败: userId={}, type={}", userId, e.getClass().getSimpleName());
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
     * Admission waits at most its separate pre-queue budget; model execution stays on workers.
     * Profile completion is not a mandatory barrier. Repeated admission does not reset its state.
     * The future is completion-only (empty string for source compatibility). Profile bodies
     * must only be read through the generation-fenced store, never from this deduplication cache.
     */
    public CompletableFuture<String> prefetchForRequest(
            Long userId, String question, String requestId) {
        if (userId == null || requestId == null || requestId.isBlank()) {
            return CompletableFuture.completedFuture("");
        }

        if (redisTemplate == null || profileExecutor == null || admissionCoordinator == null)
            return CompletableFuture.completedFuture("");
        return preparations.get(new ProfileRequest(userId, requestId, questionFingerprint(question)), ignored ->
                schedulePreparation(userId, question, requestId));
    }

    private CompletableFuture<String> schedulePreparation(Long userId, String question, String requestId) {
        OptionalLong admission = admissionCoordinator.admit(userId, requestId, question);
        if (admission.isEmpty()) return CompletableFuture.completedFuture("");
        long generation = admission.getAsLong();
        try {
            return CompletableFuture
                    .supplyAsync(() -> {
                        try {
                            profileStore.requireGeneration(userId, generation);
                            publishState(userId,requestId,generation,RoutingKeys.USER_PROFILE_PENDING,null,false);
                            PreparedProfile prepared = prepareProfile(userId, question, requestId, generation);
                            profileStore.requireGeneration(userId, generation);
                            publishPrefetchResult(userId,requestId,generation,prepared,null);
                            return "";
                        } catch (RuntimeException unavailable) {
                            publishPrefetchResult(userId,requestId,generation,null,unavailable);
                            return "";
                        }
                    }, profileExecutor);
        } catch (RejectedExecutionException error) {
            // Never perform a Redis write or execute analysis on the caller when overloaded.
            log.info("[UserProfile] Optional preparation skipped: executor overloaded");
            return CompletableFuture.completedFuture("");
        }
    }

    private PreparedProfile prepareProfile(Long userId, String question, String requestId, long generation) {
        String savedProjection = profileQueries.forUser(userId);
        if (!savedProjection.isBlank()) {
            profileStore.requireGeneration(userId, generation);
            // Existing reliable context becomes available before history/model analysis begins.
            publishState(userId,requestId,generation,RoutingKeys.USER_PROFILE_READY_PREFIX + savedProjection,null,false);
        }
        if (question != null && !question.isBlank()) {
            try {
                PreparedProfileCandidate candidate = analyzeCandidate(userId, question, requestId, generation);
                if (!shouldCommit(candidate.report())) return new PreparedProfile(savedProjection, null);
                return new PreparedProfile(reliableProjection(writeJson(candidate.report())), candidate);
            } catch (RuntimeException unavailable) {
                log.warn("[UserProfile] Optional analysis failed; retaining snapshot: requestId={}, type={}",
                        requestId, unavailable.getClass().getSimpleName());
                return new PreparedProfile(savedProjection, null);
            }
        }
        return new PreparedProfile(savedProjection, null);
    }

    private Duration prefetchTtl() { return Duration.ofSeconds(Math.max(30L, Math.min(600L,prefetchTtlSeconds))); }

    private static boolean shouldCommit(LLMPreferenceExtractor.UserInsightReport report) {
        return report != null && report.profileUpdate() != null
                && !"KEEP".equals(report.profileUpdate().action());
    }

    private String reliableProjection(String reportJson) {
        return UserProfileQueryService.candidate(reportJson);
    }

    private PreparedProfileCandidate analyzeCandidate(
            Long userId, String question, String requestId, long generation) {
        ProfileConversationContext context = buildConversationContext(userId, question, generation);
        Optional<UserProfileSnapshotStore.Snapshot> current = profileStore.load(userId);
        long expectedVersion = current.map(UserProfileSnapshotStore.Snapshot::profileVersion)
                .orElse(0L);
        String currentProfile = current.map(UserProfileSnapshotStore.Snapshot::reportJson)
                .orElse("当前没有已保存画像。");
        LLMPreferenceExtractor.UserInsightReport report =
                llmExtractor.extract(currentProfile, context.text(), question);
        return new PreparedProfileCandidate(
                userId, requestId, expectedVersion, report, question,
                context.sourceMaxMessageId(), context.messageIds(), generation);
    }

    private UserProfileSnapshotStore.Snapshot commitCandidate(
            PreparedProfileCandidate candidate) {
        LLMPreferenceExtractor.UserInsightReport report = candidate.report();
        long expectedVersion = candidate.expectedVersion();
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return profileStore.save(candidate.userId(), candidate.requestId(),
                        expectedVersion, report, candidate.sourceMaxMessageId(),
                        candidate.evidenceMessageIds(), candidate.generation());
            } catch (UserProfileSnapshotStore.OptimisticProfileUpdateException conflict) {
                if (attempt == 1) throw conflict;
                // Never rebase an erased candidate into a newer lifecycle generation.
                profileStore.requireGeneration(candidate.userId(), candidate.generation());
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

    private void publishPrefetchResult(Long userId,String requestId,long generation,
                                       PreparedProfile prepared, Throwable error) {
        if (redisTemplate == null) return;
        try {
            String state;
            String candidate=null;
            if (error != null) {
                state = RoutingKeys.USER_PROFILE_FAILED;
            } else {
                if (prepared != null && prepared.candidate() != null && shouldCommit(prepared.candidate().report())) {
                    candidate=writeJson(prepared.candidate());
                }
                state = prepared == null || prepared.projection().isBlank() ? RoutingKeys.USER_PROFILE_EMPTY
                        : RoutingKeys.USER_PROFILE_READY_PREFIX + prepared.projection();
            }
            profileStore.requireGeneration(userId,generation);
            publishState(userId,requestId,generation,state,candidate,true);
        } catch (Exception publishError) {
            log.warn("[UserProfile] Atomic publication unavailable: type={}",publishError.getClass().getSimpleName());
        }
    }

    private void publishState(Long userId,String requestId,long generation,String state,String candidate,boolean done) {
        if (publicationFence == null) throw new IllegalStateException("Profile publication fence required");
        new ProfileRequestRedisStore(redisTemplate,publicationFence).publish(userId,requestId,generation,state,candidate,done,prefetchTtl());
    }

    /** Emits a durable commit command after a turn has completed successfully. */
    public void commitAfterSuccessfulTurn(Long userId, String requestId) {
        if (userId == null || requestId == null || requestId.isBlank()) return;
        if (commitExecutor == null) return;
        try {
            commitExecutor.execute(() -> publishCommitAfterSuccessfulTurn(userId, requestId));
        } catch (RejectedExecutionException overloaded) {
            log.warn("[UserProfile] Optional commit skipped: executor overloaded, requestId={}", requestId);
        }
    }

    private void publishCommitAfterSuccessfulTurn(Long userId, String requestId) {
        try {
            PreparedProfileCandidate candidate = awaitPreparedCandidate(userId, requestId);
            if (candidate == null || !shouldCommit(candidate.report())) return;
            profileStore.requireGeneration(candidate.userId(), candidate.generation());
            commitPublisher.publish(candidate);
            new ProfileRequestRedisStore(redisTemplate).retire(userId,requestId,candidate.generation(),writeJson(candidate));
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
        if (!shouldCommit(candidate.report())) return;
        try {
            // Idempotency is checked inside the same fenced transaction as persistence.
            commitCandidate(candidate);
        } catch (ProfileGenerationFence.Rejected invalidated) {
            // Acknowledge obsolete MQ work; do not retry/re-analyze or send its contents to the DLQ.
            log.info("[UserProfile] Discarded inactive-generation candidate");
        }
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
                return readOwnedCandidate(userId, requestId, candidateJson);
            }
            String state = redisTemplate.opsForValue().get(
                    RoutingKeys.userProfileContext(requestId));
            if (RoutingKeys.USER_PROFILE_FAILED.equals(state)
                    || RoutingKeys.USER_PROFILE_EMPTY.equals(state)
                    || "DONE".equals(redisTemplate.opsForValue().get(preparationDoneKey(requestId)))) {
                // Publication can finish between the first candidate read and the terminal-state read.
                String finalCandidate = redisTemplate.opsForValue().get(RoutingKeys.userProfileCandidate(requestId));
                return finalCandidate == null || finalCandidate.isBlank() ? null
                        : readOwnedCandidate(userId, requestId, finalCandidate);
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
        String projection = profileQueries.forUser(userId);
        return projection.isBlank() ? "【电商用户洞察】\n- 当前没有可靠画像\n" : projection;
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

    private ProfileConversationContext buildConversationContext(Long userId, String currentQuestion, long generation) {
        List<RoutingCallLog> history = List.of();
        // Legacy logs have no admission-generation tag. After a reset, do not reconstruct
        // erased preferences from them; only the current message and current snapshot are used.
        if (generation == 0 && routingCallLogMapper != null) {
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

    private PreparedProfileCandidate readOwnedCandidate(Long userId, String requestId, String json) {
        PreparedProfileCandidate candidate = readCandidate(json);
        if (!Objects.equals(userId, candidate.userId()) || !Objects.equals(requestId, candidate.requestId())) {
            throw new IllegalStateException("Prepared user profile identity mismatch");
        }
        return candidate;
    }

    private record ProfileRequest(Long userId, String requestId, String question) { }

    private static String questionFingerprint(String question) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(Objects.toString(question, "").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static String preparationDoneKey(String requestId) {
        return ProfileRequestRedisStore.doneKey(requestId);
    }

    public record PreparedProfileCandidate(
            Long userId,
            String requestId,
            long expectedVersion,
            LLMPreferenceExtractor.UserInsightReport report,
            String latestUserMessage,
            Long sourceMaxMessageId,
            List<Long> evidenceMessageIds,
            long generation) {
        /** Source compatibility for v1 producers: missing generation remains zero, never recaptured. */
        public PreparedProfileCandidate(Long userId, String requestId, long expectedVersion,
                                        LLMPreferenceExtractor.UserInsightReport report, String latestUserMessage,
                                        Long sourceMaxMessageId, List<Long> evidenceMessageIds) {
            this(userId, requestId, expectedVersion, report, latestUserMessage, sourceMaxMessageId, evidenceMessageIds, 0L);
        }
    }

}
