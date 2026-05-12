/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

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
