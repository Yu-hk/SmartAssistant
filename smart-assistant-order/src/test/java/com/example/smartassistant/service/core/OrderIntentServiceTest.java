package com.example.smartassistant.service.core;

import com.example.smartassistant.common.prompt.PromptManager;
import com.example.smartassistant.common.rag.advisor.AiChatService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OrderIntentServiceTest {

    @Test
    void refundConditionsAreClassifiedAsPolicyWithoutCallingLlm() {
        AiChatService aiChatService = mock(AiChatService.class);
        OrderIntentService service = new OrderIntentService(
                aiChatService, mock(ChatModel.class), mock(PromptManager.class));

        assertEquals(OrderIntentService.IntentType.REFUND_POLICY,
                service.detect("商品退货退款需要满足哪些条件？"));
        assertEquals(OrderIntentService.IntentType.REFUND_POLICY,
                service.detect("退款多久到账？"));
        verifyNoInteractions(aiChatService);
    }

    @Test
    void concreteRefundActionStillUsesOperationalIntent() {
        AiChatService aiChatService = mock(AiChatService.class);
        ChatModel model = mock(ChatModel.class);
        PromptManager promptManager = mock(PromptManager.class);
        when(promptManager.orderIntentClassifier()).thenReturn("classify");
        when(aiChatService.entity(any(), anyString(), anyString(), any()))
                .thenReturn(new OrderIntentService.IntentResult(OrderIntentService.IntentType.REFUND));
        OrderIntentService service = new OrderIntentService(aiChatService, model, promptManager);

        assertEquals(OrderIntentService.IntentType.REFUND, service.detect("帮我退款"));
    }
}
