package com.example.smartassistant.router.service.agent;

import com.example.smartassistant.common.agent.protocol.AgentExecutionRequest;
import com.example.smartassistant.common.agent.protocol.AgentExecutionResponse;
import com.example.smartassistant.common.quality.DomainQualityResult;
import com.example.smartassistant.router.scheduler.AgentSchedulerService;
import com.example.smartassistant.router.scheduler.AgentTask;
import com.example.smartassistant.router.scheduler.AgentTaskFactory;
import com.example.smartassistant.router.scheduler.AgentTaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * Hybrid node transport for LangGraph4j.
 *
 * <p>Read-only Agent nodes may travel through the reliable Redis work queue to
 * gain back-pressure, retry and dead-letter handling. Write nodes remain on the
 * synchronous path because approval and exactly-once side effects belong to the
 * active graph transaction.</p>
 */
@Service
public class AgentMessageDispatcher {

    public static final String REAL_TITLES_KEY = "agent_real_titles";
    public static final String TAGS_BY_TITLE_KEY = "agent_tags_by_title";

    private static final Logger log = LoggerFactory.getLogger(AgentMessageDispatcher.class);

    private final AgentCallerService agentCallerService;
    private final ObjectProvider<AgentSchedulerService> schedulerProvider;
    private final boolean queueEnabled;
    private final boolean readOnlyOnly;
    private final long waitTimeoutMs;

    public AgentMessageDispatcher(
            AgentCallerService agentCallerService,
            ObjectProvider<AgentSchedulerService> schedulerProvider,
            @Value("${router.scheduler.enabled:false}") boolean queueEnabled,
            @Value("${router.scheduler.read-only-only:true}") boolean readOnlyOnly,
            @Value("${router.scheduler.wait-timeout-ms:65000}") long waitTimeoutMs) {
        this.agentCallerService = agentCallerService;
        this.schedulerProvider = schedulerProvider;
        this.queueEnabled = queueEnabled;
        this.readOnlyOnly = readOnlyOnly;
        this.waitTimeoutMs = Math.max(1_000L, waitTimeoutMs);
    }

    public AgentCallResult dispatch(String agentName, AgentExecutionRequest request,
                                    String accessMode) {
        AgentSchedulerService scheduler = schedulerProvider.getIfAvailable();
        boolean writeNode = "WRITE".equalsIgnoreCase(accessMode);
        if (!queueEnabled || scheduler == null || (readOnlyOnly && writeNode)) {
            return agentCallerService.callAgentAndExtractTitles(agentName, request);
        }

        AgentTask task = AgentTaskFactory.createTaskWithContext(
                agentName, request.question(), request.question(), parseUserId(request.userId()),
                request.executionId(), null, request.operation(), 1.0);
        task.setExecutionRequest(request);
        task.setPriority(15);
        // Queue transport retries once; LangGraph remains the owner of semantic retries.
        task.setMaxRetries(1);
        long timeout = effectiveTimeout(request.deadlineEpochMs());
        task.setTimeoutMs(timeout);

        log.info("[AgentMessageDispatcher] 节点入队: taskId={}, nodeId={}, agent={}, access={}",
                task.getTaskId(), request.nodeId(), agentName, accessMode);
        AgentTask completed = scheduler.submitAndWait(task, timeout);
        return toCallResult(completed);
    }

    private AgentCallResult toCallResult(AgentTask task) {
        if (task.getStatus() == AgentTaskStatus.TIMEOUT) {
            throw new IllegalStateException(new TimeoutException(
                    "Agent queue timed out: " + task.getTaskId()));
        }
        if (task.getStatus() != AgentTaskStatus.COMPLETED) {
            throw new IllegalStateException("Agent queue failed: " + task.getTaskId()
                    + ", reason=" + task.getErrorMessage());
        }

        AgentExecutionResponse response = task.getExecutionResponse();
        if (response == null) {
            return new AgentCallResult(task.getResult());
        }
        Map<String, Object> data = new LinkedHashMap<>(response.data());
        List<String> titles = stringList(data.get(REAL_TITLES_KEY));
        Map<String, String> tags = stringMap(data.get(TAGS_BY_TITLE_KEY));
        DomainQualityResult quality = response.quality() != null
                ? response.quality().toDomainQuality() : DomainQualityResult.unknown();
        return new AgentCallResult(response.answer(), titles, tags, quality, data);
    }

    private long effectiveTimeout(Long deadlineEpochMs) {
        if (deadlineEpochMs == null) return waitTimeoutMs;
        long remaining = deadlineEpochMs - System.currentTimeMillis();
        return Math.max(1_000L, Math.min(waitTimeoutMs, remaining));
    }

    private static Long parseUserId(String userId) {
        if (userId == null || userId.isBlank()) return null;
        try {
            return Long.valueOf(userId);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> values)) return List.of();
        return values.stream().filter(item -> item != null)
                .map(String::valueOf).toList();
    }

    private static Map<String, String> stringMap(Object value) {
        if (!(value instanceof Map<?, ?> values)) return Map.of();
        Map<String, String> result = new LinkedHashMap<>();
        values.forEach((key, item) -> {
            if (key != null && item != null) result.put(String.valueOf(key), String.valueOf(item));
        });
        return result;
    }
}
