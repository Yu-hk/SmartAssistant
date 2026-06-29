package com.example.smartassistant.common.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Agent 任务工厂 — 创建标准化的 {@link AgentTask} 实例。
 */
public class AgentTaskFactory {

    private static final Logger log = LoggerFactory.getLogger(AgentTaskFactory.class);

    private static final AtomicInteger counter = new AtomicInteger(0);

    /**
     * 创建一个标准的 Agent 执行任务。
     *
     * @param agentName  目标 Agent 名称
     * @param question   Agent 处理的问题
     * @param userId     用户 ID
     * @param requestId  路由请求 ID
     * @return 已初始化的 AgentTask 实例
     */
    public static AgentTask createTask(String agentName, String question,
                                       Long userId, String requestId) {
        AgentTask task = new AgentTask();
        task.setTaskId(generateTaskId());
        task.setAgentName(agentName);
        task.setQuestion(question);
        task.setUserId(userId);
        task.setRequestId(requestId);
        task.setStatus(AgentTaskStatus.PENDING);
        task.setPriority(10);
        task.setTimeoutMs(60_000);
        task.setMaxRetries(2);

        log.debug("[AgentTaskFactory] 创建任务: taskId={}, agent={}", task.getTaskId(), agentName);
        return task;
    }

    /**
     * 创建附带完整上下文的任务。
     */
    public static AgentTask createTaskWithContext(String agentName, String question,
                                                  String originalQuestion, Long userId,
                                                  String requestId, String sessionId,
                                                  String intentTag, double confidence) {
        AgentTask task = createTask(agentName, question, userId, requestId);
        task.setOriginalQuestion(originalQuestion);
        task.setSessionId(sessionId);
        task.setIntentTag(intentTag);
        task.setConfidence(confidence);
        return task;
    }

    /**
     * 生成全局唯一的任务 ID。
     * 格式: task-{8位UUID前缀}-{自增序号}
     */
    private static String generateTaskId() {
        String uuidPrefix = UUID.randomUUID().toString().substring(0, 8);
        return "task-" + uuidPrefix + "-" + counter.incrementAndGet();
    }
}
