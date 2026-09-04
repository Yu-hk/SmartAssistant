package com.example.smartassistant.router.service.agent;

import com.example.smartassistant.common.agent.protocol.AgentExecutionRequest;
import com.example.smartassistant.common.agent.protocol.AgentExecutionResponse;
import com.example.smartassistant.common.agent.protocol.AgentNodeOutput;
import com.example.smartassistant.common.quality.DomainQualityResult;
import com.example.smartassistant.router.scheduler.AgentSchedulerService;
import com.example.smartassistant.router.scheduler.AgentTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentMessageDispatcherTest {

    @Mock AgentCallerService caller;
    @Mock AgentSchedulerService scheduler;
    @Mock ObjectProvider<AgentSchedulerService> schedulerProvider;

    private AgentExecutionRequest request;

    @BeforeEach
    void setUp() {
        request = new AgentExecutionRequest(
                AgentExecutionRequest.CURRENT_VERSION, "exec-1", "product_search", "42",
                "QUERY_PRODUCT", "查询热门耳机", Map.of("limit", 3), List.of("source"),
                List.of("仅查询"), System.currentTimeMillis() + 60_000, null,
                Map.of("source", new AgentNodeOutput(
                        "source", "general", "COMPLETED", "需求已确认", Map.of())),
                "shopping", 2, "sha256:v2", 0, "trace-1");
    }

    @Test
    void queuesReadNodeAndPreservesProtocolAndTypedResult() {
        when(schedulerProvider.getIfAvailable()).thenReturn(scheduler);
        when(scheduler.submitAndWait(any(AgentTask.class), anyLong())).thenAnswer(invocation -> {
            AgentTask task = invocation.getArgument(0);
            task.setExecutionResponse(AgentExecutionResponse.success(
                    "热门商品：降噪耳机",
                    Map.of(
                            AgentMessageDispatcher.REAL_TITLES_KEY, List.of("降噪耳机"),
                            AgentMessageDispatcher.TAGS_BY_TITLE_KEY, Map.of("降噪耳机", "办公")),
                    DomainQualityResult.pass(0.95, "PRODUCT_EVIDENCE_OK")));
            return task.markCompleted("热门商品：降噪耳机");
        });
        AgentMessageDispatcher dispatcher = new AgentMessageDispatcher(
                caller, schedulerProvider, true, true, 65_000);

        AgentCallResult result = dispatcher.dispatch("product", request, "READ");

        ArgumentCaptor<AgentTask> taskCaptor = ArgumentCaptor.forClass(AgentTask.class);
        verify(scheduler).submitAndWait(taskCaptor.capture(), anyLong());
        AgentTask queued = taskCaptor.getValue();
        assertThat(queued.getExecutionRequest().predecessorOutputs()).containsKey("source");
        assertThat(queued.getExecutionRequest().workflowVersion()).isEqualTo(2);
        assertThat(result.getResponse()).contains("降噪耳机");
        assertThat(result.getRealTitles()).containsExactly("降噪耳机");
        assertThat(result.getDomainQuality().isPass()).isTrue();
        verify(caller, never()).callAgentAndExtractTitles(any(), any(AgentExecutionRequest.class));
    }

    @Test
    void keepsWriteNodeOnSynchronousPath() {
        when(schedulerProvider.getIfAvailable()).thenReturn(scheduler);
        AgentExecutionRequest writeRequest = new AgentExecutionRequest(
                request.protocolVersion(), request.executionId(), "create_order", request.userId(),
                "CREATE_ORDER", "创建订单", Map.of(), List.of(), List.of(),
                request.deadlineEpochMs(), "idem-1", Map.of(),
                "shopping", 2, "sha256:v2", 0, "trace-1");
        when(caller.callAgentAndExtractTitles(eq("order"), eq(writeRequest)))
                .thenReturn(new AgentCallResult("等待确认"));
        AgentMessageDispatcher dispatcher = new AgentMessageDispatcher(
                caller, schedulerProvider, true, true, 65_000);

        AgentCallResult result = dispatcher.dispatch("order", writeRequest, "WRITE");

        assertThat(result.getResponse()).isEqualTo("等待确认");
        verify(scheduler, never()).submitAndWait(any(), anyLong());
    }
}
