/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.product.tool;

import com.example.smartassistant.common.gateway.tool.ToolDefinition;
import com.example.smartassistant.common.gateway.tool.ToolRegistry;
import com.example.smartassistant.common.tool.client.ToolRegistryClient;
import com.example.smartassistant.common.rag.AclContext;
import com.example.smartassistant.common.rag.KnowledgeRetrievalService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Product knowledge base query tool — retrieves product-related info and knowledge.
 * <p>Uses {@link KnowledgeRetrievalService} from common infrastructure.</p>
 */
@Component
public class KnowledgeQueryTool {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeQueryTool.class);

    private final KnowledgeRetrievalService retrievalService;
    private final ToolRegistry toolRegistry;
    private final ToolRegistryClient registryClient;

    public KnowledgeQueryTool(KnowledgeRetrievalService retrievalService, ToolRegistry toolRegistry,
                              ToolRegistryClient registryClient) {
        this.retrievalService = retrievalService;
        this.toolRegistry = toolRegistry;
        this.registryClient = registryClient;
    }

    @PostConstruct
    public void initTools() {
        toolRegistry.register(ToolDefinition.read("queryKnowledge", "查询商品知识库")
                .toBuilder().tags(new String[]{"PRODUCT", "READ_ONLY"})
                .functionalCapabilities(java.util.List.of("product-knowledge", "product-faq", "product-info")).build());
        registryClient.registerWithFallback(ToolDefinition.read("queryKnowledge", "查询商品知识库")
                .toBuilder().tags(new String[]{"PRODUCT", "READ_ONLY"})
                .functionalCapabilities(java.util.List.of("product-knowledge", "product-faq", "product-info")).build(), toolRegistry);
    }

    @Tool(description = "查询产品知识库：从商品咨询的知识库中检索商品信息、分类说明、价格政策、"
            + "库存状态、评价指南、商品对比建议、售后服务等。"
            + "适用场景：用户询问商品分类、价格保护政策、库存状态含义、如何对比商品、"
            + "退换货保修规则等知识类问题。"
            + "输入需要查询的关键词。")
    public String queryKnowledge(
            @ToolParam(description = "查询关键词，如 '手机分类'、'价格保护'、'库存状态'、'售后服务'",
                    required = true) String query) {
        log.info("[KnowledgeTool] 查询产品知识库: query={}", query);

        AclContext acl = AclContext.fromMdc();
        String result = retrievalService.search("product_knowledge", query, 5, acl);
        log.info("[KnowledgeTool] 返回知识库结果: length={}, tenant={}", result.length(), acl.getTenantId());
        return result;
    }
}
