package com.example.smartassistant.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final HttpServletRequest request = mock(HttpServletRequest.class);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(handler, "moduleName", "router-service");
        when(request.getRequestURI()).thenReturn("/api/router/route");
    }

    @Test
    void missingIdentityHeaderIsUnauthorizedInsteadOfInternalError() {
        var response = handler.handleMissingRequestHeader(
                new MissingRequestHeaderException("X-User-Id", null), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(401);
        assertThat(response.getBody().getError().getType()).isEqualTo("ROUTER-SERVICE_002");
    }

    @Test
    void preservesExplicitAuthorizationStatus() {
        var response = handler.handleResponseStatusException(
                new ResponseStatusException(HttpStatus.FORBIDDEN, "identity mismatch"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(403);
        assertThat(response.getBody().getError().getType()).isEqualTo("ROUTER-SERVICE_002");
    }
}
