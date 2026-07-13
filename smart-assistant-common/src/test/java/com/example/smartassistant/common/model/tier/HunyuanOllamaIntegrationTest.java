/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.model.tier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaOptions;

import com.example.smartassistant.common.rag.pipeline.QueryComplexityClassifier;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 混元(Hunyuan)本地部署联调测试 —— 验证「Spring AI OllamaChatModel 路径」下混元可用，
 * 并通过项目自有的 {@link TieredModelRouter} canary 机制验证「混元接入路由层」闭环。
 *
 * <p><b>默认关闭</b>：需满足以下前置条件并以 {@code -Dhunyuan.integration=true} 启用，避免 CI 因无本地 Ollama/模型而失败：
 * <ul>
 *   <li>本地 Ollama 已启动（默认 {@code http://localhost:11434}）</li>
 *   <li>已拉取模型：{@code ollama pull alibayram/hunyuan}</li>
 * </ul>
 *
 * <p>运行：{@code mvn -pl smart-assistant-common test -Dtest=HunyuanOllamaIntegrationTest -Dhunyuan.integration=true}</p>
 */
@EnabledIfSystemProperty(named = "hunyuan.integration", matches = "true")
class HunyuanOllamaIntegrationTest {

    private static final String MODEL = "alibayram/hunyuan";
    private static final String BASE_URL = "http://localhost:11434";

    private OllamaChatModel buildOllamaModel() {
        OllamaApi ollamaApi = OllamaApi.builder()
                .baseUrl(BASE_URL)
                .build();
        OllamaOptions options = OllamaOptions.builder()
                .model(MODEL)
                .temperature(0.3)
                .build();
        return OllamaChatModel.builder()
                .ollamaApi(ollamaApi)
                .defaultOptions(options)
                .build();
    }

    @Test
    @DisplayName("基础对话：混元返回非空中文回复")
    void basicChatReturnsNonEmpty() {
        OllamaChatModel model = buildOllamaModel();
        ChatResponse resp = model.call(new Prompt("用一句话介绍腾讯混元大模型。"));
        assertNotNull(resp);
        String text = resp.getResult().getOutput().getText();
        assertNotNull(text);
        assertTrue(text.trim().length() > 0, "混元应返回非空回复");
        System.out.println("[Hunyuan IT] basic chat => " + text);
    }

    @Test
    @DisplayName("canary 路由闭环：TieredModelRouter 经 canary 把流量切到混元")
    void tieredRouterCanaryRoutesToHunyuan() {
        OllamaChatModel ollama = buildOllamaModel();
        OllamaOptions canaryOpt = OllamaOptions.builder()
                .model(MODEL)
                .temperature(0.3)
                .build();
        // DelegatingOptionsChatModel 与 TieredModelRouter 同包，可直接构造
        ChatModel canary = new DelegatingOptionsChatModel(ollama, canaryOpt);

        // 档位模型用 dummy 占位（灰度比例=1.0 时不会真正调用它们）
        ChatModel dummy = org.mockito.Mockito.mock(ChatModel.class);
        EnumMap<ModelTier, TierModelRegistry.TierModelEntry> m = new EnumMap<>(ModelTier.class);
        m.put(ModelTier.LIGHT, new TierModelRegistry.TierModelEntry(dummy, "qwen2.5:3b"));
        m.put(ModelTier.STANDARD, new TierModelRegistry.TierModelEntry(dummy, "deepseek-r1:7b"));
        m.put(ModelTier.HEAVY, new TierModelRegistry.TierModelEntry(dummy, "deepseek-chat"));
        TierModelRegistry registry = new TierModelRegistry(m);

        // canary-ratio=1.0 → 100% 流量走混元
        TieredModelRouter router = new TieredModelRouter(
                new QueryComplexityClassifier(), registry, Map.of(), true,
                1.0, MODEL, canary, null);

        TierSelection sel = router.call(new Prompt("北京今天适合户外运动吗？简单回答。"),
                "北京适合户外运动吗", null);
        assertEquals(MODEL, sel.servedModelName(), "canary 应将 servedModelName 指向混元");
        assertFalse(sel.degraded());
        String text = sel.response().getResult().getOutput().getText();
        assertTrue(text.trim().length() > 0);
        System.out.println("[Hunyuan IT] canary routing => " + text);
    }
}
