package com.example.smartassistant.router.workflow;

import com.example.smartassistant.router.service.agent.AgentDiscoveryService;
import com.example.smartassistant.router.service.core.LangGraphRouteExecutionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowPublishServiceTest {

    @Test
    void publishesOnlyAfterValidationAndNativeCompile() {
        WorkflowVersionRepository repository = mock(WorkflowVersionRepository.class);
        LangGraphRouteExecutionService runtime = mock(LangGraphRouteExecutionService.class);
        AgentDiscoveryService discovery = mock(AgentDiscoveryService.class);
        when(discovery.getCachedAgents()).thenReturn(List.of());
        WorkflowDefinition definition = validDefinition();
        WorkflowVersion draft = version(definition, WorkflowVersion.Status.DRAFT, null);
        WorkflowVersion published = version(definition, WorkflowVersion.Status.PUBLISHED, "checksum");
        when(repository.find("commerce", 1)).thenReturn(Optional.of(draft), Optional.of(published));
        when(repository.publish(any(), any(Integer.class), any(), any())).thenReturn(true);
        WorkflowPublishService service = new WorkflowPublishService(repository,
                new WorkflowValidator(), new WorkflowGraphCompiler(), runtime, discovery,
                new ObjectMapper());

        var result = service.publish("commerce", 1, 7L);

        assertTrue(result.published());
        assertNotNull(result.workflow().checksum());
        verify(runtime).validateCompilation(any());
        verify(repository).publish(any(), any(Integer.class), any(), any());
    }

    @Test
    void invalidDraftNeverReachesLangGraphOrPublication() {
        WorkflowVersionRepository repository = mock(WorkflowVersionRepository.class);
        LangGraphRouteExecutionService runtime = mock(LangGraphRouteExecutionService.class);
        AgentDiscoveryService discovery = mock(AgentDiscoveryService.class);
        when(discovery.getCachedAgents()).thenReturn(List.of());
        WorkflowDefinition invalid = new WorkflowDefinition(1, "bad", null, 0,
                List.of(), Map.of());
        when(repository.find("commerce", 1)).thenReturn(Optional.of(
                version(invalid, WorkflowVersion.Status.DRAFT, null)));
        WorkflowPublishService service = new WorkflowPublishService(repository,
                new WorkflowValidator(), new WorkflowGraphCompiler(), runtime, discovery,
                new ObjectMapper());

        var result = service.publish("commerce", 1, 7L);

        assertFalse(result.published());
        verify(runtime, never()).validateCompilation(any());
        verify(repository, never()).publish(any(), any(Integer.class), any(), any());
    }

    private static WorkflowDefinition validDefinition() {
        return new WorkflowDefinition(1, "commerce", null, 0, List.of(
                new WorkflowDefinition.WorkflowNode(
                        "lookup", WorkflowDefinition.NodeType.AGENT, "lookup products", "product",
                        "QUERY_PRODUCT", Map.of(), List.of(), List.of(), "has result", false,
                        List.of(), null)), Map.of());
    }

    private static WorkflowVersion version(WorkflowDefinition definition,
                                           WorkflowVersion.Status status, String checksum) {
        return new WorkflowVersion("commerce", 1, status, definition, checksum,
                7L, Instant.now(), status == WorkflowVersion.Status.PUBLISHED ? 7L : null,
                status == WorkflowVersion.Status.PUBLISHED ? Instant.now() : null);
    }
}
