package com.example.smartassistant.router.controller;

import com.example.smartassistant.router.service.recovery.WorkflowRecoveryApplicationService;
import com.example.smartassistant.router.service.recovery.WorkflowRecoveryJob;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Administrator recovery API. Gateway and application service both enforce the admin role. */
@RestController
@RequestMapping("/api/admin/workflows")
@ConditionalOnProperty(prefix = "router.graph.recovery", name = "enabled", havingValue = "true")
public class WorkflowRecoveryAdminController {

    private final WorkflowRecoveryApplicationService recoveryService;

    public WorkflowRecoveryAdminController(WorkflowRecoveryApplicationService recoveryService) {
        this.recoveryService = recoveryService;
    }

    @PostMapping("/{requestId}/recovery-requests")
    public ResponseEntity<WorkflowRecoveryJob> request(
            @PathVariable String requestId,
            @RequestHeader("X-User-Id") Long actorId,
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestBody(required = false) AdminRecoveryRequest request) {
        AdminRecoveryRequest body = request != null ? request : new AdminRecoveryRequest(null, null);
        return ResponseEntity.accepted().body(recoveryService.requestAdminRecovery(
                requestId, actorId, role, body.reason(), body.expectedCheckpointVersion()));
    }

    @GetMapping("/recoveries/{recoveryId}")
    public ResponseEntity<WorkflowRecoveryJob> status(
            @PathVariable String recoveryId,
            @RequestHeader(value = "X-User-Role", required = false) String role) {
        return ResponseEntity.ok(recoveryService.findForAdmin(recoveryId, role));
    }

    public record AdminRecoveryRequest(String reason, Long expectedCheckpointVersion) {
    }
}
