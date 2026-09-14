package com.example.smartassistant.spi;

import java.util.List;

/** Truthful fallback used when the live catalog is unavailable in production. */
public final class UnavailableProductBackend implements ProductBackend {

    private static final String MESSAGE = com.example.smartassistant.common.tool.ToolResult.error(
            com.example.smartassistant.common.error.AgentErrorCode.TOOL_EXECUTION_ERROR,
            ProductCatalogUnavailableException.MESSAGE);

    @Override public String queryProductInfo(String productCode) { return MESSAGE; }
    @Override public String checkStock(String productCode) { return MESSAGE; }
    @Override public String getPrice(String productCode) { return MESSAGE; }
    @Override public String searchProduct(String keyword) { return MESSAGE; }
    @Override public List<ProductSummary> listPopularProducts(int limit) { throw new ProductCatalogUnavailableException(); }
    @Override public List<String> listProductCategories() { throw new ProductCatalogUnavailableException(); }
}
