/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.tools;

import com.example.smartassistant.common.rag.KnowledgeRetrievalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 订单知识库查询工具——从 BGE 向量知识库检索订单政策、退款规则、发货规则等。
 * <p>
 * 基于两阶段检索架构：BGE 向量粗筛 → BM25+时效性精排。
 * 知识库包含：退款政策、发货规则、支付说明、订单状态等 10 篇文档。
 * </p>
 */
@Component
public class OrderKnowledgeTool {

    private static final Logger log = LoggerFactory.getLogger(OrderKnowledgeTool.class);

    private final KnowledgeRetrievalService retrievalService;

    public OrderKnowledgeTool(KnowledgeRetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }

    @Tool(description = "查询订单知识库：从订单政策、退款规则、发货规则、支付方式等知识库中检索信息。"
            + "适用场景：用户询问退换货政策、退款到账时间、发货时效、支付方式、订单状态含义、"
            + "优惠券规则、客服联系方式等需要查询知识库的问题。"
            + "输入需要查询的意图或问题关键词。")
    public String queryOrderKnowledge(
            @ToolParam(description = "查询关键词，如 '退款多久到账'、'发货时间'、'取消订单规则'、'优惠券怎么用'",
                    required = true) String query) {
        log.info("[OrderKnowledgeTool] 查询订单知识库: query={}", query);

        String result = retrievalService.search("order_knowledge", query, 5);
        log.info("[OrderKnowledgeTool] 返回知识库结果: length={}", result.length());
        return result;
    }
}
