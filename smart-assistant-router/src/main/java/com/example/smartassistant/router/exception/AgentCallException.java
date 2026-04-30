package com.example.smartassistant.router.exception;

import lombok.Getter;

/**
 * Agent 调用异常
 */
@Getter
public class AgentCallException extends RuntimeException {

    private final String agentName;
    private final int httpStatus;

    public AgentCallException(String message, String agentName) {
        super(message);
        this.agentName = agentName;
        this.httpStatus = 502;
    }

    public AgentCallException(String message, String agentName, int httpStatus) {
        super(message);
        this.agentName = agentName;
        this.httpStatus = httpStatus;
    }

    public AgentCallException(String message, String agentName, Throwable cause) {
        super(message, cause);
        this.agentName = agentName;
        this.httpStatus = 502;
    }
}
