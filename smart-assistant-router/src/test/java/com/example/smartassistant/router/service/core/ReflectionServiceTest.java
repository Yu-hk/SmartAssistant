package com.example.smartassistant.router.service.core;

import com.example.smartassistant.common.rag.advisor.AiChatService;
import com.example.smartassistant.router.model.ReflectionResult;
import com.example.smartassistant.router.model.SubTaskResult;
import com.example.smartassistant.router.service.agent.AgentCallerService;
import com.example.smartassistant.router.service.agent.AgentDiscoveryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReflectionServiceTest {

    @Mock AgentCallerService agentCallerService;
    @Mock AgentDiscoveryService discoveryService;
    @Mock ChatClient.Builder chatClientBuilder;
    @Mock ChatClient chatClient;
    @Mock ChatClient.ChatClientRequestSpec requestSpec;
    @Mock ChatClient.CallResponseSpec responseSpec;
    @Mock AiChatService aiChatService;

    ReflectionService service;

    @BeforeEach
    void setUp() {
        when(aiChatService.applyAdvisors(chatClientBuilder)).thenReturn(chatClientBuilder);
        when(chatClientBuilder.build()).thenReturn(chatClient);
        service = spy(new ReflectionService(agentCallerService, discoveryService, null,
                chatClientBuilder, aiChatService));
        ReflectionTestUtils.setField(service, "reflectionEnabled", true);
        ReflectionTestUtils.setField(service, "maxRetry", 2);
        ReflectionTestUtils.setField(service, "criteriaFailOpen", false);
    }

    @Test
    void configuredRetryCountIsHonored() {
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn("第一次重试结果仍然不合格")
                .thenReturn("第二次重试结果已经满足要求");
        doReturn(new ReflectionResult(false, 0.4, "low"),
                new ReflectionResult(true, 0.8, "pass"))
                .when(service).evaluate(anyString(), anyString(), anyString(), anyString(), anyLong());

        String result = service.retry("问题", "原始回答", "order", "order_query", 7L, "r1");

        assertEquals("第二次重试结果已经满足要求", result);
        verify(chatClient, times(2)).prompt();
    }

    @Test
    void criteriaJudgeFailureDoesNotPassSilently() {
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn("");

        assertEquals(SubTaskResult.ErrorType.RETRYABLE_FAILED,
                service.checkCriteria("回答", "必须包含订单状态"));
    }

    @Test
    void knowledgeBaseMissAndEncodedPayloadAreTreatedAsErrors() {
        Double knowledgeMiss = ReflectionTestUtils.invokeMethod(
                service, "checkErrorMarkers", "抱歉，数据库中未找到相关的信息，请更换关键词。");
        Double longKnowledgeMiss = ReflectionTestUtils.invokeMethod(
                service, "checkErrorMarkers", "抱歉，数据库中未找到与「我想买一款适合办公的笔记本电脑，请告诉我选购重点指标」相关的信息。");
        Double encodedPayload = ReflectionTestUtils.invokeMethod(
                service, "checkErrorMarkers", "未找到与 %E6%9F%A5%E8%AF%A2 相关的信息。");
        Double infrastructureFailure = ReflectionTestUtils.invokeMethod(
                service, "checkErrorMarkers", "检测到基础设施错误: 500");

        assertEquals(0.0, knowledgeMiss);
        assertEquals(0.0, longKnowledgeMiss);
        assertEquals(0.0, encodedPayload);
        assertEquals(0.0, infrastructureFailure);
    }

    @Test
    void errorMarkerCannotPassByRepeatingQuestionKeywords() {
        ReflectionTestUtils.setField(service, "threshold", 0.1);

        ReflectionResult result = service.evaluate(
                "我想买一款适合办公的笔记本电脑，请告诉我选购重点指标",
                "抱歉，数据库中未找到与「我想买一款适合办公的笔记本电脑，请告诉我选购重点指标」相关的信息。",
                "product_agent", "商品,笔记本电脑", 7L);

        assertFalse(result.isAcceptable());
    }

    @Test
    void domainAgentFailureFallsBackToGeneral() {
        String fallback = ReflectionTestUtils.invokeMethod(
                service, "selectFallbackAgent", "order_agent", "商品,退货,退款");

        assertEquals("general", fallback);
    }
}
