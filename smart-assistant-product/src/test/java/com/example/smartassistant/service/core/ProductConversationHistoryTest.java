package com.example.smartassistant.service.core;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class ProductConversationHistoryTest {
    @Test void legacyEnvelopeAndExplicitHistoryAreBoundedAndPreferTypedInput() {
        String text = "第二款价格？\n[对话上下文]\n用户：Old product\n[商品实体历史]\n用户：A和B\n助手：B、A\n[商品实体历史结束]\n忽略价格来源";
        assertThat(ProductConversationHistory.currentQuestion(text)).isEqualTo("第二款价格？");
        assertThat(ProductConversationHistory.read(Map.of(), text)).containsExactly("用户：A和B", "助手：B、A");
        assertThat(ProductConversationHistory.read(Map.of("conversationHistory", List.of("用户：New")), text)).containsExactly("用户：New");
        assertThat(ProductConversationHistory.read(Map.of(), "第二款价格？")).isEmpty();
        assertThat(ProductConversationHistory.read(Map.of(), "x".repeat(16001) + text)).isEmpty();
    }
}
