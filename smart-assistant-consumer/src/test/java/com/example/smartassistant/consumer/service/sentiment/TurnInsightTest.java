package com.example.smartassistant.consumer.service.sentiment;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TurnInsightTest {
    @Test void onlyNeutralOrPositiveObservationsAllowAnswerCache() {
        for (int level = 1; level <= 5; level++) {
            var insight = TurnInsight.analyzed(new SentimentAnalysisService.SentimentResult(
                    level, "情绪", "策略", false, false, 95), 1);
            assertEquals(level >= 3, insight.bypassAnswerCache());
            assertEquals(level >= 4 ? "ELEVATED" : "NORMAL", insight.suggestedPriority());
        }
        assertTrue(TurnInsight.unknown("TIMEOUT", 750).bypassAnswerCache());
    }

    @Test void adaptationIsIdempotentAndNeverClaimsTransfer() {
        var insight = TurnInsight.analyzed(new SentimentAnalysisService.SentimentResult(
                5, "愤怒", "共情", false, false, 95), 1);
        String reply = insight.adaptReply("已查到订单。");
        assertEquals(reply, insight.adaptReply(reply));
        assertEquals("抱歉给您带来不便。已查到订单。", reply);
        assertEquals("抱歉给您带来不便。售价1999元，库存充足。",
                insight.adaptReply("您好，售价1999元，库存充足。"));
        assertFalse(reply.contains("转接"));
        assertNull(insight.adaptReply(null));
    }
}
