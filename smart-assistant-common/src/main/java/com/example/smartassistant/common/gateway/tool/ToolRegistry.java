package com.example.smartassistant.common.gateway.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P0 工具注册中心。
 * <p>
 * 所有 @Tool 方法应在初始化时在此注册其 {@link ToolDefinition}，
 * 供 {@link ToolGateway} 在执行前进行源权、熔断、审批校验。
 * </p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 在 @PostConstruct 中注册
 * toolRegistry.register(ToolDefinition.highRisk("refund_order", "退款操作", true));
 * }</pre>
 *
 * @author Yu-hk
 * @since 2026-06-29
 */
@Service
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, ToolDefinition> definitions = new ConcurrentHashMap<>();

    /**
     * 注册一个工具定义。
     *
     * @param definition 工具定义
     */
    public void register(ToolDefinition definition) {
        if (definition == null || definition.name() == null || definition.name().isBlank()) {
            log.warn("[ToolRegistry] 跳过注册: 无效定义");
            return;
        }
        ToolDefinition existing = definitions.put(definition.name(), definition);
        if (existing != null) {
            log.info("[ToolRegistry] 更新注册: name={}, risk={}, oldRisk={}",
                    definition.name(), definition.riskLevel(), existing.riskLevel());
        } else {
            log.info("[ToolRegistry] 注册: name={}, risk={}, desc={}",
                    definition.name(), definition.riskLevel(), definition.description());
        }
    }

    /**
     * 批量注册。
     */
    public void registerAll(Collection<ToolDefinition> toolDefinitions) {
        toolDefinitions.forEach(this::register);
    }

    /**
     * 获取工具定义。
     *
     * @param toolName 工具名称
     * @return 工具定义，未注册返回 null
     */
    public ToolDefinition get(String toolName) {
        return definitions.get(toolName);
    }

    /**
     * 工具是否已注册。
     */
    public boolean isRegistered(String toolName) {
        return definitions.containsKey(toolName);
    }

    /**
     * 获取所有注册的工具。
     */
    public Collection<ToolDefinition> getAll() {
        return definitions.values();
    }

    /**
     * 获取注册的工具数量。
     */
    public int size() {
        return definitions.size();
    }
}
