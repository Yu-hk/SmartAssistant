package com.example.smartassistant.router.controller;

import com.example.smartassistant.common.response.ApiResponse;
import com.example.smartassistant.router.model.SubTaskResult;
import com.example.smartassistant.router.service.core.LangGraphRouteExecutionService;
import com.example.smartassistant.router.service.recovery.WorkflowRecoveryApplicationService;
import com.example.smartassistant.router.service.recovery.WorkflowRecoveryJob;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Authenticated entry points for resuming LangGraph4j checkpoints. */
@RestController
@RequestMapping("/api/router/graph")
public class GraphApprovalController {

    private final LangGraphRouteExecutionService graphExecutionService;
    private final ObjectProvider<WorkflowRecoveryApplicationService> recoveryServiceProvider;

    public GraphApprovalController(LangGraphRouteExecutionService graphExecutionService,
                                   ObjectProvider<WorkflowRecoveryApplicationService> recoveryServiceProvider) {
        this.graphExecutionService = graphExecutionService;
        this.recoveryServiceProvider = recoveryServiceProvider;
    }

    @PostMapping("/{requestId}/approve")
    public ApiResponse<List<SubTaskResult>> approve(
            @PathVariable String requestId,
            @RequestHeader("X-User-Id") Long authenticatedUserId) {
        return ApiResponse.success(
                graphExecutionService.resumeApproved(authenticatedUserId, requestId));
    }

    @PostMapping("/{requestId}/resume")
    public ResponseEntity<WorkflowRecoveryJob> resume(
            @PathVariable String requestId,
            @RequestHeader("X-User-Id") Long authenticatedUserId) {
        return ResponseEntity.accepted()
                .body(recoveryServiceProvider.getObject()
                        .requestUserRecovery(requestId, authenticatedUserId));
    }
}
