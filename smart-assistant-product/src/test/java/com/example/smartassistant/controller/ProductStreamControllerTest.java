package com.example.smartassistant.controller;

import com.example.smartassistant.service.agent.StreamingProductAgentService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductStreamControllerTest {

    @Test
    void acceptsRouterJsonQuestionContract() {
        StreamingProductAgentService service = mock(StreamingProductAgentService.class);
        when(service.execute("E2E-PROD-0001 多少钱")).thenReturn("¥1299.00");
        ProductStreamController controller = new ProductStreamController(service);

        String result = controller.chatSync(null, Map.of("question", "E2E-PROD-0001 多少钱"));

        assertThat(result).isEqualTo("¥1299.00");
        verify(service).execute("E2E-PROD-0001 多少钱");
    }

    @Test
    void queryParameterRemainsBackwardCompatible() {
        StreamingProductAgentService service = mock(StreamingProductAgentService.class);
        when(service.execute("AIRPODS-PRO 有货吗")).thenReturn("有货");
        ProductStreamController controller = new ProductStreamController(service);

        String result = controller.chatSync(
                "AIRPODS-PRO 有货吗",
                Map.of("question", "该值不应覆盖查询参数"));

        assertThat(result).isEqualTo("有货");
        verify(service).execute("AIRPODS-PRO 有货吗");
    }

    @Test
    void rejectsBlankPayload() {
        ProductStreamController controller =
                new ProductStreamController(mock(StreamingProductAgentService.class));

        assertThatThrownBy(() -> controller.chatSync(null, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("question must not be blank");
    }
}
