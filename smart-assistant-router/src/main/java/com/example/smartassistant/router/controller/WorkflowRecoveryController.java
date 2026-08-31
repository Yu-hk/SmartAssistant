package com.example.smartassistant.router.controller;

import com.example.smartassistant.router.service.recovery.WorkflowRecoveryApplicationService;
import com.example.smartassistant.router.service.recovery.WorkflowRecoveryJob;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated user entry point for asynchronously recovering an owned workflow. */
@RestController
@RequestMapping("/api/router/workflows")
@ConditionalOnProperty(prefix = "router.graph.recovery", name = "enabled", havingValue = "true")
public class WorkflowRecoveryController {

    private final WorkflowRecoveryApplicationService recoveryService;

    public WorkflowRecoveryController(WorkflowRecoveryApplicationService recoveryService) {
        this.recoveryService = recoveryService;
    }

    @PostMapping("/{requestId}/recovery-requests")
    public ResponseEntity<WorkflowRecoveryJob> request(
            @PathVariable String requestId,
            @RequestHeader("X-User-Id") Long authenticatedUserId) {
        return ResponseEntity.accepted()
                .body(recoveryService.requestUserRecovery(requestId, authenticatedUserId));
    }

    @GetMapping("/recovery-requests/{recoveryId}")
    public ResponseEntity<WorkflowRecoveryJob> status(
            @PathVariable String recoveryId,
            @RequestHeader("X-User-Id") Long authenticatedUserId) {
        return ResponseEntity.ok(recoveryService.findForUser(recoveryId, authenticatedUserId));
    }
}
