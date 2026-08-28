package com.example.smartassistant.router.controller;

import com.example.smartassistant.router.service.recovery.WorkflowRecoveryRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** Stable HTTP mapping for expected asynchronous recovery rejections. */
@RestControllerAdvice(assignableTypes = {
        WorkflowRecoveryController.class,
        WorkflowRecoveryAdminController.class,
        GraphApprovalController.class
})
public class WorkflowRecoveryExceptionHandler {

    @ExceptionHandler(WorkflowRecoveryRejectedException.class)
    ResponseEntity<Map<String, String>> rejected(WorkflowRecoveryRejectedException error) {
        HttpStatus status = switch (error.reason()) {
            case CHECKPOINT_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case APPROVAL_REQUIRED, ACTIVE_EXECUTION, CHECKPOINT_VERSION_CONFLICT -> HttpStatus.CONFLICT;
        };
        return ResponseEntity.status(status).body(Map.of(
                "code", error.reason().name(),
                "error", error.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("error", error.getMessage()));
    }
}
