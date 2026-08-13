package com.example.smartassistant.toolregistry.general.sandbox;

import com.example.smartassistant.common.error.AgentErrorCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ScriptSandboxTest {

    @Test
    void evaluatesBoundedMathScript() {
        ScriptSandbox sandbox = new ScriptSandbox(new ScriptSandboxProperties());
        try {
            var result = sandbox.execute("a = 3\nb = 4\nc = sqrt(a^2+b^2)");
            assertTrue(result.success());
            assertTrue(result.output().contains("c = 5"));
        } finally {
            sandbox.shutdown();
        }
    }

    @Test
    void rejectsDangerousAndOversizedScripts() {
        ScriptSandbox sandbox = new ScriptSandbox(new ScriptSandboxProperties());
        try {
            var dangerous = sandbox.execute("y = system(1)");
            assertFalse(dangerous.success());
            assertEquals(AgentErrorCode.SECURITY_SCRIPT_REJECTED, dangerous.errorCode());

            var oversized = sandbox.execute("1+".repeat(1001));
            assertFalse(oversized.success());
            assertEquals(AgentErrorCode.SECURITY_SCRIPT_RESOURCE_LIMIT, oversized.errorCode());
        } finally {
            sandbox.shutdown();
        }
    }

    @Test
    void disabledSandboxUsesInlineExecution() {
        ScriptSandboxProperties properties = new ScriptSandboxProperties();
        properties.setEnabled(false);
        ScriptSandbox sandbox = new ScriptSandbox(properties);
        try {
            assertTrue(sandbox.execute("x = 2 * 3").output().contains("= 6"));
        } finally {
            sandbox.shutdown();
        }
    }
}
