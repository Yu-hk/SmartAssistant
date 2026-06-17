package com.example.smartassistant.consumer.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 线程池配置类
 * 
 * <p>在 JDK 21 + Spring Boot 3.4 虚拟线程模式下，I/O 密集型线程池由
 * {@code spring.threads.virtual.enabled=true} 全局接管（@Async、Tomcat 等），
 * 本配置仅保留 CPU 密集型的 Embedding 计算线程池。</p>
 * 
 * <p>已删除的线程池（由虚拟线程替代）：</p>
 * <ul>
 *     <li><b>taskExecutor</b> — 通用异步任务 → 虚拟线程 per-task</li>
 *     <li><b>scheduledTaskExecutor</b> — 定时任务 → 虚拟线程 per-task</li>
 *     <li><b>asyncRouteExecutor</b> — 异步路由日志 → 虚拟线程 per-task</li>
 * </ul>
 * 
 * <h3>保留的线程池：</h3>
 * <ul>
 *     <li><b>asyncEmbeddingExecutor</b> - Embedding 计算（CPU 密集，保留平台线程池）</li>
 * </ul>
 */
@Configuration
@EnableAsync  // 启用异步方法支持（虚拟线程模式）
@EnableScheduling  // 启用定时任务支持（虚拟线程模式）
public class ThreadPoolConfig {

    private static final Logger log = LoggerFactory.getLogger(ThreadPoolConfig.class);

    // ⭐ 异步 Embedding 线程池配置
    @org.springframework.beans.factory.annotation.Value("${thread-pool.async-embedding.queue-capacity:50}")
    private int asyncEmbeddingQueueCapacity;

    /**
     * 异步 Embedding 计算线程池
     * 
     * <p>CPU 密集型任务，保留平台线程池以控制并行度。</p>
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
}
