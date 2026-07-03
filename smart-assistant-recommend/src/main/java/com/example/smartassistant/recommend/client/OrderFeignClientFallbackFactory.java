package com.example.smartassistant.recommend.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Order Feign 客户端熔断降级工厂。
 * <p>
 * 当 Order 服务不可用或熔断时，返回空列表，
 * 避免上游（RecommendService）因 Feign 异常而整体失败。
 * </p>
 */
@Component
public class OrderFeignClientFallbackFactory implements FallbackFactory<OrderFeignClient> {

    private static final Logger log = LoggerFactory.getLogger(OrderFeignClientFallbackFactory.class);

    @Override
    public OrderFeignClient create(Throwable cause) {
        log.warn("[Feign] Order 服务熔断降级: {}", cause != null ? cause.getMessage() : "unknown");

        return new OrderFeignClient() {
            @Override
            public List<String> getUserPurchasedProducts(Long userId) {
                return Collections.emptyList();
            }

            @Override
            public List<Map<String, Object>> getUserOrders(Long userId) {
                return Collections.emptyList();
            }
        };
    }
}
