package com.example.smartassistant.router.exception;

import lombok.Getter;

/**
 * 路由异常
 */
@Getter
public class RoutingException extends RuntimeException {

    private final String agentName;
    private final String routingMethod;

    public RoutingException(String message) {
        super(message);
        this.agentName = null;
        this.routingMethod = null;
    }

    public RoutingException(String message, String agentName, String routingMethod) {
        super(message);
        this.agentName = agentName;
        this.routingMethod = routingMethod;
    }

    public RoutingException(String message, Throwable cause) {
        super(message, cause);
        this.agentName = null;
        this.routingMethod = null;
    }
}
