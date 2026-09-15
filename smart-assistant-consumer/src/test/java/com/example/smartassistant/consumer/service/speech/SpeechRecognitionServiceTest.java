package com.example.smartassistant.consumer.service.speech;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class SpeechRecognitionServiceTest {
    private final RestClient.Builder builder = RestClient.builder().baseUrl("https://asr.example/v1");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final SpeechRecognitionService service = new SpeechRecognitionService(true, "test-only-key", "qwen3-asr-flash", builder.build());

    static byte[] wav(int samples) {
        ByteBuffer b = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x46464952).putInt(b.capacity() - 8).putInt(0x45564157).putInt(0x20746d66).putInt(16)
                .putShort((short)1).putShort((short)1).putInt(16000).putInt(32000).putShort((short)2)
                .putShort((short)16).putInt(0x61746164).putInt(samples * 2);
        while (b.hasRemaining()) b.putShort((short)1200);
        return b.array();
    }

    @Test void validatesCanonicalDurationAndRejectsForgedHeadersSilenceAndOversizedAudio() {
        assertEquals(1.0, SpeechRecognitionService.validateWav(wav(16000)));
        assertEquals(60.0, SpeechRecognitionService.validateWav(wav(960000)));
        for (int offset : new int[]{0, 4, 8, 12, 16, 20, 22, 24, 28, 32, 34, 36, 40}) {
            byte[] altered = wav(16000); altered[offset] ^= 1;
            assertThrows(ResponseStatusException.class, () -> SpeechRecognitionService.validateWav(altered));
        }
        assertStatus(413, () -> SpeechRecognitionService.validateWav(wav(960001)));
        assertStatus(400, () -> SpeechRecognitionService.validateWav(wav(100)));
        assertStatus(400, () -> SpeechRecognitionService.validateWav(new byte[3]));
        byte[] silent = wav(16000); Arrays.fill(silent, 44, silent.length, (byte)0);
        assertStatus(422, () -> SpeechRecognitionService.validateWav(silent));
    }

    @Test void sendsAudioToDedicatedModelAndReturnsTextAndRealUsage() throws Exception {
        byte[] audio = wav(16000);
        server.expect(requestTo("https://asr.example/v1/chat/completions"))
                .andExpect(header("Authorization", "Bearer test-only-key"))
                .andExpect(req -> {
                    var json = new ObjectMapper().readTree(((MockClientHttpRequest)req).getBodyAsString());
                    assertEquals("qwen3-asr-flash", json.path("model").asText());
                    assertFalse(json.path("stream").asBoolean());
                    assertTrue(json.path("asr_options").path("enable_itn").asBoolean());
                    assertEquals(1, json.path("messages").size());
                    assertEquals("data:audio/wav;base64," + Base64.getEncoder().encodeToString(audio),
                            json.at("/messages/0/content/0/input_audio/data").asText());
                }).andRespond(withSuccess("""
                        {"choices":[{"finish_reason":"stop","message":{"content":" 查询订单 ORD-1001 "}}],
                         "usage":{"prompt_tokens":25,"completion_tokens":9,"total_tokens":34}}
                        """, MediaType.APPLICATION_JSON));
        var result = service.transcribe(7, audio);
        assertEquals("查询订单 ORD-1001", result.text());
        assertEquals(34L, result.usage().totalTokens());
        assertEquals(1.0, result.durationSeconds());
        server.verify();
    }

    @Test void unavailableNeverCallsProvider() {
        var off = new SpeechRecognitionService(false, "test-key", "model", builder.build());
        assertFalse(off.available()); assertStatus(503, () -> off.transcribe(1, wav(16000)));
        var missing = new SpeechRecognitionService(true, " ", "model", builder.build());
        assertFalse(missing.available()); assertStatus(503, () -> missing.transcribe(1, wav(16000)));
        server.verify();
    }

    @Test void missingUsageIsUnknownNotZeroAndDuplicateRequestsAreThrottled() {
        server.expect(requestTo("https://asr.example/v1/chat/completions")).andRespond(withSuccess(
                "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"你好\"}}]}", MediaType.APPLICATION_JSON));
        assertNull(service.transcribe(1, wav(16000)).usage().totalTokens());
        assertStatus(429, () -> service.transcribe(1, wav(16000))); server.verify();
    }

    @Test void emptyTruncatedMalformedAndOversizedResponsesFailClosed() {
        String[] responses = {"null", "{}", "not-json", "x".repeat(65537),
                "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"partial\"}}]}",
                "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":[]}}]}"};
        for (String body : responses) server.expect(requestTo("https://asr.example/v1/chat/completions"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        for (int i = 0; i < responses.length; i++) {
            final int user = i; assertStatus(502, () -> service.transcribe(user, wav(16000)));
        }
        server.verify();
    }

    @Test void emptySpeechIsNotASuccessfulTranscript() {
        server.expect(requestTo("https://asr.example/v1/chat/completions")).andRespond(withSuccess(
                "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"  \"}}]}", MediaType.APPLICATION_JSON));
        assertStatus(422, () -> service.transcribe(1, wav(16000))); server.verify();
    }

    @Test void providerErrorsAreRedactedAndCapacityIsReleasedAfterFailures() {
        for (int i = 0; i < 6; i++) server.expect(requestTo("https://asr.example/v1/chat/completions"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("private-provider-canary"));
        for (int i = 0; i < 6; i++) {
            final int user = i;
            var error = assertThrows(ResponseStatusException.class, () -> service.transcribe(user, wav(16000)));
            assertEquals(502, error.getStatusCode().value());
            assertFalse(error.getMessage().contains("private-provider-canary"));
        }
        server.verify();
    }

    @Test void ioTimeoutReturnsRetryableSafeError() {
        server.expect(requestTo("https://asr.example/v1/chat/completions"))
                .andRespond(withException(new IOException("private-network-canary")));
        assertStatus(504, () -> service.transcribe(1, wav(16000))); server.verify();
    }

    @Test void providerRateLimitStaysRateLimited() {
        server.expect(requestTo("https://asr.example/v1/chat/completions"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        assertStatus(429, () -> service.transcribe(1, wav(16000))); server.verify();
    }

    private static void assertStatus(int expected, Runnable action) {
        assertEquals(expected, assertThrows(ResponseStatusException.class, action::run).getStatusCode().value());
    }
}
