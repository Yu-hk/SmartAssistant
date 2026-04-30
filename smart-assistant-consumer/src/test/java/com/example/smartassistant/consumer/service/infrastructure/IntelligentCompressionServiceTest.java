package com.example.smartassistant.consumer.service.infrastructure;

import com.example.smartassistant.consumer.config.ConversationCompressionProperties;
import com.example.smartassistant.consumer.dto.StructuredPrompt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * IntelligentCompressionService 单元测试
 * 覆盖三级压缩策略、Token 估算、降级逻辑
 */
class IntelligentCompressionServiceTest {

    private ConversationCompressionProperties properties;
    private IntelligentCompressionService service;

    @BeforeEach
    void setUp() {
        properties = new ConversationCompressionProperties();
        properties.setEnabled(true);
        properties.setMaxRoundsBeforeCompress(5);
        properties.setMaxCharsBeforeCompress(2000);
        properties.setMaxTokensBeforeCompress(1000);
        properties.setKeepRecentRounds(3);
        properties.setStrategy("auto");
        properties.setLlmSummaryMaxChars(150);
        properties.setTokenEstimateRatio(0.8);

        service = new IntelligentCompressionService(Optional.empty(), properties);
    }

    // ==================== 基础功能测试 ====================

    @Test
    void testNoCompressionNeeded() {
        List<StructuredPrompt.ConversationMessage> history = buildHistory(4); // 4轮 < 5轮阈值

        var result = service.smartCompress(history);

        assertFalse(result.compressed());
        assertEquals(8, result.history().size()); // 4轮 = 8条消息
        assertEquals("none", result.strategy());
    }

    @Test
    void testCompressionDisabled() {
        properties.setEnabled(false);
        List<StructuredPrompt.ConversationMessage> history = buildHistory(20);

        var result = service.smartCompress(history);

        assertFalse(result.compressed());
        assertEquals(40, result.history().size());
    }

    @Test
    void testNullAndEmptyHistory() {
        var result1 = service.smartCompress(null);
        assertTrue(result1.history().isEmpty());
        assertFalse(result1.compressed());

        var result2 = service.smartCompress(new ArrayList<>());
        assertTrue(result2.history().isEmpty());
    }

    // ==================== 策略 1：滑动窗口截断 ====================

    @Test
    void testSlidingWindowTruncate() {
        // 8轮 = maxRounds(5) * 1.6，触发 truncate 策略
        properties.setStrategy("truncate");
        List<StructuredPrompt.ConversationMessage> history = buildHistory(8);

        var result = service.smartCompress(history);

        assertTrue(result.compressed());
        // 保留 3 轮 = 6 条 + 1 条系统摘要消息
        assertEquals(7, result.history().size());
        assertEquals("system", result.history().get(0).getRole());
        assertTrue(result.history().get(0).getContent().contains("截断"));
    }

    @Test
    void testAutoStrategyUsesTruncateForShortHistory() {
        // 8轮 <= 5*2=10，应使用 truncate
        List<StructuredPrompt.ConversationMessage> history = buildHistory(8);

        var result = service.smartCompress(history);

        assertTrue(result.compressed());
        assertEquals("truncate", result.strategy());
    }

    // ==================== 策略 2：关键信息提取 ====================

    @Test
    void testKeyInfoExtraction() {
        properties.setStrategy("extract");
        List<StructuredPrompt.ConversationMessage> history = buildHistoryWithEntities(12);

        var result = service.smartCompress(history);

        assertTrue(result.compressed());
        // 结果应包含：系统消息 + 提取的关键信息 + 最近 3 轮(6条)
        assertTrue(result.history().size() >= 6);
    }

    @Test
    void testAutoStrategyUsesExtractForMediumHistory() {
        // 12轮 > 5*2=10 且 <= 5*4=20，应使用 extract
        List<StructuredPrompt.ConversationMessage> history = buildHistory(12);

        var result = service.smartCompress(history);

        assertTrue(result.compressed());
        assertEquals("extract", result.strategy());
    }

    @Test
    void testGreetingMessagesAreFiltered() {
        properties.setStrategy("extract");
        List<StructuredPrompt.ConversationMessage> history = new ArrayList<>();
        history.add(msg("user", "你好"));
        history.add(msg("agent", "你好！有什么可以帮您的？"));
        history.add(msg("user", "北京有什么好吃的？"));
        history.add(msg("agent", "北京有很多美食，比如烤鸭、涮羊肉。"));

        // 补充到足够轮数以触发压缩
        history.addAll(buildHistory(10));

        var result = service.smartCompress(history);

        assertTrue(result.compressed());
        // 寒暄消息应该被过滤掉或精简
        boolean hasGreeting = result.history().stream()
                .anyMatch(m -> m.getContent() != null && m.getContent().equals("你好"));
        assertFalse(hasGreeting, "寒暄消息应被过滤");
    }

    // ==================== 策略 3：LLM 语义摘要 ====================

    @Test
    void testLlmSummaryCompress() {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec responseSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn("用户询问北京美食，偏好川菜，预算约100元。");

        properties.setStrategy("llm");
        service = new IntelligentCompressionService(Optional.of(chatClient), properties);

        List<StructuredPrompt.ConversationMessage> history = buildHistory(25);
        var result = service.smartCompress(history);

        assertTrue(result.compressed());
        assertEquals("llm", result.strategy());
        assertEquals("system", result.history().get(0).getRole());
        assertTrue(result.history().get(0).getContent().contains("摘要"));
    }

    @Test
    void testAutoStrategyUsesLlmForLongHistory() {
        // 25轮 > 5*4=20，策略判断为 llm（无 ChatClient 时执行降级为 extract）
        List<StructuredPrompt.ConversationMessage> history = buildHistory(25);

        var result = service.smartCompress(history);

        assertTrue(result.compressed());
        // 策略判断仍为 llm，执行层面降级
        assertEquals("llm", result.strategy());
    }

    @Test
    void testLlmFallbackToExtractOnException() {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        when(chatClient.prompt(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenThrow(new RuntimeException("LLM 服务异常"));

        properties.setStrategy("llm");
        service = new IntelligentCompressionService(Optional.of(chatClient), properties);

        List<StructuredPrompt.ConversationMessage> history = buildHistory(25);
        var result = service.smartCompress(history);

        // 即使 LLM 异常，也应进行压缩（降级为 extract）
        assertNotNull(result.history());
        assertFalse(result.history().isEmpty());
    }

    // ==================== Token 估算测试 ====================

    @Test
    void testEstimateTokensEmpty() {
        assertEquals(0, service.estimateTokens(null));
        assertEquals(0, service.estimateTokens(new ArrayList<>()));
    }

    @Test
    void testEstimateTokensChinese() {
        List<StructuredPrompt.ConversationMessage> history = List.of(
                msg("user", "北京今天天气怎么样？"),
                msg("agent", "北京今天晴朗，气温15-25度。")
        );

        int tokens = service.estimateTokens(history);
        // 中文字符约 23 个 * 0.8 ≈ 18.4，加上英文单词估算，总 token 应在合理范围
        assertTrue(tokens > 0);
        assertTrue(tokens < 100);
    }

    @Test
    void testTokenThresholdTriggersCompression() {
        // 构建超长的中文历史，使 token 数超过 1000
        List<StructuredPrompt.ConversationMessage> history = new ArrayList<>();
        String longSentence = "北京" + "今天天气很好".repeat(50); // 约 300 字
        for (int i = 0; i < 6; i++) {
            history.add(msg("user", longSentence + " 问题" + i));
            history.add(msg("agent", longSentence + " 回答" + i));
        }

        var result = service.smartCompress(history);

        assertTrue(result.compressed(), "Token 超过阈值应触发压缩");
    }

    // ==================== 兼容旧接口测试 ====================

    @Test
    void testLegacyCompressInterface() {
        List<StructuredPrompt.ConversationMessage> history = buildHistory(12);

        List<StructuredPrompt.ConversationMessage> compressed =
                service.compress(history, 3);

        // legacy 接口保证进行了压缩处理（具体策略由 auto 决定）
        assertNotNull(compressed);
        assertTrue(compressed.size() > 0);
        // 至少包含系统摘要消息或进行了截断
        boolean hasSystemMsg = compressed.stream()
                .anyMatch(m -> "system".equals(m.getRole()));
        assertTrue(hasSystemMsg || compressed.size() < history.size(),
                "压缩后应包含系统摘要或消息数减少");
    }

    // ==================== 辅助方法 ====================

    private List<StructuredPrompt.ConversationMessage> buildHistory(int rounds) {
        List<StructuredPrompt.ConversationMessage> list = new ArrayList<>();
        for (int i = 0; i < rounds; i++) {
            list.add(msg("user", "这是第 " + (i + 1) + " 轮用户问题"));
            list.add(msg("agent", "这是第 " + (i + 1) + " 轮助手回答"));
        }
        return list;
    }

    private List<StructuredPrompt.ConversationMessage> buildHistoryWithEntities(int rounds) {
        List<StructuredPrompt.ConversationMessage> list = new ArrayList<>();
        String[] cities = {"北京", "上海", "广州", "深圳", "杭州", "成都"};
        for (int i = 0; i < rounds; i++) {
            String city = cities[i % cities.length];
            list.add(msg("user", city + "有什么好吃的？预算50元左右"));
            list.add(msg("agent", city + "推荐火锅和烧烤，人均约45元。"));
        }
        return list;
    }

    private StructuredPrompt.ConversationMessage msg(String role, String content) {
        return StructuredPrompt.ConversationMessage.builder()
                .role(role)
                .content(content)
                .timestamp(System.currentTimeMillis())
                .build();
    }
}
