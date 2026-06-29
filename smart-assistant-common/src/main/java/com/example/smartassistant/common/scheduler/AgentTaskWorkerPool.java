package com.example.smartassistant.common.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * ⭐ 任务 Worker 池（P4 Hot Agent + 调度）。
 * <p>
 * 管理一组 {@link TaskWorker}，从 {@link AgentTaskQueue} 中消费任务。
 * 每个 Worker 在独立虚拟线程中运行，轮询队列获取任务并执行。
 * </p>
 *
 * <p>生命周期：{@link #start()} → 运行中 → {@link #shutdown()}</p>
 */
public class AgentTaskWorkerPool implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(AgentTaskWorkerPool.class);

    private final AgentTaskQueue taskQueue;
    private final Function<AgentTask, String> taskExecutor;
    private final int workerCount;
    private final long pollTimeoutSeconds;

    private final List<TaskWorker> workers = new ArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService virtualThreadExecutor;

    /**
     * @param taskQueue         任务队列
     * @param taskExecutor      任务执行函数（接收 AgentTask，返回结果文本）
     * @param workerCount       Worker 数量
     * @param pollTimeoutSeconds BRPOP 超时秒数
     */
    public AgentTaskWorkerPool(AgentTaskQueue taskQueue,
                               Function<AgentTask, String> taskExecutor,
                               int workerCount,
                               long pollTimeoutSeconds) {
        this.taskQueue = taskQueue;
        this.taskExecutor = taskExecutor;
        this.workerCount = workerCount > 0 ? workerCount : Runtime.getRuntime().availableProcessors();
        this.pollTimeoutSeconds = pollTimeoutSeconds > 0 ? pollTimeoutSeconds : 10;
    }

    /**
     * 启动 Worker 池。
     */
    public synchronized void start() {
        if (running.compareAndSet(false, true)) {
            virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

            for (int i = 0; i < workerCount; i++) {
                TaskWorker worker = new TaskWorker("worker-" + i);
                workers.add(worker);
                virtualThreadExecutor.submit(worker);
                log.info("[AgentWorkerPool] Worker {} 已启动", worker.getName());
            }

            log.info("[AgentWorkerPool] Worker 池启动完成: count={}, pollTimeout={}s",
                    workerCount, pollTimeoutSeconds);
        }
    }

    /**
     * 停止 Worker 池。
     */
    public synchronized void shutdown() {
        if (running.compareAndSet(true, false)) {
            log.info("[AgentWorkerPool] 正在停止 Worker 池...");
            workers.clear();
            if (virtualThreadExecutor != null) {
                virtualThreadExecutor.shutdown();
                try {
                    if (!virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                        virtualThreadExecutor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    virtualThreadExecutor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
            log.info("[AgentWorkerPool] Worker 池已停止");
        }
    }

    public boolean isRunning() { return running.get(); }

    public int getWorkerCount() { return workers.size(); }

    // ===== Spring 生命周期 =====

    @Override
    public void afterPropertiesSet() {
        // Spring 容器启动后自动启动 Worker 池
        start();
    }

    @Override
    public void destroy() {
        shutdown();
    }

    // ===== Worker 实现 =====

    /**
     * 单个 Worker — 在专用虚拟线程中运行。
     * 轮询队列获取任务 → 执行 → 保存结果 → 继续轮询。
     */
    private class TaskWorker implements Runnable {

        private final String name;

        TaskWorker(String name) {
            this.name = name;
        }

        String getName() { return name; }

        @Override
        public void run() {
            log.debug("[AgentWorker] {} 开始轮询", name);

            while (running.get()) {
                try {
                    // 1. BRPOP 阻塞获取任务
                    var taskOpt = taskQueue.dequeue(pollTimeoutSeconds);
                    if (taskOpt.isEmpty()) {
                        continue; // 超时未获取到，继续轮询
                    }

                    AgentTask task = taskOpt.get();
                    log.info("[AgentWorker] {} 获取任务: taskId={}, agent={}",
                            name, task.getTaskId(), task.getAgentName());

                    // 2. 标记运行中
                    task.markRunning();
                    taskQueue.saveResult(task);

                    // 3. ⭐ 执行任务（调用 taskExecutor 函数）
                    try {
                        String result = taskExecutor.apply(task);
                        task.markCompleted(result);
                        log.info("[AgentWorker] {} 任务完成: taskId={}, resultLen={}",
                                name, task.getTaskId(), result != null ? result.length() : 0);
                    } catch (Exception e) {
                        task.markFailed(e.getMessage());
                        log.error("[AgentWorker] {} 任务执行失败: taskId={}, error={}",
                                name, task.getTaskId(), e.getMessage());

                        // 自动重试
                        if (task.canRetry()) {
                            task.setRetryCount(task.getRetryCount() + 1);
                            log.info("[AgentWorker] 任务重试: taskId={}, attempt={}/{}",
                                    task.getTaskId(), task.getRetryCount(), task.getMaxRetries());
                            taskQueue.enqueue(task);
                        }
                    }

                    // 4. 保存结果
                    taskQueue.saveResult(task);

                } catch (Exception e) {
                    // 检查是否由中断导致
                    if (Thread.currentThread().isInterrupted() || e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                        log.warn("[AgentWorker] {} 被中断", name);
                        break;
                    }
                    log.error("[AgentWorker] {} 异常: {}", name, e.getMessage());
                }
            }

            log.debug("[AgentWorker] {} 停止", name);
        }
    }
}
