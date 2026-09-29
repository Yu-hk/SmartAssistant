package com.example.smartassistant.service.search.handler;

import com.example.smartassistant.common.jev.JevDecisionClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Optional;

import static com.example.smartassistant.service.search.handler.JevProductFieldAdvisor.ProductField.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JevProductFieldAdvisorTest {
    @Test void acceptsMultipleHighConfidenceFieldsButNotWeakSuggestions() throws Exception {
        var client = mock(JevDecisionClient.class);
        var answer = new ObjectMapper().readTree("""
                {"identity":{"noul":0.93},"purpose":{"noul":0.95},"feature":{"noul":0.64}}
                """);
        when(client.evaluate(anyString(), anyMap(), isNull()))
                .thenReturn(Optional.of(new JevDecisionClient.Decision(answer)));
        assertThat(new JevProductFieldAdvisor(client).suggest("未明确的商品问题"))
                .isEqualTo(EnumSet.of(IDENTITY, PURPOSE));
    }

    @Test void unavailableJevReturnsNoHardFilter() {
        var client = mock(JevDecisionClient.class);
        when(client.evaluate(anyString(), anyMap(), isNull())).thenReturn(Optional.empty());
        assertThat(new JevProductFieldAdvisor(client).suggest("未明确的商品问题")).isEmpty();
    }
}
