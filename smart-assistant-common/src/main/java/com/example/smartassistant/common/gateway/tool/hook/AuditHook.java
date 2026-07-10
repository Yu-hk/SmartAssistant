package com.example.smartassistant.common.gateway.tool.hook;

import com.example.smartassistant.common.gateway.tool.ToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 审计 Hook（@Order(100)）。
 * <p>
 * 在 postExecute 阶段记录结构化审计日志（INFO），在 onError 阶段记录失败审计日志（WARN）。
 * 排在最后执行，确保审计记录的是脱敏后的结果。
 * </p>
 *
 * <h3>审计字段</h3>
 * <ul>
 *   <li>成功：tool, scope, elapsedMs, riskLevel, resultLength, success=true</li>
 *   <li>失败：tool, scope, elapsedMs, errorSummary, success=false</li>
 * </ul>
 *
 * @author Yu-hk
 * @since 2026-07-15
 */
@Component
@Order(100)
public class AuditHook implements ToolExecutionHook {

    private static final Logger log = LoggerFactory.getLogger(AuditHook.class);

    @Override
    public void preExecute(ToolHookContext context) {
        // no-op
    }

    @Override
    public String postExecute(ToolHookContext context, String result) {
        ToolDefinition def = context.getToolDefinition();
        int resultLength = result != null ? result.length() : 0;
        log.info("[AuditHook] tool={}, scope={}, elapsedMs={}, riskLevel={}, resultLength={}, success=true",
                def.getName(), context.getScope(), context.getElapsedMs(),
                def.getRiskLevel(), resultLength);
        return result;
    }

    @Override
    public void onError(ToolHookContext context, Exception ex) {
        ToolDefinition def = context.getToolDefinition();
        String errorSummary = ex != null
                ? ex.getClass().getSimpleName() + ": " + ex.getMessage()
                : "unknown";
        log.warn("[AuditHook] tool={}, scope={}, elapsedMs={}, errorSummary={}, success=false",
                def.getName(), context.getScope(), context.getElapsedMs(), errorSummary);
    }

    @Override
    public String getName() {
        return "AuditHook";
    }
}
