/*
 * Copyright (c) 2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 执行 DAG 可视化 API
 * <p>
 * 提供任务执行有向无环图的查询接口，支持前端 DAG 渲染。
 * 数据源为 Redis 中的 SSE 事件流和完整决策记录。
 * </p>
 *
 * <pre>
 * GET /api/admin/execution-dag/{requestId} → 结构化 DAG 数据（JSON）
 * GET /api/admin/execution-dag/{requestId}/view → 可视化 HTML 页面
 * </pre>
 */
@RestController
@RequestMapping("/api/admin/execution-dag")
public class ExecutionDagController {

    private static final Logger log = LoggerFactory.getLogger(ExecutionDagController.class);

    private static final String SSE_EVENTS_KEY_PREFIX = "routing:sse:events:";
    private static final String FULL_DECISION_KEY_PREFIX = "a2a:route:full-decision:";

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * DAG 节点数据。
     */
    public record DagNode(
            String id,
            String label,
            String agent,
            String status,       // running / completed / failed / skipped / replan
            String summary,
            long timestamp) {}

    /**
     * DAG 边数据。
     */
    public record DagEdge(
            String from,
            String to,
            String label) {}

    /**
     * DAG 执行结果。
     */
    public record DagResult(
            String requestId,
            String question,
            List<DagNode> nodes,
            List<DagEdge> edges,
            String finalAgent,
            String finalIntent,
            boolean completed) {}

    /**
     * 获取结构化 DAG 执行数据（JSON）。
     */
    @GetMapping("/{requestId}")
    public ResponseEntity<?> getDag(@PathVariable String requestId) {
        if (redisTemplate == null) {
            return ResponseEntity.ok(Map.of("error", "Redis 未配置"));
        }

        try {
            // 1. 读取 SSE 事件流 → 提取节点事件
            String eventsKey = SSE_EVENTS_KEY_PREFIX + requestId;
            List<String> events = redisTemplate.opsForList().range(eventsKey, 0, -1);
            if (events == null || events.isEmpty()) {
                return ResponseEntity.ok(Map.of("requestId", requestId, "nodes", List.of(), "message", "无执行数据"));
            }

            // 2. 解析事件 → 构建节点列表
            Map<String, DagNode> nodeMap = new LinkedHashMap<>();
            List<DagEdge> edges = new ArrayList<>();
            List<String> nodeOrder = new ArrayList<>();

            for (String eventJson : events) {
                try {
                    Map<String, Object> event = objectMapper.readValue(eventJson,
                            new TypeReference<Map<String, Object>>() {});
                    String type = (String) event.getOrDefault("type", "");
                    String content = (String) event.getOrDefault("content", "");
                    String agent = (String) event.getOrDefault("agent", "");

                    // 从 content 提取节点描述
                    String nodeId = extractNodeId(content);
                    if (nodeId == null) continue;

                    String status = switch (type) {
                        case "node_started" -> "running";
                        case "node_completed" -> "completed";
                        case "node_failed" -> "failed";
                        case "node_skipped" -> "skipped";
                        case "node_replan" -> "replan";
                        default -> "unknown";
                    };

                    nodeMap.put(nodeId, new DagNode(
                            nodeId, extractNodeLabel(content), agent,
                            status, truncate(content, 100), System.currentTimeMillis()));
                    if (!nodeOrder.contains(nodeId)) {
                        nodeOrder.add(nodeId);
                    }
                } catch (Exception ignored) {}
            }

            // 3. 读取完整决策 → 提取 finalAgent 和 question
            String decisionKey = FULL_DECISION_KEY_PREFIX + requestId;
            String decisionJson = redisTemplate.opsForValue().get(decisionKey);
            String finalAgent = "";
            String question = "";

            if (decisionJson != null) {
                try {
                    Map<String, Object> decision = objectMapper.readValue(decisionJson,
                            new TypeReference<Map<String, Object>>() {});
                    finalAgent = (String) decision.getOrDefault("agentName", "");
                    question = (String) decision.getOrDefault("result", "");
                    if (question.length() > 100) question = question.substring(0, 100) + "...";
                } catch (Exception ignored) {}
            }

            // 4. 按顺序输出节点，构建简单的链式边
            List<DagNode> orderedNodes = nodeOrder.stream()
                    .map(nodeMap::get)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            for (int i = 1; i < orderedNodes.size(); i++) {
                edges.add(new DagEdge(
                        orderedNodes.get(i - 1).id(),
                        orderedNodes.get(i).id(),
                        "依赖"));
            }

            boolean completed = orderedNodes.stream().anyMatch(n -> "completed".equals(n.status()));

            return ResponseEntity.ok(new DagResult(
                    requestId, question, orderedNodes, edges,
                    finalAgent, "", completed));

        } catch (Exception e) {
            log.warn("[DAG] 获取执行数据失败: {}", e.getMessage());
            return ResponseEntity.ok(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 可视化 HTML 页面。
     */
    @GetMapping(value = "/{requestId}/view", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> viewDag(@PathVariable String requestId) {
        String html = """
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head><meta charset="UTF-8"><title>执行 DAG - %s</title>
                <style>
                  * { margin:0; padding:0; box-sizing:border-box; }
                  body { font-family: -apple-system, sans-serif; background: #f5f5f5; padding: 20px; }
                  h1 { font-size: 18px; color: #333; margin-bottom: 16px; }
                  .dag-container { background: #fff; border-radius: 8px; padding: 24px; box-shadow: 0 2px 8px rgba(0,0,0,0.1); }
                  .node-list { display: flex; flex-direction: column; gap: 12px; }
                  .node { display: flex; align-items: center; padding: 12px 16px; border-radius: 6px; border: 1px solid #e0e0e0; gap: 12px; }
                  .node .status { width: 10px; height: 10px; border-radius: 50%; flex-shrink: 0; }
                  .status-completed { background: #52c41a; }
                  .status-running { background: #1890ff; animation: pulse 1.5s infinite; }
                  .status-failed { background: #f5222d; }
                  .status-skipped { background: #faad14; }
                  .status-replan { background: #722ed1; }
                  @keyframes pulse { 0%,100%{opacity:1} 50%{opacity:0.4} }
                  .node .info { flex: 1; }
                  .node .info .label { font-weight: 600; color: #333; font-size: 14px; }
                  .node .info .agent { color: #666; font-size: 12px; margin-top: 2px; }
                  .node .info .summary { color: #999; font-size: 11px; margin-top: 4px; }
                  .connector { display: flex; align-items: center; padding-left: 24px; color: #bbb; font-size: 12px; }
                  .connector::before { content: '\\2193'; margin-right: 4px; }
                  .summary { margin-top: 20px; padding: 12px; background: #f6f8fa; border-radius: 6px; color: #666; font-size: 13px; }
                  .summary strong { color: #333; }
                  .loading { text-align: center; padding: 40px; color: #999; }
                  .error { text-align: center; padding: 40px; color: #f5222d; }
                </style>
                </head>
                <body>
                <h1>\\u6267\\u884c DAG \\u53ef\\u89c6\\u5316</h1>
                <div class="dag-container" id="dag"><div class="loading">\\u52a0\\u8f7d\\u4e2d...</div></div>
                <script>
                fetch('/api/admin/execution-dag/%s')
                  .then(r => r.json())
                  .then(data => {
                    if (data.error) { document.getElementById('dag').innerHTML = '<div class="error">' + data.error + '</div>'; return; }
                    if (!data.nodes || data.nodes.length === 0) { document.getElementById('dag').innerHTML = '<div class="error">\\u65e0\\u6267\\u884c\\u6570\\u636e</div>'; return; }
                    let html = '<div class="node-list">';
                    data.nodes.forEach((n, i) => {
                      if (i > 0) html += '<div class="connector"></div>';
                      html += '<div class="node"><div class="status status-' + n.status + '"></div>';
                      html += '<div class="info"><div class="label">' + (n.label || n.id) + '</div>';
                      if (n.agent) html += '<div class="agent">' + n.agent + '</div>';
                      if (n.summary) html += '<div class="summary">' + n.summary + '</div>';
                      html += '</div></div>';
                    });
                    html += '</div>';
                    html += '<div class="summary"><strong>\\u6267\\u884c\\u7ed3\\u679c</strong>: ';
                    html += data.completed ? '\\u2713 \\u5df2\\u5b8c\\u6210' : '\\u23f3 \\u672a\\u5b8c\\u6210';
                    if (data.finalAgent) html += ' | \\u62d2\\u5f80 Agent: ' + data.finalAgent;
                    html += '</div>';
                    document.getElementById('dag').innerHTML = html;
                  })
                  .catch(e => { document.getElementById('dag').innerHTML = '<div class="error">\\u52a0\\u8f7d\\u5931\\u8d25: ' + e.message + '</div>'; });
                </script>
                </body>
                </html>
                """.formatted(requestId, requestId);

        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
    }

    private String extractNodeId(String content) {
        if (content == null) return null;
        int start = content.indexOf('[');
        int end = content.indexOf(']', start + 1);
        if (start >= 0 && end > start) {
            return content.substring(start + 1, end).trim();
        }
        // fallback: 用 content 前 8 字符的 hash
        return "node_" + Math.abs(content.hashCode() % 10000);
    }

    private String extractNodeLabel(String content) {
        if (content == null) return "";
        int start = content.indexOf('[');
        int end = content.indexOf(']', start + 1);
        if (start >= 0 && end > start) {
            return content.substring(start + 1, end).trim();
        }
        return truncate(content, 40);
    }

    private static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) + "..." : (s != null ? s : "");
    }
}
