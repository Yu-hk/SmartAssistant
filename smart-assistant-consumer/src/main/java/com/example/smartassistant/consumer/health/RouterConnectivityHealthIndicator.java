/*
 * Copyright (c) 2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.health;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestOperations;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.Map;
import java.util.Objects;

/**
 * Readiness dependency check for the synchronous Consumer -> Router boundary.
 *
 * <p>This indicator is deliberately excluded from liveness. A temporary Router
 * outage must stop new traffic from reaching Consumer, but must not cause the
 * Consumer process to be restarted in a loop.</p>
 */
@Component("routerConnectivityHealthIndicator")
public class RouterConnectivityHealthIndicator implements HealthIndicator {

    private final RestOperations restOperations;
    private final URI readinessUri;

    @Autowired
    public RouterConnectivityHealthIndicator(
            @Value("${router.service.url:http://localhost:8083}") String routerServiceUrl,
            @Value("${consumer.readiness.router.connect-timeout-ms:500}") int connectTimeoutMs,
            @Value("${consumer.readiness.router.read-timeout-ms:1000}") int readTimeoutMs) {
        this(createRestTemplate(connectTimeoutMs, readTimeoutMs), readinessUri(routerServiceUrl));
    }

    RouterConnectivityHealthIndicator(RestOperations restOperations, URI readinessUri) {
        this.restOperations = Objects.requireNonNull(restOperations, "restOperations");
        this.readinessUri = Objects.requireNonNull(readinessUri, "readinessUri");
    }

    @Override
    public Health health() {
        long startedAt = System.nanoTime();
        try {
            ResponseEntity<Map> response = restOperations.getForEntity(readinessUri, Map.class);
            String routerStatus = response.getBody() != null
                    ? Objects.toString(response.getBody().get("status"), "UNKNOWN")
                    : "UNKNOWN";
            long latencyMs = elapsedMillis(startedAt);
            if (response.getStatusCode().is2xxSuccessful() && "UP".equalsIgnoreCase(routerStatus)) {
                return Health.up()
                        .withDetail("routerStatus", routerStatus)
                        .withDetail("latencyMs", latencyMs)
                        .build();
            }
            return Health.down()
                    .withDetail("routerStatus", routerStatus)
                    .withDetail("httpStatus", response.getStatusCode().value())
                    .withDetail("latencyMs", latencyMs)
                    .build();
        } catch (RuntimeException error) {
            return Health.down()
                    .withDetail("reason", error.getClass().getSimpleName())
                    .withDetail("latencyMs", elapsedMillis(startedAt))
                    .build();
        }
    }

    private static RestTemplate createRestTemplate(int connectTimeoutMs, int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Math.max(100, connectTimeoutMs));
        factory.setReadTimeout(Math.max(100, readTimeoutMs));
        return new RestTemplate(factory);
    }

    private static URI readinessUri(String routerServiceUrl) {
        String baseUrl = Objects.requireNonNull(routerServiceUrl, "routerServiceUrl").strip();
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return URI.create(baseUrl + "/actuator/health/readiness");
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }
}
