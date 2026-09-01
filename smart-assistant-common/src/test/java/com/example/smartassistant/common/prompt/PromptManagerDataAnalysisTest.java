package com.example.smartassistant.common.prompt;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PromptManagerDataAnalysisTest {

    private final PromptManager promptManager = new PromptManager();

    @Test
    void rendersQueryAndVerifiedContextWithoutTemplatePlaceholders() {
        String prompt = promptManager.renderDataAnalysisExpert(
                "近 30 天销量是否增长",
                "2026-07 销量 100，2026-08 销量 120");

        assertThat(prompt)
                .contains("### 数据分析开始 ###")
                .contains("近 30 天销量是否增长")
                .contains("2026-07 销量 100，2026-08 销量 120")
                .contains("【数据概览】")
                .contains("【核心结论】")
                .doesNotContain("{{query}}", "{{context}}");
    }

    @Test
    void missingContextRequiresDataToolInsteadOfInventingFacts() {
        String prompt = promptManager.renderDataAnalysisExpert("用户增长如何", null);

        assertThat(prompt)
                .contains("必须先调用数据工具")
                .contains("不得直接给出具体数字")
                .doesNotContain("{{context}}");
    }

    @Test
    void rendersMachineReadableAuditAndVerifiedRecommendationPrompts() {
        String audit = promptManager.renderProductAnalysisAudit(
                "推荐耳机", "SKU-100，¥599；分析结果：符合预算");
        String recommendation = promptManager.renderVerifiedProductRecommendation(
                "推荐耳机", "分析事实核实通过");

        assertThat(audit)
                .contains("\"valid\": true")
                .contains("correction_instruction")
                .contains("销售量、性价比、口碑")
                .contains("违规分析维度")
                .contains("SKU-100，¥599")
                .doesNotContain("{{query}}", "{{context}}");
        assertThat(recommendation)
                .contains("\"conclusion\"")
                .contains("只输出一个合法 JSON 对象")
                .contains("销售量、性价比、口碑")
                .contains("没有明确用户偏好时不得虚构偏好")
                .contains("只负责推荐，不得创建订单")
                .contains("分析依据、核实过程、候选清单、维度评分和审计记录只用于内部判断")
                .doesNotContain("### 推荐核实完成 ###")
                .doesNotContain("{{query}}", "{{context}}");
    }

    @Test
    void restrictsProductAnalysisToThreeDimensionsAndConditionalPreference() {
        String withoutPreference = promptManager.renderDataAnalysisExpert(
                "推荐一款耳机",
                "SKU-100，销量 120，价格 599 元，评分 4.8，评价数 300");
        String withPreference = promptManager.renderDataAnalysisExpert(
                "推荐一款预算 600 元以内的降噪耳机",
                "用户偏好：预算 600 元以内、需要降噪；SKU-100，销量 120，价格 599 元，评分 4.8");

        assertThat(withoutPreference)
                .contains("销售量：")
                .contains("性价比：")
                .contains("口碑：")
                .contains("没有明确偏好时，禁止增加该维度")
                .contains("禁止自行增加热度、知名度、流行度、利润、综合实力等维度");
        assertThat(withPreference)
                .contains("用户偏好匹配度")
                .contains("预算 600 元以内")
                .contains("需要降噪");
    }
}
