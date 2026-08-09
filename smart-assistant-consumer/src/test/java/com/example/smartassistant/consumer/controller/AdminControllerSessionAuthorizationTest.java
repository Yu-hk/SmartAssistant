/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.controller;

import com.example.smartassistant.consumer.service.admin.AdminService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminControllerSessionAuthorizationTest {

    @Mock
    private AdminService adminService;

    private AdminController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminController(adminService, null);
    }

    @Test
    void sessionListShouldUseGatewayIdentityAndFailClosedForNonAdminRole() {
        when(adminService.getSessions(7L, false)).thenReturn(List.of());

        assertEquals(HttpStatus.OK,
                controller.getSessions(7L, "role_admin").getStatusCode());

        verify(adminService).getSessions(7L, false);
    }

    @Test
    void exactAdminRoleShouldRequestFullSessionAccess() {
        when(adminService.getSessions(1L, true)).thenReturn(List.of());

        assertEquals(HttpStatus.OK,
                controller.getSessions(1L, "ROLE_ADMIN").getStatusCode());

        verify(adminService).getSessions(1L, true);
    }

    @Test
    void inaccessibleSessionDetailAndDeleteShouldReturnNotFound() {
        when(adminService.getSessionDetail("session-b", 7L, false)).thenReturn(Optional.empty());
        when(adminService.deleteSession("session-b", 7L, false)).thenReturn(false);

        assertEquals(HttpStatus.NOT_FOUND,
                controller.getSession("session-b", 7L, "ROLE_USER").getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND,
                controller.deleteSession("session-b", 7L, "ROLE_USER").getStatusCode());
    }
}
