/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * RAG 检索管线——按 {@link RagSearchHandler#getOrder()} 顺序执行。
 *
 * <p>参考 Snail AI 的 {@code RagSearchPipeline} 设计：
 * <ol>
 *   <li>自动注入所有 {@link RagSearchHandler} Bean</li>
 *   <li>按 Order 排序后依次执行</li>
 *   <li>支持提前终止（{@link RagSearchContext#setTerminated(boolean)}）</li>
 * </ol>
 *
 * <p>使用方法：
 * <pre>{@code
 * @Autowired
 * private RagSearchPipeline pipeline;
 *
 * RagSearchContext ctx = new RagSearchContext("iPhone 15 Pro 价格");
 * ctx.setQualityThreshold(0.30);
 * ctx = pipeline.execute(ctx);
 * List<RagSearchContext.RankedItem> results = ctx.getFusedResults();
 * }</pre>
 */
public class RagSearchPipeline {

    private static final Logger log = LoggerFactory.getLogger(RagSearchPipeline.class);

    private final List<RagSearchHandler> handlers;

    public RagSearchPipeline(@Autowired(required = false) List<RagSearchHandler> handlers) {
        this.handlers = handlers != null ? new ArrayList<>(handlers) : new ArrayList<>();
        // 按 Order 排序
        this.handlers.sort(Comparator.comparingInt(RagSearchHandler::getOrder));
        log.info("[RagPipeline] Registered {} handlers: {}",
                this.handlers.size(),
                this.handlers.stream()
                        .map(h -> h.getClass().getSimpleName())
                        .collect(Collectors.joining(", ")));
    }

    /**
     * 按 Order 顺序执行所有 Handler。
     *
     * @param context 检索上下文
     * @return 执行后的上下文
     */
    public RagSearchContext execute(RagSearchContext context) {
        long start = System.currentTimeMillis();

        for (RagSearchHandler handler : handlers) {
            if (context.isTerminated()) {
                log.debug("[RagPipeline] Pipeline terminated before {}", handler.getClass().getSimpleName());
                break;
            }
            try {
                handler.handle(context);
            } catch (Exception e) {
                log.warn("[RagPipeline] Handler {} error: {}", handler.getClass().getSimpleName(), e.getMessage());
            }
        }

        long elapsed = System.currentTimeMillis() - start;
        log.info("[RagPipeline] Pipeline completed: {} handlers, {} paths active, {} fused results, {}ms",
                handlers.size(),
                context.getPathResults().values().stream().filter(p -> !p.isEmpty()).count(),
                context.getFusedResults().size(),
                elapsed);
        return context;
    }

    /**
     * 获取当前注册的 Handler 列表。
     */
    public List<RagSearchHandler> getHandlers() {
        return new ArrayList<>(handlers);
    }
}
