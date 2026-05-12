/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.config;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.alibaba.cloud.nacos.registry.NacosRegistration;
import com.alibaba.cloud.nacos.registry.NacosServiceRegistry;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Nacos 元数据动态配置器
 * <p>
 * 从 Nacos Config 读取 {serviceName}-metadata (Group=AGENT_META) 配置，
 * 覆盖 YAML 中硬编码的 metadata，并监听变更自动重新注册。
 * <p>
 * Nacos Config 中需创建配置：
 *   Data ID: food-service-metadata
 *   Group:   AGENT_META
 *   Format:  YAML
 *   Content:
 *     keywords: 美食,餐厅,火锅,烧烤,日料
 *     priority: 10
 *     capabilities: cuisine_query,restaurant_recommendation
 */
@Component
public class NacosMetadataConfigurer {

    private static final Logger log = LoggerFactory.getLogger(NacosMetadataConfigurer.class);

    private static final String CONFIG_GROUP = "AGENT_META";

    private final NacosConfigManager configManager;
    private final NacosServiceRegistry nacosRegistry;
    private final NacosRegistration registration;

    @Value("${spring.application.name:food-service}")
    private String serviceName;

    @Value("${nacos.metadata.config-id-suffix:-metadata}")
    private String configIdSuffix;

    public NacosMetadataConfigurer(NacosConfigManager configManager,
                                    NacosServiceRegistry nacosRegistry,
                                    NacosRegistration registration) {
        this.configManager = configManager;
        this.nacosRegistry = nacosRegistry;
        this.registration = registration;
    }

    @PostConstruct
    public void init() {
        String dataId = serviceName + configIdSuffix;
        try {
            // 1. 读取初始配置
            String config = configManager.getConfigService().getConfig(dataId, CONFIG_GROUP, 5000);
            if (config != null && !config.isBlank()) {
                applyConfig(config);
            } else {
                log.info("[NacosMetadata] 未在 Nacos Config 中找到配置 (dataId={}, group={})，使用 YAML 默认值",
                        dataId, CONFIG_GROUP);
            }

            // 2. 监听变更
            configManager.getConfigService().addListener(dataId, CONFIG_GROUP, new Listener() {
                @Override
                public Executor getExecutor() {
                    return Runnable::run;
                }

                @Override
                public void receiveConfigInfo(String config) {
                    log.info("[NacosMetadata] 配置变更: dataId={}", dataId);
                    applyConfig(config);
                }
            });

            log.info("[NacosMetadata] 已监听 Nacos Config: dataId={}, group={}", dataId, CONFIG_GROUP);

        } catch (NacosException e) {
            log.warn("[NacosMetadata] 初始化失败: {}", e.getMessage());
        }
    }

    private void applyConfig(String config) {
        try {
            Yaml yaml = new Yaml();
            Map<String, Object> meta = yaml.load(config);
            if (meta == null || meta.isEmpty()) return;

            log.info("[NacosMetadata] 应用 Nacos Config 元数据: {}", meta);

            // 更新注册 metadata
            Map<String, String> metadata = registration.getMetadata();
            if (meta.containsKey("keywords")) metadata.put("keywords", String.valueOf(meta.get("keywords")));
            if (meta.containsKey("priority")) metadata.put("priority", String.valueOf(meta.get("priority")));
            if (meta.containsKey("capabilities")) metadata.put("capabilities", String.valueOf(meta.get("capabilities")));
            if (meta.containsKey("agent-type")) metadata.put("agent-type", String.valueOf(meta.get("agent-type")));

            // 重新注册
            nacosRegistry.deregister(registration);
            nacosRegistry.register(registration);
            log.info("[NacosMetadata] ✅ Nacos 重新注册完成");

        } catch (Exception e) {
            log.warn("[NacosMetadata] 应用配置失败: {}", e.getMessage());
        }
    }
}
