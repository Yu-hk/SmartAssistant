/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.config;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 解决多 ChatModel Bean 的自动注入歧义。
 * <p>
 * 当容器中存在多个 {@link ChatModel} 实现（如 lightChatModel / ollamaChatModel /
 * deepSeekChatModel）时，Spring AI 的 ChatClient 等“按类型注入 ChatModel”会因候选过多而
 * 报 {@code NoUniqueBeanDefinitionException}。本配置在 BeanFactory 后置处理阶段，将“默认
 * ChatModel”标记为 primary，从而让无限定符的按类型注入稳定命中该 Bean。
 * </p>
 * <p>
 * 默认优先级：先选 {@code lightChatModel}（轻量/廉价模型，用于意图识别、路由等辅助任务）；
 * 若服务未定义 lightChatModel，则回退到 {@code deepSeekChatModel}；再否则取第一个 ChatModel。
 * </p>
 * <p>
 * 注意：{@code @Primary} / {@code setPrimary(true)} 只在“存在多个候选且无 @Qualifier 限定”时
 * 才参与决策；显式的 {@code @Qualifier("deepSeekChatModel")} 等按名称注入始终优先于 primary，
 * 因此本方案不会破坏各服务中对 deepSeek / ollama 的显式注入。
 * </p>
 */
@Configuration
public class ChatModelCandidateConfig {

    private static final String PREFERRED = "lightChatModel";
    private static final String FALLBACK = "deepSeekChatModel";

    @Bean
    public static org.springframework.beans.factory.config.BeanFactoryPostProcessor chatModelPrimaryConfigurer() {
        return (ConfigurableListableBeanFactory beanFactory) -> {
            String[] names = beanFactory.getBeanNamesForType(ChatModel.class);
            if (names.length <= 1) {
                // 仅一个 ChatModel，不存在歧义，无需标记 primary
                return;
            }
            String primaryName = null;
            if (contains(names, PREFERRED)) {
                primaryName = PREFERRED;
            } else if (contains(names, FALLBACK)) {
                primaryName = FALLBACK;
            } else {
                primaryName = names[0];
            }
            BeanDefinition bd = beanFactory.getBeanDefinition(primaryName);
            bd.setPrimary(true);
        };
    }

    private static boolean contains(String[] names, String target) {
        for (String n : names) {
            if (target.equals(n)) {
                return true;
            }
        }
        return false;
    }
}
