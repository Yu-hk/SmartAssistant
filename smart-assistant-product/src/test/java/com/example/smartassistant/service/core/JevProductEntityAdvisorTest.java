package com.example.smartassistant.service.core;
import com.example.smartassistant.common.jev.JevDecisionClient;
import com.example.smartassistant.spi.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class JevProductEntityAdvisorTest {
    @Test void candidateChoiceIsValidatedAndNeverExecutesACatalogLookup() throws Exception {
        var client = mock(JevDecisionClient.class);
        when(client.available()).thenReturn(true);
        var advisor = new JevProductEntityAdvisor(client);
        var candidates = List.of(new ProductIdentity("AP", "AirPods Pro", List.of()));
        var json = new ObjectMapper();
        for (String choice : List.of("UNKNOWN", "AMBIGUOUS", "C1", "SKU-INVENTED")) {
            when(client.evaluate(anyString(), anyMap(), any())).thenReturn(Optional.of(new JevDecisionClient.Decision(
                    json.readTree("{\"entity\":{\"choice\":\"" + choice + "\",\"confidence\":0.99}}"))));
            assertThat(advisor.suggest("苹果头戴耳机", candidates, "test")).isEmpty();
        }
        when(client.evaluate(anyString(), anyMap(), any())).thenReturn(Optional.of(new JevDecisionClient.Decision(
                json.readTree("{\"entity\":{\"choice\":\"C0\",\"confidence\":0.99}}"))));
        assertThat(advisor.suggest("苹果耳机", candidates, "test")).contains(new JevProductEntityAdvisor.Suggestion("AP", .99));
        when(client.evaluate(anyString(), anyMap(), any())).thenReturn(Optional.empty());
        assertThat(advisor.suggest("苹果耳机", candidates, "test")).isEmpty();
    }
    @Test void modelSuggestionStillRequiresHumanConfirmation() throws Exception {
        var backend = mock(ProductBackend.class);
        var advisor = mock(JevProductEntityAdvisor.class);
        when(backend.listProductIdentities()).thenReturn(List.of(new ProductIdentity("AP", "AirPods Pro", List.of())));
        when(advisor.suggest(anyString(), anyList(), any())).thenReturn(Optional.of(new JevProductEntityAdvisor.Suggestion("AP", .99)));
        var mentions = new ProductEntityResolver(backend, advisor).resolve("AirPods Pro Max价格？", List.of(), "test");
        assertThat(mentions).hasSize(1);
        assertThat(mentions.getFirst().status()).isEqualTo(ProductEntityResolver.Status.AMBIGUOUS);
        assertThat(mentions.getFirst().basis()).isEqualTo("JEV_SUGGESTION_REQUIRES_CONFIRMATION");
        assertThat(mentions.getFirst().code()).isEmpty();
        verify(backend, never()).lookupFacts(anyString());
    }
}
