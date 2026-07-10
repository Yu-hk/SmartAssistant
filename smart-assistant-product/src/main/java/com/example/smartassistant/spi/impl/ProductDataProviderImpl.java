/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.spi.impl;

import com.example.smartassistant.common.tool.spi.ProductDataProvider;
import com.example.smartassistant.spi.ProductBackend;
import org.springframework.stereotype.Component;

/**
 * Implementation of {@link ProductDataProvider} in the product module.
 * <p>Delegates all calls to the {@link ProductBackend} SPI implementation.</p>
 */
@Component
public class ProductDataProviderImpl implements ProductDataProvider {

    private final ProductBackend productBackend;

    public ProductDataProviderImpl(ProductBackend productBackend) {
        this.productBackend = productBackend;
    }

    @Override
    public String queryProductInfo(String productCode) {
        return productBackend.queryProductInfo(productCode);
    }

    @Override
    public String checkStock(String productCode) {
        return productBackend.checkStock(productCode);
    }

    @Override
    public String getPrice(String productCode) {
        return productBackend.getPrice(productCode);
    }

    @Override
    public String searchProduct(String keyword) {
        return productBackend.searchProduct(keyword);
    }
}
