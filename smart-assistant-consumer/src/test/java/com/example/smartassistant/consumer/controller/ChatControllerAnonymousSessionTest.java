package com.example.smartassistant.consumer.controller;

import com.example.smartassistant.consumer.service.cache.AnswerCacheService;
import com.example.smartassistant.consumer.service.cache.AnswerPersonalizationService;
import com.example.smartassistant.consumer.service.core.ChatConsumerService;
import com.example.smartassistant.consumer.service.session.ConversationDocumentService;
import com.example.smartassistant.consumer.service.session.ConversationValueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ChatControllerAnonymousSessionTest {

    @Mock
    private ChatConsumerService chatService;
    @Mock
    private AnswerCacheService answerCacheService;
    @Mock
    private AnswerPersonalizationService personalizationCacheService;
    @Mock
    private ConversationValueService conversationValueService;
    @Mock
    private ConversationDocumentService conversationDocumentService;
    @Mock
    private StringRedisTemplate redisTemplate;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ChatController controller = new ChatController(
                chatService,
                answerCacheService,
                personalizationCacheService,
                conversationValueService,
                conversationDocumentService,
                redisTemplate);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void anonymousSessionShouldReturnResponseWithoutParsingAnonymousAsNumber() throws Exception {
        when(chatService.calculateWithSession(
                org.mockito.ArgumentMatchers.eq("anonymous"),
                anyString(),
                org.mockito.ArgumentMatchers.eq("anonymous-session"),
                anyString()))
                .thenReturn(Map.of(
                        "result", "正在为您转接人工客服，请稍候。",
                        "agentName", "human_service",
                        "fromCache", false,
                        "toolInvoked", false));

        MvcResult result = mockMvc.perform(post("/api/math/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"我要投诉你们","sessionId":"anonymous-session"}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.agentName").value("human_service"));

        verifyNoInteractions(conversationValueService, conversationDocumentService, redisTemplate);
    }

    @Test
    void generatedSessionIdFromServiceIsReturnedWhenClientOmitsSession() throws Exception {
        when(chatService.calculateWithSession(
                org.mockito.ArgumentMatchers.eq("anonymous"),
                org.mockito.ArgumentMatchers.eq("hello"),
                org.mockito.ArgumentMatchers.isNull(),
                anyString()))
                .thenReturn(Map.of(
                        "result", "hi",
                        "agentName", "general_service",
                        "sessionId", "generated-session",
                        "fromCache", false,
                        "toolInvoked", false));

        MvcResult result = mockMvc.perform(post("/api/math/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"hello"}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sessionId").value("generated-session"));
    }
}
