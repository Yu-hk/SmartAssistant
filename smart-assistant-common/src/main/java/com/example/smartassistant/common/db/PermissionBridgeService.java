/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

/**
 * 权限请求桥接服务——连接后端工具审批与前端权限弹窗。
 *
 * <p>工作流程：
 * <ol>
 *   <li>Agent 调用 HIGH 风险工具时，创建权限请求并存入 Redis</li>
 *   <li>Consumer 将权限请求作为 SSE 事件推送到前端</li>
 *   <li>用户通过 {@link #respondToRequest(String, boolean)} 响应</li>
 *   <li>Agent 轮询 Redis 获取结果后继续或取消执行</li>
 * </ol>
 */
public class PermissionBridgeService {

    private static final Logger log = LoggerFactory.getLogger(PermissionBridgeService.class);

    /** Redis key 前缀 */
    private static final String PENDING_KEY = "a2a:permission:pending:";
    private static final String RESPONSE_KEY = "a2a:permission:response:";
    private static final String DETAIL_KEY = "a2a:permission:detail:";

    /** 等待超时（毫秒） */
    private static final long WAIT_TIMEOUT_MS = 120_000;
    /** 轮询间隔（毫秒） */
    private static final long POLL_INTERVAL_MS = 500;

    private final StringRedisTemplate redisTemplate;

    public PermissionBridgeService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 创建待审批权限请求。
     *
     * @param requestId 全局唯一请求 ID
     * @param toolName  工具名称
     * @param inputJson 工具参数 JSON
     * @param sessionId 会话 ID
     */
    public void createRequest(String requestId, String toolName,
                               String inputJson, String sessionId) {
        // 标记为待处理
        redisTemplate.opsForValue().set(PENDING_KEY + requestId, "pending",
                WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        // 存储详情
        String detail = String.format(
                "{\"toolName\":\"%s\",\"input\":%s,\"sessionId\":\"%s\",\"timestamp\":%d}",
                escapeJson(toolName), inputJson, escapeJson(sessionId),
                System.currentTimeMillis());
        redisTemplate.opsForValue().set(DETAIL_KEY + requestId, detail,
                WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        log.info("[PermissionBridge] 创建权限请求: requestId={}, tool={}", requestId, toolName);
    }

    /**
     * 阻塞等待用户权限响应。
     *
     * @param requestId 请求 ID
     * @return true=允许, false=拒绝/超时
     */
    public boolean waitForResponse(String requestId) {
        long deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            String response = redisTemplate.opsForValue().get(RESPONSE_KEY + requestId);
            if (response != null) {
                redisTemplate.delete(PENDING_KEY + requestId);
                redisTemplate.delete(DETAIL_KEY + requestId);
                redisTemplate.delete(RESPONSE_KEY + requestId);
                boolean allowed = "allow".equals(response);
                log.info("[PermissionBridge] 权限响应: requestId={}, allowed={}", requestId, allowed);
                return allowed;
            }
            try { Thread.sleep(POLL_INTERVAL_MS); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        log.warn("[PermissionBridge] 权限等待超时: requestId={}", requestId);
        redisTemplate.delete(PENDING_KEY + requestId);
        redisTemplate.delete(DETAIL_KEY + requestId);
        return false;
    }

    /**
     * 用户通过前端调用此方法响应权限请求。
     *
     * @param requestId  请求 ID
     * @param behavior   "allow" 或 "deny"
     * @return 是否成功响应
     */
    public boolean respondToRequest(String requestId, String behavior) {
        String pending = redisTemplate.opsForValue().get(PENDING_KEY + requestId);
        if (pending == null) {
            log.warn("[PermissionBridge] 权限请求不存在或已超时: requestId={}", requestId);
            return false;
        }
        redisTemplate.opsForValue().set(RESPONSE_KEY + requestId, behavior,
                30, TimeUnit.SECONDS);
        log.info("[PermissionBridge] 用户响应权限: requestId={}, behavior={}", requestId, behavior);
        return true;
    }

    /**
     * 获取待处理的权限请求详情。
     */
    public String getPendingDetail(String requestId) {
        return redisTemplate.opsForValue().get(DETAIL_KEY + requestId);
    }

    /**
     * 检查 requestId 对应的请求是否待处理。
     */
    public boolean isPending(String requestId) {
        String val = redisTemplate.opsForValue().get(PENDING_KEY + requestId);
        return "pending".equals(val);
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
