/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.config;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RouterThreadPoolConfigTest {

    @Test
    void parallelAgentExecutorPropagatesRequestContext() throws Exception {
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor)
                new RouterThreadPoolConfig().routerParallelAgentExecutor();
        try {
            MDC.put("requestId", "graph-request-1");
            CompletableFuture<String> observed = new CompletableFuture<>();
            executor.execute(() -> observed.complete(MDC.get("requestId")));
            assertEquals("graph-request-1", observed.get(5, TimeUnit.SECONDS));
        } finally {
            MDC.clear();
            executor.shutdown();
        }
    }
}
