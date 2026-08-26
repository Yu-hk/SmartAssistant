package com.example.smartassistant.router.config;

import com.example.smartassistant.common.agent.protocol.AgentExecutionResponse;
import com.example.smartassistant.common.scheduler.AgentSchedulerService;
import com.example.smartassistant.common.scheduler.AgentTaskQueue;
import com.example.smartassistant.common.scheduler.HotAgentPool;
import com.example.smartassistant.router.service.agent.AgentCallResult;
import com.example.smartassistant.router.service.agent.AgentCallerService;
import com.example.smartassistant.router.service.agent.AgentMessageDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ⭐ P4 Hot Agent + 调度配置。
 * <p>
 * 通过 {@code router.scheduler.enabled=true} 启用（默认关闭以保持向后兼容）。
 * 启用后：
 * <ol>
 *   <li>创建 {@link AgentTaskQueue} — Redis 任务队列</li>
 *   <li>创建 {@link AgentSchedulerService} — 调度服务 + Worker 池</li>
 *   <li>创建 {@link HotAgentPool} — Hot Agent 预热池</li>
 *   <li>注入到 {@link AgentCallerService} — 支持异步 Agent 调用</li>
 * </ol>
 * </p>
 */
@Configuration
public class SchedulerConfig {

    private static final Logger log = LoggerFactory.getLogger(SchedulerConfig.class);

    @Value("${router.scheduler.worker-count:4}")
    private int workerCount;

    @Value("${router.scheduler.poll-timeout:1}")
    private long pollTimeoutSeconds;

    /**
     * Redis 任务队列。
     */
    @Bean
    @ConditionalOnProperty(prefix = "router.scheduler", name = "enabled", havingValue = "true")
    public AgentTaskQueue agentTaskQueue(StringRedisTemplate redisTemplate) {
        return new AgentTaskQueue(redisTemplate);
    }

    /**
     * Agent 调度服务。
     * <p>
     * 任务执行函数根据消息中是否携带节点协议，选择兼容调用或类型化调用。
     * </p>
     */
    @Bean
    @ConditionalOnProperty(prefix = "router.scheduler", name = "enabled", havingValue = "true")
    public AgentSchedulerService agentSchedulerService(AgentTaskQueue taskQueue,
                                                       AgentCallerService agentCallerService) {
        AgentSchedulerService scheduler = new AgentSchedulerService(
                taskQueue,
                task -> executeQueuedTask(task, agentCallerService),
                workerCount,
                pollTimeoutSeconds);

        // 注入到 AgentCallerService
        agentCallerService.setSchedulerService(scheduler);

        log.info("[SchedulerConfig] 调度服务已初始化: workers={}, pollTimeout={}s",
                workerCount, pollTimeoutSeconds);
        return scheduler;
    }

    /**
     * Hot Agent 预热池。
     */
    @Bean
    @ConditionalOnProperty(prefix = "router.scheduler",
            name = {"enabled", "hot-pool-enabled"}, havingValue = "true")
    public HotAgentPool hotAgentPool(AgentTaskQueue taskQueue,
                                     AgentCallerService agentCallerService) {
        return new HotAgentPool(
                taskQueue,
                task -> executeQueuedTask(task, agentCallerService),
                workerCount);
    }

    private String executeQueuedTask(com.example.smartassistant.common.scheduler.AgentTask task,
                                     AgentCallerService agentCallerService) {
        if (task.getExecutionRequest() == null) {
            return agentCallerService.callAgentWithContext(
                    task.getAgentName(), task.getQuestion(), task.getUserId(),
                    null, task.getRequestId());
        }

        AgentCallResult result = agentCallerService.callAgentAndExtractTitles(
                task.getAgentName(), task.getExecutionRequest());
        Map<String, Object> data = new LinkedHashMap<>(result.getData());
        data.put(AgentMessageDispatcher.REAL_TITLES_KEY, result.getRealTitles());
        data.put(AgentMessageDispatcher.TAGS_BY_TITLE_KEY, result.getTagsByTitle());
        task.setExecutionResponse(AgentExecutionResponse.success(
                result.getResponse(), data, result.getDomainQuality()));
        return result.getResponse();
    }
}
