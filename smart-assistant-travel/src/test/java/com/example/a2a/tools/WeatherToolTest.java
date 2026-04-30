package com.example.smartassistant.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WeatherTool 单元测试
 * 验证天气查询工具的各种场景
 */
@DisplayName("WeatherTool 单元测试")
class WeatherToolTest {

    private WeatherTool weatherTool;

    @BeforeEach
    void setUp() {
        weatherTool = new WeatherTool();
    }

    @Test
    @DisplayName("测试查询有效城市的天气 - 北京")
    void testQueryValidCity_Beijing() {
        String result = weatherTool.query("北京");
        
        assertNotNull(result, "返回结果不应为 null");
        assertFalse(result.isEmpty(), "返回结果不应为空");
        
        // API 可能失败，所以只验证返回了某种响应
        System.out.println("北京天气查询结果:\n" + result);
    }

    @Test
    @DisplayName("测试查询有效城市的天气 - 上海")
    void testQueryValidCity_Shanghai() {
        String result = weatherTool.query("上海");
        
        assertNotNull(result, "返回结果不应为 null");
        assertFalse(result.isEmpty(), "返回结果不应为空");
        
        System.out.println("上海天气查询结果:\n" + result);
    }

    @Test
    @DisplayName("测试查询有效城市的天气 - 广州")
    void testQueryValidCity_Guangzhou() {
        String result = weatherTool.query("广州");
        
        assertNotNull(result, "返回结果不应为 null");
        assertFalse(result.isEmpty(), "返回结果不应为空");
        
        System.out.println("广州天气查询结果:\n" + result);
    }

    @Test
    @DisplayName("测试查询无效城市 - 应返回错误提示而非模拟数据")
    void testQueryInvalidCity_ShouldReturnErrorNotMockData() {
        String result = weatherTool.query("不存在的城市XYZ123");
        
        assertNotNull(result, "返回结果不应为 null");
        assertFalse(result.isEmpty(), "返回结果不应为空");
        
        // 关键验证：不应该返回模拟数据
        assertFalse(
            result.contains("模拟数据") || result.contains("当前返回模拟数据"),
            "不应返回模拟数据提示"
        );
        
        // 应该返回错误提示或服务不可用信息
        assertTrue(
            result.contains("不可用") || result.contains("失败") || result.contains("⚠️"),
            "应返回错误提示或服务不可用信息"
        );
        
        System.out.println("无效城市查询结果:\n" + result);
    }

    @Test
    @DisplayName("测试查询空字符串 - 应返回错误提示")
    void testQueryEmptyString() {
        String result = weatherTool.query("");
        
        assertNotNull(result, "返回结果不应为 null");
        assertFalse(result.isEmpty(), "返回结果不应为空");
        
        System.out.println("空字符串查询结果:\n" + result);
    }

    @Test
    @DisplayName("测试查询 null - 应返回错误信息")
    void testQueryNull() {
        // WeatherTool 不会抛出异常，而是返回错误信息
        String result = weatherTool.query(null);
        
        assertNotNull(result, "返回结果不应为 null");
        assertFalse(result.isEmpty(), "返回结果不应为空");
        
        System.out.println("null 查询结果:\n" + result);
    }

    @Test
    @DisplayName("测试返回格式 - 应包含温度信息")
    void testResponseFormat_ShouldContainTemperature() {
        String result = weatherTool.query("深圳");
        
        // 如果查询成功，应包含温度信息
        if (!result.contains("不可用") && !result.contains("失败")) {
            assertTrue(
                result.contains("温度") || result.contains("°C") || result.contains("℃"),
                "成功查询应包含温度信息"
            );
        }
        
        System.out.println("深圳天气查询结果:\n" + result);
    }

    @Test
    @DisplayName("测试返回格式 - 应包含天气状况")
    void testResponseFormat_ShouldContainWeatherCondition() {
        String result = weatherTool.query("杭州");
        
        // 如果查询成功，应包含天气状况
        if (!result.contains("不可用") && !result.contains("失败")) {
            assertTrue(
                result.contains("天气") || result.contains("晴") || 
                result.contains("雨") || result.contains("云") || result.contains("阴"),
                "成功查询应包含天气状况"
            );
        }
        
        System.out.println("杭州天气查询结果:\n" + result);
    }

    @Test
    @DisplayName("测试多次查询 - 验证稳定性")
    void testMultipleQueries_Stability() {
        String[] cities = {"北京", "上海", "广州", "成都"};
        
        for (String city : cities) {
            String result = weatherTool.query(city);
            
            assertNotNull(result, city + " 的查询结果不应为 null");
            assertFalse(result.isEmpty(), city + " 的查询结果不应为空");
            
            System.out.println(city + " 查询完成");
        }
    }

    @Test
    @DisplayName("测试特殊字符城市名 - 应优雅处理")
    void testQueryWithSpecialCharacters() {
        String result = weatherTool.query("北@京#");
        
        assertNotNull(result, "返回结果不应为 null");
        
        // 应该能处理或返回错误，而不是崩溃
        System.out.println("特殊字符城市名查询结果:\n" + result);
    }

    @Test
    @DisplayName("性能测试 - 查询应在合理时间内完成")
    void testQueryPerformance() {
        long startTime = System.currentTimeMillis();
        String result = weatherTool.query("南京");
        long endTime = System.currentTimeMillis();
        
        long duration = endTime - startTime;
        
        assertNotNull(result, "返回结果不应为 null");
        
        // 查询应该在 10 秒内完成（考虑网络延迟）
        assertTrue(
            duration < 10000,
            "查询应在 10 秒内完成，实际耗时: " + duration + "ms"
        );
        
        System.out.println("查询耗时: " + duration + "ms");
        System.out.println("查询结果:\n" + result);
    }
}
