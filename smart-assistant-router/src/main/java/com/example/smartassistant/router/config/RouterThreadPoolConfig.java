package com.example.smartassistant.router.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Router Service 线程池配置
 * <p>
 * 在 JDK 21 虚拟线程模式下，Agent 并行调用使用虚拟线程 per-task，
 * 无需预先分配固定大小的线程池。
 */
@Configuration
public class RouterThreadPoolConfig {
    
    private static final Logger log = LoggerFactory.getLogger(RouterThreadPoolConfig.class);
    
    /**
     * 并行 Agent 调用线程池（虚拟线程版本）
     * <p>
     * 使用 {@link Executors#newVirtualThreadPerTaskExecutor()} 替代
     * 传统 {@code ThreadPoolTaskExecutor}，消除线程上限。
     * 每个 Agent 调用在一个轻量级虚拟线程中执行，I/O 阻塞时自动 yield。
     */
    @Bean(name = "routerParallelAgentExecutor")
    public Executor routerParallelAgentExecutor() {
        Executor executor = Executors.newVirtualThreadPerTaskExecutor();
        log.info("[Router ThreadPool] 并行 Agent 调用线程池 → 虚拟线程模式（无限扩展）");
        return executor;
    }
}
