package com.example.smartassistant.router.controller;

import com.example.smartassistant.router.workflow.WorkflowDefinition;
import com.example.smartassistant.router.workflow.WorkflowPublishService;
import com.example.smartassistant.router.workflow.WorkflowValidationResult;
import com.example.smartassistant.router.workflow.WorkflowVersion;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Administrative API for immutable workflow publication. Gateway and service both enforce admin role. */
@RestController
@RequestMapping("/api/admin/workflows")
public class WorkflowAdminController {

    private static final String ADMIN_ROLE = "ROLE_ADMIN";
    private final WorkflowPublishService publishService;

    public WorkflowAdminController(WorkflowPublishService publishService) {
        this.publishService = publishService;
    }

    @PostMapping("/{workflowKey}/drafts")
    public ResponseEntity<?> createDraft(
            @PathVariable String workflowKey,
            @RequestHeader("X-User-Id") Long actorId,
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestBody WorkflowDefinition definition) {
        if (!isAdmin(role)) return forbidden();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(publishService.createDraft(workflowKey, definition, actorId));
    }

    @PostMapping("/validate")
    public ResponseEntity<?> validate(
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestBody WorkflowDefinition definition) {
        if (!isAdmin(role)) return forbidden();
        WorkflowValidationResult result = publishService.validate(definition);
        return ResponseEntity.status(result.valid() ? HttpStatus.OK : HttpStatus.UNPROCESSABLE_ENTITY)
                .body(result);
    }

    @PostMapping("/{workflowKey}/versions/{version}/publish")
    public ResponseEntity<?> publish(
            @PathVariable String workflowKey,
            @PathVariable int version,
            @RequestHeader("X-User-Id") Long actorId,
            @RequestHeader(value = "X-User-Role", required = false) String role) {
        if (!isAdmin(role)) return forbidden();
        WorkflowPublishService.PublishResult result = publishService.publish(workflowKey, version, actorId);
        return ResponseEntity.status(result.published() ? HttpStatus.OK : HttpStatus.UNPROCESSABLE_ENTITY)
                .body(result);
    }

    @GetMapping("/{workflowKey}/versions")
    public ResponseEntity<?> list(
            @PathVariable String workflowKey,
            @RequestHeader(value = "X-User-Role", required = false) String role) {
        if (!isAdmin(role)) return forbidden();
        List<WorkflowVersion> versions = publishService.list(workflowKey);
        return ResponseEntity.ok(versions);
    }

    @GetMapping("/{workflowKey}/versions/{version}")
    public ResponseEntity<?> get(
            @PathVariable String workflowKey,
            @PathVariable int version,
            @RequestHeader(value = "X-User-Role", required = false) String role) {
        if (!isAdmin(role)) return forbidden();
        return publishService.find(workflowKey, version)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("error", error.getMessage()));
    }

    @ExceptionHandler(WorkflowPublishService.WorkflowNotFoundException.class)
    ResponseEntity<Map<String, String>> notFound(RuntimeException error) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", error.getMessage()));
    }

    private static boolean isAdmin(String role) {
        return ADMIN_ROLE.equalsIgnoreCase(role);
    }

    private static ResponseEntity<Map<String, String>> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "Administrator privileges are required"));
    }
}
