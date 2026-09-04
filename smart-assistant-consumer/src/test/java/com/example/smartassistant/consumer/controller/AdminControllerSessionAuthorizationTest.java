/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 */

package com.example.smartassistant.consumer.controller;

import com.example.smartassistant.consumer.service.admin.AdminService;
import com.example.smartassistant.consumer.service.session.ConversationGateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminControllerSessionAuthorizationTest {

    @Mock
    private AdminService adminService;

    @Mock
    private ConversationGateService conversationGateService;

    private AdminController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminController(adminService);
        ReflectionTestUtils.setField(controller, "conversationGateService", conversationGateService);
    }

    @Test
    void everyAdminRouteFailsClosedUnlessRoleIsExact() {
        assertEquals(HttpStatus.FORBIDDEN, controller.getAdminStats(null).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN,
                controller.getAdminSessions("role_admin", null, null, null, null, 0, 20)
                        .getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN,
                controller.getAdminFaqs("ROLE_USER").getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN,
                controller.importAdminFaqs(
                        "ROLE_USER",
                        new AdminController.FaqImportRequest(
                                "knowledge.json", "json", false,
                                List.of(Map.of("question", "Q", "answer", "A"))))
                        .getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN,
                controller.getCosts("role_admin").getStatusCode());

        verifyNoInteractions(adminService);
    }

    @Test
    void exactAdminRoleCanUseGlobalPagedSessionContract() {
        AdminService.SessionPage page = new AdminService.SessionPage(List.of(), 0, 0, 20);
        when(adminService.searchAdminSessions("weather", 7L, "SUCCESS", "weather", 0, 20))
                .thenReturn(page);

        assertEquals(HttpStatus.OK,
                controller.getAdminSessions(
                        "ROLE_ADMIN", "weather", 7L, "SUCCESS", "weather", 0, 20)
                        .getStatusCode());
    }

    @Test
    void ordinarySessionsRemainUserScopedEvenForAnAdministrator() {
        when(adminService.getSessions(7L)).thenReturn(List.of());

        assertEquals(HttpStatus.OK,
                controller.getSessions(7L, "ROLE_ADMIN").getStatusCode());

        verify(adminService).getSessions(7L);
    }

    @Test
    void adminDetailUsesCompoundSessionIdentity() {
        when(adminService.getAdminSessionDetail("shared-session", 7L))
                .thenReturn(Optional.empty());

        assertEquals(HttpStatus.NOT_FOUND,
                controller.getAdminSession("shared-session", "ROLE_ADMIN", 7L).getStatusCode());

        verify(adminService).getAdminSessionDetail("shared-session", 7L);
    }

    @Test
    void satisfactionAcceptsExistingScorePayload() {
        AdminService.SatisfactionResult saved =
                new AdminService.SatisfactionResult("session-a", 5, "helpful");
        when(adminService.saveSatisfaction("session-a", 7L, 5, "helpful"))
                .thenReturn(Optional.of(saved));

        assertEquals(HttpStatus.OK,
                controller.saveSatisfaction(
                        "session-a", 7L, Map.of("score", 5, "comment", "helpful"))
                        .getStatusCode());

        verify(adminService).saveSatisfaction("session-a", 7L, 5, "helpful");
    }

    @Test
    void satisfactionRejectsInvalidRatingBeforeWriting() {
        assertEquals(HttpStatus.BAD_REQUEST,
                controller.saveSatisfaction("session-a", 7L, Map.of("score", "five"))
                        .getStatusCode());
        verifyNoInteractions(adminService);
    }

    @Test
    void authenticatedCustomerFaqHitDoesNotRequireAdministratorRole() {
        when(adminService.hitFaq("17")).thenReturn(true);

        assertEquals(HttpStatus.OK, controller.hitFaq("17", 7L).getStatusCode());

        verify(adminService).hitFaq("17");
    }

    @Test
    void exactAdminRoleCanImportExternalKnowledge() {
        AdminController.FaqImportRequest request = new AdminController.FaqImportRequest(
                "knowledge.json", "json", true,
                List.of(Map.of("question", "Q", "answer", "A")));
        AdminService.FaqImportResult result = new AdminService.FaqImportResult(1, 1, 0, 0);
        when(adminService.importFaqs(
                request.sourceName(), request.sourceType(), request.overwrite(), request.items()))
                .thenReturn(result);

        assertEquals(HttpStatus.OK,
                controller.importAdminFaqs("ROLE_ADMIN", request).getStatusCode());
        verify(adminService).importFaqs(
                request.sourceName(), request.sourceType(), request.overwrite(), request.items());
    }

    @Test
    void runningConversationCannotBeClosedUntilGenerationStops() {
        when(conversationGateService.close("7", "session-a"))
                .thenReturn(new ConversationGateService.CloseDecision(
                        ConversationGateService.CloseStatus.BUSY));

        assertEquals(HttpStatus.CONFLICT,
                controller.closeSession("session-a", 7L).getStatusCode());

        verify(adminService, never()).closeSession("session-a", 7L);
    }

    @Test
    void suspendedConversationRequiresAnExplicitConflictFreeResume() {
        when(conversationGateService.resume("7", "session-b"))
                .thenReturn(new ConversationGateService.ResumeDecision(
                        ConversationGateService.ResumeStatus.CONFLICT, "session-a"));

        assertEquals(HttpStatus.CONFLICT,
                controller.resumeSession("session-b", 7L).getStatusCode());
    }
}
