package com.example.smartassistant.common.gateway.tool.hook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.example.smartassistant.common.security.PiiPolicyEngine;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 脱敏 Hook（@Order(30)）。
 * <p>
 * 在 postExecute 阶段对结果字符串做正则脱敏：
 * <ul>
 *   <li>手机号（11位）：(\d{3})\d{4}(\d{4}) → $1****$2</li>
 *   <li>身份证号（18位）：前6后4，中间用 ******** 替代</li>
 *   <li>银行卡号（16-19位）：前4后4，中间用 **** 替代</li>
 * </ul>
 * </p>
 * <p>
 * 脱敏日志不记录原文，仅记录 {@code masked=true}。
 * </p>
 *
 * @author Yu-hk
 * @since 2026-07-15
 */
@Component
@Order(30)
public class SanitizeHook implements ToolExecutionHook {

    private static final Logger log = LoggerFactory.getLogger(SanitizeHook.class);

    private final PiiPolicyEngine piiPolicyEngine;

    public SanitizeHook() { this(PiiPolicyEngine.shared()); }

    @Autowired
    public SanitizeHook(PiiPolicyEngine piiPolicyEngine) {
        this.piiPolicyEngine = piiPolicyEngine;
    }

    @Override
    public void preExecute(ToolHookContext context) {
        // no-op
    }

    @Override
    public String postExecute(ToolHookContext context, String result) {
        if (result == null || result.isEmpty()) {
            return result;
        }

        String sanitized = piiPolicyEngine.mask(result);

        if (!sanitized.equals(result)) {
            log.info("[SanitizeHook] 脱敏处理: tool={}, masked=true", context.getToolName());
        }
        return sanitized;
    }

    @Override
    public void onError(ToolHookContext context, Exception ex) {
        // no-op
    }

    @Override
    public String getName() {
        return "SanitizeHook";
    }
}
