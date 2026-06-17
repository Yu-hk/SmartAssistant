package com.example.smartassistant.router.controller;

import com.example.smartassistant.router.service.core.GraphCheckpointPoc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * POC 验证接口 — Graph 编排 (LangGraph4j) + Checkpoint PG + DeepSeek 流式链路。
 * <p>
 * 提供手动触发端点和状态查询能力。
 */
@RestController
@RequestMapping("/api/poc")
public class GraphPocController {

    private static final Logger log = LoggerFactory.getLogger(GraphPocController.class);

    private final GraphCheckpointPoc graphCheckpointPoc;

    public GraphPocController(GraphCheckpointPoc graphCheckpointPoc) {
        this.graphCheckpointPoc = graphCheckpointPoc;
    }

    /**
     * 触发 Graph 编排 POC：执行 StateGraph → 写入 PG Checkpoint → 返回执行摘要。
     */
    @GetMapping("/graph")
    public ResponseEntity<Map<String, Object>> runGraphPoc() {
        log.info("[GraphPocAPI] 触发 Graph POC...");
        try {
            Map<String, Object> result = graphCheckpointPoc.runPoc();
            log.info("[GraphPocAPI] POC 完成, status={}", result.get("status"));
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("[GraphPocAPI] POC 异常", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("status", "error", "error", e.getMessage()));
        }
    }
}
