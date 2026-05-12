/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SmartTravelPlannerToolTest {

    @Mock private WeatherTool weatherTool;
    @Mock private AttractionRealtimeTool attractionTool;

    private SmartTravelPlannerTool planner;

    @BeforeEach
    void setUp() {
        planner = new SmartTravelPlannerTool(weatherTool, attractionTool);
    }

    @Test
    @DisplayName("完整行程规划返回天气+景点+天数信息")
    void testSmartPlanFull() {
        when(weatherTool.query("北京")).thenReturn("北京今天晴，22°C");
        when(attractionTool.getAttractionRealtimeInfo("热门景点", "北京", ""))
                .thenReturn("故宫、天坛、颐和园");

        String result = planner.smartPlan("北京", "3", "文化之旅");

        assertAll("plan should contain all sections",
                () -> assertTrue(result.contains("北京"), "应包含目的地"),
                () -> assertTrue(result.contains("3日"), "应包含天数"),
                () -> assertTrue(result.contains("文化之旅"), "应包含主题"),
                () -> assertTrue(result.contains("22°C"), "应包含天气"),
                () -> assertTrue(result.contains("故宫") || result.contains("天坛"), "应包含景点"),
                () -> assertTrue(result.contains("第 1 天"), "应包含每日框架"),
                () -> assertTrue(result.contains("第 3 天"), "应包含最后一天"));
    }

    @Test
    @DisplayName("默认天数为 3")
    void testDefaultDays() {
        when(weatherTool.query("上海")).thenReturn("上海多云");
        when(attractionTool.getAttractionRealtimeInfo("热门景点", "上海", ""))
                .thenReturn("外滩、东方明珠");

        String result = planner.smartPlan("上海", "abc", "");

        assertTrue(result.contains("3日"), "非法天数应使用默认值3");
    }

    @Test
    @DisplayName("天数为 1 时生成单日行程")
    void testSingleDayPlan() {
        when(weatherTool.query("杭州")).thenReturn("杭州小雨");
        when(attractionTool.getAttractionRealtimeInfo("热门景点", "杭州", ""))
                .thenReturn("西湖");

        String result = planner.smartPlan("杭州", "1", "亲子游");

        assertTrue(result.contains("1日"), "应包含1天信息");
        assertTrue(result.contains("亲子游"), "应包含偏好");
    }

    @Test
    @DisplayName("天数为 14 时作为上限")
    void testMaxDays() {
        when(weatherTool.query("成都")).thenReturn("成都多云");
        when(attractionTool.getAttractionRealtimeInfo("热门景点", "成都", ""))
                .thenReturn("宽窄巷子");

        String result = planner.smartPlan("成都", "20", "深度游");
        assertTrue(result.contains("14日"), "超过14天应截断为14天");
    }

    @Test
    @DisplayName("景点查询失败时返回部分结果而非抛出异常")
    void testAttractionFailureFallback() {
        when(weatherTool.query("南京")).thenReturn("南京晴");
        when(attractionTool.getAttractionRealtimeInfo("热门景点", "南京", ""))
                .thenThrow(new RuntimeException("API 超时"));

        String result = planner.smartPlan("南京", "2", "");
        assertNotNull(result);
        assertTrue(result.contains("南京"), "即使景点查询失败也应返回部分结果");
        assertTrue(result.contains("第 2 天"), "仍应包含天框架");
    }

    @Test
    @DisplayName("天气和景点工具被正确调用")
    void testToolInvocations() {
        when(weatherTool.query("西安")).thenReturn("西安多云");
        when(attractionTool.getAttractionRealtimeInfo("热门景点", "西安", ""))
                .thenReturn("兵马俑");

        planner.smartPlan("西安", "2", "");

        verify(weatherTool, times(1)).query("西安");
        verify(attractionTool, times(1)).getAttractionRealtimeInfo("热门景点", "西安", "");
    }
}
