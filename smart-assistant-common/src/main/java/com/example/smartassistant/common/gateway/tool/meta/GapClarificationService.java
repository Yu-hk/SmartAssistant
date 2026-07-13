package com.example.smartassistant.common.gateway.tool.meta;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ⭐ T2f 三期：工具缺口按需澄清服务。
 * <p>
 * 当检测到「能力缺口」（{@code UNKNOWN_TOOL} / {@code DISCOVER_MISS}）时，决定是否向用户提问
 * 并生成澄清话术；「数据缺口」（{@code EMPTY_RESULT}）永不提问——仅记录 + 优雅降级
 * （数据缺口本质不是能力缺失，跑去问用户「请提供工具信息」是错的，只会显得笨）。
 * </p>
 *
 * <h3>会话级频率护栏（仿 T2d maxDiscoveriesPerSession）</h3>
 * <ul>
 *   <li>同一 capability 只问一次（去重）；</li>
 *   <li>每实例最多问 {@code maxClarificationsPerSession} 次。</li>
 * </ul>
 *
 * <h3>状态归属</h3>
 * <p>护栏状态由本服务实例持有，调用方按需持有实例：</p>
 * <ul>
 *   <li>{@code AgentToolExecutor} 每会话新建一个 → 天然每会话隔离；</li>
 *   <li>{@code DiscoverToolsTool}（单例）持有一个 → 退化为全局去重；配合其
 *       {@code discoveredCapabilities} 全局去重，{@code DISCOVER_MISS} 每能力最多触发一次，
 *       护栏自然满足。</li>
 * </ul>
 */
public class GapClarificationService {

    private static final Logger log = LoggerFactory.getLogger(GapClarificationService.class);

    /** 默认每会话最大澄清次数 */
    public static final int DEFAULT_MAX_CLARIFICATIONS = 3;

    /** 数据缺口类型：这些类型永不向用户提问（仅记录，resolution 由调用方指定） */
    private static final Set<String> DATA_GAP_TYPES = Set.of("EMPTY_RESULT");

    private final int maxClarificationsPerSession;
    private final Set<String> askedCapabilities = ConcurrentHashMap.newKeySet();
    private int askCount = 0;

    public GapClarificationService() {
        this(DEFAULT_MAX_CLARIFICATIONS);
    }

    public GapClarificationService(int maxClarificationsPerSession) {
        this.maxClarificationsPerSession = Math.max(1, maxClarificationsPerSession);
    }

    /**
     * 决策：是否就本次缺口向用户澄清。
     *
     * @param gapType           缺口类型（UNKNOWN_TOOL / DISCOVER_MISS / EMPTY_RESULT）
     * @param capability        尝试的能力（工具名或 capabilityQuery）
     * @param fallbackResolution 护栏未触发提问时采用的处置方式（如 "logged" / "degraded"）
     * @return 决策结果（ask / prompt / resolution）
     */
    public GapDecision maybeClarify(String gapType, String capability, String fallbackResolution) {
        // 数据缺口：永不提问，沿用调用方指定的 resolution
        if (DATA_GAP_TYPES.contains(gapType)) {
            return GapDecision.noAsk(fallbackResolution);
        }
        // 已问过该能力 → 不再重复问
        if (capability != null && askedCapabilities.contains(capability)) {
            return GapDecision.noAsk(fallbackResolution);
        }
        // 达到每会话提问上限 → 不再问
        if (askCount >= maxClarificationsPerSession) {
            log.debug("[GapClarification] 已达每会话澄清上限({})，capability={}", maxClarificationsPerSession, capability);
            return GapDecision.noAsk(fallbackResolution);
        }
        // 通过护栏 → 提问
        if (capability != null) {
            askedCapabilities.add(capability);
        }
        askCount++;
        String prompt = buildPrompt(gapType, capability);
        return GapDecision.ask(prompt);
    }

    /** 是否已就该 capability 提问过 */
    public boolean hasAsked(String capability) {
        return capability != null && askedCapabilities.contains(capability);
    }

    /** 当前实例已提问次数 */
    public int getAskCount() {
        return askCount;
    }

    private String buildPrompt(String gapType, String capability) {
        if ("DISCOVER_MISS".equals(gapType)) {
            return "我在工具库里没找到「" + capability + "」相关的能力。你能换个说法描述需求，"
                    + "或直接把所需数据提供给我吗？";
        }
        // 默认（UNKNOWN_TOOL 等能力缺口）
        return "我暂时没有「" + capability + "」这个工具。你能直接提供相关数据，"
                + "或说明你希望我达成的目的吗？";
    }

    // ==================== 决策结果 ====================

    /** 澄清决策结果。 */
    public static class GapDecision {
        private final boolean ask;
        private final String prompt;
        private final String resolution; // "asked_user" 或调用方指定的 fallback

        private GapDecision(boolean ask, String prompt, String resolution) {
            this.ask = ask;
            this.prompt = prompt;
            this.resolution = resolution;
        }

        /** 触发提问：resolution = asked_user */
        public static GapDecision ask(String prompt) {
            return new GapDecision(true, prompt != null ? prompt : "", "asked_user");
        }

        /** 不提问：沿用调用方的 fallback resolution（logged / degraded） */
        public static GapDecision noAsk(String fallbackResolution) {
            return new GapDecision(false, "", fallbackResolution != null ? fallbackResolution : "logged");
        }

        public boolean isAsk() { return ask; }
        public String getPrompt() { return prompt; }
        public String getResolution() { return resolution; }
    }
}
