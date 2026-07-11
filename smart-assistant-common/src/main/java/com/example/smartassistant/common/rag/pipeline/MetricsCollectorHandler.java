/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.pipeline;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * RAG 评估指标采集 Handler。
 *
 * <p>参考腾讯面试题 #4，采集 Pipeline 执行过程中的关键指标：
 * <ul>
 *   <li>检索耗时（Timer）</li>
 *   <li>召回数量（Gauge）</li>
 *   <li>各 Path 命中次数（Counter）</li>
 *   <li>质量得分分布（Gauge）</li>
 * </ul>
 *
 * <p>指标通过 Micrometer {@link MeterRegistry} 暴露，Prometheus 可抓取。
 *
 * <p>Order=1000（最后执行，不影响结果）。
 */
public class MetricsCollectorHandler implements RagSearchHandler {

    private static final Logger log = LoggerFactory.getLogger(MetricsCollectorHandler.class);

    private final MeterRegistry meterRegistry;
    private final boolean enabled;

    public MetricsCollectorHandler(MeterRegistry meterRegistry, boolean enabled) {
        this.meterRegistry = meterRegistry;
        this.enabled = enabled;
    }

    @Override
    public void handle(RagSearchContext context) {
        if (!enabled || meterRegistry == null) return;

        // 各 Path 召回数量
        for (var entry : context.getPathResults().entrySet()) {
            String pathName = entry.getKey();
            int count = entry.getValue() != null ? entry.getValue().getItems().size() : 0;
            meterRegistry.counter("rag.path.hits", "path", pathName).increment(count);

            // 去重后 + Rerank 后数量
            if (context.getFusedResults() != null) {
                meterRegistry.gauge("rag.fused.count",
                        context.getFusedResults(), List::size);
            }
        }

        // 质量得分
        meterRegistry.gauge("rag.quality.score", context, RagSearchContext::getQualityScore);

        // 检索耗时（从 attributes 读取）
        Object elapsedObj = context.getAttribute("pipeline.elapsedMs");
        if (elapsedObj instanceof Number) {
            meterRegistry.timer("rag.pipeline.duration")
                    .record(((Number) elapsedObj).longValue(), TimeUnit.MILLISECONDS);
        }

        log.debug("[MetricsCollector] RAG 指标已记录: paths={}, fused={}, quality={}",
                context.getPathResults().size(),
                context.getFusedResults() != null ? context.getFusedResults().size() : 0,
                String.format("%.2f", context.getQualityScore()));
    }

    @Override
    public int getOrder() {
        return 1000;
    }
}
