package com.example.smartassistant.consumer.streaming;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RedisSseProgressForwarderTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final SseEventBus bus = mock(SseEventBus.class);
    private final RedisSseProgressForwarder forwarder =
            new RedisSseProgressForwarder(redis, new ObjectMapper());

    @Test
    void listForwardsProgressButNeverTheTerminalEvent() {
        @SuppressWarnings("unchecked")
        ListOperations<String, String> list = mock(ListOperations.class);
        when(redis.opsForList()).thenReturn(list);
        when(list.leftPop("events")).thenReturn(
                "{\"type\":\"response\",\"content\":\"ok\"}", "{\"type\":\"done\"}", null);

        assertThat(forwarder.forwardList(bus, "events")).isTrue();
        var event = org.mockito.ArgumentCaptor.forClass(SseEvent.class);
        verify(bus).send(event.capture());
        assertThat(event.getValue().render()).contains("event: response").doesNotContain("event: done");
    }

    @Test
    void streamTracksForwardedProgressAndSkipsDone() {
        @SuppressWarnings("unchecked")
        StreamOperations<String, Object, Object> stream = mock(StreamOperations.class);
        @SuppressWarnings("unchecked")
        MapRecord<String, Object, Object> progress = mock(MapRecord.class);
        @SuppressWarnings("unchecked")
        MapRecord<String, Object, Object> done = mock(MapRecord.class);
        when(redis.opsForStream()).thenReturn(stream);
        when(progress.getId()).thenReturn(RecordId.of("1-0"));
        when(progress.getValue()).thenReturn(Map.of("payload", "{\"type\":\"step\"}"));
        when(done.getId()).thenReturn(RecordId.of("2-0"));
        when(done.getValue()).thenReturn(Map.of("payload", "{\"type\":\"done\"}"));
        when(stream.range(eq("progress"), any(Range.class))).thenReturn(List.of(progress, done));

        var cursor = new RedisSseProgressForwarder.Cursor();
        forwarder.forwardStream(bus, "progress", cursor);

        assertThat(cursor.forwardedAny()).isTrue();
        var event = org.mockito.ArgumentCaptor.forClass(SseEvent.class);
        verify(bus).send(event.capture());
        assertThat(event.getValue().render()).contains("event: step").doesNotContain("event: done");
    }

    @Test
    void listFailureDoesNotClaimSuccess() {
        @SuppressWarnings("unchecked")
        ListOperations<String, String> list = mock(ListOperations.class);
        when(redis.opsForList()).thenReturn(list);
        when(list.leftPop("events")).thenThrow(new IllegalStateException("redis unavailable"));

        assertThat(forwarder.forwardList(bus, "events")).isFalse();
        verifyNoInteractions(bus);
    }
}
