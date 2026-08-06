package com.example.smartassistant.common.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeedbackLogTest {

    @Test
    void recordsProgressAndBuildsContextFromRecentFailures() {
        FeedbackLog log = new FeedbackLog();
        log.recordProgress(1, "high");
        log.recordFailure(2, "重复调用", "不要重放", "切换工具");

        assertEquals(2, log.getFeedbackHistory().size());
        assertEquals(1, log.getRecentFailures(3).size());
        String context = log.buildPromptContext(3);
        assertTrue(context.contains("重复调用"));
        assertTrue(context.contains("切换工具"));
    }
}
