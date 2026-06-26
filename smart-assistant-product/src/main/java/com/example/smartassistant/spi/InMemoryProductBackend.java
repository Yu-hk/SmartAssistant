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

import java.util.Map;
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
public class InMemoryProductBackend implements ProductBackend {

    private static final Logger log = LoggerFactory.getLogger(InMemoryProductBackend.class);

    private static final Map<String, Map<String, String>> PRODUCTS = new ConcurrentHashMap<>();
    static {
        PRODUCTS.put("IPHONE-15-PRO", Map.of(
            "name", "iPhone 15 Pro", "price", "8999", "stock", "充足",
            "spec", "钛金属、A17 Pro芯片、4800万像素", "color", "原色钛金属/蓝色钛金属/白色钛金属/黑色钛金属"
        ));
        PRODUCTS.put("AIRPODS-PRO", Map.of(
            "name", "AirPods Pro（第二代）", "price", "1999", "stock", "充足",
            "spec", "降噪、自适应音频、USB-C充电", "color", "白色"
        ));
        PRODUCTS.put("MACBOOK-AIR-M3", Map.of(
            "name", "MacBook Air M3", "price", "8999起", "stock", "紧张",
            "spec", "13.6英寸、M3芯片、18小时续航", "color", "午夜色/星光色/深空灰色/银色"
        ));
    }

    private Map<String, String> findProduct(String productCode) {
        Map<String, String> p = PRODUCTS.get(productCode);
        if (p == null) {
            for (var entry : PRODUCTS.entrySet()) {
                if (entry.getValue().get("name").contains(productCode) || productCode.contains(entry.getKey())) {
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
        StringBuilder sb = new StringBuilder();
        for (var entry : PRODUCTS.entrySet()) {
            Map<String, String> p = entry.getValue();
            if (p.get("name").contains(keyword) || keyword.contains(entry.getKey())) {
                sb.append("· ").append(p.get("name")).append(" — ¥").append(p.get("price")).append("\n");
            }
        }
        if (sb.isEmpty()) return "未找到匹配的商品";
        return "搜索结果：\n" + sb.toString().trim();
    }
}
