package com.example.smartassistant.router.controller;

import com.example.smartassistant.router.service.core.*;
import com.example.smartassistant.router.service.recovery.WorkflowRecoveryApplicationService;
import com.example.smartassistant.router.service.tool.RoutingToolChecker;
import com.example.smartassistant.common.tracing.DistributedTracingService;
import com.example.smartassistant.router.model.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RouterCancellationRecoveryTest {
    @Test void realRouterServicePreservesCancellationBeforeFailureRedisWrites() {
        var guard = mock(com.example.smartassistant.router.service.guardrail.GuardrailService.class);
        var trace = mock(com.example.smartassistant.common.agent.ExecutionTraceStore.class);
        var budget = mock(com.example.smartassistant.router.governance.budget.BudgetTracker.class);
        var service = new RouterService(null, null, null, null, null, guard, null, null, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "executionTraceStore", trace);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "budgetTracker", budget);
        when(guard.check(anyString())).thenAnswer(invocation -> {
            Thread.currentThread().interrupt();
            throw new RuntimeException("LangGraph4j Router execution failed",
                    new java.util.concurrent.CompletionException(new java.util.concurrent.ExecutionException(
                            new WorkflowCancelledException("cancel-real-service"))));
        });
        var controller = new RouterController(service, mock(DistributedTracingService.class), mock(RoutingToolChecker.class), null);
        try {
            var response = controller.route(42L, RouteRequest.builder()
                    .requestId("cancel-real-service").question("查商品").build()).getData();
            assertEquals(RoutingResult.WorkflowStatus.CANCELLED, response.getWorkflowStatus());
            assertFalse(Thread.currentThread().isInterrupted());
            verify(budget).endSession();
            verifyNoInteractions(trace);
        } finally { Thread.interrupted(); }
    }

    @Test void nestedCancellationReturnsCancelledInsteadOf503AndClearsInterrupt() {
        var service = mock(RouterService.class);
        var request = RouteRequest.builder().requestId("cancel-fixture").question("查商品").build();
        when(service.route(request)).thenAnswer(invocation -> {
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CompletionException(new java.util.concurrent.ExecutionException(
                    new WorkflowCancelledException("cancel-fixture")));
        });
        var controller = new RouterController(service, mock(DistributedTracingService.class), mock(RoutingToolChecker.class), null);
        try {
            var result = controller.route(42L, request).getData();
            assertEquals(RoutingResult.WorkflowStatus.CANCELLED, result.getWorkflowStatus());
            assertFalse(Thread.currentThread().isInterrupted());
            verify(service, never()).recordConversation(any(), any());
        } finally { Thread.interrupted(); }
    }

    @Test void nonCancellationFailuresAreNotMasked() {
        var service = mock(RouterService.class);
        var request = RouteRequest.builder().requestId("timeout-fixture").question("查商品").build();
        var failure = new java.util.concurrent.CompletionException(new java.net.SocketTimeoutException());
        when(service.route(request)).thenThrow(failure);
        var controller = new RouterController(service, mock(DistributedTracingService.class), mock(RoutingToolChecker.class), null);
        assertSame(failure, assertThrows(RuntimeException.class, () -> controller.route(42L, request)));
    }

    @Test void recoveryReportsActualAvailabilityAndDisabledCallsAreExplicit503() {
        ObjectProvider<WorkflowRecoveryApplicationService> provider = mock(ObjectProvider.class);
        var controller = new WorkflowRecoveryController(provider);
        assertEquals(false, controller.capabilities().get("available"));
        assertEquals(503, assertThrows(ResponseStatusException.class, () -> controller.request("request", 42L)).getStatusCode().value());
        assertEquals(503, assertThrows(ResponseStatusException.class, () -> controller.status("recovery", 42L)).getStatusCode().value());
        var service = mock(WorkflowRecoveryApplicationService.class);
        when(provider.getIfAvailable()).thenReturn(service);
        assertEquals(true, controller.capabilities().get("available"));
        controller.request("request", 42L);
        verify(service).requestUserRecovery("request", 42L);
        controller.status("recovery", 42L);
        verify(service).findForUser("recovery", 42L);
    }
}
