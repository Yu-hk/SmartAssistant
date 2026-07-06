/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Function;

/**
 * 查询重写 Handler。
 *
 * <p>参考 Snail AI 的 {@code QueryRewriteHandler} 设计。
 * 使用 LLM 将用户原始查询改写为对检索更友好的形式，
 * 例如：扩展缩写、补充同义词、优化句式结构。
 *
 * <p>与 {@link com.example.smartassistant.common.rag.MultiQueryService} 不同，
 * MultiQuery 生成多个变体做扩展召回，而 QueryRewrite 直接改写 query 本身
 * 以提升单次检索的命中率。
 *
 * <p>通过 {@link Function}{@code <String, String>} 注入 LLM 调用能力，
 * 不依赖具体 ChatClient/ChatModel 实现。
 *
 * <p>Order=2，在 MultiQueryHandler (Order=0) 之后执行，
 * 改写后的 query 会替换 {@link RagSearchContext#getQueryVariants()} 中的原始 query。
 */
public class QueryRewriteHandler implements RagSearchHandler {

    private static final Logger log = LoggerFactory.getLogger(QueryRewriteHandler.class);

    /** LLM 调用接口：接收 prompt，返回重写后的 query */
    private final Function<String, String> llmCall;

    /** 是否启用 */
    private final boolean enabled;

    /** 重写 prompt 模板 */
    private static final String REWRITE_PROMPT = """
            你是一个搜索查询优化助手。你的任务是将用户的原始查询改写为对检索系统更友好的形式。
            
            改写规则：
            1. 扩展缩写和简称（如 "iPhone 15" → "iPhone 15 系列手机"）
            2. 补充领域相关的同义词和关键词（提升向量检索命中率）
            3. 将口语化表达转为书面检索表达
            4. 保持原始查询的核心语义不变
            5. 不要添加原问题中不存在的限定条件
            6. 只输出改写后的查询，不要解释、不要加引号
            7. 直接给出最合适的 1 个版本即可
            
            示例：
            原始：iPhone 15 Pro 多少钱
            改写：iPhone 15 Pro 价格 售价 多少钱
            
            原始：有什么好吃的推荐
            改写：推荐美食 好吃的东西 必吃推荐
            
            原始：今天天气怎么样
            改写：今日天气预报 实时天气情况
            
            原始：%s
            改写：
            """;

    public QueryRewriteHandler(Function<String, String> llmCall, boolean enabled) {
        this.llmCall = llmCall;
        this.enabled = enabled;
    }

    /**
     * 默认构造：启用重写。
     */
    public QueryRewriteHandler(Function<String, String> llmCall) {
        this(llmCall, true);
    }

    @Override
    public void handle(RagSearchContext context) {
        if (!enabled || llmCall == null) {
            log.debug("[QueryRewrite] 未启用或 LLM 未配置，跳过");
            return;
        }

        String originalQuery = context.getOriginalQuery();
        if (originalQuery == null || originalQuery.isBlank()) return;

        try {
            String prompt = String.format(REWRITE_PROMPT, originalQuery);
            String rewritten = llmCall.apply(prompt);

            if (rewritten == null || rewritten.isBlank() || rewritten.equalsIgnoreCase(originalQuery)) {
                log.debug("[QueryRewrite] 重写无变化: '{}'", originalQuery);
                return;
            }

            // 清理结果
            rewritten = rewritten.trim();
            // 去掉可能的多余引号
            if ((rewritten.startsWith("\"") && rewritten.endsWith("\""))
                    || (rewritten.startsWith("'") && rewritten.endsWith("'"))) {
                rewritten = rewritten.substring(1, rewritten.length() - 1).trim();
            }

            // 替换 context 中的原始 query（保持原有 variants）
            // 用改写后的 query 替换 queryVariants 中的原始查询
            if (!context.getQueryVariants().isEmpty()) {
                context.getQueryVariants().set(0, rewritten);
            }

            // 设置属性，方便下游 Handler 感知
            context.setAttribute("queryRewrite.original", originalQuery);
            context.setAttribute("queryRewrite.rewritten", rewritten);

            log.info("[QueryRewrite] '{}' → '{}'", originalQuery, rewritten);

        } catch (Exception e) {
            log.warn("[QueryRewrite] 重写失败: {}", e.getMessage());
        }
    }

    @Override
    public int getOrder() {
        return 2; // 在 MultiQueryHandler (Order=0) 之后执行
    }
}
