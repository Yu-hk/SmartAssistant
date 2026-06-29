package com.example.smartassistant.common.scheduler;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Agent 任务模型。
 * <p>
 * 表示一次 Agent 执行请求，包含从 Router 发往目标 Agent 的全部信息。
 * 支持同步（直接 HTTP 调用）和异步（Redis 队列 + Worker 池）两种执行模式。
 * </p>
 */
public class AgentTask implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 任务唯一 ID */
    private String taskId;
    /** 请求 ID（来自 Router） */
    private String requestId;
    /** 会话 ID */
    private String sessionId;
    /** 用户 ID */
    private Long userId;
    /** 目标 Agent 名称 */
    private String agentName;
    /** Agent 处理的问题（已改写/增强） */
    private String question;
    /** 原始用户问题 */
    private String originalQuestion;
    /** 意图标签 */
    private String intentTag;
    /** 路由置信度 */
    private double confidence;

    /** 任务状态 */
    private AgentTaskStatus status;
    /** 任务优先级（越高越优先） */
    private int priority;
    /** 创建时间 */
    private LocalDateTime createdAt;
    /** 开始执行时间 */
    private LocalDateTime startedAt;
    /** 完成时间 */
    private LocalDateTime completedAt;
    /** 超时毫秒数 */
    private long timeoutMs;
    /** 已重试次数 */
    private int retryCount;
    /** 最大重试次数 */
    private int maxRetries;

    /** 执行结果文本 */
    private String result;
    /** 失败原因 */
    private String errorMessage;

    public AgentTask() {
        this.status = AgentTaskStatus.PENDING;
        this.createdAt = LocalDateTime.now();
        this.priority = 10;
        this.timeoutMs = 60_000;
        this.maxRetries = 2;
    }

    // ===== 便利方法 =====

    /** 是否可以开始执行（状态检查） */
    public boolean canStart() {
        return status == AgentTaskStatus.PENDING || status == AgentTaskStatus.FAILED;
    }

    /** 是否已完成（终态检查） */
    public boolean isTerminal() {
        return status == AgentTaskStatus.COMPLETED
                || status == AgentTaskStatus.CANCELLED
                || status == AgentTaskStatus.TIMEOUT;
    }

    /** 是否可以重试 */
    public boolean canRetry() {
        return retryCount < maxRetries
                && (status == AgentTaskStatus.FAILED || status == AgentTaskStatus.TIMEOUT);
    }

    // ===== 状态转换 =====

    public AgentTask markRunning() {
        this.status = AgentTaskStatus.RUNNING;
        this.startedAt = LocalDateTime.now();
        return this;
    }

    public AgentTask markCompleted(String result) {
        this.status = AgentTaskStatus.COMPLETED;
        this.result = result;
        this.completedAt = LocalDateTime.now();
        return this;
    }

    public AgentTask markFailed(String error) {
        this.status = AgentTaskStatus.FAILED;
        this.errorMessage = error;
        this.completedAt = LocalDateTime.now();
        return this;
    }

    public AgentTask markTimeout() {
        this.status = AgentTaskStatus.TIMEOUT;
        this.completedAt = LocalDateTime.now();
        return this;
    }

    public AgentTask markCancelled() {
        this.status = AgentTaskStatus.CANCELLED;
        this.completedAt = LocalDateTime.now();
        return this;
    }

    // ===== getter/setter =====

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getAgentName() { return agentName; }
    public void setAgentName(String agentName) { this.agentName = agentName; }

    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }

    public String getOriginalQuestion() { return originalQuestion; }
    public void setOriginalQuestion(String originalQuestion) { this.originalQuestion = originalQuestion; }

    public String getIntentTag() { return intentTag; }
    public void setIntentTag(String intentTag) { this.intentTag = intentTag; }

    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }

    public AgentTaskStatus getStatus() { return status; }
    public void setStatus(AgentTaskStatus status) { this.status = status; }

    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }

    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }

    public long getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; }

    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }

    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }

    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
