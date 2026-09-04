/*
 * Copyright (c) 2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.health;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestOperations;

import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RouterConnectivityHealthIndicatorTest {

    private static final URI ROUTER_READINESS =
            URI.create("http://smart-router:8083/actuator/health/readiness");

    @Test
    void reportsUpOnlyWhenRouterReadinessIsUp() {
        RestOperations restOperations = mock(RestOperations.class);
        when(restOperations.getForEntity(ROUTER_READINESS, Map.class))
                .thenReturn(ResponseEntity.ok(Map.of("status", "UP")));

        var indicator = new RouterConnectivityHealthIndicator(restOperations, ROUTER_READINESS);

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void reportsDownWhenRouterIsNotReady() {
        RestOperations restOperations = mock(RestOperations.class);
        when(restOperations.getForEntity(ROUTER_READINESS, Map.class))
                .thenReturn(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(Map.of("status", "DOWN")));

        var indicator = new RouterConnectivityHealthIndicator(restOperations, ROUTER_READINESS);

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(indicator.health().getDetails()).containsEntry("routerStatus", "DOWN");
    }

    @Test
    void reportsDownInsteadOfThrowingWhenRouterCannotBeResolved() {
        RestOperations restOperations = mock(RestOperations.class);
        when(restOperations.getForEntity(ROUTER_READINESS, Map.class))
                .thenThrow(new ResourceAccessException("DNS resolution failed"));

        var indicator = new RouterConnectivityHealthIndicator(restOperations, ROUTER_READINESS);

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(indicator.health().getDetails())
                .containsEntry("reason", "ResourceAccessException");
    }
}
