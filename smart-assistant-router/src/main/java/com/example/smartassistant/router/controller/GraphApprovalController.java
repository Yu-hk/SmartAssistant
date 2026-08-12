package com.example.smartassistant.router.controller;

import com.example.smartassistant.common.response.ApiResponse;
import com.example.smartassistant.router.model.SubTaskResult;
import com.example.smartassistant.router.service.core.LangGraphRouteExecutionService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Authenticated entry point for resuming a LangGraph4j human-approval checkpoint. */
@RestController
@RequestMapping("/api/router/graph")
public class GraphApprovalController {

    private final LangGraphRouteExecutionService graphExecutionService;

    public GraphApprovalController(LangGraphRouteExecutionService graphExecutionService) {
        this.graphExecutionService = graphExecutionService;
    }

    @PostMapping("/{requestId}/approve")
    public ApiResponse<List<SubTaskResult>> approve(
            @PathVariable String requestId,
            @RequestHeader("X-User-Id") Long authenticatedUserId) {
        return ApiResponse.success(
                graphExecutionService.resumeApproved(authenticatedUserId, requestId));
    }
}
