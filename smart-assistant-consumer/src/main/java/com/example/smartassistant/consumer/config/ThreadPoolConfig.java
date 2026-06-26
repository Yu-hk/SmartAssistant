/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
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
 * <p>为不同的业务场景配置专用的执行器。JDK 21 升级后按任务性质区分线程模型：</p>
 *
 * <h3>执行器清单（按任务性质）：</h3>
 * <ul>
 *     <li><b>taskExecutor</b>（I/O 阻塞型）- 通用 @Async 池，<b>已切换为虚拟线程</b>（Session DB / 文件写入）</li>
 *     <li><b>asyncRouteExecutor</b>（I/O 阻塞型）- 异步路由日志，<b>已切换为虚拟线程</b></li>
 *     <li><b>asyncEmbeddingExecutor</b>（CPU 密集型）- Embedding 计算，<b>保留平台线程池</b>（虚拟线程对 CPU 密集无收益且受核数约束）</li>
 *     <li><b>scheduledTaskExecutor</b> / TaskScheduler（定时调度）- <b>保留平台线程池</b>（@Scheduled 触发依赖固定调度线程）</li>
 * </ul>
 */
@Configuration
@EnableAsync  // 启用异步方法支持
@EnableScheduling  // 启用定时任务支持
public class ThreadPoolConfig implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(ThreadPoolConfig.class);

    // ⭐ 通用异步任务线程池配置
    // 注：taskExecutor 已改为虚拟线程，以下 size/queue 参数对其不再生效，保留仅为兼容历史配置文件
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
    // 注：asyncRouteExecutor 已改为虚拟线程，以下 size/queue 参数对其不再生效，保留仅为兼容历史配置文件
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
     * ⭐ 通用异步任务执行器（@Async 默认池）—— JDK 21 虚拟线程版
     *
     * <p>任务性质：[I/O 阻塞型]（Session 数据库写入、记忆文件写入、Redis 操作）。</p>
     * <p>原 {@code ThreadPoolTaskExecutor(core=5/max=10/queue=200)} 替换为基于虚拟线程的
     * {@link SimpleAsyncTaskExecutor}：每个任务运行在一根虚拟线程上，阻塞 I/O 期间自动释放
     * 载体线程，无需再为核心数/最大数/队列容量调参，并发吞吐近乎不受线程数限制。</p>
     */
    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("async-task-vt-");

        // 启用虚拟线程（每任务一线程）
        executor.setVirtualThreads(true);

        // 优雅关闭：等待在途任务完成，最多 60s（替代原 setWaitForTasksToCompleteOnShutdown + awaitTermination）
        executor.setTaskTerminationTimeout(60_000);

        log.info("[ThreadPool] 通用异步任务执行器初始化完成: 模式=虚拟线程(VirtualThreads), 优雅关闭超时=60s");

        return executor;
    }

    /**
     * 定时任务专用线程池（保留平台线程池）
     *
     * <p>任务性质：[定时调度]。{@code @Scheduled} 的触发依赖固定的调度线程，
     * 不适合替换为无界虚拟线程，故保留传统 {@link ThreadPoolTaskExecutor}。</p>
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
     * 异步路由日志记录执行器 —— JDK 21 虚拟线程版
     *
     * <p>任务性质：[I/O 阻塞型]（单次路由日志 DB insert）。原 {@code ThreadPoolTaskExecutor
     * (core=2/max=5/queue=100, DiscardPolicy)} 替换为虚拟线程：每条日志一根轻量虚拟线程，
     * 不再需要有界队列与丢弃策略，写入阻塞不再占用平台线程。</p>
     */
    @Bean(name = "asyncRouteExecutor")
    public Executor asyncRouteExecutor() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("async-route-vt-");

        // 启用虚拟线程
        executor.setVirtualThreads(true);

        // 优雅关闭：等待在途日志写入完成，最多 30s
        executor.setTaskTerminationTimeout(30_000);

        log.info("[ThreadPool] 异步路由日志执行器初始化完成: 模式=虚拟线程(VirtualThreads), 优雅关闭超时=30s");

        return executor;
    }

    /**
     * 异步 Embedding 计算线程池（保留平台线程池）
     *
     * <p>任务性质：[CPU 密集型]（向量 Embedding 计算）。虚拟线程的优势在于阻塞 I/O 期间释放
     * 载体线程，对纯 CPU 计算无收益，且其吞吐最终仍受 CPU 核数约束；同时本池刻意将并发上限
     * 绑定到 CPU 核数，起到天然背压作用。因此<b>保留传统 {@link ThreadPoolTaskExecutor}</b>。</p>
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
        
        log.info("[ThreadPool] 异步 Embedding 线程池初始化完成: core={}, max={}, queue={} (CPU 密集型，保留平台线程池)", 
                cpuCores, cpuCores * 2, asyncEmbeddingQueueCapacity);
        
        return executor;
    }

    /**
     * 配置定时任务的线程池（保留平台线程池）
     *
     * <p>任务性质：[定时调度]。调度器负责按固定周期/cron 触发任务，依赖稳定的平台线程，
     * 故保留 {@link ThreadPoolTaskScheduler}。被触发的任务体若为 I/O 阻塞，可由各自的
     * 虚拟线程执行器承接。</p>
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
        
        log.info("[ThreadPool] 定时任务调度器初始化完成: poolSize=3 (定时调度，保留平台线程池)");
    }
}
