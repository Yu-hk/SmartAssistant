package com.example.smartassistant.controller;

import com.example.smartassistant.service.core.OrderCheckoutHistoryService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OrderCheckoutHistoryControllerTest {
    @Test
    void requiresGatewayVerifiedOwnerAndDisablesCaching() {
        var history = mock(OrderCheckoutHistoryService.class);
        var controller = new OrderCheckoutHistoryController(history);
        assertThrows(ResponseStatusException.class, () -> controller.suggestions(null));
        verifyNoInteractions(history);

        var result = new OrderCheckoutHistoryService.Suggestions(List.of(), false);
        when(history.suggestions(7L)).thenReturn(result);
        var response = controller.suggestions(7L);
        assertEquals(result, response.getBody());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        verify(history).suggestions(7L);
    }
}

