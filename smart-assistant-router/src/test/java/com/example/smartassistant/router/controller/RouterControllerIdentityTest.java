package com.example.smartassistant.router.controller;

import com.example.smartassistant.common.tracing.DistributedTracingService;
import com.example.smartassistant.router.model.RouteRequest;
import com.example.smartassistant.router.model.RoutingResult;
import com.example.smartassistant.router.service.core.RouterService;
import com.example.smartassistant.router.service.tool.RoutingToolChecker;
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

    private RouterController controller(RouterService routerService) {
        return new RouterController(
                routerService,
                mock(DistributedTracingService.class),
                mock(RoutingToolChecker.class),
                null);
    }
}
