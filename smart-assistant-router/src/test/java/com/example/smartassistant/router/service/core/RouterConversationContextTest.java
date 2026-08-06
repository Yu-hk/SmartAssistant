package com.example.smartassistant.router.service.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouterConversationContextTest {

    @Test
    void contextualFollowUpIncludesPreviousUserQuestion() {
        String enriched = RouterService.addConversationContextIfNeeded(
                "如果我更看重续航和便携，应该优先关注什么？",
                List.of("用户：我想买一款适合办公的笔记本电脑。", "助手：请看选购指标。"));

        assertTrue(enriched.contains("办公的笔记本电脑"));
        assertTrue(enriched.contains("不要再次要求用户说明产品类型"));
    }

    @Test
    void independentQuestionIsNotPollutedByHistory() {
        String question = "北京今天天气怎么样？";
        assertEquals(question, RouterService.addConversationContextIfNeeded(
                question, List.of("用户：我想买笔记本电脑。")));
    }
}
