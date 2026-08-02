package com.example.smartassistant.router.service.core;

import com.example.smartassistant.common.rag.advisor.AiChatService;
import com.example.smartassistant.router.model.ReflectionResult;
import com.example.smartassistant.router.service.agent.AgentCallerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReflectionServiceTest {

    private ReflectionService service;

    @BeforeEach
    void setUp() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        AiChatService aiChatService = mock(AiChatService.class);
        when(aiChatService.applyAdvisors(builder)).thenReturn(builder);
        when(builder.build()).thenReturn(mock(ChatClient.class));
        service = new ReflectionService(
                mock(AgentCallerService.class), null, null, builder, aiChatService);
        ReflectionTestUtils.setField(service, "reflectionEnabled", true);
        ReflectionTestUtils.setField(service, "threshold", 0.60);
    }

    @Test
    void missingCriticalRequirements_shouldNotBeMaskedByWeightedScore() {
        String question = "审计订单 ORD-2024001，列出当前状态、支付方式、物流公司、"
                + "最新两条物流轨迹，并判断是否满足取消条件。核验标记 NC-TRACE-001";
        String incomplete = "【订单信息】订单 ORD-2024001，状态：已发货，"
                + "支付方式：微信支付，物流公司：顺丰速运，最新轨迹：运输中。";

        ReflectionResult result = service.evaluate(
                question, incomplete, "order_agent", "订单查询", 1L);

        assertFalse(result.isAcceptable());
        assertTrue(result.getReason().contains("取消条件判断"));
        assertTrue(result.getReason().contains("核验标记"));
    }

    @Test
    void completeCompositeAnswer_shouldPassCoverageGate() {
        String question = "审计订单 ORD-2024001，列出当前状态、支付方式、物流公司、"
                + "最新两条物流轨迹，并判断是否满足取消条件。核验标记 NC-TRACE-001";
        String complete = """
                【订单信息】订单 ORD-2024001
                当前状态：已发货
                支付方式：微信支付
                物流公司：顺丰速运
                最新2条轨迹：北京运输中；上海已发出
                【取消条件判断】当前不满足直接取消条件。
                【核验标记】NC-TRACE-001
                """;

        ReflectionResult result = service.evaluate(
                question, complete, "order_agent", "订单查询", 1L);

        assertTrue(result.isAcceptable(), result.getReason());
        assertFalse(result.getReason().contains("关键要求未覆盖"));
    }

    @Test
    void authorizationDenial_shouldPassWithoutFallbackRetry() {
        String denial = "⚠️ 未找到该订单或您无权查看，请核对订单号后重试。";

        ReflectionResult result = service.evaluate(
                "查询订单 ORD-LOAD000001003 的状态和物流",
                denial, "order_agent", "订单查询", 2L);

        assertTrue(result.isAcceptable(), result.getReason());
        assertEquals(1.0, result.getScore());
        assertTrue(result.getReason().contains("安全拒答"));
    }
}
