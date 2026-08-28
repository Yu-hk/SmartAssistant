/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.spi;

import com.example.smartassistant.common.error.AgentErrorCode;
import com.example.smartassistant.common.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

import java.util.Map;
import java.util.Locale;
import java.util.List;
import java.math.BigDecimal;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ⭐ 默认内存 Mock 商品后端。
 * <p>
 * 当没有其他 {@link ProductBackend} Bean 时自动注册。
 * 使用静态数据模拟商品信息，无需数据库即可运行。
 * </p>
 */
@Component
@ConditionalOnMissingBean(ProductBackend.class)
@Profile({"dev", "test"})
public class InMemoryProductBackend implements ProductBackend {

    private static final Logger log = LoggerFactory.getLogger(InMemoryProductBackend.class);

    private static final Map<String, Map<String, String>> PRODUCTS = new ConcurrentHashMap<>();
    static {
        PRODUCTS.put("IPHONE-15-PRO", Map.of(
            "name", "iPhone 15 Pro", "price", "8999", "stock", "充足",
            "spec", "钛金属、A17 Pro芯片、4800万像素", "color", "原色钛金属/蓝色钛金属/白色钛金属/黑色钛金属",
            "category", "手机"
        ));
        PRODUCTS.put("AIRPODS-PRO", Map.of(
            "name", "AirPods Pro（第二代）", "price", "1999", "stock", "充足",
            "spec", "降噪、自适应音频、USB-C充电", "color", "白色", "category", "耳机"
        ));
        PRODUCTS.put("MACBOOK-AIR-M3", Map.of(
            "name", "MacBook Air M3", "price", "8999起", "stock", "紧张",
            "spec", "13.6英寸、M3芯片、18小时续航", "color", "午夜色/星光色/深空灰色/银色",
            "category", "笔记本电脑"
        ));
        PRODUCTS.put("IPAD-PRO-M4", Map.of(
            "name", "iPad Pro M4 13英寸", "price", "9499", "stock", "充足",
            "spec", "13英寸、M4芯片、OLED显示屏", "color", "深空黑色/银色",
            "category", "平板电脑"
        ));
    }

    private Map<String, String> findProduct(String productCode) {
        String normalized = normalize(productCode);
        Map<String, String> p = PRODUCTS.get(normalized);
        if (p == null) {
            for (var entry : PRODUCTS.entrySet()) {
                String name = normalize(entry.getValue().get("name"));
                String code = normalize(entry.getKey());
                if (name.contains(normalized) || normalized.contains(name)
                        || code.contains(normalized) || normalized.contains(code)) {
                    return entry.getValue();
                }
            }
        }
        return p;
    }

    @Override
    public String queryProductInfo(String productCode) {
        log.info("[MockProduct] 查商品: {}", productCode);
        Map<String, String> p = findProduct(productCode);
        if (p == null) {
            return ToolResult.error(AgentErrorCode.PRODUCT_NOT_FOUND, "未找到商品 " + productCode,
                    "请确认商品编码或名称是否正确");
        }
        return String.format("%s\n价格：%s 元\n库存：%s\n规格：%s\n颜色：%s",
                p.get("name"), p.get("price"), p.get("stock"), p.get("spec"), p.get("color"));
    }

    @Override
    public String checkStock(String productCode) {
        log.info("[MockProduct] 查库存: {}", productCode);
        Map<String, String> p = findProduct(productCode);
        if (p == null) return ToolResult.error(AgentErrorCode.PRODUCT_NOT_FOUND, "未找到商品 " + productCode);
        String stock = p.get("stock");
        if ("充足".equals(stock)) {
            return p.get("name") + " 库存充足，下单后 24 小时内发货。";
        } else if ("紧张".equals(stock)) {
            return p.get("name") + " 库存紧张，建议尽快下单，预计 3-5 天发货。";
        }
        return p.get("name") + " 暂时缺货，补货时间待定。";
    }

    @Override
    public String getPrice(String productCode) {
        log.info("[MockProduct] 查价格: {}", productCode);
        Map<String, String> p = findProduct(productCode);
        if (p == null) return ToolResult.error(AgentErrorCode.PRODUCT_NOT_FOUND, "未找到商品 " + productCode);
        return String.format("%s 售价 %s 元，支持 3/6/12/24 期免息分期。", p.get("name"), p.get("price"));
    }

    @Override
    public String searchProduct(String keyword) {
        log.info("[MockProduct] 搜索: {}", keyword);
        String normalized = normalize(keyword);
        StringBuilder sb = new StringBuilder();
        for (var entry : PRODUCTS.entrySet()) {
            Map<String, String> p = entry.getValue();
            String name = normalize(p.get("name"));
            String code = normalize(entry.getKey());
            if (name.contains(normalized) || normalized.contains(name)
                    || code.contains(normalized) || normalized.contains(code)) {
                sb.append("· ").append(p.get("name")).append(" — ¥").append(p.get("price")).append("\n");
            }
        }
        if (sb.isEmpty()) return "未找到匹配的商品";
        return "搜索结果：\n" + sb.toString().trim();
    }

    @Override
    public List<ProductSummary> listPopularProducts(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 20));
        return PRODUCTS.entrySet().stream()
                .map(entry -> {
                    Map<String, String> product = entry.getValue();
                    String rawPrice = product.getOrDefault("price", "0").replace("起", "");
                    BigDecimal price;
                    try {
                        price = new BigDecimal(rawPrice);
                    } catch (NumberFormatException ignored) {
                        price = null;
                    }
                    return new ProductSummary(
                            entry.getKey(), product.get("name"), price,
                            product.get("stock"), product.get("spec"), 0L,
                            product.get("category"));
                })
                .sorted((left, right) -> {
                    int stockComparison = Integer.compare(stockRank(left.stock()), stockRank(right.stock()));
                    return stockComparison != 0
                            ? stockComparison
                            : left.code().compareTo(right.code());
                })
                .limit(safeLimit)
                .toList();
    }

    @Override
    public List<String> listProductCategories() {
        return PRODUCTS.values().stream()
                .map(product -> product.get("category"))
                .filter(category -> category != null && !category.isBlank())
                .distinct()
                .sorted()
                .toList();
    }

    private static int stockRank(String stock) {
        if ("充足".equals(stock)) return 0;
        if ("紧张".equals(stock)) return 1;
        return 2;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
