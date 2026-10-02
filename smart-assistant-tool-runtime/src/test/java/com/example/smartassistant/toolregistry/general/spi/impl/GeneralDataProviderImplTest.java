package com.example.smartassistant.toolregistry.general.spi.impl;

import com.example.smartassistant.common.error.AgentErrorCode;
import com.example.smartassistant.toolregistry.general.sandbox.ScriptSandbox;
import com.example.smartassistant.toolregistry.general.sandbox.ScriptSandboxProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class GeneralDataProviderImplTest {
    @Test
    void realSandboxSuccessKeepsOutputAndNoErrorFields() {
        var sandbox = new ScriptSandbox(new ScriptSandboxProperties());
        try {
            var result = new GeneralDataProviderImpl(sandbox).executeScript("x=2\ny=x*3");
            assertTrue(result.success());
            assertTrue(result.output().endsWith("y = 6"));
            assertNull(result.errorCode());
            assertNull(result.message());
            assertNull(result.hint());
        } finally {
            sandbox.shutdown();
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"system(1)", "1+"})
    void realSandboxFailureKeepsMachineCodeMessageAndRecoveryHint(String input) {
        var sandbox = new ScriptSandbox(new ScriptSandboxProperties());
        try {
            var result = new GeneralDataProviderImpl(sandbox).executeScript(input);
            var expected = "system(1)".equals(input) ? AgentErrorCode.SECURITY_SCRIPT_REJECTED
                    : AgentErrorCode.VALIDATION_SCRIPT_EMPTY;
            assertFalse(result.success());
            assertNull(result.output());
            assertEquals(expected.name(), result.errorCode());
            assertFalse(result.message().isBlank());
            assertFalse(result.hint().isBlank());
        } finally {
            sandbox.shutdown();
        }
    }
}
