package com.example.smartassistant.service.core;

import com.example.smartassistant.common.prompt.PromptManager;
import com.example.smartassistant.common.rag.advisor.AiChatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class OrderIntentServiceTest {

    private AiChatService aiChatService;
    private ChatModel lightModel;
    private PromptManager promptManager;
    private OrderIntentService service;

    @BeforeEach
    void setUp() {
        aiChatService = mock(AiChatService.class);
        lightModel = mock(ChatModel.class);
        promptManager = mock(PromptManager.class);
        service = new OrderIntentService(aiChatService, lightModel, promptManager);
    }

    @Test
    void bareOrderIdentifierIsDetectedWithoutCallingLlm() {
        var intent = service.detect("ORD-LOAD000001001");

        assertEquals(OrderIntentService.IntentType.QUERY_ORDER, intent);
        verifyNoInteractions(aiChatService, lightModel, promptManager);
    }

    @Test
    void lowerCaseOrderIdentifierInMessageIsDetectedWithoutCallingLlm() {
        var intent = service.detect("please check ord-load000001001");

        assertEquals(OrderIntentService.IntentType.QUERY_ORDER, intent);
        verifyNoInteractions(aiChatService, lightModel, promptManager);
    }
}
