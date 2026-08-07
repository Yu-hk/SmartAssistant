package com.example.smartassistant.common.sse;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SseEventBusTest {

    @Test
    void writesEventsAfterResponseHasBeenCommitted() throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ServletOutputStream output = new ServletOutputStream() {
            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setWriteListener(WriteListener writeListener) {
            }

            @Override
            public void write(int value) {
                bytes.write(value);
            }
        };
        when(response.getOutputStream()).thenReturn(output);
        when(response.isCommitted()).thenReturn(true);

        SseEventBus bus = new SseEventBus(response);
        bus.send(SseEvent.done());
        bus.close();

        String rendered = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(rendered.contains("event: done"));
        assertTrue(rendered.contains("data: {\"type\":\"done\"}"));
    }
}
