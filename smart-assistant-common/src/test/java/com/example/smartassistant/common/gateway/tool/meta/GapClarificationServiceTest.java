package com.example.smartassistant.common.gateway.tool.meta;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link GapClarificationService} 单元测试（T2f 三期）。
 * <p>覆盖：数据缺口永不问、能力缺口首次问、同 capability 去重、每会话上限。</p>
 */
class GapClarificationServiceTest {

    @Test
    @DisplayName("数据缺口(EMPTY_RESULT)永不提问，沿用 fallback resolution")
    void dataGapNeverAsks() {
        var svc = new GapClarificationService();
        var d = svc.maybeClarify("EMPTY_RESULT", "order-123", "logged");
        assertFalse(d.isAsk());
        assertEquals("logged", d.getResolution());
        assertTrue(d.getPrompt().isEmpty());
    }

    @Test
    @DisplayName("能力缺口(UNKNOWN_TOOL)首次提问，话术含工具名，resolution=asked_user")
    void capabilityGapAsksFirstTime() {
        var svc = new GapClarificationService();
        var d = svc.maybeClarify("UNKNOWN_TOOL", "fooTool", "logged");
        assertTrue(d.isAsk());
        assertEquals("asked_user", d.getResolution());
        assertTrue(d.getPrompt().contains("fooTool"));
    }

    @Test
    @DisplayName("能力缺口(DISCOVER_MISS)首次提问，话术含能力名")
    void discoverMissAsksFirstTime() {
        var svc = new GapClarificationService();
        var d = svc.maybeClarify("DISCOVER_MISS", "sql-query", "degraded");
        assertTrue(d.isAsk());
        assertEquals("asked_user", d.getResolution());
        assertTrue(d.getPrompt().contains("sql-query"));
    }

    @Test
    @DisplayName("同一 capability 不重复提问（沿用 fallback resolution）")
    void noDuplicateAskSameCapability() {
        var svc = new GapClarificationService();
        assertTrue(svc.maybeClarify("UNKNOWN_TOOL", "fooTool", "logged").isAsk());
        var second = svc.maybeClarify("UNKNOWN_TOOL", "fooTool", "logged");
        assertFalse(second.isAsk());
        assertEquals("logged", second.getResolution());
        assertTrue(second.getPrompt().isEmpty());
    }

    @Test
    @DisplayName("达到每会话上限后不再提问")
    void sessionLimitEnforced() {
        var svc = new GapClarificationService(2);
        assertTrue(svc.maybeClarify("DISCOVER_MISS", "a", "logged").isAsk());
        assertTrue(svc.maybeClarify("DISCOVER_MISS", "b", "logged").isAsk());
        var third = svc.maybeClarify("DISCOVER_MISS", "c", "logged");
        assertFalse(third.isAsk());
        assertEquals(2, svc.getAskCount());
    }
}
