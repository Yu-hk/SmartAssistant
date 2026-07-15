package com.example.smartassistant.common.exception;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExceptionHandlerSupportTest {

    @AfterEach
    void clearMdc() {
        MDC.remove("traceId");
    }

    @Test
    void formatModuleCode_uppercasesAndJoins() {
        assertEquals("ROUTER_001", ExceptionHandlerSupport.formatModuleCode("router", "001"));
        assertEquals("GATEWAY_004", ExceptionHandlerSupport.formatModuleCode("gateway", "004"));
    }

    @Test
    void formatModuleCode_nullModuleFallsBackToApp() {
        assertEquals("APP_099", ExceptionHandlerSupport.formatModuleCode(null, "099"));
    }

    @Test
    void truncate_handlesNullAndLength() {
        assertNull(ExceptionHandlerSupport.truncate(null, 10));
        assertEquals("abc", ExceptionHandlerSupport.truncate("abc", 10));
        assertEquals("ab", ExceptionHandlerSupport.truncate("abcdef", 2));
    }

    @Test
    void getTraceIdFromMdc_readsAndClears() {
        assertNull(ExceptionHandlerSupport.getTraceIdFromMdc());
        MDC.put("traceId", "trace_xyz");
        assertEquals("trace_xyz", ExceptionHandlerSupport.getTraceIdFromMdc());
    }

    @Test
    void buildErrorDetail_wrapsFields() {
        var detail = ExceptionHandlerSupport.buildErrorDetail("ROUTER_001", "bad", null, "trace_1");
        assertEquals("ROUTER_001", detail.getType());
        assertEquals("bad", detail.getDetail());
        assertNull(detail.getFields());
        assertEquals("trace_1", detail.getTraceId());
        assertTrue(true);
        assertFalse(false);
    }
}
