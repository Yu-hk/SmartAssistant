package com.example.smartassistant.common.gateway.tool.meta;

import com.example.smartassistant.common.trace.TraceSpan;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

/**
 * 工具缺口可观测事件（T2f）。
 *
 * <p>与 {@link DiscoveryEvent}（供给侧：发现了什么工具）互补，本事件记录
 * <b>需求侧</b>缺口——即「用户 / LLM 想要但当前工具集无法满足」的情况，用于后续
 * 分析应补建哪些能力。覆盖三类缺口：
 * <ul>
 *   <li>{@code UNKNOWN_TOOL} — LLM 调用了工具集中不存在的工具名（工具幻觉）；</li>
 *   <li>{@code DISCOVER_MISS} — {@code discover_tools} 在 Registry 中也找不到对应能力；</li>
 *   <li>{@code EMPTY_RESULT} — 工具存在且执行成功，但返回空 / 查不到数据（预留，二期启用）。</li>
 * </ul>
 *
 * <p><b>记录字段</b>（均为结构化，便于后续聚合分析）：
 * <ul>
 *   <li>{@code gapType} — 缺口类型（上列三类）；</li>
 *   <li>{@code attemptedCapability} — LLM 尝试的能力：工具名（UNKNOWN_TOOL）或 capabilityQuery（DISCOVER_MISS）；</li>
 *   <li>{@code attemptedArgs} — LLM 实际传入的参数（JSON 原文，可为 null）；</li>
 *   <li>{@code reason} — 派生原因：为何查这个工具 / 能力；</li>
 *   <li>{@code desiredResultHint} — 期望得到的结果提示（能力的自然语言描述或期望返回结构）；</li>
 *   <li>{@code resolution} — 处置方式：logged（仅记录）/ asked_user（已向用户澄清）/ degraded（优雅降级）；</li>
 *   <li>{@code agentId} / {@code conversationId} / {@code timestamp} — 溯源字段。</li>
 * </ul>
 *
 * <p><b>记录方式</b>：优先接入 Micrometer {@link ObservationRegistry} span
 * {@code agent-tool-gap}；若 registry 不可用，回退 SLF4J 结构化日志（与 DiscoveryEvent 一致）。</p>
 */
public class ToolGapEvent {

    private static final Logger log = LoggerFactory.getLogger(ToolGapEvent.class);

    private final String gapType;
    private final String attemptedCapability;
    private final String attemptedArgs;
    private final String reason;
    private final String desiredResultHint;
    private final String resolution;
    private final String agentId;
    private final String conversationId;
    private final Instant timestamp;

    private ToolGapEvent(Builder b) {
        this.gapType = b.gapType;
        this.attemptedCapability = b.attemptedCapability;
        this.attemptedArgs = b.attemptedArgs;
        this.reason = b.reason;
        this.desiredResultHint = b.desiredResultHint;
        this.resolution = b.resolution != null ? b.resolution : "logged";
        this.agentId = b.agentId;
        this.conversationId = b.conversationId;
        this.timestamp = b.timestamp != null ? b.timestamp : Instant.now();
    }

    /**
     * 记录本事件：优先 Observation span，回退 SLF4J。
     *
     * @param observationRegistry 可注入的 ObservationRegistry（可为 null → 仅 SLF4J）
     */
    public void record(ObservationRegistry observationRegistry) {
        if (observationRegistry != null && observationRegistry != ObservationRegistry.NOOP) {
            TraceSpan.of(observationRegistry, "agent-tool-gap").run(this::logEvent);
        } else {
            logEvent();
        }
    }

    private void logEvent() {
        log.info("[ToolGapEvent] gapType={}, capability={}, args={}, reason={}, "
                        + "desired={}, resolution={}, agentId={}, conversationId={}, timestamp={}",
                gapType, attemptedCapability,
                attemptedArgs != null ? truncate(attemptedArgs, 200) : "",
                reason, desiredResultHint, resolution, agentId, conversationId, timestamp);
    }

    private static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) + "..." : s;
    }

    // ==================== Getters ====================

    public String getGapType() { return gapType; }
    public String getAttemptedCapability() { return attemptedCapability; }
    public String getAttemptedArgs() { return attemptedArgs; }
    public String getReason() { return reason; }
    public String getDesiredResultHint() { return desiredResultHint; }
    public String getResolution() { return resolution; }
    public String getAgentId() { return agentId; }
    public String getConversationId() { return conversationId; }
    public Instant getTimestamp() { return timestamp; }

    // ==================== Builder ====================

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String gapType;
        private String attemptedCapability;
        private String attemptedArgs;
        private String reason;
        private String desiredResultHint;
        private String resolution;
        private String agentId;
        private String conversationId;
        private Instant timestamp;

        public Builder gapType(String v) { this.gapType = v; return this; }
        public Builder attemptedCapability(String v) { this.attemptedCapability = v; return this; }
        public Builder attemptedArgs(String v) { this.attemptedArgs = v; return this; }
        public Builder reason(String v) { this.reason = v; return this; }
        public Builder desiredResultHint(String v) { this.desiredResultHint = v; return this; }
        public Builder resolution(String v) { this.resolution = v; return this; }
        public Builder agentId(String v) { this.agentId = v; return this; }
        public Builder conversationId(String v) { this.conversationId = v; return this; }
        public Builder timestamp(Instant v) { this.timestamp = v; return this; }

        public ToolGapEvent build() {
            return new ToolGapEvent(this);
        }
    }
}
