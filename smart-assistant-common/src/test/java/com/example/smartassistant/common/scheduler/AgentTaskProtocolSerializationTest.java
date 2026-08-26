package com.example.smartassistant.common.scheduler;

import com.example.smartassistant.common.agent.protocol.AgentExecutionRequest;
import com.example.smartassistant.common.agent.protocol.AgentExecutionResponse;
import com.example.smartassistant.common.agent.protocol.AgentNodeOutput;
import com.example.smartassistant.common.quality.DomainQualityResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentTaskProtocolSerializationTest {

    @Test
    void queuePayloadKeepsWorkflowProtocolAndTypedResponse() throws Exception {
        AgentTask task = AgentTaskFactory.createTask("order", "创建订单", 42L, "exec-4");
        task.setExecutionRequest(new AgentExecutionRequest(
                "1.0", "exec-4", "create_order", "42", "CREATE_ORDER", "创建订单",
                Map.of("quantity", 1), List.of("products"), List.of("需确认"),
                null, "idem-4", null,
                Map.of("products", new AgentNodeOutput(
                        "products", "product", "COMPLETED", "找到商品",
                        Map.of("sku", "SKU-1"))),
                "shopping", 4, "sha256:v4", 0, "trace-4"));
        task.setExecutionResponse(AgentExecutionResponse.success(
                "订单待确认", Map.of("orderId", "ORD-4"),
                DomainQualityResult.pass(1.0, "ORDER_READY")));

        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        AgentTask restored = mapper.readValue(mapper.writeValueAsString(task), AgentTask.class);

        assertThat(restored.getExecutionRequest().workflowVersion()).isEqualTo(4);
        assertThat(restored.getExecutionRequest().predecessorOutputs()).containsKey("products");
        assertThat(restored.getExecutionResponse().data()).containsEntry("orderId", "ORD-4");
        assertThat(restored.getExecutionResponse().quality().toDomainQuality().isPass()).isTrue();
    }
}
