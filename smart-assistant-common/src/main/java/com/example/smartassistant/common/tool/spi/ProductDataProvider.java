/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.tool.spi;

/**
 * Product domain data provider — implemented by the product module.
 * <p>Tool code accesses product information through this interface.</p>
 */
public interface ProductDataProvider {

    /** Query detailed product information by product code. */
    String queryProductInfo(String productCode);

    /** Check stock availability for a product. */
    String checkStock(String productCode);

    /** Get the price for a product. */
    String getPrice(String productCode);

    /** Search products by keyword. */
    String searchProduct(String keyword);
}
