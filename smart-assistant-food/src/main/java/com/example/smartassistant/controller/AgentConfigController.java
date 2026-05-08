package com.example.smartassistant.controller;

import com.alibaba.cloud.nacos.registry.NacosServiceRegistry;
import com.example.smartassistant.config.AgentRegistrationConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.serviceregistry.Registration;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Agent 注册配置管理接口
 * 支持动态更新 Nacos 注册 metadata，无需重启服务
 */
@RestController
@RequestMapping("/api/admin/agent-config")
public class AgentConfigController {

    private static final Logger log = LoggerFactory.getLogger(AgentConfigController.class);

    private final AgentRegistrationConfigService configService;
    private final NacosServiceRegistry nacosRegistry;
    private final Registration registration;

    public AgentConfigController(AgentRegistrationConfigService configService,
                                  NacosServiceRegistry nacosRegistry,
                                  Registration registration) {
        this.configService = configService;
        this.nacosRegistry = nacosRegistry;
        this.registration = registration;
    }

    /**
     * 获取当前配置
     */
    @GetMapping("/{serviceName}")
    public ResponseEntity<?> getConfig(@PathVariable String serviceName) {
        return configService.getConfig(serviceName)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 更新配置并重新注册到 Nacos
     */
    @PutMapping("/{serviceName}")
    public ResponseEntity<Map<String, Object>> updateConfig(
            @PathVariable String serviceName,
            @RequestBody Map<String, Object> body) {

        String keywords = (String) body.getOrDefault("keywords", "");
        Integer priority = body.get("priority") instanceof Number
                ? ((Number) body.get("priority")).intValue() : 5;
        String capabilities = (String) body.getOrDefault("capabilities", "");

        boolean updated = configService.updateConfig(serviceName, keywords, priority, capabilities);

        Map<String, Object> result = new HashMap<>();
        if (updated) {
            // 重新注册到 Nacos（使新 metadata 生效）
            try {
                nacosRegistry.deregister(registration);
                nacosRegistry.register(registration);
                log.info("[AgentConfig] 配置已更新并重新注册: serviceName={}", serviceName);
                result.put("success", true);
                result.put("message", "配置已更新，Nacos 已重新注册");
            } catch (Exception e) {
                log.warn("[AgentConfig] 重新注册 Nacos 失败: {}", e.getMessage());
                result.put("success", true);
                result.put("message", "配置已更新，但重新注册失败，请手动重启服务");
            }
        } else {
            result.put("success", false);
            result.put("message", "配置更新失败（可能服务名不存在）");
        }

        return ResponseEntity.ok(result);
    }
}
