package com.example.smartassistant.common.tool;

import com.example.smartassistant.common.audit.ToolUsageCache;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ToolLogAspectAuditTest {

    @AfterEach
    void clearContext() {
        ToolLogContext.clear();
        ToolUsageCache.consume("aspect-audit");
        ToolUsageCache.consume("managed-audit");
    }

    @Test
    void directAnnotatedToolPathRecordsTelemetry() throws Throwable {
        ProceedingJoinPoint joinPoint = joinPoint("queryProductInfo", "ok");
        ToolLogContext.setRequestId("aspect-audit");

        Object result = new ToolLogAspect(null).logToolCall(joinPoint);

        assertEquals("ok", result);
        ToolUsageCache.ToolUsage usage = ToolUsageCache.consume("aspect-audit");
        assertNotNull(usage);
        assertTrue(usage.complete());
        assertEquals(1, usage.calls().size());
        assertEquals("queryProductInfo", usage.calls().getFirst().name());
        assertEquals("SUCCESS", usage.calls().getFirst().status());
    }

    @Test
    void executorManagedPathDoesNotDoubleRecord() throws Throwable {
        ProceedingJoinPoint joinPoint = joinPoint("queryWeather", "sunny");
        ToolLogContext.setRequestId("managed-audit");
        ToolLogContext.enterExecutorManagedCall();
        try {
            new ToolLogAspect(null).logToolCall(joinPoint);
        } finally {
            ToolLogContext.exitExecutorManagedCall();
        }

        assertNull(ToolUsageCache.consume("managed-audit"));
    }

    private ProceedingJoinPoint joinPoint(String methodName, Object result) throws Throwable {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        Signature signature = mock(Signature.class);
        when(signature.getName()).thenReturn(methodName);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getArgs()).thenReturn(new Object[]{"sample"});
        when(joinPoint.proceed()).thenReturn(result);
        return joinPoint;
    }
}
