package com.example.smartassistant.router.config;

import com.example.smartassistant.common.scheduler.AgentSchedulerService;
import com.example.smartassistant.common.scheduler.AgentTaskQueue;
import com.example.smartassistant.common.scheduler.HotAgentPool;
import com.example.smartassistant.router.service.agent.AgentCallerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * ⭐ P4 Hot Agent + 调度配置。
 * <p>
 * 通过 {@code scheduler.enabled=true} 启用（默认关闭以保持向后兼容）。
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

    @Value("${scheduler.worker-count:4}")
    private int workerCount;

    @Value("${scheduler.poll-timeout:10}")
    private long pollTimeoutSeconds;

    /**
     * Redis 任务队列。
     */
    @Bean
    @ConditionalOnProperty(name = "scheduler.enabled", havingValue = "true")
    public AgentTaskQueue agentTaskQueue(StringRedisTemplate redisTemplate) {
        return new AgentTaskQueue(redisTemplate);
    }

    /**
     * Agent 调度服务。
     * <p>
     * 任务执行函数委托给 {@link AgentCallerService#callAgentWithContext}。
     * </p>
     */
    @Bean
    @ConditionalOnProperty(name = "scheduler.enabled", havingValue = "true")
    public AgentSchedulerService agentSchedulerService(AgentTaskQueue taskQueue,
                                                       AgentCallerService agentCallerService) {
        AgentSchedulerService scheduler = new AgentSchedulerService(
                taskQueue,
                task -> agentCallerService.callAgentWithContext(
                        task.getAgentName(),
                        task.getQuestion(),
                        task.getUserId(),
                        null,
                        task.getRequestId()),
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
    @ConditionalOnProperty(name = "scheduler.enabled", havingValue = "true")
    public HotAgentPool hotAgentPool(AgentTaskQueue taskQueue,
                                     AgentCallerService agentCallerService) {
        return new HotAgentPool(
                taskQueue,
                task -> agentCallerService.callAgentWithContext(
                        task.getAgentName(),
                        task.getQuestion(),
                        task.getUserId(),
                        null,
                        task.getRequestId()),
                workerCount);
    }
}
