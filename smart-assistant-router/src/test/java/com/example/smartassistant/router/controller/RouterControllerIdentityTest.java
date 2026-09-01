package com.example.smartassistant.router.controller;

import com.example.smartassistant.common.tracing.DistributedTracingService;
import com.example.smartassistant.router.model.RouteRequest;
import com.example.smartassistant.router.model.RoutingResult;
import com.example.smartassistant.router.service.core.RouterService;
import com.example.smartassistant.router.service.core.WorkflowCancellationService;
import com.example.smartassistant.router.service.tool.RoutingToolChecker;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RouterControllerIdentityTest {

    @Test
    void bindsGatewayIdentityWhenBodyOmitsUserId() {
        RouterService routerService = mock(RouterService.class);
        RouteRequest request = RouteRequest.builder().question("hello").requestId("identity-ok").build();
        when(routerService.route(request)).thenReturn(RoutingResult.builder()
                .agentName("general_agent").result("ok").confidence(0.9).build());

        controller(routerService).route(42L, request);

        assertEquals(42L, request.getUserId());
        verify(routerService).route(request);
        verify(routerService).recordConversation(eq(request), any(RoutingResult.class));
    }

    @Test
    void rejectsBodyIdentityThatDoesNotMatchGateway() {
        RouterService routerService = mock(RouterService.class);
        RouteRequest request = RouteRequest.builder().userId(7L).question("hello").build();

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> controller(routerService).route(42L, request));

        assertEquals(403, error.getStatusCode().value());
        verifyNoInteractions(routerService);
    }

    @Test
    void rejectsMissingGatewayIdentity() {
        RouterService routerService = mock(RouterService.class);
        RouteRequest request = RouteRequest.builder().userId(7L).question("hello").build();

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> controller(routerService).route(null, request));

        assertEquals(401, error.getStatusCode().value());
        verifyNoInteractions(routerService);
    }

    @Test
    @SuppressWarnings("unchecked")
    void returnsCancelledWithoutExecutingRouterWhenCancelWinsRegistrationRace() {
        RouterService routerService = mock(RouterService.class);
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        WorkflowCancellationService cancellationService = new WorkflowCancellationService(provider);
        cancellationService.requestCancellation("cancel-before-route", 42L);
        RouterController controller = controller(routerService);
        controller.setCancellationService(cancellationService);

        var response = controller.route(42L, RouteRequest.builder()
                .question("查询商品")
                .requestId("cancel-before-route")
                .build());

        assertEquals(RoutingResult.WorkflowStatus.CANCELLED,
                response.getData().getWorkflowStatus());
        verifyNoInteractions(routerService);
    }

    private RouterController controller(RouterService routerService) {
        return new RouterController(
                routerService,
                mock(DistributedTracingService.class),
                mock(RoutingToolChecker.class),
                null);
    }
}
