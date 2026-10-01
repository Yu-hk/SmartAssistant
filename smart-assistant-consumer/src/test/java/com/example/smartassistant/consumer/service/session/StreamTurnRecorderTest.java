package com.example.smartassistant.consumer.service.session;

import com.example.smartassistant.consumer.service.infrastructure.RoutingCallLogService;
import com.example.smartassistant.consumer.service.infrastructure.TokenUsageExtractor;
import com.example.smartassistant.consumer.service.recommendation.UserProfileService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

class StreamTurnRecorderTest {
    @Test void sourceIsPersistedWithoutCopyingOriginalGenerationCost() {
        for (boolean cached : new boolean[]{true, false}) {
            recorder.record(null, "42", "s", cached ? "cache" : "live", "商品价格", "product", "回复",
                    System.currentTimeMillis(), "SUCCESS", new TokenUsageExtractor.TokenUsage(0L, 0L, 0L), null, cached);
            verify(logs).saveLog(eq(42L), eq("s"), eq(cached ? "cache" : "live"), eq("商品价格"),
                    eq("product"), eq(cached ? "STREAM_CACHE" : "STREAM_LIVE"), anyLong(),
                    eq("SUCCESS"), eq("回复"), eq(0L), eq(0L), eq(0L), eq("商品价格"), isNull());
        }
    }
    private final RoutingCallLogService logs = mock(RoutingCallLogService.class);
    private final UserProfileService profiles = mock(UserProfileService.class);
    private final StreamTurnRecorder recorder = new StreamTurnRecorder(logs);

    @Test
    void successfulKnownUserCommitsProfileAfterSavingTurn() {
        recorder.record(profiles, "42", "session-1", "request-1", "你好", "product",
                "已回复", System.currentTimeMillis(), "SUCCESS",
                TokenUsageExtractor.TokenUsage.unknown(), null);

        var order = inOrder(logs, profiles);
        order.verify(logs).saveLog(eq(42L), eq("session-1"), eq("request-1"), eq("你好"),
                eq("product"), eq("STREAM_ROUTER_SERVICE"), anyLong(), eq("SUCCESS"), eq("已回复"),
                isNull(), isNull(), isNull(), eq("你好"), isNull());
        order.verify(profiles).commitAfterSuccessfulTurn(42L, "request-1");
    }

    @Test
    void failedOrAnonymousTurnNeverCommitsProfile() {
        recorder.record(profiles, "42", "session-1", "request-1", "你好", null,
                null, System.currentTimeMillis(), "FAILED",
                TokenUsageExtractor.TokenUsage.unknown(), null);
        recorder.record(profiles, "anonymous", "session-2", "request-2", "你好", null,
                null, System.currentTimeMillis(), "SUCCESS",
                TokenUsageExtractor.TokenUsage.unknown(), null);

        verifyNoInteractions(profiles);
        verify(logs).saveLog(isNull(), eq("session-2"), eq("request-2"), eq("你好"),
                eq("unknown"), eq("STREAM_ROUTER_SERVICE"), anyLong(), eq("SUCCESS"), isNull(),
                isNull(), isNull(), isNull(), eq("你好"), isNull());
    }
}
