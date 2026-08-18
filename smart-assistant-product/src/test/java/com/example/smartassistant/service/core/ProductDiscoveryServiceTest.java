package com.example.smartassistant.service.core;

import com.example.smartassistant.spi.InMemoryProductBackend;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProductDiscoveryServiceTest {

    private final ProductDiscoveryService service =
            new ProductDiscoveryService(new InMemoryProductBackend());

    @Test
    void recognizesGenericPopularProductQuery() {
        assertThat(service.supports("现在有什么热门商品")).isTrue();
        assertThat(service.supports("给我一份商品列表")).isTrue();
        assertThat(service.supports("推荐无线耳机")).isFalse();
    }

    @Test
    void returnsCatalogInsteadOfPretendingToHaveSalesRanking() {
        ProductDiscoveryService.DiscoveryResult result =
                service.discover("现在有什么热门商品", 3);

        assertThat(result.productCount()).isEqualTo(3);
        assertThat(result.popularityBased()).isFalse();
        assertThat(result.answer())
                .contains("当前推荐商品")
                .contains("暂无足够销量数据")
                .contains("iPhone 15 Pro")
                .doesNotContain("数据库中未找到");
    }

    @Test
    void scenarioSpecificDiscoveryStatesEvidenceBoundary() {
        ProductDiscoveryService.DiscoveryResult result = service.discover(
                "查询适合十五人办公室和视频会议使用的热门商品，并比较适用场景", 3);

        assertThat(result.scenarioEvidenceLimited()).isTrue();
        assertThat(result.answer())
                .contains("不能把热度直接等同于适合")
                .contains("场景适配：现有目录证据不足")
                .contains("摄像头、麦克风")
                .contains("不应把上述候选表述为最终推荐");
    }
}
