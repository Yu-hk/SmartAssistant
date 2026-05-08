package com.example.smartassistant.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

/**
 * Agent 注册配置服务
 * 从 agent_registration_config 表读取动态配置，替代 YAML 中的硬编码 metadata
 */
@Service
public class AgentRegistrationConfigService {

    private static final Logger log = LoggerFactory.getLogger(AgentRegistrationConfigService.class);

    private final JdbcTemplate jdbcTemplate;

    public AgentRegistrationConfigService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 获取服务的注册配置
     */
    public Optional<RegistrationConfig> getConfig(String serviceName) {
        try {
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT * FROM agent_registration_config WHERE service_name = ?", serviceName);
            return Optional.of(new RegistrationConfig(
                    (String) row.get("service_name"),
                    (String) row.get("agent_type"),
                    (String) row.get("keywords"),
                    row.get("priority") != null ? ((Number) row.get("priority")).intValue() : 5,
                    (String) row.get("capabilities")
            ));
        } catch (Exception e) {
            log.warn("[AgentConfig] 读取配置失败: serviceName={}, error={}", serviceName, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 更新服务配置
     */
    public boolean updateConfig(String serviceName, String keywords, Integer priority, String capabilities) {
        try {
            int updated = jdbcTemplate.update(
                    "UPDATE agent_registration_config SET keywords = ?, priority = ?, capabilities = ?, updated_at = NOW() WHERE service_name = ?",
                    keywords, priority, capabilities, serviceName);
            return updated > 0;
        } catch (Exception e) {
            log.warn("[AgentConfig] 更新配置失败: {}", e.getMessage());
            return false;
        }
    }

    public record RegistrationConfig(
            String serviceName,
            String agentType,
            String keywords,
            int priority,
            String capabilities
    ) {}
}
