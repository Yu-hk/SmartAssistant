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

    @Test
    void lifecycleExplanationIsReadOnlyGuidanceWithoutCallingLlm() {
        AiChatService aiChatService = mock(AiChatService.class);
        OrderIntentService service = new OrderIntentService(
                aiChatService, mock(ChatModel.class), mock(PromptManager.class));

        assertEquals(OrderIntentService.IntentType.ORDER_GUIDANCE,
                service.detect("请说明下单后如何查询订单、取消订单和申请售后"));
        assertEquals(OrderIntentService.IntentType.ORDER_GUIDANCE,
                service.detect("请说明如何查询订单、取消订单和申请退款"));
        assertEquals(OrderIntentService.IntentType.REFUND_POLICY,
                service.detect("退款多久到账？"));
        verifyNoInteractions(aiChatService);
    }

    @Test
    void preOrderChecklistIsReadOnlyGuidanceWithoutCallingLlm() {
        AiChatService aiChatService = mock(AiChatService.class);
        OrderIntentService service = new OrderIntentService(
                aiChatService, mock(ChatModel.class), mock(PromptManager.class));

        assertEquals(OrderIntentService.IntentType.ORDER_PREPARATION_GUIDANCE,
                service.detect("说明创建订单前需要确认哪些信息，但不要执行下单"));
        assertEquals(OrderIntentService.IntentType.ORDER_PREPARATION_GUIDANCE,
                service.detect("说明创建订单前需确认的信息（不执行下单、不创建测试订单）"));
        verifyNoInteractions(aiChatService);
    }

    @Test
    void concreteOrderIdKeepsOperationalClassification() {
        AiChatService aiChatService = mock(AiChatService.class);
        ChatModel model = mock(ChatModel.class);
        PromptManager promptManager = mock(PromptManager.class);
        when(promptManager.orderIntentClassifier()).thenReturn("classify");
        when(aiChatService.entity(any(), anyString(), anyString(), any()))
                .thenReturn(new OrderIntentService.IntentResult(OrderIntentService.IntentType.CANCEL));
        OrderIntentService service = new OrderIntentService(aiChatService, model, promptManager);

        assertEquals(OrderIntentService.IntentType.CANCEL,
                service.detect("怎么取消订单 ORD-20260818-1"));
    }

    @Test
    void fulfillmentIntentsCanBeReturnedByStructuredClassifier() {
        AiChatService aiChatService = mock(AiChatService.class);
        ChatModel model = mock(ChatModel.class);
        PromptManager promptManager = mock(PromptManager.class);
        when(promptManager.orderIntentClassifier()).thenReturn("classify");
        when(aiChatService.entity(any(), anyString(), anyString(), any()))
                .thenReturn(new OrderIntentService.IntentResult(OrderIntentService.IntentType.PAY))
                .thenReturn(new OrderIntentService.IntentResult(OrderIntentService.IntentType.SHIP))
                .thenReturn(new OrderIntentService.IntentResult(OrderIntentService.IntentType.TRACK_LOGISTICS))
                .thenReturn(new OrderIntentService.IntentResult(OrderIntentService.IntentType.CONFIRM_DELIVERY));
        OrderIntentService service = new OrderIntentService(aiChatService, model, promptManager);

        assertEquals(OrderIntentService.IntentType.PAY,
                service.detect("确认支付订单 ORD-1"));
        assertEquals(OrderIntentService.IntentType.SHIP,
                service.detect("发货订单 ORD-1"));
        assertEquals(OrderIntentService.IntentType.TRACK_LOGISTICS,
                service.detect("查询订单 ORD-1 的物流"));
        assertEquals(OrderIntentService.IntentType.CONFIRM_DELIVERY,
                service.detect("确认收货订单 ORD-1"));
    }
}
