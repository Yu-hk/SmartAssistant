package com.example.smartassistant.intake.service.admin;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class ProductIdentityUpdateTest {
    private final ObjectMapper json = new ObjectMapper();
    @Test void validatesVocabularySourceAndRevision() throws Exception {
        var update = ProductIdentityUpdate.parse(json.readTree("{\"expectedRevision\":0,\"brand\":\"Apple\",\"parentCode\":\"airpods-pro\",\"source\":\"厂商目录\"}"));
        assertThat(update.metadata()).containsEntry("parentCode", "AIRPODS-PRO").containsEntry("model", "");
        for (String bad : new String[]{"{}", "{\"expectedRevision\":-1}", "{\"expectedRevision\":0.5}", "{\"expectedRevision\":0,\"source\":\"\"}",
                "{\"expectedRevision\":0,\"source\":\"test\",\"price\":1}", "{\"expectedRevision\":0,\"source\":\"test\",\"brand\":false}"})
            assertThatThrownBy(() -> ProductIdentityUpdate.parse(json.readTree(bad))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
}
