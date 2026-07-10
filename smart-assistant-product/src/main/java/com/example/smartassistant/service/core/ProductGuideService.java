/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.service.core;

import com.example.smartassistant.common.observability.OpsMetrics;
import com.example.smartassistant.common.rag.RetrievalQualityResult;
import com.example.smartassistant.service.search.ProductRagService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * ⭐ 商品咨询引导服务。
 * <p>
 * 将 RAG 检索结果格式化为带下单引导的完整回复。
 * 使用询问语气引导用户完成下单，不替用户做决定。
 * </p>
 */
@Service
public class ProductGuideService {

    private static final Logger log = LoggerFactory.getLogger(ProductGuideService.class);

    private final ProductRagService productRagService;

    public ProductGuideService(ProductRagService productRagService) {
        this.productRagService = productRagService;
    }

    /** ⭐ G4 运营指标收集器（应答/无证据拒答），零装配、全局注册表 */
    private final OpsMetrics opsMetrics = new OpsMetrics();

    /**
     * 处理商品咨询并生成带下单引导的回复（P1 质量感知版）。
     *
     * <p>使用 {@link ProductRagService#retrieveWithQuality(String)} 获取质量分数，
     * 质量低于阈值时使用兜底提示，避免将低质量检索结果传入 LLM。</p>
     *
     * @param query 用户查询
     * @return 格式化后的完整回复
     */
    public String processAndGuide(String query) {
        if (query == null || query.isBlank()) return "";

        // ⭐ G4 运营指标：记录一次商品域应答（无答案率分母）
        opsMetrics.recordAnswer("product", "product");

        // 1. 多路 RAG 检索（含结构化质量评估）
        RetrievalQualityResult result = productRagService.retrieveWithQualityResult(query);

        // 2. 构建带引导的回复
        StringBuilder response = new StringBuilder();

        if (result.isHighQuality()) {
            // 质量合格：附加检索结果
            response.append(result.getContent()).append("\n\n");
            log.info("[ProductGuide] RAG 质量合格: query={}, qualityScore={}",
                    query, String.format("%.4f", result.getNormalizedScore()));
        } else if (result.isRejected()) {
            // ⭐ P1 无证据拒答：直接使用结构化拒答消息
            log.warn("[ProductGuide] RAG 无证据拒答: query={}, code={}",
                    query, result.getRejectionCode());
            // ⭐ G4 运营指标：记录无证据拒答
            opsMetrics.recordNoEvidenceAnswer("product", "product");
            response.append(result.getRejectionMessage()).append("\n\n");
        } else {
            // 质量低（未达阈值但非结构化拒答）：使用兜底提示，不附加低质量检索结果
            log.warn("[ProductGuide] RAG 质量低，使用兜底: query={}, qualityScore={}",
                    query, String.format("%.4f", result.getNormalizedScore()));
            response.append("未找到相关商品信息，请提供更详细的商品名称或编码。\n\n");
        }

        // 3. 追加询问语气引导
        response.append("💬 请问您需要了解更多详情吗？如果需要下单，请告诉我以下信息：\n");
        response.append("• 商品名称或编码\n");
        response.append("• 购买数量\n");
        response.append("• 您的收货地址和联系方式\n\n");
        response.append("我会帮您完成下单流程 😊");

        log.info("[ProductGuide] 生成引导回复: query={}, ragResultLen={}, highQuality={}",
                query, result.getContent().length(), result.isHighQuality());
        return response.toString();
    }
}
