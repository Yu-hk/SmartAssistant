/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.controller;

import com.example.smartassistant.common.rag.InMemoryKnowledgeBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 知识库管理端点——支持运行时重新索引和状态查看。
 * <p>
 * 预留的 Nacos 热更新入口：可通过 Nacos Config Listener 调用此端点，
 * 或直接 POST 触发 reindex。
 * </p>
 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeController.class);

    private final ApplicationContext applicationContext;

    public KnowledgeController(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @PostMapping("/reindex")
    public ResponseEntity<Map<String, Object>> reindex(
            @RequestParam(required = false) String kbName) {
        Map<String, InMemoryKnowledgeBase> bases =
                applicationContext.getBeansOfType(InMemoryKnowledgeBase.class);

        int count = 0;
        for (var entry : bases.entrySet()) {
            InMemoryKnowledgeBase kb = entry.getValue();
            if (kbName == null || kb.getName().equals(kbName)) {
                kb.reindex();
                count++;
                log.info("[KnowledgeAPI] 重新索引: bean={}, name={}, docs={}",
                        entry.getKey(), kb.getName(), kb.size());
            }
        }

        return ResponseEntity.ok(Map.of(
                "reindexed", count,
                "total", bases.size(),
                "message", count > 0 ? "重新索引完成" : "未找到匹配的知识库"
        ));
    }

    @PostMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        Map<String, InMemoryKnowledgeBase> bases =
                applicationContext.getBeansOfType(InMemoryKnowledgeBase.class);
        return ResponseEntity.ok(Map.of(
                "knowledge_bases", bases.entrySet().stream()
                        .map(e -> Map.of(
                                "bean", e.getKey(),
                                "name", e.getValue().getName(),
                                "docs", e.getValue().size()
                        )).toList()
        ));
    }
}
