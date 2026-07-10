package com.example.smartassistant.common.gateway.tool.hook;

import com.example.smartassistant.common.error.AgentErrorCode;
import com.example.smartassistant.common.gateway.tool.ToolDefinition;
import com.example.smartassistant.common.gateway.tool.ToolExecutionException;
import com.example.smartassistant.common.gateway.tool.ToolStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 工具状态拦截 Hook（@Order(10)）。
 * <p>
 * 在 preExecute 阶段检查工具生命周期状态（REQ-01）：
 * <ul>
 *   <li>{@link ToolStatus#DISABLED} / {@link ToolStatus#REMOVED} →
 *       抛 {@link ToolExecutionException}（{@link AgentErrorCode#TOOL_STATUS_DISABLED}）拦截</li>
 *   <li>{@link ToolStatus#DEPRECATED} → WARN 日志提醒（含 sunsetDate、deprecatedBy），放行</li>
 *   <li>{@link ToolStatus#ACTIVE} / {@link ToolStatus#EXPERIMENTAL} → 放行</li>
 * </ul>
 * </p>
 *
 * @author Yu-hk
 * @since 2026-07-15
 */
@Component
@Order(10)
public class ToolStatusHook implements ToolExecutionHook {

    private static final Logger log = LoggerFactory.getLogger(ToolStatusHook.class);

    @Override
    public void preExecute(ToolHookContext context) {
        ToolDefinition def = context.getToolDefinition();
        ToolStatus status = def.getStatus();

        switch (status) {
            case DISABLED, REMOVED -> {
                log.warn("[ToolStatusHook] 工具已停用/移除: tool={}, status={}",
                        def.getName(), status);
                throw new ToolExecutionException(def.getName(),
                        AgentErrorCode.TOOL_STATUS_DISABLED,
                        "工具已停用或移除: " + def.getName() + ", status=" + status);
            }
            case DEPRECATED -> {
                log.warn("[ToolStatusHook] ⚠️ 工具已废弃: tool={}, sunsetDate={}, deprecatedBy={}",
                        def.getName(), def.getSunsetDate(), def.getDeprecatedBy());
            }
            case ACTIVE, EXPERIMENTAL -> {
                // 放行，无需处理
            }
        }
    }

    @Override
    public String postExecute(ToolHookContext context, String result) {
        return result;
    }

    @Override
    public void onError(ToolHookContext context, Exception ex) {
        // no-op
    }

    @Override
    public String getName() {
        return "ToolStatusHook";
    }
}
