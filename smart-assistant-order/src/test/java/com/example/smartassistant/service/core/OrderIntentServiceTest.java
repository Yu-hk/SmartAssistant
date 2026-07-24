package com.example.smartassistant.service.core;

import com.example.smartassistant.common.prompt.PromptManager;
import com.example.smartassistant.common.rag.advisor.AiChatService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import static com.example.smartassistant.service.core.OrderIntentService.IntentType.QUERY_ORDER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class OrderIntentServiceTest {

    @Test
    void recognizesLatestOrderLogisticsAsQueryWithoutCallingLlm() {
        AiChatService aiChatService = mock(AiChatService.class);
        ChatModel lightModel = mock(ChatModel.class);
        PromptManager promptManager = mock(PromptManager.class);
        OrderIntentService service = new OrderIntentService(aiChatService, lightModel, promptManager);

        assertEquals(QUERY_ORDER, service.detect("帮我查询最近一笔订单的物流状态"));
        verifyNoInteractions(aiChatService, lightModel, promptManager);
    }
}
