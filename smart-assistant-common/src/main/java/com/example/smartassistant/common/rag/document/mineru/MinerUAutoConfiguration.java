/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.document.mineru;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MinerU 适配器自动装配（组件扫描生效，风格同 {@code ReActProfileAutoConfiguration}）。
 * <p>
 * <ul>
 *   <li>通过 {@link EnableConfigurationProperties} 注册 {@link MinerUProperties} 为 Bean；</li>
 *   <li>仅当 {@code app.rag.mineru.enabled=true} 时注册 {@link MinerUClient}（sidecar）Bean，
 *       未启用则不产生该 Bean，{@code DocumentParseRouter} 据此保持纯 PDFBox 行为（零回归）。</li>
 * </ul>
 * </p>
 */
@Configuration
@EnableConfigurationProperties(MinerUProperties.class)
public class MinerUAutoConfiguration {

    /**
     * MinerU sidecar 客户端 Bean——仅在启用时创建。
     * 进程池懒加载于首次 parse（构造不 spawn，避免 bean 创建即失败）。
     */
    @Bean
    @ConditionalOnProperty(prefix = "app.rag.mineru", name = "enabled", havingValue = "true")
    public MinerUClient minerUClient(MinerUProperties properties) {
        return new MinerUSidecarClient(properties);
    }
}
