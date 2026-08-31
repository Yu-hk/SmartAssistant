package com.example.smartassistant.spi;

import java.util.List;

/** Truthful fallback used when the live catalog is unavailable in production. */
public final class UnavailableProductBackend implements ProductBackend {

    private static final String MESSAGE = "商品目录暂时不可用，请稍后重试。";

    @Override public String queryProductInfo(String productCode) { return MESSAGE; }
    @Override public String checkStock(String productCode) { return MESSAGE; }
    @Override public String getPrice(String productCode) { return MESSAGE; }
    @Override public String searchProduct(String keyword) { return MESSAGE; }
    @Override public List<ProductSummary> listPopularProducts(int limit) { return List.of(); }
    @Override public List<String> listProductCategories() { return List.of(); }
}
