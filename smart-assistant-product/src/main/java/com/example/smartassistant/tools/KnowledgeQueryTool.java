package com.example.smartassistant.tools;

import com.example.smartassistant.common.gateway.tool.ToolDefinition;
import com.example.smartassistant.common.gateway.tool.ToolRegistry;
import com.example.smartassistant.common.rag.AclContext;
import com.example.smartassistant.common.rag.KnowledgeRetrievalService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 产品知识库查询工具 — 从 BGE 向量知识库检索商品相关知识和历史经验。
 * <p>
 * 知识库包含商品分类、价格政策、库存规则、用户评价指南、商品对比建议、售后服务说明等。
 * 基于两阶段检索：BGE 向量粗筛 → BM25+时效性精排。
 * </p>
 * <p>权限：检索前由服务端根据请求身份（MDC 注入的 tenantId / userId / 角色 / 安全等级）
 * 生成细粒度 ACL filter（文章⑤：权限进入检索层），不信任客户端传入条件。</p>
 */
@Component
public class KnowledgeQueryTool {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeQueryTool.class);

    private final KnowledgeRetrievalService retrievalService;
    private final ToolRegistry toolRegistry;

    public KnowledgeQueryTool(KnowledgeRetrievalService retrievalService, ToolRegistry toolRegistry) {
        this.retrievalService = retrievalService;
        this.toolRegistry = toolRegistry;
    }

    @PostConstruct
    public void initTools() {
        toolRegistry.register(ToolDefinition.read("queryKnowledge", "查询商品知识库"));
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

        // ⭐ 从请求上下文 MDC 构建细粒度 ACL，服务端生成检索层 filter（文章⑤）
        AclContext acl = AclContext.fromMdc();
        String result = retrievalService.search("product_knowledge", query, 5, acl);
        log.info("[KnowledgeTool] 返回知识库结果: length={}, tenant={}", result.length(), acl.getTenantId());
        return result;
    }
}
