/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.product.tool;

import com.example.smartassistant.common.tool.spi.ProductDataProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Product consultation toolset.
 * <p>Data is accessed via {@link ProductDataProvider} SPI.</p>
 */
@Component
public class ProductTools {

    private static final Logger log = LoggerFactory.getLogger(ProductTools.class);

    private final ProductDataProvider productData;

    public ProductTools(ProductDataProvider productData) {
        this.productData = productData;
        log.info("[ProductTool] 初始化完成, provider={}", productData.getClass().getSimpleName());
    }


    @Tool(description = "查询商品详细信息，包括价格、规格、颜色、库存状态等")
    public String queryProductInfo(
            @ToolParam(description = "商品编码或名称，如 IPHONE-15-PRO", required = true) String productCode) {
        log.info("[ProductTool] 查商品: {}", productCode);
        return productData.queryProductInfo(productCode.trim().toUpperCase());
    }

    @Tool(description = "查询商品库存状态，返回是否可购买及预计发货时间")
    public String checkStock(
            @ToolParam(description = "商品编码，如 IPHONE-15-PRO", required = true) String productCode) {
        log.info("[ProductTool] 查库存: {}", productCode);
        return productData.checkStock(productCode.trim().toUpperCase());
    }

    @Tool(description = "查询商品价格，支持查询原价、促销价和是否支持分期")
    public String getPrice(
            @ToolParam(description = "商品编码，如 IPHONE-15-PRO", required = true) String productCode) {
        log.info("[ProductTool] 查价格: {}", productCode);
        return productData.getPrice(productCode.trim().toUpperCase());
    }
}
