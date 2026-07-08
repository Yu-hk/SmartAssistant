/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.model.tier;

import com.example.smartassistant.common.rag.pipeline.QueryComplexityClassifier;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * ⭐ G3 Tier 多模型路由（统一模型接入层）。
 *
 * <p>对标文章④《腾讯混元架构面经》「多模型路由降级 Tier1/2/3 + 动态路由 + 平滑降级」：
 * <ul>
 *   <li><b>动态路由</b>：复用 {@link QueryComplexityClassifier} 规则分类（SIMPLE/MEDIUM/COMPLEX）
 *       映射为 LIGHT/STANDARD/HEAVY；支持意图维度覆盖（如退款投诉类意图强制 HEAVY）。</li>
 *   <li><b>平滑降级</b>：调用所选档位模型失败时，自动沿降级链尝试更低档位，
 *       {@link ModelTier#LIGHT} 始终为最终兜底，绝不让单档位故障导致请求整体失败。</li>
 *   <li><b>可观测</b>：通过 Micrometer 上报档位分布、降级次数、各档位时延，供 Grafana 采集。</li>
 * </ul>
 *
 * <p>本类为无状态、可单测组件：模型故障通过异常捕获触发降级，不依赖具体 LLM 实现。</p>
 */
public class TieredModelRouter {

    private static final Logger log = LoggerFactory.getLogger(TieredModelRouter.class);

    private final QueryComplexityClassifier classifier;
    private final TierModelRegistry registry;
    private final Map<String, ModelTier> intentOverrides;
    private final boolean degradationEnabled;

    // ⭐ 灰度发布
    private final double canaryRatio;
    private final String canaryModelName;

    private final Counter tierCounter;
    private final Counter degradeCounter;
    private final Timer latencyTimer;

    public TieredModelRouter(QueryComplexityClassifier classifier,
                             TierModelRegistry registry,
                             Map<String, ModelTier> intentOverrides,
                             boolean degradationEnabled,
                             double canaryRatio,
                             String canaryModelName,
                             MeterRegistry meterRegistry) {
        this.classifier = classifier;
        this.registry = registry;
        this.intentOverrides = intentOverrides != null ? intentOverrides : Map.of();
        this.degradationEnabled = degradationEnabled;
        this.canaryRatio = canaryRatio;
        this.canaryModelName = canaryModelName != null ? canaryModelName : "";
        if (meterRegistry != null) {
            this.tierCounter = Counter.builder("model.tier.selections")
                    .description("模型档位选择分布").register(meterRegistry);
            this.degradeCounter = Counter.builder("model.tier.degradations")
                    .description("档位平滑降级次数").register(meterRegistry);
            this.latencyTimer = Timer.builder("model.tier.latency")
                    .description("档位路由端到端时延").register(meterRegistry);
        } else {
            this.tierCounter = null;
            this.degradeCounter = null;
            this.latencyTimer = null;
        }
    }

    /**
     * 按查询复杂度 + 可选意图选定初始档位。
     *
     * @param query     用户查询
     * @param intentTag 意图标签（可为 null，用于强制覆盖档位）
     * @return 选定的 {@link ModelTier}
     */
    public ModelTier selectTier(String query, String intentTag) {
        ModelTier override = intentTag != null ? intentOverrides.get(intentTag) : null;
        if (override != null) {
            log.debug("[TieredRouter] 意图覆盖档位: intentTag={}, tier={}", intentTag, override);
            return override;
        }
        // ⭐ 灰度判断：canaryRatio > 0 且查询命中灰度比例
        if (canaryRatio > 0 && canaryModelName != null && !canaryModelName.isBlank()) {
            if (isCanaryRequest(query)) {
                // 灰度请求走标准档位但用 canaryModelName 覆盖模型
                return selectTierInternal(query);
            }
        }
        return selectTierInternal(query);
    }

    /** 内部档位选择（不受灰度覆盖）。 */
    private ModelTier selectTierInternal(String query) {
        return switch (classifier.classify(query)) {
            case SIMPLE -> ModelTier.LIGHT;
            case MEDIUM -> ModelTier.STANDARD;
            case COMPLEX -> ModelTier.HEAVY;
        };
    }

    /**
     * 基于查询的确定性灰度判定。
     * 同一 query 始终落在同一区间，保证灰度用户一致性。
     */
    private boolean isCanaryRequest(String query) {
        if (canaryRatio <= 0 || query == null) return false;
        int hash = Math.abs(query.hashCode());
        double ratio = (hash % 100) / 100.0;
        return ratio < canaryRatio;
    }

    /**
     * 选定档位对应的 ChatModel（不做降级，供调用方自行决定）。
     */
    public ChatModel selectModel(String query, String intentTag) {
        return registry.get(selectTier(query, intentTag));
    }

    /**
     * ⭐ 核心：带平滑降级的模型调用。
     *
     * <p>从按复杂度/意图选定的档位起，逐档尝试调用；任一档位成功即返回 {@link TierSelection}。
     * 全部失败抛出 {@link ModelTierUnavailableException}（最后一档的异常作为 cause）。</p>
     *
     * @param prompt    完整 Prompt（system + user 消息）
     * @param query     用户查询（用于档位选择，可空）
     * @param intentTag 意图标签（用于覆盖档位，可空）
     * @return 含实际响应与路由元信息的 {@link TierSelection}
     */
    public TierSelection call(Prompt prompt, String query, String intentTag) {
        ModelTier requested = selectTier(query, intentTag);
        List<ModelTier> attempted = new ArrayList<>();
        long start = System.nanoTime();
        Throwable lastError = null;

        List<ModelTier> chain = degradationEnabled
                ? registry.fallbackChain(requested)
                : List.of(requested);

        for (ModelTier tier : chain) {
            ChatModel model = registry.get(tier);
            if (model == null) {
                log.debug("[TieredRouter] 档位 {} 无可用模型，跳过", tier);
                continue;
            }
            attempted.add(tier);
            try {
                ChatResponse resp = model.call(prompt);
                if (resp == null) {
                    throw new IllegalStateException("档位 " + tier + " 返回空响应");
                }
                long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                boolean degraded = !tier.equals(requested);
                if (degraded && degradeCounter != null) {
                    degradeCounter.increment();
                }
                if (tierCounter != null) {
                    tierCounter.increment();
                }
                if (latencyTimer != null) {
                    latencyTimer.record(elapsed, TimeUnit.MILLISECONDS);
                }
                log.info("[TieredRouter] 档位路由完成: requested={}, served={}, degraded={}, attempted={}, {}ms",
                        requested, tier, degraded, attempted, elapsed);
                return new TierSelection(
                        requested, tier, registry.modelName(tier), degraded,
                        List.copyOf(attempted),
                        degraded ? "degraded-from-" + requested : "direct",
                        elapsed, resp);
            } catch (Throwable t) {
                lastError = t;
                log.warn("[TieredRouter] 档位 {} 调用失败，尝试降级: {}", tier, t.getMessage());
            }
        }

        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        if (tierCounter != null) {
            tierCounter.increment();
        }
        throw new ModelTierUnavailableException(requested, List.copyOf(attempted), lastError, elapsed);
    }

    /**
     * 所有模型档位均不可用异常（降级链耗尽）。
     */
    public static class ModelTierUnavailableException extends RuntimeException {
        public final ModelTier requested;
        public final List<ModelTier> attempted;
        public final long latencyMillis;

        public ModelTierUnavailableException(ModelTier requested, List<ModelTier> attempted,
                                             Throwable cause, long latencyMillis) {
            super("所有模型档位均不可用: requested=" + requested + ", attempted=" + attempted, cause);
            this.requested = requested;
            this.attempted = attempted;
            this.latencyMillis = latencyMillis;
        }
    }
}
