package com.example.smartassistant.router.service.core;

import com.example.smartassistant.common.gateway.llm.AgentLLMGateway;
import com.example.smartassistant.common.gateway.llm.LLMCallConfig;
import com.example.smartassistant.common.gateway.llm.LLMCallResult;
import com.example.smartassistant.common.model.tier.ModelTier;
import com.example.smartassistant.common.model.tier.TierModelRegistry;
import com.example.smartassistant.common.rag.advisor.AiChatService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelRoutingServiceTest {

    @Test
    void usesChatTierForShortQuestionAndReasonerTierForLongQuestion() {
        assertEquals(ModelTier.LIGHT,
                ModelRoutingService.selectIntentTier("查一下热门商品", 20));
        assertEquals(ModelTier.HEAVY,
                ModelRoutingService.selectIntentTier("请先查询热门商品，按价格和库存比较，然后查询我的订单并说明后续步骤", 20));
    }

    @Test
    void countsChineseCharactersByUnicodeCodePoint() {
        assertEquals(ModelTier.LIGHT,
                ModelRoutingService.selectIntentTier("天气🌤", 4));
        assertEquals(ModelTier.HEAVY,
                ModelRoutingService.selectIntentTier("天气🌤吗", 4));
    }

    @Test
    void selectsOneModelAndDoesNotSeriallyFallBackAfterTimeout() {
        AgentLLMGateway gateway = mock(AgentLLMGateway.class);
        when(gateway.call(any(), eq("deepseek-v4-pro"), any(LLMCallConfig.class)))
                .thenReturn(LLMCallResult.failure("timeout after 50000ms", 50_000));

        ChatModel defaultModel = mock(ChatModel.class);
        ChatModel flashModel = mock(ChatModel.class);
        ChatModel proModel = mock(ChatModel.class);
        TierModelRegistry registry = new TierModelRegistry(Map.of(
                ModelTier.LIGHT, new TierModelRegistry.TierModelEntry(flashModel, "deepseek-v4-flash"),
                ModelTier.HEAVY, new TierModelRegistry.TierModelEntry(proModel, "deepseek-v4-pro")));
        @SuppressWarnings("unchecked")
        ObjectProvider<TierModelRegistry> registryProvider = mock(ObjectProvider.class);
        when(registryProvider.getIfAvailable()).thenReturn(registry);

        ModelRoutingService service = new ModelRoutingService(
                ChatClient.builder(defaultModel), gateway,
                new AiChatService(null, null, null, null), registryProvider,
                mock(DeepSeekPlanningClient.class));
        assertThrows(RuntimeException.class, () -> service.callForIntent(
                "system", "user", "长".repeat(160)));
        verify(gateway).call(any(), eq("deepseek-v4-pro"), any(LLMCallConfig.class));
        verify(gateway, never()).call(any(), eq("deepseek-v4-flash"), any(LLMCallConfig.class));
    }
}
