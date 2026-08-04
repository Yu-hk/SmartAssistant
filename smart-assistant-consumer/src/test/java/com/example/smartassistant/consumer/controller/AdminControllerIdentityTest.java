package com.example.smartassistant.consumer.controller;

import com.example.smartassistant.consumer.service.admin.AdminService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminControllerIdentityTest {

    private final AdminService adminService = mock(AdminService.class);
    private final AdminController controller = new AdminController(adminService, null);

    @Test
    void normalUserOnlyRequestsOwnSessions() {
        when(adminService.getSessions(42L, false)).thenReturn(List.of());

        assertEquals(200, controller.getSessions("42", "ROLE_USER").getStatusCode().value());
        verify(adminService).getSessions(42L, false);
    }

    @Test
    void administratorCanRequestAllSessions() {
        when(adminService.getSessions(7L, true)).thenReturn(List.of());

        assertEquals(200, controller.getSessions("7", "ROLE_ADMIN").getStatusCode().value());
        verify(adminService).getSessions(7L, true);
    }

    @Test
    void closeSessionUsesAuthenticatedUserId() {
        when(adminService.closeSession("session-1", 42L, false)).thenReturn(true);

        assertEquals(200, controller.closeSession(
                "session-1", "42", "ROLE_USER").getStatusCode().value());
        verify(adminService).closeSession("session-1", 42L, false);
    }

    @Test
    void rejectsMissingIdentityAndNonAdminStatsAccess() {
        assertThrows(ResponseStatusException.class,
                () -> controller.getSessions(null, "ROLE_USER"));
        assertThrows(ResponseStatusException.class,
                () -> controller.getStats("42", "ROLE_USER"));
    }
}
