package com.example.smartassistant.consumer.streaming;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void forwardStreamReportsTransportFailure() throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        HttpURLConnection connection = mock(HttpURLConnection.class);
        when(connection.getInputStream()).thenThrow(new IOException("upstream unavailable"));

        SseEventBus bus = new SseEventBus(response);

        assertFalse(bus.forwardStream(connection));
    }

    @Test
    void capturesUsageAndSuppressesUpstreamTerminalEvents() throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ServletOutputStream output = outputStream(bytes);
        when(response.getOutputStream()).thenReturn(output);

        String upstream = "event: response\n"
                + "data: {\"type\":\"response\",\"content\":\"ok\"}\n\n"
                + "event: token_usage\n"
                + "data: {\"type\":\"token_usage\",\"promptTokens\":12,"
                + "\"completionTokens\":5,\"totalTokens\":17}\n\n"
                + "data: {\"type\":\"done\"}";
        HttpURLConnection connection = mock(HttpURLConnection.class);
        when(connection.getInputStream()).thenReturn(
                new ByteArrayInputStream(upstream.getBytes(StandardCharsets.UTF_8)));

        SseEventBus bus = new SseEventBus(response);
        SseEventBus.ForwardResult result = bus.forwardStreamCapturingUsage(connection);
        bus.close();

        assertTrue(result.success());
        assertEquals(12L, result.promptTokens());
        assertEquals(5L, result.completionTokens());
        assertEquals(17L, result.totalTokens());
        String rendered = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(rendered.contains("event: response"));
        assertFalse(rendered.contains("event: token_usage"));
        assertFalse(rendered.contains("event: done"));
    }

    @Test
    void upstreamErrorMarksForwardingFailedButRemainsVisible() throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        when(response.getOutputStream()).thenReturn(outputStream(bytes));
        HttpURLConnection connection = mock(HttpURLConnection.class);
        when(connection.getInputStream()).thenReturn(new ByteArrayInputStream((
                "event: error\ndata: {\"type\":\"error\",\"content\":\"failed\"}\n\n"
                        + "event: done\ndata: {\"type\":\"done\"}\n\n")
                .getBytes(StandardCharsets.UTF_8)));

        SseEventBus bus = new SseEventBus(response);
        SseEventBus.ForwardResult result = bus.forwardStreamCapturingUsage(connection);
        bus.close();

        assertFalse(result.success());
        String rendered = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(rendered.contains("event: error"));
        assertFalse(rendered.contains("event: done"));
    }

    private static ServletOutputStream outputStream(ByteArrayOutputStream bytes) {
        return new ServletOutputStream() {
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
    }
}
