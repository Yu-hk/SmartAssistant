package com.example.smartassistant.controller;

import com.example.smartassistant.service.graph.ProductGraphService;
import com.example.smartassistant.spi.ProductBackend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * P3 商品推荐 REST API（供推荐服务调用）。
 *
 * @author Yu-hk
 * @since 2026-06-29
 */
@RestController
@RequestMapping("/api/product")
public class ProductRecommendController {

    private static final Logger log = LoggerFactory.getLogger(ProductRecommendController.class);

    private final ProductGraphService graphService;
    private final ProductBackend productBackend;

    public ProductRecommendController(ProductGraphService graphService,
                                      ProductBackend productBackend) {
        this.graphService = graphService;
        this.productBackend = productBackend;
    }

    /**
     * 获取所有商品列表。
     */
    @GetMapping("/list")
    public List<Map<String, String>> getAllProducts() {
        List<Map<String, String>> result = new ArrayList<>();
        for (String code : graphService.getAllProductCodes()) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("code", code);
            item.put("name", graphService.getProductName(code));
            item.put("category", graphService.getProductCategory(code));
            item.put("brand", graphService.getProductBrand(code));
            result.add(item);
        }
        return result;
    }

    /**
     * 获取指定商品的关联推荐（基于 ProductGraphService 图谱）。
     */
    @GetMapping("/{code}/recommend")
    public List<Map<String, Object>> getProductRecommendations(@PathVariable("code") String code) {
        var recommendations = graphService.queryRecommendations(code, 5);
        return recommendations.stream().map(rec -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("productCode", rec.getProductCode());
            item.put("productName", rec.getProductName());
            item.put("relationType", rec.getRelationType().name());
            item.put("relevanceScore", rec.getRelevanceScore());
            return item;
        }).collect(Collectors.toList());
    }

    /**
     * 获取商品详情。
     */
    @GetMapping("/{code}/info")
    public String getProductInfo(@PathVariable("code") String code) {
        return productBackend.queryProductInfo(code);
    }
}
