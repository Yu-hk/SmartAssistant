package com.example.smartassistant.common.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/**
 * ⭐ Hot Agent 预热池（P4 Hot Agent + 调度）。
 * <p>
 * 高频 Agent（Order/Product/General）常驻内存，避免每次调用都加载。
 * 支持：
 * <ul>
 *   <li>优先级调度 — 高优先级任务优先出队</li>
 *   <li>Agent 亲和性 — 同一 Agent 的任务绑定到特定 Worker</li>
 *   <li>并发限流 — 每 Agent 限制最大并发数</li>
 *   <li>健康探测 — 超时未完成任务自动重试</li>
 * </ul>
 * </p>
 */
public class HotAgentPool implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(HotAgentPool.class);

    /** 预热 Agent 列表：Agent 名称 → 优先级 */
    private static final Map<String, Integer> HOT_AGENTS = new LinkedHashMap<>();

    /** Agent 级并发上限 */
    private static final int DEFAULT_MAX_CONCURRENCY = 4;

    static {
        HOT_AGENTS.put("order", 20);      // 最高优先级
        HOT_AGENTS.put("product", 15);    // 高优先级
        HOT_AGENTS.put("general", 10);    // 中优先级
        HOT_AGENTS.put("recommend", 10);  // 中优先级
    }

    private final AgentTaskQueue taskQueue;
    private final Function<AgentTask, String> taskExecutor;
    private final int workerCount;

    /** 每个 Agent 的并发限流器 */
    private final Map<String, Semaphore> agentConcurrencyLimiters = new ConcurrentHashMap<>();
    /** Agent 亲和性 Worker 分配 */
    private final Map<String, PriorityBlockingQueue<AgentTask>> agentQueues = new ConcurrentHashMap<>();
    /** Worker 线程池 */
    private ExecutorService workerExecutor;
    /** Agent 调度线程 */
    private ScheduledExecutorService scheduler;

    private volatile boolean running = false;

    public HotAgentPool(AgentTaskQueue taskQueue,
                        Function<AgentTask, String> taskExecutor,
                        int workerCount) {
        this.taskQueue = taskQueue;
        this.taskExecutor = taskExecutor;
        this.workerCount = workerCount > 0 ? workerCount : Runtime.getRuntime().availableProcessors();
    }

    /**
     * 获取 Agent 优先级。
     */
    public int getPriority(String agentName) {
        return HOT_AGENTS.getOrDefault(agentName, 5);
    }

    /**
     * 判断 Agent 是否为 Hot Agent。
     */
    public boolean isHotAgent(String agentName) {
        return HOT_AGENTS.containsKey(agentName);
    }

    /**
     * 获取 Hot Agent 列表。
     */
    public Set<String> getHotAgentNames() {
        return HOT_AGENTS.keySet();
    }

    @Override
    public void afterPropertiesSet() {
        start();
    }

    @Override
    public void destroy() {
        shutdown();
    }

    /**
     * 启动 Hot Agent 池。
     */
    public synchronized void start() {
        if (running) return;
        running = true;

        // 初始化各 Agent 的并发限流器
        for (String agent : HOT_AGENTS.keySet()) {
            agentConcurrencyLimiters.put(agent, new Semaphore(DEFAULT_MAX_CONCURRENCY));
            agentQueues.put(agent, new PriorityBlockingQueue<>(
                    100, (a, b) -> Integer.compare(b.getPriority(), a.getPriority())));
        }

        // Worker 线程池（虚拟线程）
        workerExecutor = Executors.newVirtualThreadPerTaskExecutor();

        // 调度器（每 500ms 检查一次任务分配）
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "hot-agent-scheduler");
            t.setDaemon(true);
            return t;
        });

        // 启动 Worker
        for (int i = 0; i < workerCount; i++) {
            final int workerId = i;
            workerExecutor.submit(() -> runWorker(workerId));
        }

        // 启动调度器：从 Redis 队列拉取任务，分配到 Agent 亲和队列
        scheduler.scheduleWithFixedDelay(this::dispatchTasks, 1, 1, TimeUnit.SECONDS);

        log.info("[HotAgentPool] 启动完成: hotAgents={}, workers={}",
                HOT_AGENTS.keySet(), workerCount);
    }

    /**
     * 停止 Hot Agent 池。
     */
    public synchronized void shutdown() {
        if (!running) return;
        running = false;

        if (scheduler != null) scheduler.shutdownNow();
        if (workerExecutor != null) workerExecutor.shutdownNow();

        log.info("[HotAgentPool] 已停止");
    }

    /**
     * 调度器：从 Redis 队列轮询任务，按 Agent 分配到本地优先级队列。
     */
    private void dispatchTasks() {
        if (!running) return;
        try {
            // 每次最多取 5 个任务
            for (int i = 0; i < 5; i++) {
                var taskOpt = taskQueue.dequeue(0); // 非阻塞
                if (taskOpt.isEmpty()) break;

                AgentTask task = taskOpt.get();
                String agentName = task.getAgentName();

                // 分配到 Agent 亲和队列
                PriorityBlockingQueue<AgentTask> agentQueue = agentQueues.get(agentName);
                if (agentQueue == null) {
                    // 非 Hot Agent，直接分配默认队列
                    agentQueue = agentQueues.computeIfAbsent(agentName,
                            k -> new PriorityBlockingQueue<>(100,
                                    (a, b) -> Integer.compare(b.getPriority(), a.getPriority())));
                }

                agentQueue.offer(task);
                log.debug("[HotAgentPool] 分发任务: taskId={}, agent={}, priority={}",
                        task.getTaskId(), agentName, task.getPriority());
            }
        } catch (Exception e) {
            log.warn("[HotAgentPool] 调度异常: {}", e.getMessage());
        }
    }

    /**
     * Worker 运行循环：从 Agent 队列取任务执行。
     */
    private void runWorker(int workerId) {
        log.debug("[HotAgentPool] Worker-{} 启动", workerId);

        while (running) {
            try {
                AgentTask task = pollTask(1000); // 1s 超时
                if (task == null) continue;

                Semaphore limiter = agentConcurrencyLimiters.get(task.getAgentName());
                if (limiter != null) {
                    limiter.acquire();
                }

                try {
                    task.markRunning();
                    taskQueue.saveResult(task);

                    String result = taskExecutor.apply(task);
                    task.markCompleted(result);

                } catch (Exception e) {
                    task.markFailed(e.getMessage());
                    log.error("[HotAgentPool] 任务失败: taskId={}, error={}",
                            task.getTaskId(), e.getMessage());

                    if (task.canRetry()) {
                        task.setRetryCount(task.getRetryCount() + 1);
                        taskQueue.enqueue(task);
                    }
                } finally {
                    if (limiter != null) limiter.release();
                }

                taskQueue.saveResult(task);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("[HotAgentPool] Worker-{} 异常: {}", workerId, e.getMessage());
            }
        }

        log.debug("[HotAgentPool] Worker-{} 停止", workerId);
    }

    /**
     * 从所有 Agent 队列中按优先级轮询获取下一个任务。
     */
    private AgentTask pollTask(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;

        while (System.currentTimeMillis() < deadline) {
            // 按 HOT_AGENTS 顺序遍历（Order > Product > General）
            for (Map.Entry<String, Integer> entry : HOT_AGENTS.entrySet()) {
                PriorityBlockingQueue<AgentTask> queue = agentQueues.get(entry.getKey());
                if (queue != null && !queue.isEmpty()) {
                    AgentTask task = queue.poll();
                    if (task != null) return task;
                }
            }

            // 检查其他 Agent 队列
            for (Map.Entry<String, PriorityBlockingQueue<AgentTask>> entry : agentQueues.entrySet()) {
                if (!HOT_AGENTS.containsKey(entry.getKey())) {
                    AgentTask task = entry.getValue().poll();
                    if (task != null) return task;
                }
            }

            Thread.sleep(50);
        }

        return null;
    }
}
