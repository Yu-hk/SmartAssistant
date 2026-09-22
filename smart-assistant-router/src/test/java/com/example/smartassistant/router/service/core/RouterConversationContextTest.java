package com.example.smartassistant.router.service.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouterConversationContextTest {

    @Test
    void parameterAnswersKeepContextWithoutAuthorizingHistoricalOrderIntent() {
        for (String question : List.of("重量上限1公斤，金额为3000元", "重量上线3公斤把，金额改为5000元",
                "确认按这个条件来筛选")) {
            String enriched = RouterService.addConversationContextIfNeeded(question,
                    List.of("用户：帮我下单一款便携式笔记本，预算3000以内", "助手：请提供重量上限"));
            assertTrue(enriched.startsWith(question));
            assertTrue(enriched.contains("便携式笔记本"));
            assertTrue(enriched.contains("最近一轮用户明确提供的值"));
            assertTrue(enriched.contains("不代表确认下单"));
        }
    }

    @Test
    void contextualFollowUpIncludesPreviousUserQuestion() {
        String enriched = RouterService.addConversationContextIfNeeded(
                "如果我更看重续航和便携，应该优先关注什么？",
                List.of("用户：我想买一款适合办公的笔记本电脑。", "助手：请看选购指标。"));

        assertTrue(enriched.contains("办公的笔记本电脑"));
        assertTrue(enriched.contains("不要再次要求用户说明产品类型"));
    }

    @Test
    void specificationsFollowUpInheritsProductNotAssistantOffer() {
        String enriched = RouterService.addConversationContextIfNeeded("这个规格是多少",
                List.of("用户：AirPods Pro多少钱？有货吗？", "助手：还可以了解颜色和规格。"));
        assertTrue(enriched.startsWith("这个规格是多少"));
        assertTrue(enriched.contains("AirPods Pro"));
        assertTrue(enriched.contains("回答维度以本轮用户问题为准"));
        org.junit.jupiter.api.Assertions.assertFalse(enriched.contains("还可以了解颜色和规格"));
    }

    @Test
    void repeatedFollowUpsKeepOriginalProductAndCurrentScope() {
        String enriched = RouterService.addConversationContextIfNeeded("颜色呢？", List.of(
                "用户：AirPods Pro多少钱？有货吗？", "助手：可以再了解颜色和规格。",
                "用户：这个规格是多少", "助手：支持降噪和 USB-C。"));
        assertTrue(enriched.startsWith("颜色呢？"));
        assertTrue(enriched.contains("AirPods Pro多少钱"));
        assertTrue(enriched.contains("这个规格是多少"));
        org.junit.jupiter.api.Assertions.assertFalse(enriched.contains("可以再了解"));
        org.junit.jupiter.api.Assertions.assertFalse(enriched.contains("支持降噪"));
    }

    @Test
    void productSwitchRetainsChronologicalUserReferences() {
        String enriched = RouterService.addConversationContextIfNeeded("这个颜色呢", List.of(
                "用户：AirPods Pro多少钱？", "用户：换成 iPhone 15，规格如何？"));
        assertTrue(enriched.indexOf("AirPods Pro") < enriched.indexOf("iPhone 15"));
        assertTrue(enriched.contains("以最近明确提及的商品为准"));
    }

    @Test
    void independentQuestionIsNotPollutedByHistory() {
        String question = "北京今天天气怎么样？";
        assertEquals(question, RouterService.addConversationContextIfNeeded(
                question, List.of("用户：我想买笔记本电脑。")));
        String shopping = "推荐蓝牙耳机，预算2000以内";
        assertEquals(shopping, RouterService.addConversationContextIfNeeded(shopping,
                List.of("用户：推荐重量上限1公斤的笔记本，预算5000元")));
    }
}
