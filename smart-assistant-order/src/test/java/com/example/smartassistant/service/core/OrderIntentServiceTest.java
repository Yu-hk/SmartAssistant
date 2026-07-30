package com.example.smartassistant.service.core;

import com.example.smartassistant.common.prompt.PromptManager;
import com.example.smartassistant.common.rag.advisor.AiChatService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import static com.example.smartassistant.service.core.OrderIntentService.IntentType.POLICY_QA;
import static com.example.smartassistant.service.core.OrderIntentService.IntentType.QUERY_ORDER;
import static com.example.smartassistant.service.core.OrderIntentService.IntentType.REFUND;
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

    @Test
    void routesGenericRefundPolicyToKnowledgeBaseWithoutCallingLlm() {
        AiChatService aiChatService = mock(AiChatService.class);
        ChatModel lightModel = mock(ChatModel.class);
        PromptManager promptManager = mock(PromptManager.class);
        OrderIntentService service = new OrderIntentService(aiChatService, lightModel, promptManager);

        assertEquals(POLICY_QA,
                service.detect("非质量原因退货的运费由谁承担？质量问题时客服要做什么？"));
        verifyNoInteractions(aiChatService, lightModel, promptManager);
    }

    @Test
    void concreteOrderReferenceWinsOverPolicyWording() {
        AiChatService aiChatService = mock(AiChatService.class);
        ChatModel lightModel = mock(ChatModel.class);
        PromptManager promptManager = mock(PromptManager.class);
        OrderIntentService service = new OrderIntentService(aiChatService, lightModel, promptManager);

        assertEquals(REFUND, service.detect("订单 ORD-E2E-0001 是否满足退款条件？"));
        verifyNoInteractions(aiChatService, lightModel, promptManager);
    }
}
