/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.recommend.service;

import com.example.smartassistant.recommend.client.OrderFeignClient;
import com.example.smartassistant.recommend.client.ProductFeignClient;
import com.example.smartassistant.recommend.dto.RecommendItem;
import com.example.smartassistant.recommend.dto.RecommendRequest;
import com.example.smartassistant.recommend.dto.RecommendResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * {@link RecommendService} 单元测试（Mockito mock 两个 Feign 客户端）。
 * <p>覆盖：maxResults 默认值与截断、协同过滤排除已购商品、热门兜底、得分降序。</p>
 */
@DisplayName("[recommend] RecommendService 单元测试")
@ExtendWith(MockitoExtension.class)
class RecommendServiceTest {

    @Mock
    private ProductFeignClient productClient;
    @Mock
    private OrderFeignClient orderClient;

    private RecommendService service;

    @BeforeEach
    void setUp() {
        service = new RecommendService(productClient, orderClient);
    }

    private Map<String, Object> rec(String code, String name, String type, double score) {
        return Map.of(
                "productCode", code,
                "productName", name,
                "relationType", type,
                "relevanceScore", score);
    }

    // ============ maxResults 默认与截断 ============

    @Test
    @DisplayName("maxResults=0 时回退为 5，并对结果截断")
    void maxResults_defaultAndTruncate() {
        List<Map<String, Object>> recs = List.of(
                rec("P1", "A", "SAME_CATEGORY", 0.9),
                rec("P2", "B", "ACCESSORY", 0.8),
                rec("P3", "C", "ALTERNATIVE", 0.7),
                rec("P4", "D", "COMPLEMENT", 0.6),
                rec("P5", "E", "UPGRADE", 0.5),
                rec("P6", "F", "ACCESSORY", 0.4),
                rec("P7", "G", "SAME_CATEGORY", 0.3));
        when(productClient.getProductRecommendations("X")).thenReturn(recs);

        RecommendResult result = service.recommend(
                RecommendRequest.builder().productCode("X").maxResults(0).build());

        assertEquals("graph+cf", result.getStrategy());
        assertEquals(5, result.getItems().size(), "maxResults=0 应截断为 5 条");
    }

    // ============ 协同过滤排除已购 ============

    @Test
    @DisplayName("协同过滤：排除用户已购买的商品")
    void collaborativeFiltering_excludesPurchased() {
        when(orderClient.getUserPurchasedProducts(1L)).thenReturn(List.of("P1"));
        // P1 的关联推荐含 P1（自身，应排除）与 P2
        when(productClient.getProductRecommendations("P1"))
                .thenReturn(List.of(rec("P1", "Self", "SAME_CATEGORY", 0.9),
                                    rec("P2", "Other", "ACCESSORY", 0.8)));

        RecommendResult result = service.recommend(
                RecommendRequest.builder().userId(1L).productCode(null).build());

        assertEquals("cf", result.getStrategy());
        assertTrue(result.getItems().stream()
                        .noneMatch(i -> "P1".equals(i.getProductCode())),
                "已购买商品 P1 不应出现在推荐中");
        assertTrue(result.getItems().stream()
                        .anyMatch(i -> "P2".equals(i.getProductCode())),
                "应推荐关联商品 P2");
    }

    // ============ 热门兜底 ============

    @Test
    @DisplayName("无 productCode 且无 userId 时走热门兜底")
    void popularFallback_whenNoInputs() {
        when(productClient.getAllProducts()).thenReturn(List.of(
                Map.of("code", "P1", "name", "商品一"),
                Map.of("code", "P2", "name", "商品二")));

        RecommendResult result = service.recommend(
                RecommendRequest.builder().productCode(null).userId(null).maxResults(3).build());

        assertEquals("popular", result.getStrategy());
        assertEquals(2, result.getItems().size());
        assertEquals(0.5, result.getItems().get(0).getScore());
    }

    // ============ 得分降序 ============

    @Test
    @DisplayName("推荐结果按得分降序排列")
    void items_sortedByScoreDescending() {
        when(productClient.getProductRecommendations("X")).thenReturn(List.of(
                rec("P1", "Low", "ACCESSORY", 0.2),
                rec("P2", "High", "SAME_CATEGORY", 0.95),
                rec("P3", "Mid", "ALTERNATIVE", 0.6)));

        RecommendResult result = service.recommend(
                RecommendRequest.builder().productCode("X").maxResults(10).build());

        List<RecommendItem> items = result.getItems();
        assertEquals(0.95, items.get(0).getScore());
        for (int i = 1; i < items.size(); i++) {
            assertTrue(items.get(i - 1).getScore() >= items.get(i).getScore(),
                    "推荐结果应按得分降序排列");
        }
    }
}
