package com.example.smartassistant.service.search;

import com.example.smartassistant.common.rag.pipeline.*;
import com.example.smartassistant.config.NativeRagProperties;
import com.example.smartassistant.service.search.handler.KnowledgeSearchHandler;
import com.example.smartassistant.service.search.handler.RrfFusionHandler;
import org.slf4j.MDC;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/** One read-only KB re-search. No LLM planner, tools, global weight mutation or unbounded queue. */
final class BoundedRetrievalFeedback implements AutoCloseable {
    private static final Pattern MUTATION = Pattern.compile("下单|购买|支付|付款|扣款|退款|充值|删除|清除|取消|修改|更新|提交|收货人|地址|手机号");
    private static final Pattern KNOWLEDGE = Pattern.compile("如何|怎么|为什么|知识库|规则|政策|流程|计算|区别|原理|保修|条件|说明");
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(0, 2, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), task -> { Thread thread = new Thread(task, "rag-feedback"); thread.setDaemon(true); return thread; },
            new ThreadPoolExecutor.AbortPolicy());
    private final RagSearchPipeline pipeline;
    private final NativeRagProperties properties;

    BoundedRetrievalFeedback(RagSearchPipeline pipeline, NativeRagProperties properties) {
        this.pipeline = pipeline; this.properties = properties;
    }

    record Decision(RagSearchContext selected, RagSearchContext candidate, Map<String, Object> diagnostics) { }

    Decision apply(String query, RagSearchContext baseline, int maxItems) {
        var before = EvidenceGapEvaluator.assess(query, baseline, maxItems);
        if (!properties.isAutomaticRetryEnabled()) return decision(baseline, null, "DISABLED", before, null);
        if (Thread.currentThread().isInterrupted()) return decision(baseline, null, "CANCELLED_BEFORE_RETRY", before, null);
        if (query.length() > 500 || MUTATION.matcher(query).find() || !KNOWLEDGE.matcher(query).find())
            return decision(baseline, null, "NOT_READ_ONLY_KNOWLEDGE", before, null);
        if (!before.valid()) return decision(baseline, null, "BASELINE_DEGRADED_OR_BUDGET", before, null);
        if (before.required() == 0 || before.coverage() >= 1.0)
            return decision(baseline, null, "SUFFICIENT_PROXY", before, null);
        // Whitelist actual re-search + local fusion/dedup only. Never replay agent/tool/planner stages.
        List<RagSearchHandler> handlers = pipeline.getHandlers().stream().filter(handler ->
                handler instanceof KnowledgeSearchHandler || handler instanceof RrfFusionHandler || handler instanceof DedupHandler).toList();
        if (handlers.stream().noneMatch(KnowledgeSearchHandler.class::isInstance)
                || handlers.stream().noneMatch(RrfFusionHandler.class::isInstance))
            return decision(baseline, null, "RETRIEVERS_UNAVAILABLE", before, null);
        var mdc = MDC.getCopyOfContextMap();
        Future<RagSearchContext> future;
        try {
            future = executor.submit(() -> {
                try {
                    if (mdc != null) MDC.setContextMap(mdc); else MDC.clear();
                    RagSearchContext retry = new RagSearchContext(query);
                    retry.setQualityThreshold(baseline.getQualityThreshold());
                    for (String key : List.of("rag.originalUserQuery", "rag.knowledgeBases", "rag.scopeReason")) {
                        Object value = baseline.getAttribute(key);
                        if (value != null) retry.setAttribute(key, value);
                    }
                    retry.setAttribute("rag.attempt", 2);
                    retry.setAttribute("rag.retryKnowledgeTopK", 8);
                    retry.setAttribute(AdaptiveWeightHandler.ATTR_SPARSE_WEIGHT, 0.7);
                    retry.setAttribute(AdaptiveWeightHandler.ATTR_DENSE_WEIGHT, 0.3);
                    // Keep prior catalog/graph paths, unchanged. KB is replaced by fresh ACL-filtered search.
                    baseline.getPathResults().values().forEach(path -> retry.addPathResult(path.getPathName(), List.copyOf(path.getItems())));
                    new RagSearchPipeline(handlers.stream().filter(KnowledgeSearchHandler.class::isInstance).toList()).execute(retry);
                    EvidenceGapEvaluator.constrainCandidate(query, baseline, retry, before);
                    return new RagSearchPipeline(handlers.stream().filter(h -> !(h instanceof KnowledgeSearchHandler)).toList()).execute(retry);
                } finally { MDC.clear(); }
            });
        } catch (RejectedExecutionException busy) { return decision(baseline, null, "CAPACITY_LIMIT", before, null); }
        try {
            RagSearchContext candidate = future.get(properties.getRetryTimeoutMs(), TimeUnit.MILLISECONDS);
            var after = EvidenceGapEvaluator.assess(query, candidate, maxItems);
            boolean accepted = EvidenceGapEvaluator.improves(before, after, candidate);
            return decision(accepted ? candidate : baseline, candidate, accepted ? "ACCEPTED" : "NO_SAFE_IMPROVEMENT", before, after);
        } catch (InterruptedException cancelled) {
            future.cancel(true); Thread.currentThread().interrupt();
            return decision(baseline, null, "CANCELLED", before, null);
        } catch (TimeoutException timeout) {
            future.cancel(true); return decision(baseline, null, "TIMEOUT", before, null);
        } catch (ExecutionException failed) {
            return decision(baseline, null, "ERROR", before, null);
        }
    }

    private Decision decision(RagSearchContext selected, RagSearchContext candidate, String status,
                              EvidenceGapEvaluator.Assessment before, EvidenceGapEvaluator.Assessment after) {
        Map<String, Object> diagnostic = new LinkedHashMap<>();
        diagnostic.put("policy", "bounded-kb-feedback-v1"); diagnostic.put("status", status);
        diagnostic.put("expansionGate", "explicit_title_anchor_and_missing_body_term_preserve_baseline");
        if (candidate != null) diagnostic.put("addedChunks", candidate.getAttribute("rag.feedbackAddedChunks"));
        diagnostic.put("enabled", properties.isAutomaticRetryEnabled());
        diagnostic.put("maxRetries", 1); diagnostic.put("timeoutMs", properties.getRetryTimeoutMs());
        diagnostic.put("before", before.diagnostic());
        if (after != null) diagnostic.put("after", after.diagnostic());
        diagnostic.put("accepted", "ACCEPTED".equals(status));
        diagnostic.put("attempted", candidate != null || Set.of("TIMEOUT", "ERROR", "CANCELLED").contains(status));
        return new Decision(selected, candidate, Collections.unmodifiableMap(diagnostic));
    }
    @Override public void close() { executor.shutdownNow(); }
}
