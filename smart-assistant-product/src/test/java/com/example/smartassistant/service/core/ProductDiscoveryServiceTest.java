package com.example.smartassistant.service.core;

import com.example.smartassistant.spi.InMemoryProductBackend;
import com.example.smartassistant.spi.ProductBackend;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ProductDiscoveryServiceTest {

    private final ProductDiscoveryService service =
            new ProductDiscoveryService(new InMemoryProductBackend());

    @Test
    void recognizesGenericPopularProductQuery() {
        assertThat(service.supports("现在有什么热门商品")).isTrue();
        assertThat(service.supports("给我一份商品列表")).isTrue();
        assertThat(service.supports("推荐无线耳机")).isTrue();
    }

    @Test
    void obtainsCategoriesFromBackendInsteadOfHardCodingThem() {
        ProductBackend backend = new InMemoryProductBackend() {
            @Override
            public List<String> listProductCategories() {
                return List.of("投影仪");
            }

            @Override
            public List<ProductSummary> listPopularProducts(ProductDiscoveryCriteria criteria) {
                return List.of(new ProductSummary("PROJECTOR-01", "会议投影仪",
                        new BigDecimal("3999"), "充足", "4K", 12L, "投影仪"));
            }
        };
        ProductDiscoveryService dynamicService = new ProductDiscoveryService(backend);

        assertThat(dynamicService.listProductCategories()).containsExactly("投影仪");
        assertThat(dynamicService.supports("推荐一款热门投影仪")).isTrue();
        assertThat(dynamicService.discover("推荐一款热门投影仪", 1).category())
                .isEqualTo("投影仪");
    }

    @Test
    void doesNotGuessWhenCategoryFragmentIsAmbiguous() {
        assertThat(service.discover("推荐电脑", "电脑", 5).category()).isEqualTo("电脑");
    }

    @Test
    void discoversOnlyTabletCandidatesForEncodedNaturalLanguageRequest() {
        String question = "我想买一部平板电脑，帮我推荐一款热门的&#x20;";

        ProductDiscoveryService.DiscoveryResult result = service.discover(question, 1);

        assertThat(service.supports(question)).isTrue();
        assertThat(result.category()).isEqualTo("平板电脑");
        assertThat(result.products())
                .singleElement()
                .satisfies(product -> {
                    assertThat(product.code()).isEqualTo("IPAD-PRO-M4");
                    assertThat(product.category()).isEqualTo("平板电脑");
                });
        assertThat(result.answer())
                .contains("推荐平板电脑", "iPad Pro M4")
                .doesNotContain("AirPods", "iPhone", "MacBook");
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
    void exposesSalesPriceAndReputationEvidenceForPopularProducts() {
        ProductBackend backend = new InMemoryProductBackend() {
            @Override
            public List<ProductSummary> listPopularProducts(ProductDiscoveryCriteria criteria) {
                return List.of(new ProductSummary("ROBOT-01", "扫拖机器人",
                        new BigDecimal("2999"), "充足", "自动集尘", 1680L,
                        "智能家居", new BigDecimal("3499"), new BigDecimal("4.8"), 7350L));
            }
        };

        ProductDiscoveryService.DiscoveryResult result =
                new ProductDiscoveryService(backend).discover("热门商品", 5);

        assertThat(result.popularityBased()).isTrue();
        assertThat(result.answer())
                .contains("近30天站内销量：1680")
                .contains("参考价：¥3499")
                .contains("评分：4.8/5（7350条评价）");
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

    @Test
    void appliesBudgetAndCategoryAsHardCandidateConstraints() {
        AtomicReference<ProductBackend.ProductDiscoveryCriteria> receivedCriteria =
                new AtomicReference<>();
        ProductBackend backend = new InMemoryProductBackend() {
            @Override
            public List<String> listProductCategories() {
                return List.of("手机");
            }

            @Override
            public List<ProductSummary> listPopularProducts(ProductDiscoveryCriteria criteria) {
                receivedCriteria.set(criteria);
                return List.of(
                        new ProductSummary("PHONE-PRO", "旗舰手机",
                                new BigDecimal("8999"), "充足", "旗舰芯片", 99, "手机"),
                        new ProductSummary("PHONE-LITE", "高性价比手机",
                                new BigDecimal("3999"), "充足", "长续航", 80, "手机"));
            }
        };
        ProductDiscoveryService constrainedService = new ProductDiscoveryService(backend);

        ProductDiscoveryService.DiscoveryResult result = constrainedService.discover(
                "我预算5000元，优先性价比，只看手机", 5);

        assertThat(constrainedService.supports("我预算5000元，优先性价比，只看手机")).isTrue();
        assertThat(receivedCriteria.get().category()).isEqualTo("手机");
        assertThat(receivedCriteria.get().maxPrice()).isEqualByComparingTo("5000");
        assertThat(result.products()).extracting(ProductBackend.ProductSummary::code)
                .containsExactly("PHONE-LITE");
        assertThat(result.answer()).contains("高性价比手机").doesNotContain("旗舰手机");
    }

    @Test
    void explainsWhenNoProductSatisfiesHardBudget() {
        ProductDiscoveryService.DiscoveryResult result = service.discover(
                "预算1000元以内，只看手机", 5);

        assertThat(result.productCount()).isZero();
        assertThat(result.answer()).contains("手机", "不超过1000元", "调整预算或品类");
    }

    @Test
    void appliesExplicitStockAvailabilityAsHardConstraint() {
        ProductBackend backend = new InMemoryProductBackend() {
            @Override
            public List<String> listProductCategories() {
                return List.of("手机");
            }

            @Override
            public List<ProductSummary> listPopularProducts(ProductDiscoveryCriteria criteria) {
                assertThat(criteria.inStockOnly()).isTrue();
                return List.of(
                        new ProductSummary("SOLD-OUT", "缺货手机",
                                new BigDecimal("2999"), "售罄", "", 100, "手机"),
                        new ProductSummary("IN-STOCK", "现货手机",
                                new BigDecimal("3999"), "充足", "", 80, "手机"));
            }
        };

        ProductDiscoveryService.DiscoveryResult result =
                new ProductDiscoveryService(backend).discover("只看有货手机，预算5000元", 5);

        assertThat(result.products()).extracting(ProductBackend.ProductSummary::code)
                .containsExactly("IN-STOCK");
    }
}
