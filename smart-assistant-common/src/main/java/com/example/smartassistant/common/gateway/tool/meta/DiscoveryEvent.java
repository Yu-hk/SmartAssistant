package com.example.smartassistant.common.gateway.tool.meta;

import com.example.smartassistant.common.trace.TraceSpan;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 工具发现可观测事件（T2d）。
 *
 * <p>每次 {@link DiscoverToolsTool} 完成一轮发现后创建并记录本事件，包含：
 * <ul>
 *   <li>查询上下文：agentId / conversationId / capabilityQuery / keywords</li>
 *   <li>命中详情：matchedNames / hitCount / source(registry|cache|miss) </li>
 *   <li>注入详情：discoverToolsInjected / latencyMs</li>
 *   <li>时间戳：timestamp</li>
 * </ul>
 *
 * <p>记录方式：优先接入 Micrometer {@link ObservationRegistry} span
 * {@code agent-tool-discovery}；若 registry 不可用，回退 SLF4J 结构化日志。
 */
public class DiscoveryEvent {

    private static final Logger log = LoggerFactory.getLogger(DiscoveryEvent.class);

    private final String agentId;
    private final String conversationId;
    private final String capabilityQuery;
    private final String[] keywords;
    private final List<String> matchedNames;
    private final int hitCount;
    private final String source;
    private final long latencyMs;
    private final int discoverToolsInjected;
    private final Instant timestamp;

    private DiscoveryEvent(Builder builder) {
        this.agentId = builder.agentId;
        this.conversationId = builder.conversationId;
        this.capabilityQuery = builder.capabilityQuery;
        this.keywords = builder.keywords;
        this.matchedNames = builder.matchedNames;
        this.hitCount = builder.hitCount;
        this.source = builder.source;
        this.latencyMs = builder.latencyMs;
        this.discoverToolsInjected = builder.discoverToolsInjected;
        this.timestamp = builder.timestamp != null ? builder.timestamp : Instant.now();
    }

    /**
     * 记录本事件：优先 Observation span，回退 SLF4J。
     *
     * @param observationRegistry 可注入的 ObservationRegistry（可为 null）
     */
    public void record(ObservationRegistry observationRegistry) {
        if (observationRegistry != null && observationRegistry != ObservationRegistry.NOOP) {
            TraceSpan.of(observationRegistry, "agent-tool-discovery").run(() -> {
                logEvent();
            });
        } else {
            logEvent();
        }
    }

    private void logEvent() {
        log.info("[DiscoveryEvent] agentId={}, conversationId={}, query={}, keywords={}, "
                        + "matched={}, hitCount={}, source={}, latencyMs={}, injected={}, timestamp={}",
                agentId, conversationId, capabilityQuery,
                keywords != null ? String.join(",", keywords) : "",
                matchedNames, hitCount, source, latencyMs, discoverToolsInjected, timestamp);
    }

    // ==================== Getters ====================

    public String getAgentId() { return agentId; }
    public String getConversationId() { return conversationId; }
    public String getCapabilityQuery() { return capabilityQuery; }
    public String[] getKeywords() { return keywords; }
    public List<String> getMatchedNames() { return matchedNames; }
    public int getHitCount() { return hitCount; }
    public String getSource() { return source; }
    public long getLatencyMs() { return latencyMs; }
    public int getDiscoverToolsInjected() { return discoverToolsInjected; }
    public Instant getTimestamp() { return timestamp; }

    // ==================== Builder ====================

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String agentId;
        private String conversationId;
        private String capabilityQuery;
        private String[] keywords;
        private List<String> matchedNames;
        private int hitCount;
        private String source;
        private long latencyMs;
        private int discoverToolsInjected;
        private Instant timestamp;

        public Builder agentId(String agentId) {
            this.agentId = agentId;
            return this;
        }

        public Builder conversationId(String conversationId) {
            this.conversationId = conversationId;
            return this;
        }

        public Builder capabilityQuery(String capabilityQuery) {
            this.capabilityQuery = Objects.requireNonNull(capabilityQuery, "capabilityQuery must not be null");
            return this;
        }

        public Builder keywords(String[] keywords) {
            this.keywords = keywords;
            return this;
        }

        public Builder matchedNames(List<String> matchedNames) {
            this.matchedNames = matchedNames;
            this.hitCount = matchedNames != null ? matchedNames.size() : 0;
            return this;
        }

        public Builder hitCount(int hitCount) {
            this.hitCount = hitCount;
            return this;
        }

        public Builder source(String source) {
            this.source = source;
            return this;
        }

        public Builder latencyMs(long latencyMs) {
            this.latencyMs = latencyMs;
            return this;
        }

        public Builder discoverToolsInjected(int discoverToolsInjected) {
            this.discoverToolsInjected = discoverToolsInjected;
            return this;
        }

        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public DiscoveryEvent build() {
            return new DiscoveryEvent(this);
        }
    }
}
