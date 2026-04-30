package com.example.smartassistant.consumer.service.recommendation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import com.example.smartassistant.common.tokenizer.ChineseTokenizer;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * LLMSuggestionService 单元测试
 * <p>覆盖长对话场景下的智能建议生成，包括单意图延续和对话话题切换场景</p>
 */
@ExtendWith(MockitoExtension.class)
class LLMSuggestionServiceTest {

    @Mock
    private ChatClient.Builder chatClientBuilder;

    @Mock
    private ChatClient chatClient;

    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;

    @Mock
    private ChatClient.CallResponseSpec responseSpec;

    @Mock
    private SuggestionEngine fallbackSuggestionEngine;

    @Mock
    private ColdStartService coldStartService;

    @Mock
    private ContextualRecommendationService contextualService;

    @Mock
    private SuggestionPersonalizationService personalizationService;

    @Mock
    private ChineseTokenizer chineseTokenizer;

    private LLMSuggestionService llmSuggestionService;

    @BeforeEach
    void setUp() {
        when(chatClientBuilder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);

        // 默认行为：个性化排序直接返回原始列表（避免 Mockito 返回空列表）
        Mockito.lenient().when(personalizationService.personalizeSuggestions(anyString(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(1));

        llmSuggestionService = new LLMSuggestionService(
                chatClientBuilder,
                fallbackSuggestionEngine,
                coldStartService,
                contextualService,
                personalizationService,
                chineseTokenizer
        );
    }

    // ==================== 工具方法 ====================

    private List<Map<String, String>> buildHistory(String... entries) {
        List<Map<String, String>> history = new ArrayList<>();
        for (int i = 0; i < entries.length; i += 2) {
            Map<String, String> msg = new LinkedHashMap<>();
            msg.put("role", entries[i]);
            msg.put("content", entries[i + 1]);
            history.add(msg);
        }
        return history;
    }

    private String jsonSuggestions(String... items) {
        StringBuilder sb = new StringBuilder("{\"suggestions\":[");
        for (int i = 0; i < items.length; i++) {
            sb.append("\"").append(items[i]).append("\"");
            if (i < items.length - 1) sb.append(",");
        }
        sb.append("]}");
        return sb.toString();
    }

    // ==================== 1. 单意图长对话测试 ====================

    @Test
    @DisplayName("单意图长对话 - 天气话题延续，LLM 返回 JSON 建议")
    void testSingleIntentLongConversation_Weather() {
        // 对话历史：用户连续追问天气相关
        List<Map<String, String>> history = buildHistory(
                "user", "北京今天天气怎么样",
                "assistant", "北京今天晴，25°C",
                "user", "明天会下雨吗",
                "assistant", "明天有小雨，记得带伞",
                "user", "后天呢"
        );

        String llmResponse = jsonSuggestions(
                "未来一周北京天气趋势",
                "北京最佳出行时间",
                "下雨天北京室内景点推荐"
        );
        when(responseSpec.content()).thenReturn(llmResponse);

        List<String> suggestions = llmSuggestionService.generateSuggestions(
                "user_001", "后天呢", history, null, "北京后天多云转晴"
        );

        assertEquals(3, suggestions.size());
        assertEquals("未来一周北京天气趋势", suggestions.get(0));
        assertEquals("北京最佳出行时间", suggestions.get(1));
        assertEquals("下雨天北京室内景点推荐", suggestions.get(2));
    }

    @Test
    @DisplayName("单意图长对话 - 美食话题深挖，验证建议上下文连贯性")
    void testSingleIntentLongConversation_Food() {
        List<Map<String, String>> history = buildHistory(
                "user", "推荐北京烤鸭店",
                "assistant", "全聚德、便宜坊都是不错的选择",
                "user", "便宜坊有什么特色菜",
                "assistant", "便宜坊以焖炉烤鸭著称，还有芥末鸭掌"
        );

        String llmResponse = jsonSuggestions(
                "便宜坊营业时间",
                "焖炉烤鸭和挂炉烤鸭的区别",
                "北京其他老字号餐厅"
        );
        when(responseSpec.content()).thenReturn(llmResponse);

        List<String> suggestions = llmSuggestionService.generateSuggestions(
                "user_002", "便宜坊怎么预约", history, "喜欢传统美食", null
        );

        assertEquals(3, suggestions.size());
        assertTrue(suggestions.stream().anyMatch(s -> s.contains("便宜坊")));
    }

    // ==================== 2. 对话话题切换（原多意图场景） ====================

    @Test
    @DisplayName("对话话题切换 - 历史涉及天气+美食，建议应综合上下文")
    void testTopicSwitching_WeatherThenFood() {
        // 对话历史先天气后美食（话题切换）
        List<Map<String, String>> history = buildHistory(
                "user", "上海明天天气",
                "assistant", "上海明天晴，28°C",
                "user", "上海有什么好吃的",
                "assistant", "小笼包、生煎包是特色"
        );

        String llmResponse = jsonSuggestions(
                "上海本帮菜推荐",
                "上海城隍庙美食街",
                "上海晴天适合去的户外餐厅"
        );
        when(responseSpec.content()).thenReturn(llmResponse);

        List<String> suggestions = llmSuggestionService.generateSuggestions(
                "user_003", "推荐一家生煎店", history, null, null
        );

        assertEquals(3, suggestions.size());
        // 验证建议结合了地点（上海）和当前话题（美食）
        assertTrue(suggestions.stream().allMatch(s -> s.contains("上海")));
    }

    @Test
    @DisplayName("对话话题切换 - 历史涉及旅游+交通+美食，验证建议综合多个话题")
    void testTopicSwitching_TravelTransportFood() {
        List<Map<String, String>> history = buildHistory(
                "user", "杭州西湖怎么玩",
                "assistant", "西湖十景值得游览",
                "user", "怎么去西湖",
                "assistant", "地铁1号线到龙翔桥",
                "user", "附近有什么好吃的"
        );

        String llmResponse = jsonSuggestions(
                "西湖边杭帮菜餐厅",
                "龙翔桥地铁站附近小吃",
                "西湖醋鱼哪里最正宗"
        );
        when(responseSpec.content()).thenReturn(llmResponse);

        List<String> suggestions = llmSuggestionService.generateSuggestions(
                "user_004", "推荐一家西湖边的餐厅", history, "喜欢清淡口味", null
        );

        assertEquals(3, suggestions.size());
    }

    // ==================== 3. LLM 响应格式兼容性测试 ====================

    @Test
    @DisplayName("LLM 返回 markdown 代码块格式，应正确解析")
    void testMarkdownJsonParsing() {
        String llmResponse = "```json\n" +
                "{\"suggestions\":[\"建议一\",\"建议二\",\"建议三\"]}\n" +
                "```";
        when(responseSpec.content()).thenReturn(llmResponse);

        List<String> suggestions = llmSuggestionService.generateSuggestions(
                "user_005", "测试", Collections.emptyList(), null, null
        );

        assertEquals(3, suggestions.size());
        assertEquals("建议一", suggestions.get(0));
    }

    @Test
    @DisplayName("LLM 返回非 JSON 文本，应通过正则降级提取建议")
    void testRegexFallbackExtraction() {
        // LLM 不按格式输出，返回纯文本
        String llmResponse = "根据分析，用户可能想问：\"附近有什么景点\"、\"门票多少钱\"、\"最佳游览时间\"";
        when(responseSpec.content()).thenReturn(llmResponse);

        List<String> suggestions = llmSuggestionService.generateSuggestions(
                "user_006", "测试", Collections.emptyList(), null, null
        );

        // 正则应提取引号内容
        assertFalse(suggestions.isEmpty());
        assertTrue(suggestions.stream().anyMatch(s -> s.contains("附近有什么景点")));
    }

    @Test
    @DisplayName("LLM 返回空 suggestions 数组，应降级到规则引擎")
    void testEmptySuggestionsFallback() {
        when(responseSpec.content()).thenReturn("{\"suggestions\":[]}");
        when(fallbackSuggestionEngine.generateSuggestions(anyString(), anyString()))
                .thenReturn(List.of("规则引擎建议1", "规则引擎建议2"));

        List<String> suggestions = llmSuggestionService.generateSuggestions(
                "user_007", "成都美食", Collections.emptyList(), null, null
        );

        assertFalse(suggestions.isEmpty());
        verify(fallbackSuggestionEngine).generateSuggestions("成都美食", "auto");
    }

    @Test
    @DisplayName("LLM 调用异常，应降级到规则引擎")
    void testLlmExceptionFallback() {
        when(requestSpec.call()).thenThrow(new RuntimeException("LLM 服务异常"));
        when(fallbackSuggestionEngine.generateSuggestions(anyString(), anyString()))
                .thenReturn(List.of("异常降级建议"));

        List<String> suggestions = llmSuggestionService.generateSuggestions(
                "user_008", "测试", Collections.emptyList(), null, null
        );

        assertFalse(suggestions.isEmpty());
        assertEquals("异常降级建议", suggestions.get(0));
    }

    // ==================== 4. 特殊场景测试 ====================

    @Test
    @DisplayName("冷启动用户（anonymous），使用冷启动建议")
    void testColdStartAnonymousUser() {
        when(responseSpec.content()).thenReturn("{\"suggestions\":[]}");
        when(coldStartService.isColdStart("anonymous", 0)).thenReturn(true);
        when(coldStartService.generateColdStartSuggestions(anyString()))
                .thenReturn(List.of("冷启动建议1", "冷启动建议2", "冷启动建议3"));

        List<String> suggestions = llmSuggestionService.generateSuggestions(
                "anonymous", "北京", Collections.emptyList(), null, null
        );

        assertFalse(suggestions.isEmpty());
        verify(coldStartService).generateColdStartSuggestions(anyString());
    }

    @Test
    @DisplayName("有对话历史但 LLM 返回空，使用上下文推荐")
    void testContextualFallbackWithHistory() {
        List<Map<String, String>> history = buildHistory(
                "user", "重庆火锅推荐",
                "assistant", "推荐珮姐老火锅"
        );

        when(responseSpec.content()).thenReturn("{\"suggestions\":[]}");
        when(coldStartService.isColdStart("user_009", 2)).thenReturn(false);
        when(contextualService.generateContextualSuggestions(anyString(), any()))
                .thenReturn(List.of("上下文建议1", "上下文建议2"));

        List<String> suggestions = llmSuggestionService.generateSuggestions(
                "user_009", "还有别的吗", history, null, null
        );

        assertFalse(suggestions.isEmpty());
        verify(contextualService).generateContextualSuggestions(anyString(), any());
    }

    @Test
    @DisplayName("LLM 返回超过 5 条建议，应截断为 5 条")
    void testSuggestionLimit() {
        String llmResponse = jsonSuggestions("建议1", "建议2", "建议3", "建议4", "建议5", "建议6", "建议7");
        when(responseSpec.content()).thenReturn(llmResponse);

        List<String> suggestions = llmSuggestionService.generateSuggestions(
                "user_010", "测试", Collections.emptyList(), null, null
        );

        assertEquals(5, suggestions.size());
    }

    @Test
    @DisplayName("带用户画像时，Prompt 应包含画像信息")
    void testUserProfileIncludedInPrompt() {
        String llmResponse = jsonSuggestions("个性化建议");
        when(responseSpec.content()).thenReturn(llmResponse);

        String userProfile = "用户偏好：喜欢川菜，辣度中等，预算人均100元";

        llmSuggestionService.generateSuggestions(
                "user_011", "推荐餐厅", Collections.emptyList(), userProfile, null
        );

        // 验证 user 方法被调用，且参数包含用户画像
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).user(promptCaptor.capture());
        String prompt = promptCaptor.getValue();
        assertTrue(prompt.contains("用户画像"));
        assertTrue(prompt.contains("喜欢川菜"));
    }

    @Test
    @DisplayName("带 Router 结果摘要时，Prompt 应包含摘要信息")
    void testRouterResultIncludedInPrompt() {
        String llmResponse = jsonSuggestions("基于摘要的建议");
        when(responseSpec.content()).thenReturn(llmResponse);

        String routerResult = "Agent 返回：北京今天晴，25°C，适合户外活动";

        llmSuggestionService.generateSuggestions(
                "user_012", "北京天气", Collections.emptyList(), null, routerResult
        );

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).user(promptCaptor.capture());
        String prompt = promptCaptor.getValue();
        assertTrue(prompt.contains("当前回答摘要"));
        assertTrue(prompt.contains("北京今天晴"));
    }

    @Test
    @DisplayName("空对话历史，应正常生成建议")
    void testEmptyConversationHistory() {
        String llmResponse = jsonSuggestions("默认建议1", "默认建议2");
        when(responseSpec.content()).thenReturn(llmResponse);

        List<String> suggestions = llmSuggestionService.generateSuggestions(
                "user_013", "杭州旅游", Collections.emptyList(), null, null
        );

        assertEquals(2, suggestions.size());
    }

    @Test
    @DisplayName("超长 Router 结果应被截断到 300 字")
    void testLongRouterResultTruncation() {
        String llmResponse = jsonSuggestions("截断测试建议");
        when(responseSpec.content()).thenReturn(llmResponse);

        String longResult = "a".repeat(1000);

        llmSuggestionService.generateSuggestions(
                "user_014", "测试", Collections.emptyList(), null, longResult
        );

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).user(promptCaptor.capture());
        String prompt = promptCaptor.getValue();
        int summaryStart = prompt.indexOf("【当前回答摘要】");
        assertTrue(summaryStart > 0);
        String summaryPart = prompt.substring(summaryStart);
        // 摘要部分应包含截断标记 "..."
        assertTrue(summaryPart.contains("..."));
    }
}
