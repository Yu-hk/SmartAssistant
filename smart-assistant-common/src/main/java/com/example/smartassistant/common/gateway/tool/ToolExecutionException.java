package com.example.smartassistant.common.gateway.tool;

import com.example.smartassistant.common.error.AgentErrorCode;

/**
 * 工具执行异常（封装错误码，供 ToolGateway 抛出）。
 */
public class ToolExecutionException extends RuntimeException {

    private final String toolName;
    private final AgentErrorCode errorCode;

    public ToolExecutionException(String toolName, AgentErrorCode errorCode, String message) {
        super(message);
        this.toolName = toolName;
        this.errorCode = errorCode;
    }

    public String getToolName() { return toolName; }
    public AgentErrorCode getErrorCode() { return errorCode; }
}
