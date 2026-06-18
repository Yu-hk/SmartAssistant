/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.spi;

/**
 * ⭐ 商品数据后端 SPI。
 * <p>
 * 下游集成商可以通过实现此接口替换默认的商品数据源。
 * 框架提供 {@link InMemoryProductBackend} 作为默认 Mock 实现，
 * 通过 {@code @ConditionalOnMissingBean} 自动注册。
 * </p>
 *
 * <h3>接入方式</h3>
 * <pre>
 * &#64;Component
 * public class MyDbProductBackend implements ProductBackend {
 *     // Spring 自动检测到此 Bean，默认的 InMemoryProductBackend 自动让位
 * }
 * </pre>
 */
public interface ProductBackend {

    /** 查询商品详细信息 */
    String queryProductInfo(String productCode);

    /** 查询商品库存状态 */
    String checkStock(String productCode);

    /** 查询商品价格 */
    String getPrice(String productCode);

    /** 搜索商品（按关键词模糊匹配） */
    String searchProduct(String keyword);
}
