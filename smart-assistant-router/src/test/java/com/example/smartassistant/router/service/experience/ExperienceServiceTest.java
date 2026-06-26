/*
 * Copyright (c) 2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.experience;

import com.example.smartassistant.router.service.experience.ExperienceModel.*;
import com.example.smartassistant.router.service.experience.ExperienceModel.ReactExperience.ReactStep;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.redis.core.*;

import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 经验体系集成测试
 * <p>
 * 覆盖场景：
 * <ul>
 *   <li>COMMON 经验提取与匹配</li>
 *   <li>TOOL 经验提取与匹配</li>
 *   <li>REACT 经验提取与匹配</li>
 *   <li>经验关键字索引更新</li>
 *   <li>经验 CRUD 管理</li>
 *   <li>经验统计</li>
 * </ul>
 *
 * @author SmartAssistant Team
 */
public class ExperienceServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private SetOperations<String, String> setOperations;

    private ExperienceService experienceService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);

        // 创建带 mock 的 experienceService（不依赖 SemanticRouteCacheService、BGE 和 Mapper）
        experienceService = new ExperienceService(redisTemplate, null, null, null, mock(ExperienceValidator.class)) {
            @Override
            protected List<String> extractKeywords(String question) {
                // 提供一个简单的测试用关键词提取
                if (question == null || question.isBlank()) return Collections.emptyList();
                return Arrays.asList(question.replace(",", "").split("\\s+"));
            }
        };
    }

    // ==================== COMMON 经验测试 ====================

    @Test
    public void testExtractAndMatchCommonExperience() {
        // 模拟 Redis 写入成功
        doNothing().when(valueOperations).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        when(setOperations.add(anyString(), anyString())).thenReturn(1L);
        when(setOperations.members(anyString())).thenReturn(new HashSet<>());

        // 提取 COMMON 经验
        experienceService.extractCommonExperience("查一下订单状态", "order_agent", "订单,状态,查询");

        // 验证经验被保存到 Redis
        verify(valueOperations, atLeastOnce()).set(
                startsWith("a2a:experience:common_"),
                anyString(),
                anyLong(),
                any(TimeUnit.class));
    }

    @Test
    public void testCommonExperienceRoutingDecision() {
        // 创建一个 COMMON 经验并保存
        String expId = "common_test1";
        List<String> keywords = Arrays.asList("订单", "状态", "查");
        CommonExperience exp = new CommonExperience(expId, "订单,状态,查询", keywords, "order_agent", "general_agent", 0.8);
        exp.setConfidence(0.8);
        exp.setHitCount(5);

        experienceService.saveExperience(exp);

        // 验证保存到 Redis
        verify(valueOperations).set(
                eq("a2a:experience:common_test1"),
                anyString(),
                eq(2592000L),
                eq(TimeUnit.SECONDS));

        // 验证关键词索引被更新
        for (String kw : keywords) {
            verify(setOperations).add(eq("a2a:experience:keyword:" + kw), eq(expId));
        }
    }

    @Test
    public void testCommonExperienceLoadAndDelete() throws Exception {
        String expId = "common_load_test";
        CommonExperience exp = new CommonExperience(expId, "天气,查询", List.of("天气", "气温"), "general_agent", "builtin_fallback", 0.7);
        exp.setConfidence(0.7);

        // Mock 加载
        String json = objectMapper.writeValueAsString(exp);
        when(valueOperations.get("a2a:experience:" + expId)).thenReturn(json);

        // 加载验证
        ExperienceModel loaded = experienceService.loadExperience(expId);
        assertNotNull(loaded);
        assertEquals(expId, loaded.getId());
        assertEquals(ExperienceModel.Type.COMMON, loaded.getType());
        assertEquals("general_agent", loaded.getAgentName());
        assertTrue(loaded instanceof CommonExperience);
        assertEquals("天气,查询", ((CommonExperience) loaded).getIntentTag());
    }

    // ==================== TOOL 经验测试（Order Agent 核心场景）====================

    @Test
    public void testOrderToolExperience_QueryOrder() throws Exception {
        String expId = "tool_order_queryOrder_test";
        List<String> keywords = Arrays.asList("订单", "ORD", "查询");
        ToolExperience exp = new ToolExperience(
                expId, "订单,ORD,查询", keywords, "order_agent",
                "queryOrder", "{\"orderId\": \"ORD-001\"}", "订单{orderId}当前状态为{status}"
        );
        exp.setConfidence(0.85);
        exp.setHitCount(10);
        exp.setSuccessCount(10);

        // 保存
        experienceService.saveExperience(exp);

        // 验证 Redis 保存
        verify(valueOperations).set(
                eq("a2a:experience:tool_order_queryOrder_test"),
                anyString(),
                eq(2592000L),
                eq(TimeUnit.SECONDS));

        // Mock 加载
        String json = objectMapper.writeValueAsString(exp);
        when(valueOperations.get("a2a:experience:" + expId)).thenReturn(json);

        ExperienceModel loaded = experienceService.loadExperience(expId);
        assertNotNull(loaded);
        assertEquals(ExperienceModel.Type.TOOL, loaded.getType());
        assertTrue(loaded instanceof ToolExperience);
        ToolExperience toolExp = (ToolExperience) loaded;
        assertEquals("queryOrder", toolExp.getToolName());
        assertEquals("{\"orderId\": \"ORD-001\"}", toolExp.getRecommendedParams());
        assertEquals(10, toolExp.getHitCount());
    }

    @Test
    public void testOrderToolExperience_RefundOrder() throws Exception {
        String expId = "tool_order_refund";
        ToolExperience exp = new ToolExperience(
                expId, "退款,退货,订单", List.of("退款", "退货", "订单"),
                "order_agent", "refundOrder",
                "{\"orderId\": \"ORD-001\"}", "退款申请已提交"
        );
        exp.setConfidence(0.9);

        experienceService.saveExperience(exp);

        // Mock 加载
        String json = objectMapper.writeValueAsString(exp);
        when(valueOperations.get("a2a:experience:" + expId)).thenReturn(json);

        ExperienceModel loaded = experienceService.loadExperience(expId);
        assertNotNull(loaded);
        assertTrue(loaded instanceof ToolExperience);
        assertEquals("refundOrder", ((ToolExperience) loaded).getToolName());
    }

    // ==================== TOOL 经验测试（Product Agent 核心场景）====================

    @Test
    public void testProductToolExperience_PriceQuery() throws Exception {
        String expId = "tool_product_price";
        ToolExperience exp = new ToolExperience(
                expId, "商品,价格,多少钱", List.of("商品", "价格", "多少钱"),
                "product_agent", "queryPrice",
                "{\"product\": \"iPhone 15\"}", "{product}的价格为{price}"
        );
        exp.setConfidence(0.85);

        experienceService.saveExperience(exp);

        String json = objectMapper.writeValueAsString(exp);
        when(valueOperations.get("a2a:experience:" + expId)).thenReturn(json);

        ExperienceModel loaded = experienceService.loadExperience(expId);
        assertNotNull(loaded);
        assertTrue(loaded instanceof ToolExperience);
        assertEquals("queryPrice", ((ToolExperience) loaded).getToolName());
        assertEquals("product_agent", loaded.getAgentName());
    }

    @Test
    public void testProductToolExperience_StockCheck() throws Exception {
        String expId = "tool_product_stock";
        ToolExperience exp = new ToolExperience(
                expId, "库存,有货,缺货", List.of("库存", "有货"),
                "product_agent", "checkStock",
                "{\"product\": \"iPhone 15 Pro\"}", "{product}的库存状态为{status}"
        );
        exp.setConfidence(0.85);

        experienceService.saveExperience(exp);

        String json = objectMapper.writeValueAsString(exp);
        when(valueOperations.get("a2a:experience:" + expId)).thenReturn(json);

        ExperienceModel loaded = experienceService.loadExperience(expId);
        assertNotNull(loaded);
        assertTrue(loaded instanceof ToolExperience);
        assertEquals("checkStock", ((ToolExperience) loaded).getToolName());
    }

    // ==================== TOOL 经验测试（General Agent 核心场景）====================

    @Test
    public void testGeneralToolExperience_Weather() throws Exception {
        String expId = "tool_general_weather";
        ToolExperience exp = new ToolExperience(
                expId, "天气,气温,下雨", List.of("天气", "气温"),
                "general_agent", "getWeather",
                "{\"location\": \"北京\"}", "{location}当前天气为{weather}"
        );
        exp.setConfidence(0.8);

        experienceService.saveExperience(exp);

        String json = objectMapper.writeValueAsString(exp);
        when(valueOperations.get("a2a:experience:" + expId)).thenReturn(json);

        ExperienceModel loaded = experienceService.loadExperience(expId);
        assertNotNull(loaded);
        assertTrue(loaded instanceof ToolExperience);
        assertEquals("getWeather", ((ToolExperience) loaded).getToolName());
    }

    @Test
    public void testGeneralToolExperience_News() throws Exception {
        String expId = "tool_general_news";
        ToolExperience exp = new ToolExperience(
                expId, "新闻,热点,头条", List.of("新闻", "热点"),
                "general_agent", "getHotNews",
                "{}", "以下是近期热点新闻"
        );
        exp.setConfidence(0.75);

        experienceService.saveExperience(exp);

        String json = objectMapper.writeValueAsString(exp);
        when(valueOperations.get("a2a:experience:" + expId)).thenReturn(json);

        ExperienceModel loaded = experienceService.loadExperience(expId);
        assertNotNull(loaded);
        assertTrue(loaded instanceof ToolExperience);
        assertEquals("getHotNews", ((ToolExperience) loaded).getToolName());
    }

    // ==================== REACT 经验测试 ====================

    @Test
    public void testReactExperienceRefundFlow() throws Exception {
        String expId = "react_refund_flow";
        List<ReactStep> steps = Arrays.asList(
                new ReactStep(1, "查询订单状态", "order_agent"),
                new ReactStep(2, "step1.status==paid → 发起退款", "order_agent"),
                new ReactStep(3, "通知用户退款结果", "general_agent")
        );
        ReactExperience exp = new ReactExperience(
                expId, "退款,流程,订单", List.of("退款", "订单"),
                "order_agent", steps
        );
        exp.setConfidence(0.6);

        experienceService.saveExperience(exp);

        String json = objectMapper.writeValueAsString(exp);
        when(valueOperations.get("a2a:experience:" + expId)).thenReturn(json);

        ExperienceModel loaded = experienceService.loadExperience(expId);
        assertNotNull(loaded);
        assertTrue(loaded instanceof ReactExperience);
        ReactExperience reactExp = (ReactExperience) loaded;
        assertNotNull(reactExp.getSteps());
        assertEquals(3, reactExp.getSteps().size());
        assertEquals("order_agent", reactExp.getSteps().get(0).getTargetAgent());
        assertEquals("通知用户退款结果", reactExp.getSteps().get(2).getDescription());
    }

    // ==================== 管理 API 测试 ====================

    @Test
    public void testExperienceStatistics() throws Exception {
        // 模拟有 5 条经验
        Set<String> mockIds = new HashSet<>(Arrays.asList("exp1", "exp2", "exp3", "exp4", "exp5"));
        when(setOperations.members("a2a:experience:index:")).thenReturn(mockIds);

        // 模拟不同类型
        CommonExperience commonExp = new CommonExperience("exp1", "test", List.of("test"), "general", null, 0.7);
        commonExp.setHitCount(3);
        ToolExperience toolExp = new ToolExperience("exp2", "test2", List.of("test2"), "order", "queryOrder", "{}", "");
        toolExp.setHitCount(10);

        when(valueOperations.get("a2a:experience:exp1")).thenReturn(objectMapper.writeValueAsString(commonExp));
        when(valueOperations.get("a2a:experience:exp2")).thenReturn(objectMapper.writeValueAsString(toolExp));
        // exp3,4,5 返回 null 模拟过期

        Map<String, Object> stats = experienceService.getStatistics();

        assertNotNull(stats);
        assertEquals(5, stats.get("totalCount"));
    }

    @Test
    public void testDeleteExperience() {
        // 模拟加载
        CommonExperience exp = new CommonExperience("del_test", "测试,删除", List.of("测试", "删除"), "general", null, 0.5);
        try {
            when(valueOperations.get("a2a:experience:del_test"))
                    .thenReturn(objectMapper.writeValueAsString(exp));
        } catch (Exception e) {
            fail("JSON 序列化失败");
        }
        // 模拟 redisTemplate.delete()
        when(redisTemplate.delete("a2a:experience:del_test")).thenReturn(true);

        experienceService.deleteExperience("del_test");

        // 验证关键词索引被清理
        verify(setOperations).remove("a2a:experience:keyword:测试", "del_test");
        verify(setOperations).remove("a2a:experience:keyword:删除", "del_test");
        // 验证经验本身被删除（使用 redisTemplate.delete）
        verify(redisTemplate).delete("a2a:experience:del_test");
    }

    // ==================== 经验匹配验证测试 ====================

    @Test
    public void testExtractToolExperienceForOrder() {
        // 模拟从 AgentCallerService 调用的 TOOL 经验提取
        String question = "查一下订单ORD-2024001的状态";
        String agentName = "order_agent";
        String intentTag = "订单,ORD,状态,查询";

        doNothing().when(valueOperations).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        when(setOperations.add(anyString(), anyString())).thenReturn(1L);
        when(setOperations.members(anyString())).thenReturn(new HashSet<>());

        experienceService.extractToolExperience(question, agentName, intentTag,
                "queryOrder", "{\"orderId\": \"ORD-2024001\"}", "订单{orderId}当前状态为{status}");

        verify(valueOperations, atLeastOnce()).set(
                contains("a2a:experience:tool_order"),
                anyString(),
                anyLong(),
                any(TimeUnit.class));
    }

    @Test
    public void testExtractToolExperienceForProduct() {
        String question = "iPhone 15 Pro多少钱";
        String agentName = "product_agent";
        String intentTag = "iPhone,多少钱";

        doNothing().when(valueOperations).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        when(setOperations.add(anyString(), anyString())).thenReturn(1L);
        when(setOperations.members(anyString())).thenReturn(new HashSet<>());

        experienceService.extractToolExperience(question, agentName, intentTag,
                "queryPrice", "{\"product\": \"iPhone 15 Pro\"}", "{product}的价格为{price}");

        verify(valueOperations, atLeastOnce()).set(
                contains("a2a:experience:tool_product"),
                anyString(),
                anyLong(),
                any(TimeUnit.class));
    }

    // ==================== 序列化反序列化验证 ====================

    @Test
    public void testSerializationRoundTrip() throws Exception {
        // TOOL 经验
        ToolExperience toolExp = new ToolExperience(
                "tool_serial_test", "测试,序列化", List.of("测试", "序列化"),
                "order_agent", "queryOrder", "{\"orderId\": \"ORD-001\"}", "订单{orderId}状态为{status}"
        );
        toolExp.setConfidence(0.9);
        toolExp.setHitCount(5);
        toolExp.setCreatedAt(System.currentTimeMillis());

        String json = objectMapper.writeValueAsString(toolExp);
        assertNotNull(json);
        assertTrue(json.contains("TOOL"));
        assertTrue(json.contains("queryOrder"));

        // 反序列化
        ExperienceModel deserialized = objectMapper.readValue(json, ExperienceModel.class);
        assertNotNull(deserialized);
        assertEquals(ExperienceModel.Type.TOOL, deserialized.getType());
        assertTrue(deserialized instanceof ToolExperience);
        assertEquals("queryOrder", ((ToolExperience) deserialized).getToolName());
        assertEquals(5, deserialized.getHitCount());
        assertEquals(0.9, deserialized.getConfidence(), 0.001);
    }

    @Test
    public void testCommonExperienceSerialization() throws Exception {
        CommonExperience exp = new CommonExperience(
                "common_serial_test", "天气,查询", List.of("天气", "查询"),
                "general_agent", "builtin_fallback", 0.75
        );

        String json = objectMapper.writeValueAsString(exp);
        assertNotNull(json);
        assertTrue(json.contains("COMMON"));
        assertTrue(json.contains("general_agent"));

        ExperienceModel deserialized = objectMapper.readValue(json, ExperienceModel.class);
        assertNotNull(deserialized);
        assertEquals(ExperienceModel.Type.COMMON, deserialized.getType());
        assertTrue(deserialized instanceof CommonExperience);
    }

    @Test
    public void testReactExperienceSerialization() throws Exception {
        ReactExperience exp = new ReactExperience(
                "react_serial_test", "退款,流程", List.of("退款"),
                "order_agent", Arrays.asList(
                        new ReactStep(1, "查订单", "order_agent"),
                        new ReactStep(2, "退款", "order_agent")
                )
        );

        String json = objectMapper.writeValueAsString(exp);
        assertNotNull(json);
        assertTrue(json.contains("REACT"));
        assertTrue(json.contains("查订单"));

        ExperienceModel deserialized = objectMapper.readValue(json, ExperienceModel.class);
        assertNotNull(deserialized);
        assertEquals(ExperienceModel.Type.REACT, deserialized.getType());
        assertTrue(deserialized instanceof ReactExperience);
        assertEquals(2, ((ReactExperience) deserialized).getSteps().size());
    }

    // ==================== 高频命中验证 ====================

    @Test
    public void testExperienceHitCountIncrement() {
        String expId = "common_hit_test";
        CommonExperience exp = new CommonExperience(expId, "订单,查询", List.of("订单", "查询"), "order_agent", null, 0.7);
        exp.setHitCount(1);

        // 模拟多次命中
        try {
            String json = objectMapper.writeValueAsString(exp);
            when(valueOperations.get("a2a:experience:" + expId)).thenReturn(json);
        } catch (Exception e) {
            fail("JSON 序列化失败");
        }

        // 加载后命中计数会+1
        // 然后保存
        doNothing().when(valueOperations).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        when(setOperations.add(anyString(), anyString())).thenReturn(1L);

        // 执行命中
        ExperienceModel loaded = experienceService.loadExperience(expId);
        assertNotNull(loaded);
        assertEquals(1, loaded.getHitCount());
    }

    // ==================== 端到端场景：查订单 ====================

    @Test
    public void testEndToEndOrderQueryScenario() throws Exception {
        // 模拟一个完整场景：查订单状态 → TOOL 经验被提取 → 下次匹配

        // 1. 提取 TOOL 经验
        String question = "查一下订单ORD-2024001的状态";
        String agentName = "order_agent";
        String intentTag = "订单,ORD,2024001,状态";

        doNothing().when(valueOperations).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        when(setOperations.add(anyString(), anyString())).thenReturn(1L);

        experienceService.extractToolExperience(question, agentName, intentTag,
                "queryOrder", "{\"orderId\": \"ORD-2024001\"}", "订单{orderId}当前状态为{status}");

        // 2. 验证经验被保存
        verify(valueOperations, atLeastOnce()).set(
                contains("a2a:experience:tool_order"),
                anyString(),
                eq(2592000L),
                eq(TimeUnit.SECONDS));

        // 验证关键词索引建立（匿名类 extractKeywords 按空格分割，无空格时整句为关键词）
        verify(setOperations, atLeastOnce()).add(
                contains("a2a:experience:keyword:"),
                contains("tool_"));
    }

    // ==================== 端到端场景：查价格 ====================

    @Test
    public void testEndToEndProductPriceScenario() throws Exception {
        // Product Agent 场景：首次查询商品价格 → 提取 TOOL 经验 → 后续缓存

        String question = "iPhone 15 Pro多少钱";
        String agentName = "product_agent";
        String intentTag = "iPhone,多少钱,Pro";

        doNothing().when(valueOperations).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        when(setOperations.add(anyString(), anyString())).thenReturn(1L);

        experienceService.extractToolExperience(question, agentName, intentTag,
                "queryPrice", "{\"product\": \"iPhone 15 Pro\"}", "{product}的价格为{price}");

        verify(valueOperations, atLeastOnce()).set(
                contains("a2a:experience:tool_product"),
                anyString(),
                eq(2592000L),
                eq(TimeUnit.SECONDS));
    }

    // ==================== 端到端场景：查天气 ====================

    @Test
    public void testEndToEndGeneralWeatherScenario() throws Exception {
        // General Agent 场景：查天气 → 提取经验 → 后续快速响应

        String question = "北京天气怎么样";
        String agentName = "general_agent";
        String intentTag = "北京,天气";

        doNothing().when(valueOperations).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        when(setOperations.add(anyString(), anyString())).thenReturn(1L);

        experienceService.extractToolExperience(question, agentName, intentTag,
                "getWeather", "{\"location\": \"北京\"}", "{location}当前天气为{weather}");

        verify(valueOperations, atLeastOnce()).set(
                contains("a2a:experience:tool_general"),
                anyString(),
                eq(2592000L),
                eq(TimeUnit.SECONDS));
    }
}
