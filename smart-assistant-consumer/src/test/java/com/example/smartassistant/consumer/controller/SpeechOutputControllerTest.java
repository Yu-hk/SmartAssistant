package com.example.smartassistant.consumer.controller;
import com.example.smartassistant.consumer.service.speech.SpeechSynthesisService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class SpeechOutputControllerTest {
    @Test void requiresTrustedIdentity() throws Exception {
        var service=mock(SpeechSynthesisService.class);var mvc=MockMvcBuilders.standaloneSetup(new SpeechOutputController(service)).build();
        mvc.perform(get("/api/speech/output-capabilities")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/speech/syntheses").contentType("application/json").content("{\"requestId\":\"request\"}")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
    @Test void returnsPrivateAudioAndCharacterUsageNotTokens() throws Exception {
        var service=mock(SpeechSynthesisService.class);when(service.synthesize(7,"request")).thenReturn(new SpeechSynthesisService.Audio(new byte[]{'I','D','3',1},12L));
        var mvc=MockMvcBuilders.standaloneSetup(new SpeechOutputController(service)).build();
        mvc.perform(post("/api/speech/syntheses").header("X-User-Id",7).contentType("application/json").content("{\"requestId\":\"request\"}"))
                .andExpect(status().isOk()).andExpect(content().contentType("audio/mpeg"))
                .andExpect(content().bytes(new byte[]{'I','D','3',1}))
                .andExpect(header().string("Cache-Control","private, no-store")).andExpect(header().string("X-Speech-Characters","12"));
    }
}
