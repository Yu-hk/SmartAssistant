/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.ingestion.job;

import com.example.smartassistant.common.rag.ingestion.ReviewItem;
import com.example.smartassistant.common.rag.ingestion.ReviewQueueService;
import com.example.smartassistant.common.response.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 复核队列 REST 端点（REQ-1 脏数据拦截后的运营审批入口）。
 * <p>
 * 提供：待复核列表查询、审批通过（可带覆盖项）、审批拒绝。
 * 审批通过后由运营后台触发「重投」（即重新摄入），拒绝则丢弃并通知上传人。
 * </p>
 */
@RestController
@ConditionalOnWebApplication
@RequestMapping("/api/knowledge/review/queue")
public class ReviewQueueController {

    private static final Logger log = LoggerFactory.getLogger(ReviewQueueController.class);

    private final ReviewQueueService reviewQueueService;

    public ReviewQueueController(ReviewQueueService reviewQueueService) {
        this.reviewQueueService = reviewQueueService;
    }

    /** 查询复核队列（按状态过滤，缺省全部） */
    @GetMapping
    public ApiResponse<List<ReviewItem>> list(
            @RequestParam(required = false, defaultValue = "") String status) {
        List<ReviewItem> items = reviewQueueService.list(status.isBlank() ? null : status);
        return ApiResponse.success(items);
    }

    /** 查询单条 */
    @GetMapping("/{id}")
    public ApiResponse<ReviewItem> get(@PathVariable String id) {
        ReviewItem item = reviewQueueService.get(id);
        if (item == null) return ApiResponse.error(404, "复核项不存在: " + id);
        return ApiResponse.success(item);
    }

    /** 审批通过（可选覆盖项 JSON，运营据此重投） */
    @PostMapping("/{id}/approve")
    public ApiResponse<Map<String, Object>> approve(
            @PathVariable String id,
            @RequestParam(required = false, defaultValue = "operator") String reviewedBy,
            @RequestBody(required = false) Map<String, Object> overrides) {
        boolean ok = reviewQueueService.approve(id, reviewedBy,
                overrides != null ? toJson(overrides) : null);
        if (!ok) return ApiResponse.error(404, "复核项不存在或已处理: " + id);
        log.info("[ReviewQueue] 审批通过: id={}, reviewer={}", id, reviewedBy);
        return ApiResponse.success(Map.of("id", id, "status", ReviewItem.STATUS_APPROVED));
    }

    /** 审批拒绝（丢弃） */
    @PostMapping("/{id}/reject")
    public ApiResponse<Map<String, Object>> reject(
            @PathVariable String id,
            @RequestParam(required = false, defaultValue = "operator") String reviewedBy) {
        boolean ok = reviewQueueService.reject(id, reviewedBy);
        if (!ok) return ApiResponse.error(404, "复核项不存在或已处理: " + id);
        log.info("[ReviewQueue] 审批拒绝(丢弃): id={}, reviewer={}", id, reviewedBy);
        return ApiResponse.success(Map.of("id", id, "status", ReviewItem.STATUS_REJECTED));
    }

    /** 待复核数量 */
    @GetMapping("/count/pending")
    public ApiResponse<Map<String, Object>> pendingCount() {
        return ApiResponse.success(Map.of("pending", reviewQueueService.pendingCount()));
    }

    private static String toJson(Map<String, Object> map) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }
}
