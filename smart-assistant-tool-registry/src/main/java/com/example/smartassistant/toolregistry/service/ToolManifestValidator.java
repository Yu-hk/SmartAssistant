package com.example.smartassistant.toolregistry.service;

import com.example.smartassistant.common.gateway.tool.ToolCapability;
import com.example.smartassistant.common.gateway.tool.ToolDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 工具 Manifest 校验器（REQ-05）。
 * <p>
 * 在注册阶段对 {@link ToolDefinition} 进行 manifest 校验：
 * <ul>
 *   <li>capabilities 为空/null → 填默认 ["unknown"] + WARN</li>
 *   <li>capabilities 含非预定义值 → WARN（不阻断，Q6 v1 仅预定义 6 个 + unknown）</li>
 *   <li>outputSchema 非 null → 用 Jackson {@code readTree()} 校验 JSON 合法性，失败 WARN 不阻断（Q5 决策）</li>
 * </ul>
 * </p>
 *
 * @author Yu-hk
 * @since 2026-07-15
 */
@Service
public class ToolManifestValidator {

    private static final Logger log = LoggerFactory.getLogger(ToolManifestValidator.class);

    /** capabilities 默认值 */
    private static final String[] DEFAULT_CAPABILITIES = new String[]{"unknown"};

    private final ObjectMapper objectMapper;

    public ToolManifestValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 校验并修正工具定义的 manifest 字段。
     * <p>对 capabilities 为空的情况会就地填充默认值（修改传入的 def 对象）。</p>
     *
     * @param def 工具定义
     */
    public void validate(ToolDefinition def) {
        validateCapabilities(def);
        validateOutputSchema(def);
    }

    /**
     * 校验 capabilities 字段。
     * <p>为空时填充默认 ["unknown"]；含非预定义值时 WARN 不阻断。</p>
     *
     * @param def 工具定义
     */
    private void validateCapabilities(ToolDefinition def) {
        String[] capabilities = def.getCapabilities();

        if (capabilities == null || capabilities.length == 0) {
            log.warn("[ToolManifestValidator] capabilities 为空，填充默认值: tool={}",
                    def.getName());
            def.setCapabilities(DEFAULT_CAPABILITIES.clone());
            return;
        }

        for (String cap : capabilities) {
            if (!ToolCapability.isValid(cap)) {
                log.warn("[ToolManifestValidator] capabilities 含非预定义值: tool={}, capability={}",
                        def.getName(), cap);
            }
        }
    }

    /**
     * 校验 outputSchema 字段。
     * <p>非 null 时用 Jackson readTree() 校验 JSON 合法性，失败 WARN 不阻断。</p>
     *
     * @param def 工具定义
     */
    private void validateOutputSchema(ToolDefinition def) {
        String outputSchema = def.getOutputSchema();
        if (outputSchema == null || outputSchema.isBlank()) {
            return;
        }

        try {
            objectMapper.readTree(outputSchema);
        } catch (Exception e) {
            log.warn("[ToolManifestValidator] outputSchema 非法 JSON: tool={}, error={}",
                    def.getName(), e.getMessage());
        }
    }
}
