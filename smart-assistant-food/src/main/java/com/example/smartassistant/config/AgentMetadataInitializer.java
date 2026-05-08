package com.example.smartassistant.config;

import com.alibaba.cloud.nacos.registry.NacosServiceRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.serviceregistry.Registration;
import org.springframework.stereotype.Component;

/**
 * Agent 元数据初始化器
 * 启动时从 DB 读取配置，覆盖 YAML 中的硬编码 metadata
 * 使 Nacos 注册的 metadata 始终与数据库一致
 */
@Component
public class AgentMetadataInitializer {

    private static final Logger log = LoggerFactory.getLogger(AgentMetadataInitializer.class);

    private final AgentRegistrationConfigService configService;
    private final NacosServiceRegistry nacosRegistry;
    private final Registration registration;

    @Value("${spring.application.name:food-service}")
    private String serviceName;

    public AgentMetadataInitializer(AgentRegistrationConfigService configService,
                                     NacosServiceRegistry nacosRegistry,
                                     Registration registration) {
        this.configService = configService;
        this.nacosRegistry = nacosRegistry;
        this.registration = registration;
    }

    @PostConstruct
    public void init() {
        try {
            var opt = configService.getConfig(serviceName);
            if (opt.isPresent()) {
                log.info("[AgentMetadata] 从数据库读取配置: serviceName={}, keywords={}, priority={}",
                        serviceName, opt.get().keywords(), opt.get().priority());

                // 重新注册使新 metadata 生效
                nacosRegistry.deregister(registration);
                nacosRegistry.register(registration);
                log.info("[AgentMetadata] Nacos 重新注册完成");
            } else {
                log.info("[AgentMetadata] 数据库无配置记录，使用 YAML 默认值");
            }
        } catch (Exception e) {
            log.warn("[AgentMetadata] 初始化失败，使用 YAML 默认值: {}", e.getMessage());
        }
    }
}
