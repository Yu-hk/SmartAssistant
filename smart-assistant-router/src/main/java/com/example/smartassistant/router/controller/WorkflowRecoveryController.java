package com.example.smartassistant.router.controller;

import com.example.smartassistant.router.service.recovery.WorkflowRecoveryApplicationService;
import com.example.smartassistant.router.service.recovery.WorkflowRecoveryJob;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
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
public class WorkflowRecoveryController {

    private final ObjectProvider<WorkflowRecoveryApplicationService> recoveryService;

    public WorkflowRecoveryController(ObjectProvider<WorkflowRecoveryApplicationService> recoveryService) {
        this.recoveryService = recoveryService;
    }

    @GetMapping("/capabilities")
    public java.util.Map<String, Boolean> capabilities() {
        return java.util.Map.of("available", recoveryService.getIfAvailable() != null);
    }

    private WorkflowRecoveryApplicationService requireRecovery() {
        WorkflowRecoveryApplicationService service = recoveryService.getIfAvailable();
        if (service == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "回答恢复功能暂未启用");
        return service;
    }

    @PostMapping("/{requestId}/recovery-requests")
    public ResponseEntity<WorkflowRecoveryJob> request(
            @PathVariable String requestId,
            @RequestHeader("X-User-Id") Long authenticatedUserId) {
        return ResponseEntity.accepted()
                .body(requireRecovery().requestUserRecovery(requestId, authenticatedUserId));
    }

    @GetMapping("/recovery-requests/{recoveryId}")
    public ResponseEntity<WorkflowRecoveryJob> status(
            @PathVariable String recoveryId,
            @RequestHeader("X-User-Id") Long authenticatedUserId) {
        return ResponseEntity.ok(requireRecovery().findForUser(recoveryId, authenticatedUserId));
    }
}
