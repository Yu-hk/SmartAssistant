package com.example.smartassistant.consumer.controller;

import com.example.smartassistant.common.sentiment.SentimentAnalysisService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InsightControllerTest {

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new InsightController(new SentimentAnalysisService()))
            .build();

    @Test
    void shouldReturnFrontendEmotionContractForPositiveText() throws Exception {
        mockMvc.perform(post("/api/insight/emotion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text":"谢谢，服务很棒","triggerTopic":"售后咨询"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("正面"))
                .andExpect(jsonPath("$.score").value(0))
                .andExpect(jsonPath("$.confidence").value(95));
    }

    @Test
    void shouldReturnMaximumRiskScoreForAngryText() throws Exception {
        mockMvc.perform(post("/api/insight/emotion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text":"我要投诉你们"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("愤怒"))
                .andExpect(jsonPath("$.score").value(100))
                .andExpect(jsonPath("$.confidence").value(95));
    }

    @Test
    void shouldReturnZeroConfidenceForBlankText() throws Exception {
        mockMvc.perform(post("/api/insight/emotion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text":""}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("中性"))
                .andExpect(jsonPath("$.score").value(25))
                .andExpect(jsonPath("$.confidence").value(0));
    }
}
