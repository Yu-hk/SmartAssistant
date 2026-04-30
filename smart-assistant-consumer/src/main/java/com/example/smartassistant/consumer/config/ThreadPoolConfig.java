package com.example.smartassistant.consumer.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 线程池配置类
 * 
 * <p>为不同的业务场景配置专用的线程池，避免资源竞争和线程泄漏</p>
 * 
 * <h3>配置的线程池：</h3>
 * <ul>
 *     <li><b>scheduledTaskExecutor</b> - 定时任务专用线程池（会话清理等）</li>
 *     <li><b>asyncRouteExecutor</b> - 异步路由日志记录线程池</li>
 *     <li><b>asyncEmbeddingExecutor</b> - 异步 Embedding 计算线程池</li>
 * </ul>
 */
@Configuration
@EnableAsync  // 启用异步方法支持
@EnableScheduling  // 启用定时任务支持
public class ThreadPoolConfig implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(ThreadPoolConfig.class);

    // ⭐ 通用异步任务线程池配置
    @org.springframework.beans.factory.annotation.Value("${thread-pool.async-task.core-size:5}")
    private int asyncTaskCoreSize;
    
    @org.springframework.beans.factory.annotation.Value("${thread-pool.async-task.max-size:10}")
    private int asyncTaskMaxSize;
    
    @org.springframework.beans.factory.annotation.Value("${thread-pool.async-task.queue-capacity:200}")
    private int asyncTaskQueueCapacity;
    
    @org.springframework.beans.factory.annotation.Value("${thread-pool.async-task.keep-alive-seconds:60}")
    private int asyncTaskKeepAliveSeconds;
    
    // ⭐ 定时任务线程池配置
    @org.springframework.beans.factory.annotation.Value("${thread-pool.scheduled-task.core-size:1}")
    private int scheduledTaskCoreSize;
    
    @org.springframework.beans.factory.annotation.Value("${thread-pool.scheduled-task.max-size:3}")
    private int scheduledTaskMaxSize;
    
    @org.springframework.beans.factory.annotation.Value("${thread-pool.scheduled-task.queue-capacity:50}")
    private int scheduledTaskQueueCapacity;
    
    // ⭐ 异步路由日志线程池配置
    @org.springframework.beans.factory.annotation.Value("${thread-pool.async-route.core-size:2}")
    private int asyncRouteCoreSize;
    
    @org.springframework.beans.factory.annotation.Value("${thread-pool.async-route.max-size:5}")
    private int asyncRouteMaxSize;
    
    @org.springframework.beans.factory.annotation.Value("${thread-pool.async-route.queue-capacity:100}")
    private int asyncRouteQueueCapacity;
    
    @org.springframework.beans.factory.annotation.Value("${thread-pool.async-route.keep-alive-seconds:120}")
    private int asyncRouteKeepAliveSeconds;
    
    // ⭐ 异步 Embedding 线程池配置
    @org.springframework.beans.factory.annotation.Value("${thread-pool.async-embedding.queue-capacity:50}")
    private int asyncEmbeddingQueueCapacity;

    /**
     * ⭐ 通用异步任务线程池（用于 Session 数据库写入等）
     * 
     * <p>用于 @Async 注解的默认线程池</p>
     * <ul>
     *     <li>核心线程数：可配置（默认 5）</li>
     *     <li>最大线程数：可配置（默认 10）</li>
     *     <li>队列容量：可配置（默认 200）</li>
     *     <li>拒绝策略：CallerRunsPolicy（保证任务执行）</li>
     * </ul>
     */
    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        
        // 核心线程数
        executor.setCorePoolSize(asyncTaskCoreSize);
        
        // 最大线程数
        executor.setMaxPoolSize(asyncTaskMaxSize);
        
        // 队列容量
        executor.setQueueCapacity(asyncTaskQueueCapacity);
        
        // 线程名称前缀
        executor.setThreadNamePrefix("async-task-");
        
        // 空闲线程存活时间（秒）
        executor.setKeepAliveSeconds(asyncTaskKeepAliveSeconds);
        
        // 拒绝策略：由调用线程执行，保证任务不丢失
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        
        // 等待所有任务结束后再关闭线程池
        executor.setWaitForTasksToCompleteOnShutdown(true);
        
        // 等待时间（秒）
        executor.setAwaitTerminationSeconds(60);
        
        // 初始化
        executor.initialize();
        
        log.info("[ThreadPool] 通用异步任务线程池初始化完成: core={}, max={}, queue={}", 
                asyncTaskCoreSize, asyncTaskMaxSize, asyncTaskQueueCapacity);
        
        return executor;
    }

    /**
     * 定时任务专用线程池
     * 
     * <p>用于执行 @Scheduled 注解的定时任务</p>
     * <ul>
     *     <li>核心线程数：可配置（默认 1，定时任务通常串行执行）</li>
     *     <li>最大线程数：可配置（默认 3，应对突发任务）</li>
     *     <li>队列容量：可配置（默认 50）</li>
     *     <li>拒绝策略：CallerRunsPolicy（由调用线程执行，保证任务不丢失）</li>
     * </ul>
     */
    @Bean(name = "scheduledTaskExecutor")
    public Executor scheduledTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        
        // 核心线程数
        executor.setCorePoolSize(scheduledTaskCoreSize);
        
        // 最大线程数
        executor.setMaxPoolSize(scheduledTaskMaxSize);
        
        // 队列容量
        executor.setQueueCapacity(scheduledTaskQueueCapacity);
        
        // 线程名称前缀
        executor.setThreadNamePrefix("scheduled-task-");
        
        // 空闲线程存活时间（秒）
        executor.setKeepAliveSeconds(60);
        
        // 拒绝策略：由调用线程执行，保证任务不丢失
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        
        // 等待所有任务结束后再关闭线程池
        executor.setWaitForTasksToCompleteOnShutdown(true);
        
        // 等待时间（秒）
        executor.setAwaitTerminationSeconds(60);
        
        // 初始化
        executor.initialize();
        
        log.info("[ThreadPool] 定时任务线程池初始化完成: core={}, max={}, queue={}", 
                scheduledTaskCoreSize, scheduledTaskMaxSize, scheduledTaskQueueCapacity);
        
        return executor;
    }

    /**
     * 异步路由日志记录线程池
     * 
     * <p>用于异步保存路由调用日志，不阻塞主业务流程</p>
     * <ul>
     *     <li>核心线程数：可配置（默认 2）</li>
     *     <li>最大线程数：可配置（默认 5）</li>
     *     <li>队列容量：可配置（默认 100）</li>
     *     <li>拒绝策略：DiscardPolicy（日志丢失可接受）</li>
     * </ul>
     */
    @Bean(name = "asyncRouteExecutor")
    public Executor asyncRouteExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        
        // 核心线程数
        executor.setCorePoolSize(asyncRouteCoreSize);
        
        // 最大线程数
        executor.setMaxPoolSize(asyncRouteMaxSize);
        
        // 队列容量
        executor.setQueueCapacity(asyncRouteQueueCapacity);
        
        // 线程名称前缀
        executor.setThreadNamePrefix("async-route-");
        
        // 空闲线程存活时间（秒）
        executor.setKeepAliveSeconds(asyncRouteKeepAliveSeconds);
        
        // 拒绝策略：丢弃任务（日志记录失败可以接受）
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        
        // 等待所有任务结束后再关闭线程池
        executor.setWaitForTasksToCompleteOnShutdown(true);
        
        // 等待时间（秒）
        executor.setAwaitTerminationSeconds(30);
        
        // 初始化
        executor.initialize();
        
        log.info("[ThreadPool] 异步路由日志线程池初始化完成: core={}, max={}, queue={}", 
                asyncRouteCoreSize, asyncRouteMaxSize, asyncRouteQueueCapacity);
        
        return executor;
    }

    /**
     * 异步 Embedding 计算线程池
     * 
     * <p>用于异步更新规则的 Embedding 向量（CPU 密集型任务）</p>
     * <ul>
     *     <li>核心线程数：CPU 核心数</li>
     *     <li>最大线程数：CPU 核心数 * 2</li>
     *     <li>队列容量：可配置（默认 50）</li>
     *     <li>拒绝策略：AbortPolicy（抛出异常，需要重试）</li>
     * </ul>
     */
    @Bean(name = "asyncEmbeddingExecutor")
    public Executor asyncEmbeddingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        
        // 获取 CPU 核心数
        int cpuCores = Runtime.getRuntime().availableProcessors();
        
        // 核心线程数 = CPU 核心数
        executor.setCorePoolSize(cpuCores);
        
        // 最大线程数 = CPU 核心数 * 2
        executor.setMaxPoolSize(cpuCores * 2);
        
        // 队列容量
        executor.setQueueCapacity(asyncEmbeddingQueueCapacity);
        
        // 线程名称前缀
        executor.setThreadNamePrefix("async-embedding-");
        
        // 空闲线程存活时间（秒）
        executor.setKeepAliveSeconds(60);
        
        // 拒绝策略：抛出异常（Embedding 计算重要，不能静默丢弃）
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        
        // 允许核心线程超时
        executor.setAllowCoreThreadTimeOut(true);
        
        // 等待所有任务结束后再关闭线程池
        executor.setWaitForTasksToCompleteOnShutdown(true);
        
        // 等待时间（秒）
        executor.setAwaitTerminationSeconds(60);
        
        // 初始化
        executor.initialize();
        
        log.info("[ThreadPool] 异步 Embedding 线程池初始化完成: core={}, max={}, queue={}", 
                cpuCores, cpuCores * 2, asyncEmbeddingQueueCapacity);
        
        return executor;
    }

    /**
     * 配置定时任务的线程池
     */
    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        
        // 核心线程数
        scheduler.setPoolSize(3);
        
        // 线程名称前缀
        scheduler.setThreadNamePrefix("scheduled-task-");
        
        // 等待所有任务结束后再关闭线程池
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        
        // 等待时间（秒）
        scheduler.setAwaitTerminationSeconds(60);
        
        // 初始化
        scheduler.initialize();
        
        taskRegistrar.setTaskScheduler(scheduler);
        
        log.info("[ThreadPool] 定时任务调度器初始化完成: poolSize=3");
    }
}
