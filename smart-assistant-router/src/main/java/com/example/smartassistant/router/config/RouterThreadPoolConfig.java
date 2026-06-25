/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Router Service 线程池配置
 */
@Configuration
public class RouterThreadPoolConfig {
    
    private static final Logger log = LoggerFactory.getLogger(RouterThreadPoolConfig.class);
    
    /**
     * 并行 Agent 调用线程池（保留传统线程池 —— 限流型）
     *
     * <p>任务性质：[限流型]。该池被刻意设小（core=5/max=10/queue=20），本质是一道
     * <b>对下游 Agent 的并发闸门</b>，限制同时在途的 Agent 远程调用数量，避免压垮下游服务。</p>
     *
     * <p>⚠️ 升级 JDK 21 后<b>未改用虚拟线程</b>：虚拟线程执行器是无界的，直接替换会丢失此处
     * 限流语义，可能导致对下游 Agent 的并发调用失控。如需改用虚拟线程，须配合
     * {@code Semaphore} 等限流器维持“≤5 并发”的上限。</p>
     * <ul>
     *     <li>核心线程数：5（支持同时调用 5 个 Agent）</li>
     *     <li>最大线程数：10（应对突发流量）</li>
     *     <li>队列容量：20</li>
     *     <li>拒绝策略：CallerRunsPolicy（保证任务不丢失）</li>
     * </ul>
     */
    @Bean(name = "routerParallelAgentExecutor")
    public Executor routerParallelAgentExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        
        // 核心线程数：支持 5 个 Agent 并行
        executor.setCorePoolSize(5);
        
        // 最大线程数
        executor.setMaxPoolSize(10);
        
        // 队列容量
        executor.setQueueCapacity(20);
        
        // 线程名称前缀
        executor.setThreadNamePrefix("router-parallel-agent-");
        
        // 空闲线程存活时间（秒）
        executor.setKeepAliveSeconds(120);
        
        // 拒绝策略：由调用线程执行，保证任务不丢失
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        
        // 等待所有任务结束后再关闭线程池
        executor.setWaitForTasksToCompleteOnShutdown(true);
        
        // 等待时间（秒）
        executor.setAwaitTerminationSeconds(60);
        
        // 初始化
        executor.initialize();
        
        log.info("[Router ThreadPool] 并行 Agent 调用线程池初始化完成: core=5, max=10, queue=20");
        
        return executor;
    }
}
