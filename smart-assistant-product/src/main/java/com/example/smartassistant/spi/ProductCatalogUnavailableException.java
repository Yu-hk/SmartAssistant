package com.example.smartassistant.spi;

/** An unavailable catalog is not an empty catalog and must never produce demo recommendations. */
public final class ProductCatalogUnavailableException extends RuntimeException {
    public static final String CODE = "PRODUCT_CATALOG_UNAVAILABLE";
    public static final String MESSAGE = "商品目录暂时不可用，暂时无法核实商品价格、库存和推荐结果，请稍后重试。";

    public ProductCatalogUnavailableException() { super(MESSAGE); }
    public ProductCatalogUnavailableException(Throwable cause) { super(MESSAGE, cause); }
}
