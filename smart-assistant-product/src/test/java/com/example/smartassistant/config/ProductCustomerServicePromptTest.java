package com.example.smartassistant.config;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;

class ProductCustomerServicePromptTest {
    @Test
    void customerStyleKeepsFactAndConfirmationBoundaries() throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream("prompts/product-system-prompt.txt")) {
            assertThat(input).isNotNull();
            String prompt = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(prompt).contains("商品客服", "一至三句", "不要每轮重复问候", "不强制每轮追问",
                    "不主动扩展到销量", "不能从价格推断", "未知说成不支持", "需要商品事实时必须调用当前可用工具",
                    "不创建订单", "专业措辞不能改变", "所有面向用户的商品回复都不展示内部商品编码",
                    "不得要求用户提供内部编码", "真实型号和规格", "以本轮用户提问确定回答范围",
                    "独立的回答维度", "不是输出清单", "用户明确同时询问多个维度才一起回答",
                    "完整回答即结束，不另加服务邀约");
        }
    }
}
