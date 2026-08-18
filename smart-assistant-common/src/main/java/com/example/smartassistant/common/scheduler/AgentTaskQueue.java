package com.example.smartassistant.common.scheduler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * ⭐ Redis 任务队列（P4 Hot Agent + 调度）。
 * <p>
 * 基于 Redis List 的 FIFO 任务队列，支持：
 * <ul>
 *   <li>任务入队（LPUSH）</li>
 *   <li>带阻塞的出队（BRPOP）</li>
 *   <li>任务状态持久化（String）</li>
 *   <li>结果回调（Pub/Sub）</li>
 * </ul>
 * </p>
 * Redis Key 约定：
 * <ul>
 *   <li>{@code a2a:queue:tasks} — 任务队列（List）</li>
 *   <li>{@code a2a:queue:task:{taskId}} — 任务详情（String）</li>
 *   <li>{@code a2a:queue:result:{taskId}} — 任务结果（String, TTL=300s）</li>
 *   <li>{@code a2a:queue:notify} — 结果通知 Channel（Pub/Sub）</li>
 * </ul>
 * </p>
 */
public class AgentTaskQueue {

    private static final Logger log = LoggerFactory.getLogger(AgentTaskQueue.class);

    private static final String QUEUE_KEY = "a2a:queue:tasks";
    private static final String PROCESSING_KEY = "a2a:queue:processing";
    private static final String DEAD_LETTER_KEY = "a2a:queue:dead-letter";
    private static final String TASK_PREFIX = "a2a:queue:task:";
    private static final String RESULT_PREFIX = "a2a:queue:result:";
    private static final Duration RESULT_TTL = Duration.ofSeconds(300);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public AgentTaskQueue(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    /**
     * 将任务入队。
     *
     * @param task Agent 任务
     */
    public void enqueue(AgentTask task) {
        try {
            // 1. 保存任务详情
            String taskJson = objectMapper.writeValueAsString(task);
            redisTemplate.opsForValue().set(TASK_PREFIX + task.getTaskId(), taskJson,
                    Duration.ofHours(1));

            // 2. 任务入队
            redisTemplate.opsForList().leftPush(QUEUE_KEY, task.getTaskId());

            log.info("[AgentQueue] 任务入队: taskId={}, agent={}, question={}",
                    task.getTaskId(), task.getAgentName(),
                    truncate(task.getQuestion(), 50));

        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize Agent task " + task.getTaskId(), e);
        }
    }

    /**
     * 阻塞从队列获取任务。
     *
     * @param timeout 超时秒数
     * @return 任务 Optional
     */
    public Optional<AgentTask> dequeue(long timeout) {
        String claimedTaskId = null;
        try {
            // BRPOPLPUSH atomically claims the task. It remains recoverable until acknowledge().
            var entry = redisTemplate.opsForList().rightPopAndLeftPush(
                    QUEUE_KEY, PROCESSING_KEY, timeout, TimeUnit.SECONDS);
            if (entry == null) {
                return Optional.empty();
            }

            String taskId = entry;
            claimedTaskId = taskId;
            String taskJson = redisTemplate.opsForValue().get(TASK_PREFIX + taskId);
            if (taskJson == null) {
                log.warn("[AgentQueue] 任务数据不存在: taskId={}", taskId);
                redisTemplate.opsForList().leftPush(DEAD_LETTER_KEY, taskId);
                acknowledge(taskId);
                return Optional.empty();
            }

            AgentTask task = objectMapper.readValue(taskJson, AgentTask.class);
            task.markRunning();
            persistTask(task);
            return Optional.of(task);

        } catch (Exception e) {
            log.error("[AgentQueue] 出队失败: {}", e.getMessage());
            if (claimedTaskId != null) {
                try {
                    redisTemplate.opsForList().leftPush(DEAD_LETTER_KEY, claimedTaskId);
                    acknowledge(claimedTaskId);
                } catch (Exception deadLetterError) {
                    log.error("[AgentQueue] 无法隔离损坏任务: taskId={}", claimedTaskId, deadLetterError);
                }
            }
            return Optional.empty();
        }
    }

    /** Confirms a successfully handled delivery and removes it from the in-flight list. */
    public void acknowledge(String taskId) {
        if (taskId != null) redisTemplate.opsForList().remove(PROCESSING_KEY, 1, taskId);
    }

    /** Requeues a failed delivery without leaving a stale processing entry. */
    public void retry(AgentTask task) {
        task.prepareRetry();
        enqueue(task);
        // Enqueue first: a crash can cause a duplicate delivery, never a lost delivery.
        acknowledge(task.getTaskId());
    }

    /** Moves an exhausted delivery to a durable dead-letter list for diagnosis/replay. */
    public void deadLetter(AgentTask task) {
        saveResult(task);
        redisTemplate.opsForList().leftPush(DEAD_LETTER_KEY, task.getTaskId());
        acknowledge(task.getTaskId());
        log.warn("[AgentQueue] 任务进入死信队列: taskId={}, retries={}",
                task.getTaskId(), task.getRetryCount());
    }

    /**
     * Reclaims deliveries whose worker disappeared after claiming them.
     * The retry budget is shared with ordinary execution failures.
     */
    public int reclaimTimedOut(Duration visibilityTimeout) {
        List<String> claimed = redisTemplate.opsForList().range(PROCESSING_KEY, 0, -1);
        if (claimed == null || claimed.isEmpty()) return 0;
        LocalDateTime cutoff = LocalDateTime.now().minus(visibilityTimeout);
        int reclaimed = 0;
        for (String taskId : claimed) {
            Optional<AgentTask> candidate = getTask(taskId);
            if (candidate.isEmpty()) {
                redisTemplate.opsForList().leftPush(DEAD_LETTER_KEY, taskId);
                acknowledge(taskId);
                continue;
            }
            AgentTask task = candidate.get();
            if (task.getStartedAt() == null || task.getStartedAt().isAfter(cutoff)) continue;
            task.markTimeout();
            if (task.canRetry()) {
                task.setRetryCount(task.getRetryCount() + 1);
                retry(task);
            } else {
                deadLetter(task);
            }
            reclaimed++;
        }
        return reclaimed;
    }

    /**
     * 获取任务详情。
     */
    public Optional<AgentTask> getTask(String taskId) {
        try {
            String json = redisTemplate.opsForValue().get(TASK_PREFIX + taskId);
            if (json == null) return Optional.empty();
            return Optional.of(objectMapper.readValue(json, AgentTask.class));
        } catch (Exception e) {
            log.warn("[AgentQueue] 获取任务失败: taskId={}", taskId);
            return Optional.empty();
        }
    }

    /**
     * 更新任务状态并保存结果。
     */
    public void saveResult(AgentTask task) {
        try {
            // 更新任务详情
            String taskJson = persistTask(task);

            // 保存结果（短 TTL）
            if (task.getResult() != null || task.getErrorMessage() != null) {
                redisTemplate.opsForValue().set(
                        RESULT_PREFIX + task.getTaskId(), taskJson, RESULT_TTL);
            }

            // ⭐ 发布通知（Consumer 可订阅）
            redisTemplate.convertAndSend("a2a:queue:notify", task.getTaskId());

        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot persist Agent task result " + task.getTaskId(), e);
        }
    }

    private String persistTask(AgentTask task) throws JsonProcessingException {
        String taskJson = objectMapper.writeValueAsString(task);
        redisTemplate.opsForValue().set(TASK_PREFIX + task.getTaskId(), taskJson,
                Duration.ofHours(1));
        return taskJson;
    }

    /**
     * 读取任务结果（Consumer 快速轮询用）。
     */
    public Optional<AgentTask> pollResult(String taskId) {
        try {
            String json = redisTemplate.opsForValue().get(RESULT_PREFIX + taskId);
            if (json == null) return Optional.empty();
            return Optional.of(objectMapper.readValue(json, AgentTask.class));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * 获取队列长度。
     */
    public long queueSize() {
        Long size = redisTemplate.opsForList().size(QUEUE_KEY);
        return size != null ? size : 0;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
