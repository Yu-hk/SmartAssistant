package com.example.smartassistant.recommend.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Product Feign 客户端熔断降级工厂。
 * <p>
 * 当 Product 服务不可用或熔断时，返回空列表/空字符串，
 * 避免上游（RecommendService）因 Feign 异常而整体失败。
 * </p>
 */
@Component
public class ProductFeignClientFallbackFactory implements FallbackFactory<ProductFeignClient> {

    private static final Logger log = LoggerFactory.getLogger(ProductFeignClientFallbackFactory.class);

    @Override
    public ProductFeignClient create(Throwable cause) {
        log.warn("[Feign] Product 服务熔断降级: {}", cause != null ? cause.getMessage() : "unknown");

        return new ProductFeignClient() {
            @Override
            public List<Map<String, String>> getAllProducts() {
                return Collections.emptyList();
            }

            @Override
            public List<Map<String, Object>> getProductRecommendations(String code) {
                return Collections.emptyList();
            }

            @Override
            public String getProductInfo(String code) {
                return "";
            }
        };
    }
}
