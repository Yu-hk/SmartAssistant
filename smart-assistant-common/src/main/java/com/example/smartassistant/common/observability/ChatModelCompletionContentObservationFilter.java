/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.observability;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.content.Content;
import org.springframework.ai.observation.ObservabilityHelper;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * ⭐ ChatModel 调用内容观测过滤器 — 将 Spring AI ChatModel 的 prompt/completion
 * 内容写入 OpenTelemetry span 属性，供 Langfuse 展示。
 * <p>
 * 必须注册为 Spring {@link @Component}，否则 Langfuse 中显示 null 输入/输出。
 * {@code application.yml} 中需启用：
 * <pre>
 * spring.ai.chat.observations.log-prompt: true
 * spring.ai.chat.observations.log-completion: true
 * </pre>
 * </p>
 */
@Component
public class ChatModelCompletionContentObservationFilter implements ObservationFilter {

    @Override
    public Observation.Context map(Observation.Context context) {
        if (!(context instanceof ChatModelObservationContext chatCtx)) {
            return context;
        }

        var prompts = processPrompts(chatCtx);
        var completions = processCompletion(chatCtx);

        chatCtx.addHighCardinalityKeyValue(new KeyValue() {
            @Override
            public String getKey() {
                return "gen_ai.prompt";
            }

            @Override
            public String getValue() {
                return ObservabilityHelper.concatenateStrings(prompts);
            }
        });

        chatCtx.addHighCardinalityKeyValue(new KeyValue() {
            @Override
            public String getKey() {
                return "gen_ai.completion";
            }

            @Override
            public String getValue() {
                return ObservabilityHelper.concatenateStrings(completions);
            }
        });

        return chatCtx;
    }

    private List<String> processPrompts(ChatModelObservationContext ctx) {
        if (ctx.getRequest() == null || CollectionUtils.isEmpty(ctx.getRequest().getInstructions())) {
            return List.of();
        }
        return ctx.getRequest().getInstructions().stream()
                .map(Content::getText)
                .toList();
    }

    private List<String> processCompletion(ChatModelObservationContext ctx) {
        if (ctx.getResponse() == null || ctx.getResponse().getResults() == null
                || CollectionUtils.isEmpty(ctx.getResponse().getResults())) {
            return List.of();
        }
        return ctx.getResponse().getResults().stream()
                .filter(g -> g.getOutput() != null && StringUtils.hasText(g.getOutput().getText()))
                .map(g -> g.getOutput().getText())
                .toList();
    }
}
