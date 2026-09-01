package com.example.smartassistant.common.gateway.tool;

import com.example.smartassistant.common.error.AgentErrorCode;
import com.example.smartassistant.common.governance.CallLimitProperties;
import com.example.smartassistant.common.governance.InvocationBudgetRegistry;
import com.example.smartassistant.common.security.PiiPolicyEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ToolGatewayBudgetTest {

    private final InvocationBudgetRegistry budgets = new InvocationBudgetRegistry();

    @BeforeEach void setup() {
        MDC.put("requestId", "budget-request");
        MDC.put("sessionId", "budget-session");
    }

    @AfterEach void cleanup() { MDC.clear(); }

    @Test
    void enforcesRequestToolBudget() {
        CallLimitProperties properties = new CallLimitProperties();
        properties.getTool().setMaxPerRequest(2);
        properties.getTool().setMaxPerSession(10);
        ToolGateway gateway = new ToolGateway(new ToolRegistry(), List.of(), budgets,
                properties, PiiPolicyEngine.shared());
        ToolDefinition tool = ToolDefinition.read("lookup", "lookup");

        assertEquals("ok", gateway.execute(tool, () -> "ok", null, null));
        assertEquals("ok", gateway.execute(tool, () -> "ok", null, null));
        ToolExecutionException error = assertThrows(ToolExecutionException.class,
                () -> gateway.execute(tool, () -> "never", null, null));
        assertEquals(AgentErrorCode.SYSTEM_BUDGET_EXCEEDED, error.getErrorCode());
    }
}
