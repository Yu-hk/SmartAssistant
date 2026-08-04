package com.example.smartassistant.consumer.service.infrastructure;

import com.example.smartassistant.consumer.entity.RoutingCallLog;
import com.example.smartassistant.consumer.mapper.RoutingCallLogMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RoutingCallLogServiceTest {

    @Test
    void storesUserAndSessionAsSeparateOwnershipFields() {
        RoutingCallLogMapper mapper = mock(RoutingCallLogMapper.class);
        RoutingCallLogService service = new RoutingCallLogService(mapper, mock(JdbcTemplate.class));

        service.saveLog(42L, "session-1", "hello", "general_agent",
                "STREAM_ROUTER", 12L, "SUCCESS", "world");

        ArgumentCaptor<RoutingCallLog> captor = ArgumentCaptor.forClass(RoutingCallLog.class);
        verify(mapper).insert(captor.capture());
        assertEquals(42L, captor.getValue().getUserId());
        assertEquals("session-1", captor.getValue().getSessionId());
        assertEquals("world", captor.getValue().getResponseSummary());
    }
}
