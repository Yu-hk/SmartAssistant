package com.example.smartassistant.common.gateway.tool.hook;

import com.example.smartassistant.common.error.AgentErrorCode;
import com.example.smartassistant.common.gateway.tool.ToolDefinition;
import com.example.smartassistant.common.gateway.tool.ToolExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 审批 Hook（@Order(20)）。
 * <p>
 * v1 策略：异步先拒绝 —— {@code needsApproval=true} 的工具在 preExecute 阶段
 * 直接抛 {@link ToolExecutionException}（{@link AgentErrorCode#APPROVAL_REJECTED}），
 * 不阻塞线程等待人工审批。
 * </p>
 * <p>
 * v2 规划：引入审批工单（ApprovalTicket）+ 状态机 + 回调恢复执行机制。
 * </p>
 *
 * @author Yu-hk
 * @since 2026-07-15
 */
@Component
@Order(20)
public class ApprovalHook implements ToolExecutionHook {

    private static final Logger log = LoggerFactory.getLogger(ApprovalHook.class);

    @Override
    public void preExecute(ToolHookContext context) {
        ToolDefinition def = context.getToolDefinition();
        if (def.isNeedsApproval()) {
            log.warn("[ApprovalHook] 工具需要审批，异步先拒绝: tool={}, risk={}",
                    def.getName(), def.getRiskLevel());
            throw new ToolExecutionException(def.getName(),
                    AgentErrorCode.APPROVAL_REJECTED,
                    "操作未通过审批（异步先拒绝）: " + def.getName());
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
        return "ApprovalHook";
    }
}
