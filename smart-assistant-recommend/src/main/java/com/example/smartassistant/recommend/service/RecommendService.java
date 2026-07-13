package com.example.smartassistant.recommend.service;

import com.example.smartassistant.recommend.client.OrderFeignClient;
import com.example.smartassistant.recommend.client.ProductFeignClient;
import com.example.smartassistant.recommend.dto.RecommendItem;
import com.example.smartassistant.recommend.dto.RecommendRequest;
import com.example.smartassistant.recommend.dto.RecommendResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * P3 跨模块推荐服务。
 * <p>
 * 核心策略（按优先级）：
 * <ol>
 *   <li><b>图谱推荐</b>：基于 ProductGraphService 的商品关系图谱（同类/配件/替代）</li>
 *   <li><b>协同过滤</b>：基于 OrderGraphService 的"买过 A 也买了 B"关联</li>
 *   <li><b>热门推荐</b>：全量排序兜底</li>
 * </ol>
 * </p>
 *
 * @author Yu-hk
 * @since 2026-06-29
 */
@Service
public class RecommendService {

    private static final Logger log = LoggerFactory.getLogger(RecommendService.class);

    private final ProductFeignClient productClient;
    private final OrderFeignClient orderClient;

    public RecommendService(ProductFeignClient productClient,
                            OrderFeignClient orderClient) {
        this.productClient = productClient;
        this.orderClient = orderClient;
    }

    /**
     * 执行交叉推荐。
     */
    public RecommendResult recommend(RecommendRequest request) {
        long start = System.currentTimeMillis();
        Long userId = request.getUserId();
        String productCode = request.getProductCode();
        int maxResults = request.getMaxResults() > 0 ? request.getMaxResults() : 5;

        // 使用 LinkedHashMap 去重保序
        Map<String, RecommendItem> merged = new LinkedHashMap<>();

        // ===== 策略 1: 商品图谱推荐（如果提供了商品编码） =====
        if (productCode != null && !productCode.isBlank()) {
            try {
                List<Map<String, Object>> graphRecs = productClient.getProductRecommendations(productCode);
                for (Map<String, Object> rec : graphRecs) {
                    String code = (String) rec.get("productCode");
                    merged.putIfAbsent(code, RecommendItem.builder()
                            .productCode(code)
                            .productName((String) rec.get("productName"))
                            .reason("商品关联推荐：" + rec.get("relationType"))
                            .relationType((String) rec.get("relationType"))
                            .score(toDouble(rec.get("relevanceScore")))
                            .build());
                }
                log.info("[Recommend] 图谱推荐命中: productCode={}, recs={}", productCode, graphRecs.size());
            } catch (Exception e) {
                log.warn("[Recommend] 图谱推荐失败: {}", e.getMessage());
            }
        }

        // ===== 策略 2: 协同过滤（基于用户购买历史） =====
        if (userId != null) {
            try {
                List<String> purchased = orderClient.getUserPurchasedProducts(userId);
                if (purchased != null && !purchased.isEmpty()) {
                    log.info("[Recommend] 用户购买历史: userId={}, products={}", userId, purchased);

                    // 对每个买过的商品，查图谱推荐
                    for (String purchasedCode : purchased) {
                        try {
                            List<Map<String, Object>> recs = productClient.getProductRecommendations(purchasedCode);
                            for (Map<String, Object> rec : recs) {
                                String code = (String) rec.get("productCode");
                                // 排除用户已买过的商品
                                if (purchased.contains(code)) continue;
                                merged.putIfAbsent(code, RecommendItem.builder()
                                        .productCode(code)
                                        .productName((String) rec.get("productName"))
                                        .reason("买过" + purchasedCode + "的用户也看了")
                                        .relationType("COLLAB_FILTER")
                                        .score(toDouble(rec.get("relevanceScore")) * 0.8)
                                        .build());
                            }
                        } catch (Exception e) {
                            log.warn("[Recommend] 解析协同过滤结果失败: {}", e.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("[Recommend] 协同过滤失败: {}", e.getMessage());
            }
        }

        // ===== 策略 3: 热门兜底（如果什么都没有） =====
        if (merged.isEmpty()) {
            try {
                List<Map<String, String>> allProducts = productClient.getAllProducts();
                if (allProducts != null) {
                    for (Map<String, String> p : allProducts) {
                        String name = p.get("name");
                        String code = p.getOrDefault("code", name);
                        merged.putIfAbsent(code, RecommendItem.builder()
                                .productCode(code)
                                .productName(name)
                                .reason("热门推荐")
                                .relationType("POPULAR")
                                .score(0.5)
                                .build());
                    }
                }
            } catch (Exception e) {
                log.warn("[Recommend] 热门兜底失败: {}", e.getMessage());
            }
        }

        long elapsed = System.currentTimeMillis() - start;

        // 按得分降序 + 截断
        List<RecommendItem> items = merged.values().stream()
                .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
                .limit(maxResults)
                .collect(Collectors.toList());

        String strategy = productCode != null ? "graph+cf" : userId != null ? "cf" : "popular";

        log.info("[Recommend] 推荐完成: userId={}, productCode={}, items={}, strategy={}, elapsed={}ms",
                userId, productCode, items.size(), strategy, elapsed);

        return RecommendResult.builder()
                .userId(userId)
                .items(items)
                .strategy(strategy)
                .elapsedMs(elapsed)
                .build();
    }

    private double toDouble(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(value)); }
        catch (NumberFormatException e) { return 0.5; }
    }
}
