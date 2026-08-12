package com.example.smartassistant.router.service.core;

import com.example.smartassistant.common.prompt.PromptManager;
import com.example.smartassistant.router.model.IntentGraph;
import com.example.smartassistant.router.model.SubTaskResult;
import com.example.smartassistant.router.service.agent.AgentCallerService;
import com.example.smartassistant.router.service.experience.ExperienceService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RouteLangGraphExecutionTest {

    @Test
    void alwaysExecutesThroughLangGraph4j() {
        LangGraphRouteExecutionService langGraph = mock(LangGraphRouteExecutionService.class);
        RouteExecutionService route = routeService(langGraph);
        IntentGraph graph = graph();
        List<SubTaskResult> expected = List.of(result());
        when(langGraph.execute(graph, 9L, "events", "request")).thenReturn(expected);

        assertThat(route.executeGraph(graph, 9L, "events", "request"))
                .containsExactlyElementsOf(expected);
        verify(langGraph).execute(graph, 9L, "events", "request");
    }

    @Test
    void propagatesFrameworkFailureWithoutReplayingTasksInAnotherEngine() {
        LangGraphRouteExecutionService langGraph = mock(LangGraphRouteExecutionService.class);
        RouteExecutionService route = routeService(langGraph);
        IntentGraph graph = graph();
        IllegalStateException failure = new IllegalStateException("framework failure");
        when(langGraph.execute(graph, 9L, null, "request")).thenThrow(failure);

        assertThatThrownBy(() -> route.executeGraph(graph, 9L, null, "request"))
                .isSameAs(failure);
    }

    private static RouteExecutionService routeService(LangGraphRouteExecutionService langGraph) {
        return new RouteExecutionService(
                mock(AgentCallerService.class),
                mock(TaskPlannerService.class),
                langGraph,
                mock(ResultMerger.class),
                mock(ExperienceService.class),
                mock(RouteFinalizer.class),
                mock(PromptManager.class),
                mock(ChatModel.class),
                null);
    }

    private static IntentGraph graph() {
        return new IntentGraph("查询商品", List.of(
                new IntentGraph.IntentNode("task_1", "查询商品", "product_agent", List.of())));
    }

    private static SubTaskResult result() {
        return new SubTaskResult("task_1", "查询商品", "product_agent", "结果", true);
    }
}
