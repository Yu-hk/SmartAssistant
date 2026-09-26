package com.example.smartassistant.consumer.streaming;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VisibleReplyAccumulatorTest {
    @Test
    void capturesOnlyVisibleTextAndFinalResponseWinsOverPartialChunks() {
        var reply = new VisibleReplyAccumulator();
        reply.accept("step", "{\"type\":\"step\",\"message\":\"private progress\"}");
        reply.accept("text", "{\"type\":\"text\",\"content\":\"您好，\"}");
        reply.accept("text", "{\"type\":\"text\",\"content\":\"有货。\"}");
        assertThat(reply.text()).isEqualTo("您好，有货。");
        reply.accept("response", "{\"type\":\"response\",\"content\":\"您好，目前有货。\"}");
        assertThat(reply.text()).isEqualTo("您好，目前有货。");
    }

    @Test
    void ignoresMalformedAndNonVisibleEvents() {
        var reply = new VisibleReplyAccumulator();
        reply.accept("tool_result", "{\"content\":\"secret\"}");
        reply.accept("response", "not-json");
        assertThat(reply.text()).isEmpty();
    }

    @Test
    void capturesVisibleMessageFallback() {
        var reply = new VisibleReplyAccumulator();
        reply.accept("response", "{\"type\":\"response\",\"message\":\"您好，目前有货。\"}");
        assertThat(reply.text()).isEqualTo("您好，目前有货。");
    }
}
