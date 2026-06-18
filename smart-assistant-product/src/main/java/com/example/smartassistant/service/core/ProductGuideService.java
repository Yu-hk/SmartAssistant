/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.service.core;

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

    /**
     * 处理商品咨询并生成带下单引导的回复。
     *
     * @param query 用户查询
     * @return 格式化后的完整回复
     */
    public String processAndGuide(String query) {
        if (query == null || query.isBlank()) return "";

        // 1. 多路 RAG 检索
        String ragResult = productRagService.retrieve(query);

        // 2. 构建带引导的回复
        StringBuilder response = new StringBuilder();

        if (!ragResult.isBlank()) {
            response.append(ragResult).append("\n\n");
        } else {
            response.append("抱歉，我没有找到相关商品信息。请提供更详细的商品名称或编码。\n\n");
        }

        // 3. 追加询问语气引导
        response.append("💬 请问您需要了解更多详情吗？如果需要下单，请告诉我以下信息：\n");
        response.append("• 商品名称或编码\n");
        response.append("• 购买数量\n");
        response.append("• 您的收货地址和联系方式\n\n");
        response.append("我会帮您完成下单流程 😊");

        log.info("[ProductGuide] 生成引导回复: query={}, ragResultLen={}", query, ragResult.length());
        return response.toString();
    }
}
