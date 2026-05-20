package com.example.smartassistant.tools;

import com.example.smartassistant.common.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 商品咨询工具集。
 */
@Component
public class ProductTools {

    private static final Logger log = LoggerFactory.getLogger(ProductTools.class);

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

    @Tool(description = "查询商品详细信息，包括价格、规格、颜色、库存状态等")
    public String queryProductInfo(
            @ToolParam(description = "商品编码或名称，如 IPHONE-15-PRO", required = true) String productCode) {
        log.info("[ProductTool] 查商品: {}", productCode);
        Map<String, String> p = PRODUCTS.get(productCode);
        if (p == null) {
            // 尝试模糊匹配
            for (var entry : PRODUCTS.entrySet()) {
                if (entry.getValue().get("name").contains(productCode) || productCode.contains(entry.getKey())) {
                    p = entry.getValue();
                    break;
                }
            }
        }
        if (p == null) {
            return ToolResult.error("PRODUCT_NOT_FOUND", "未找到商品 " + productCode,
                    false, "请确认商品编码或名称是否正确");
        }
        return String.format(
            "%s\n价格：%s 元\n库存：%s\n规格：%s\n颜色：%s",
            p.get("name"), p.get("price"), p.get("stock"), p.get("spec"), p.get("color"));
    }

    @Tool(description = "查询商品库存状态，返回是否可购买及预计发货时间")
    public String checkStock(
            @ToolParam(description = "商品编码，如 IPHONE-15-PRO", required = true) String productCode) {
        log.info("[ProductTool] 查库存: {}", productCode);
        Map<String, String> p = PRODUCTS.get(productCode);
        if (p == null) return ToolResult.error("PRODUCT_NOT_FOUND", "未找到商品 " + productCode, false);
        String stock = p.get("stock");
        if ("充足".equals(stock)) {
            return p.get("name") + " 库存充足，下单后 24 小时内发货。";
        } else if ("紧张".equals(stock)) {
            return p.get("name") + " 库存紧张，建议尽快下单，预计 3-5 天发货。";
        }
        return p.get("name") + " 暂时缺货，补货时间待定。";
    }

    @Tool(description = "查询商品价格，支持查询原价、促销价和是否支持分期")
    public String getPrice(
            @ToolParam(description = "商品编码，如 IPHONE-15-PRO", required = true) String productCode) {
        log.info("[ProductTool] 查价格: {}", productCode);
        Map<String, String> p = PRODUCTS.get(productCode);
        if (p == null) return ToolResult.error("PRODUCT_NOT_FOUND", "未找到商品 " + productCode, false);
        return String.format("%s 售价 %s 元，支持 3/6/12/24 期免息分期。", p.get("name"), p.get("price"));
    }
}
