package com.example.smartassistant.router.controller;

import com.example.smartassistant.router.service.trace.AgentFlowTraceStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Administrator-only API exposing the real Agent/LangGraph execution topology. */
@RestController
@RequestMapping("/api/admin/execution-dag")
public class ExecutionDagController {

    private static final String ADMIN_ROLE = "ROLE_ADMIN";
    private final AgentFlowTraceStore traceStore;

    public ExecutionDagController(AgentFlowTraceStore traceStore) {
        this.traceStore = traceStore;
    }

    @GetMapping("/{requestId}")
    public ResponseEntity<?> getDag(
            @PathVariable String requestId,
            @RequestHeader(value = "X-User-Role", required = false) String role) {
        if (!ADMIN_ROLE.equalsIgnoreCase(role)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Administrator privileges are required"));
        }
        return traceStore.get(requestId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.ok(Map.of(
                        "requestId", requestId,
                        "status", "unavailable",
                        "nodes", java.util.List.of(),
                        "edges", java.util.List.of(),
                        "message", "该会话暂无 Agent 执行链路，可能是旧数据或未进入 Agent 流程")));
    }
}
