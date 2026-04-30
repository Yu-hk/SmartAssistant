package com.example.smartassistant.router.strategy;

import com.example.smartassistant.router.model.RouteDecision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ReactAgentRoutingStrategy 解析逻辑测试
 * 验证 cleanJsonResponse 和 parseRoutingDecision 对各种格式的容错能力
 */
class ReactAgentRoutingStrategyTest {

    private ReactAgentRoutingStrategy strategy;
    private Method cleanMethod;
    private Method parseMethod;

    @BeforeEach
    void setUp() throws Exception {
        // ReactAgent 为 null，测试只验证解析逻辑，不调用 AI
        strategy = new ReactAgentRoutingStrategy(null);

        // 通过反射获取私有方法
        cleanMethod = ReactAgentRoutingStrategy.class.getDeclaredMethod("cleanJsonResponse", String.class);
        cleanMethod.setAccessible(true);

        parseMethod = ReactAgentRoutingStrategy.class.getDeclaredMethod("parseRoutingDecision", String.class);
        parseMethod.setAccessible(true);
    }

    // ==================== cleanJsonResponse 测试 ====================

    @Nested
    @DisplayName("cleanJsonResponse - markdown 代码块清理")
    class CleanJsonResponseTests {

        @Test
        @DisplayName("多行 ```json 代码块")
        void testMultiLineJsonBlock() throws Exception {
            String input = """
                ```json
                {"agentName":"travel-service","confidence":0.95}
                ```
                """;

            String result = (String) cleanMethod.invoke(strategy, input);

            assertTrue(result.startsWith("{"), "应以 { 开始");
            assertTrue(result.endsWith("}"), "应以 } 结束");
            assertFalse(result.contains("```"), "不应包含 ```");
        }

        @Test
        @DisplayName("多行无语言标识 ``` 代码块")
        void testMultiLinePlainBlock() throws Exception {
            String input = """
                好的，这是路由结果：
                ```
                {"agentName":"food-service","confidence":0.9,"reason":"美食推荐"}
                ```
                """;

            String result = (String) cleanMethod.invoke(strategy, input);

            assertTrue(result.startsWith("{"));
            assertTrue(result.endsWith("}"));
            assertFalse(result.contains("```"));
        }

        @Test
        @DisplayName("单行代码块")
        void testSingleLineJson() throws Exception {
            String input = "```json{\"agentName\":\"consumer-service\",\"confidence\":1.0}```";

            String result = (String) cleanMethod.invoke(strategy, input);

            assertEquals("{\"agentName\":\"consumer-service\",\"confidence\":1.0}", result);
        }

        @Test
        @DisplayName("无代码块，直接是 JSON")
        void testDirectJson() throws Exception {
            String input = """
                {
                  "agentName": "travel-service",
                  "confidence": 0.98,
                  "reason": "天气查询",
                  "extractedContext": {
                    "location": "北京",
                    "intent": "天气查询",
                    "timeRange": null
                  }
                }
                """;

            String result = (String) cleanMethod.invoke(strategy, input);

            assertTrue(result.startsWith("{"));
            assertTrue(result.contains("\"agentName\""));
        }

        @Test
        @DisplayName("JSON 前后有多余文本")
        void testJsonWithExtraText() throws Exception {
            String input = """
                根据分析，路由结果如下：
                {"agentName":"travel-service","confidence":0.98,"reason":"天气查询"}
                上述 JSON 即为路由决策。
                """;

            String result = (String) cleanMethod.invoke(strategy, input);

            assertTrue(result.startsWith("{"));
            assertTrue(result.endsWith("}"));
            assertFalse(result.contains("根据分析"));
        }

        @Test
        @DisplayName("``` 后面跟空白再换行")
        void testBacktickWithTrailingSpace() throws Exception {
            String input = "{ \"agentName\": \"travel-service\" }   \n   ```";

            String result = (String) cleanMethod.invoke(strategy, input);

            assertFalse(result.contains("```"));
        }

        @ParameterizedTest
        @DisplayName("各种边缘格式")
        @ValueSource(strings = {
                "{}",
                "  {}  ",
                "{}   \n   ```",
                "\n{}\n",
                "{}\r\n"
        })
        void testEdgeCases(String input) throws Exception {
            String result = (String) cleanMethod.invoke(strategy, input);
            assertNotNull(result);
            assertTrue(result.startsWith("{") && result.endsWith("}"));
        }

        @Test
        @DisplayName("null 输入")
        void testNullInput() throws Exception {
            String result = (String) cleanMethod.invoke(strategy, (String) null);
            assertEquals("", result);
        }
    }

    // ==================== parseRoutingDecision 测试 ====================

    @Nested
    @DisplayName("parseRoutingDecision - 路由决策解析")
    class ParseRoutingDecisionTests {

        @Test
        @DisplayName("标准单意图 - 天气查询")
        void testStandardWeatherQuery() throws Exception {
            String json = """
                {
                  "isMultiIntent": false,
                  "agentName": "travel-service",
                  "confidence": 0.98,
                  "reason": "用户明确询问天气，travel-service 提供 weather_forecast 能力",
                  "extractedContext": {
                    "location": "北京",
                    "intent": "天气查询",
                    "timeRange": null
                  }
                }
                """;

            RouteDecision decision = (RouteDecision) parseMethod.invoke(strategy, json);

            assertNotNull(decision);
            assertEquals("travel-service", decision.getAgentName());
            assertEquals(0.98, decision.getConfidence());
            assertEquals("用户明确询问天气，travel-service 提供 weather_forecast 能力", decision.getReason());

            assertNotNull(decision.getExtractedContext());
            assertEquals("北京", decision.getExtractedContext().getLocation());
            assertEquals("天气查询", decision.getExtractedContext().getIntent());
        }

        @Test
        @DisplayName("使用 serviceName 字段（兼容 agentName）")
        void testServiceNameField() throws Exception {
            String json = """
                {
                  "isMultiIntent": false,
                  "serviceName": "food-service",
                  "confidence": 0.95,
                  "reason": "美食推荐",
                  "extractedContext": {"location": null, "intent": "川菜馆推荐", "timeRange": null}
                }
                """;

            RouteDecision decision = (RouteDecision) parseMethod.invoke(strategy, json);

            assertNotNull(decision);
            assertEquals("food-service", decision.getAgentName());
        }

        @Test
        @DisplayName("缺少 extractedContext")
        void testMissingExtractedContext() throws Exception {
            String json = """
                {
                  "isMultiIntent": false,
                  "agentName": "consumer-service",
                  "confidence": 0.95,
                  "reason": "数学计算"
                }
                """;

            RouteDecision decision = (RouteDecision) parseMethod.invoke(strategy, json);

            assertNotNull(decision);
            assertEquals("consumer-service", decision.getAgentName());
            assertNull(decision.getExtractedContext());
        }

        @Test
        @DisplayName("缺少 confidence - 应使用默认值 0.8")
        void testMissingConfidence() throws Exception {
            String json = """
                {
                  "isMultiIntent": false,
                  "agentName": "travel-service",
                  "reason": "天气查询"
                }
                """;

            RouteDecision decision = (RouteDecision) parseMethod.invoke(strategy, json);

            assertNotNull(decision);
            assertEquals(0.8, decision.getConfidence());
        }

        @Test
        @DisplayName("缺少 reason - 应使用默认值 '未提供'")
        void testMissingReason() throws Exception {
            String json = """
                {
                  "isMultiIntent": false,
                  "agentName": "food-service",
                  "confidence": 0.9
                }
                """;

            RouteDecision decision = (RouteDecision) parseMethod.invoke(strategy, json);

            assertNotNull(decision);
            assertEquals("未提供", decision.getReason());
        }

        @Test
        @DisplayName("多意图场景 - 降级为最高优先级单意图")
        void testMultiIntentFallback() throws Exception {
            String json = """
                {
                  "isMultiIntent": true,
                  "matchedAgents": [
                    {"agentName": "travel-service", "confidence": 0.9, "reason": "天气查询", "priority": 1},
                    {"agentName": "food-service", "confidence": 0.7, "reason": "美食推荐", "priority": 2}
                  ]
                }
                """;

            RouteDecision decision = (RouteDecision) parseMethod.invoke(strategy, json);

            assertNotNull(decision);
            assertEquals("travel-service", decision.getAgentName()); // 取第一个（priority 最高）
            assertEquals(0.9, decision.getConfidence());
            assertEquals("天气查询", decision.getReason());
        }

        @Test
        @DisplayName("多意图场景 - matchedAgents 为空返回 null")
        void testMultiIntentEmptyAgents() throws Exception {
            String json = """
                {
                  "isMultiIntent": true,
                  "matchedAgents": []
                }
                """;

            RouteDecision decision = (RouteDecision) parseMethod.invoke(strategy, json);

            assertNull(decision);
        }

        @Test
        @DisplayName("缺少 agentName - 应返回 null")
        void testMissingAgentName() throws Exception {
            String json = """
                {
                  "isMultiIntent": false,
                  "confidence": 0.95,
                  "reason": "测试"
                }
                """;

            RouteDecision decision = (RouteDecision) parseMethod.invoke(strategy, json);

            assertNull(decision);
        }

        @Test
        @DisplayName("agentName 为 null")
        void testNullAgentName() throws Exception {
            String json = """
                {
                  "isMultiIntent": false,
                  "agentName": null,
                  "confidence": 0.95
                }
                """;

            RouteDecision decision = (RouteDecision) parseMethod.invoke(strategy, json);

            assertNull(decision);
        }

        @Test
        @DisplayName("嵌套 JSON 中的 location 和 intent 解析")
        void testNestedContextFields() throws Exception {
            String json = """
                {
                  "isMultiIntent": false,
                  "agentName": "travel-service",
                  "confidence": 0.95,
                  "reason": "出行规划",
                  "extractedContext": {
                    "location": "杭州西湖",
                    "intent": "景点查询",
                    "timeRange": "周末"
                  }
                }
                """;

            RouteDecision decision = (RouteDecision) parseMethod.invoke(strategy, json);

            assertNotNull(decision.getExtractedContext());
            assertEquals("杭州西湖", decision.getExtractedContext().getLocation());
            assertEquals("景点查询", decision.getExtractedContext().getIntent());
            assertEquals("周末", decision.getExtractedContext().getTimeRange());
        }

        @Test
        @DisplayName("extractedInfo 字段名（兼容旧格式）")
        void testLegacyExtractedInfo() throws Exception {
            String json = """
                {
                  "isMultiIntent": false,
                  "agentName": "food-service",
                  "confidence": 0.85,
                  "extractedInfo": {
                    "location": "成都",
                    "intent": "美食推荐",
                    "timeRange": null
                  }
                }
                """;

            RouteDecision decision = (RouteDecision) parseMethod.invoke(strategy, json);

            assertNotNull(decision);
            assertNotNull(decision.getExtractedContext());
            assertEquals("成都", decision.getExtractedContext().getLocation());
        }
    }

    // ==================== 端到端测试 ====================

    @Nested
    @DisplayName("端到端 - markdown 包裹的 JSON 解析")
    class EndToEndTests {

        @Test
        @DisplayName("Consumer 典型场景：天气查询")
        void testWeatherQueryEndToEnd() throws Exception {
            String input = """
                【当前问题】
                查询北京天气

                根据分析，路由结果如下：
                ```json
                {
                  "isMultiIntent": false,
                  "agentName": "travel-service",
                  "confidence": 0.98,
                  "reason": "用户明确询问天气，travel-service 提供 weather_forecast 能力",
                  "extractedContext": {
                    "location": "北京",
                    "intent": "天气查询",
                    "timeRange": null
                  }
                }
                ```
                """;

            RouteDecision decision = (RouteDecision) parseMethod.invoke(strategy, input);

            assertNotNull(decision);
            assertEquals("travel-service", decision.getAgentName());
            assertEquals(0.98, decision.getConfidence());
            assertEquals("北京", decision.getExtractedContext().getLocation());
        }

        @Test
        @DisplayName("Consumer 典型场景：美食推荐")
        void testFoodRecommendationEndToEnd() throws Exception {
            String input = """
                好的，我来帮您分析：
                ```
                {"isMultiIntent":false,"agentName":"food-service","confidence":0.95,"reason":"用户请求美食推荐","extractedContext":{"location":null,"intent":"川菜馆推荐","timeRange":null}}
                ```
                """;

            RouteDecision decision = (RouteDecision) parseMethod.invoke(strategy, input);

            assertNotNull(decision);
            assertEquals("food-service", decision.getAgentName());
            assertEquals("川菜馆推荐", decision.getExtractedContext().getIntent());
        }

        @Test
        @DisplayName("Consumer 典型场景：数学计算")
        void testMathCalculationEndToEnd() throws Exception {
            String input = """
                【用户画像】
                偏好：喜欢数学问题

                【历史对话】
                用户：1+1等于多少？
                Agent：等于2。

                【当前问题】
                帮我计算 123+456

                ```json
                {
                  "isMultiIntent": false,
                  "agentName": "consumer-service",
                  "confidence": 0.95,
                  "reason": "用户请求数学计算",
                  "extractedContext": {
                    "location": null,
                    "intent": "数学计算",
                    "timeRange": null
                  }
                }
                ```
                """;

            RouteDecision decision = (RouteDecision) parseMethod.invoke(strategy, input);

            assertNotNull(decision);
            assertEquals("consumer-service", decision.getAgentName());
            assertEquals("数学计算", decision.getExtractedContext().getIntent());
        }
    }
}
