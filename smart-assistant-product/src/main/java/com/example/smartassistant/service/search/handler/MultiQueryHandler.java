/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.service.search.handler;

import com.example.smartassistant.common.rag.pipeline.RagSearchContext;
import com.example.smartassistant.common.rag.pipeline.RagSearchHandler;
import com.example.smartassistant.common.rag.MultiQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * H00: Multi-Query 查询扩展 Handler（可选）。
 *
 * <p>利用 LLM 从多个角度重写用户查询，生成 3 个变体，
 * 后续 Handler 会对每个变体分别做召回。
 */
@Component
public class MultiQueryHandler implements RagSearchHandler {

    private static final Logger log = LoggerFactory.getLogger(MultiQueryHandler.class);

    private final MultiQueryService multiQueryService;

    @Value("${product.rag.multi-query.enabled:false}")
    private boolean multiQueryEnabled;

    public MultiQueryHandler(@Autowired(required = false) MultiQueryService multiQueryService) {
        this.multiQueryService = multiQueryService;
    }

    @Override
    public void handle(RagSearchContext context) {
        if (!multiQueryEnabled || multiQueryService == null) {
            return;
        }

        try {
            List<String> variants = multiQueryService.withCount(3).generate(context.getOriginalQuery());
            context.addQueryVariants(variants);
            log.info("[RagHandler] Multi-Query 扩展: '{}' → {} 个变体",
                    context.getOriginalQuery(), context.getQueryVariants().size());
        } catch (Exception e) {
            log.warn("[RagHandler] Multi-Query 失败，使用原始 query: {}", e.getMessage());
        }
    }

    @Override
    public int getOrder() {
        return 0; // 最先执行
    }
}
