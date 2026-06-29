package com.example.smartassistant.common.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * ⭐ Agent 调度服务（P4 核心入口）。
 * <p>
 * 统一管理 Agent 任务的调度和执行，支持同步/异步两种模式：
 * <ul>
 *   <li>{@link #submitAsync(AgentTask)} — 异步提交，立即返回 taskId</li>
 *   <li>{@link #submitAndWait(AgentTask, long)} — 同步等待结果</li>
 *   <li>{@link #pollResult(String)} — 轮询异步结果</li>
 * </ul>
 * </p>
 */
public class AgentSchedulerService implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(AgentSchedulerService.class);

    private final AgentTaskQueue taskQueue;
    private final AgentTaskWorkerPool workerPool;

    /**
     * @param taskQueue          任务队列
     * @param taskExecutor       任务执行函数
     * @param workerCount        Worker 数量
     * @param pollTimeoutSeconds BRPOP 超时
     */
    public AgentSchedulerService(AgentTaskQueue taskQueue,
                                 java.util.function.Function<AgentTask, String> taskExecutor,
                                 int workerCount,
                                 long pollTimeoutSeconds) {
        this.taskQueue = taskQueue;
        this.workerPool = new AgentTaskWorkerPool(taskQueue, taskExecutor, workerCount, pollTimeoutSeconds);
    }

    // ===== 公开 API =====

    /**
     * 异步提交任务，立即返回 taskId。
     * Consumer 可通过 {@link #pollResult(String)} 轮询结果。
     */
    public String submitAsync(AgentTask task) {
        taskQueue.enqueue(task);
        log.info("[AgentScheduler] 任务异步提交: taskId={}, agent={}", task.getTaskId(), task.getAgentName());
        return task.getTaskId();
    }

    /**
     * 异步提交并返回 CompletableFuture。
     * 适用于需要异步等待结果的场景。
     */
    public CompletableFuture<AgentTask> submitFuture(AgentTask task) {
        taskQueue.enqueue(task);
        log.info("[AgentScheduler] 任务提交(Future): taskId={}, agent={}", task.getTaskId(), task.getAgentName());

        return CompletableFuture.supplyAsync(() -> {
            String taskId = task.getTaskId();
            long deadline = System.currentTimeMillis() + task.getTimeoutMs() + 5000;
            while (System.currentTimeMillis() < deadline) {
                Optional<AgentTask> result = taskQueue.pollResult(taskId);
                if (result.isPresent() && result.get().isTerminal()) {
                    return result.get();
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            task.markTimeout();
            return task;
        });
    }

    /**
     * 同步等待任务完成（阻塞调用方线程）。
     */
    public AgentTask submitAndWait(AgentTask task, long timeoutMs) {
        taskQueue.enqueue(task);
        String taskId = task.getTaskId();
        long deadline = System.currentTimeMillis() + timeoutMs;

        while (System.currentTimeMillis() < deadline) {
            Optional<AgentTask> result = taskQueue.pollResult(taskId);
            if (result.isPresent() && result.get().isTerminal()) {
                log.info("[AgentScheduler] 任务同步完成: taskId={}", taskId);
                return result.get();
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        task.markTimeout();
        taskQueue.saveResult(task);
        log.warn("[AgentScheduler] 任务同步超时: taskId={}, timeout={}ms", taskId, timeoutMs);
        return task;
    }

    /**
     * 轮询异步任务结果。
     */
    public Optional<AgentTask> pollResult(String taskId) {
        return taskQueue.pollResult(taskId);
    }

    /**
     * 获取队列长度。
     */
    public long queueSize() {
        return taskQueue.queueSize();
    }

    // ===== 生命周期 =====

    @Override
    public void afterPropertiesSet() {
        workerPool.start();
        log.info("[AgentScheduler] 调度服务就绪");
    }

    @Override
    public void destroy() {
        workerPool.shutdown();
        log.info("[AgentScheduler] 调度服务已关闭");
    }
}
