package com.example.smartassistant.tools;

import com.example.smartassistant.common.tool.ToolResult;
import com.example.smartassistant.spi.ProductBackend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 商品咨询工具集。
 * <p>
 * 数据通过 {@link ProductBackend} SPI 获取，默认使用 {@code InMemoryProductBackend}，
 * 下游集成商实现自己的 {@link ProductBackend} 即可替换数据源。
 * </p>
 */
@Component
public class ProductTools {

    private static final Logger log = LoggerFactory.getLogger(ProductTools.class);

    private final ProductBackend productBackend;

    public ProductTools(ProductBackend productBackend) {
        this.productBackend = productBackend;
        log.info("[ProductTool] 初始化完成, backend={}", productBackend.getClass().getSimpleName());
    }

    @Tool(description = "查询商品详细信息，包括价格、规格、颜色、库存状态等")
    public String queryProductInfo(
            @ToolParam(description = "商品编码或名称，如 IPHONE-15-PRO", required = true) String productCode) {
        log.info("[ProductTool] 查商品: {}", productCode);
        return productBackend.queryProductInfo(productCode.trim().toUpperCase());
    }

    @Tool(description = "查询商品库存状态，返回是否可购买及预计发货时间")
    public String checkStock(
            @ToolParam(description = "商品编码，如 IPHONE-15-PRO", required = true) String productCode) {
        log.info("[ProductTool] 查库存: {}", productCode);
        return productBackend.checkStock(productCode.trim().toUpperCase());
    }

    @Tool(description = "查询商品价格，支持查询原价、促销价和是否支持分期")
    public String getPrice(
            @ToolParam(description = "商品编码，如 IPHONE-15-PRO", required = true) String productCode) {
        log.info("[ProductTool] 查价格: {}", productCode);
        return productBackend.getPrice(productCode.trim().toUpperCase());
    }
}
