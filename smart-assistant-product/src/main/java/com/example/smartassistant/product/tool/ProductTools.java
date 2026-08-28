/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.product.tool;

import com.example.smartassistant.common.tool.spi.ProductDataProvider;
import com.example.smartassistant.service.core.ProductDiscoveryService;
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
    private final ProductDiscoveryService productDiscoveryService;

    public ProductTools(ProductDataProvider productData,
                        ProductDiscoveryService productDiscoveryService) {
        this.productData = productData;
        this.productDiscoveryService = productDiscoveryService;
        log.info("[ProductTool] 初始化完成, provider={}", productData.getClass().getSimpleName());
    }


    @Tool(description = "查询商品详细信息，包括价格、规格、颜色、库存状态等")
    public String queryProductInfo(
            @ToolParam(description = "商品编码或名称", required = true) String productCode) {
        log.info("[ProductTool] 查商品: {}", productCode);
        return productData.queryProductInfo(productCode.trim().toUpperCase());
    }

    @Tool(description = "查询商品库存状态，返回是否可购买及预计发货时间")
    public String checkStock(
            @ToolParam(description = "商品编码", required = true) String productCode) {
        log.info("[ProductTool] 查库存: {}", productCode);
        return productData.checkStock(productCode.trim().toUpperCase());
    }

    @Tool(description = "查询商品价格，支持查询原价、促销价和是否支持分期")
    public String getPrice(
            @ToolParam(description = "商品编码", required = true) String productCode) {
        log.info("[ProductTool] 查价格: {}", productCode);
        return productData.getPrice(productCode.trim().toUpperCase());
    }

    @Tool(description = "查询当前热门商品、推荐商品或商品列表；没有销量数据时会明确返回当前可售商品")
    public String listRecommendedProducts(
            @ToolParam(description = "最多返回多少件商品，默认 5，最大 10", required = false) Integer limit) {
        log.info("[ProductTool] 查询推荐商品: limit={}", limit);
        return productDiscoveryService.discover("热门商品", limit).answer();
    }

    @Tool(description = "查询商品目录中当前存在的全部商品类型；商品类型来自实时目录，不使用固定枚举")
    public String listProductCategories() {
        log.info("[ProductTool] 查询全部商品类型");
        var categories = productDiscoveryService.listProductCategories();
        return categories.isEmpty()
                ? "当前商品目录中没有可用的商品类型。"
                : "当前商品类型：" + String.join("、", categories);
    }
}
