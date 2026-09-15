package com.example.smartassistant.consumer.controller;

import com.example.smartassistant.consumer.service.speech.SpeechRecognitionService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SpeechControllerTest {
    @Test void requiresIdentityBeforeReadingAudioOrCallingModel() throws Exception {
        var service = mock(SpeechRecognitionService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new SpeechController(service)).build();
        mvc.perform(get("/api/speech/capabilities")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/speech/transcriptions").contentType("audio/wav").content(new byte[10]))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/speech/transcriptions").header("X-User-Id", "0").contentType("audio/wav"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test void exposesCapabilityAndReturnsTranscriptOnly() throws Exception {
        var service = mock(SpeechRecognitionService.class);
        when(service.available()).thenReturn(true);
        when(service.transcribe(eq(7L), any())).thenReturn(new SpeechRecognitionService.Transcript(
                "查订单", "qwen3-asr-flash", 1.0, new SpeechRecognitionService.Usage(null, null, null)));
        var mvc = MockMvcBuilders.standaloneSetup(new SpeechController(service)).build();
        mvc.perform(get("/api/speech/capabilities").header("X-User-Id", "7"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true)).andExpect(jsonPath("$.maxSeconds").value(60));
        mvc.perform(post("/api/speech/transcriptions").header("X-User-Id", "7").contentType("audio/wav").content(new byte[10]))
                .andExpect(status().isOk()).andExpect(jsonPath("$.text").value("查订单"));
    }

    @Test void rejectsOversizeAndOtherFormatsBeforeModelInvocation() throws Exception {
        var service = mock(SpeechRecognitionService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new SpeechController(service)).build();
        mvc.perform(post("/api/speech/transcriptions").header("X-User-Id", "7").contentType("audio/wav")
                .content(new byte[SpeechRecognitionService.MAX_BYTES + 1])).andExpect(status().isPayloadTooLarge());
        mvc.perform(post("/api/speech/transcriptions").header("X-User-Id", "7").contentType("audio/webm")
                .content(new byte[10])).andExpect(status().isUnsupportedMediaType());
        verifyNoInteractions(service);
    }

    @Test void unconfiguredAndRecognitionFailuresReturnExplicitError() throws Exception {
        var service = mock(SpeechRecognitionService.class);
        when(service.transcribe(anyLong(), any())).thenThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "语音识别暂未启用"));
        var mvc = MockMvcBuilders.standaloneSetup(new SpeechController(service)).build();
        mvc.perform(post("/api/speech/transcriptions").header("X-User-Id", "7").contentType("audio/wav").content(new byte[10]))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.message").value("语音识别暂未启用"));
    }
}
