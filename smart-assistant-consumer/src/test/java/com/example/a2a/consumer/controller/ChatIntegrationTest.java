/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.controller;

import com.example.smartassistant.consumer.client.RouterClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * A2A Consumer 集成测试
 * 通过 HTTP chat 接口模拟远程调用，验证地点识别功能
 * <p>
 * Router 是独立服务，因此在本测试中固定远程边界，只验证 Consumer HTTP/异步响应契约。
 * Router 与真实模型的端到端行为由部署后的客服场景回归单独验证。
 * </p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChatIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RouterClient routerClient;

    @BeforeEach
    void stubRouterResponse() {
        when(routerClient.callRouterRaw(anyString(), anyString(), any(), anyString(), any(), any()))
                .thenAnswer(invocation -> Map.of(
                        "result", invocation.getArgument(0, String.class),
                        "agentName", "food_agent"));
    }

    @Test
    @DisplayName("集成测试1: 河北美食推荐（单意图）")
    void testHebeiFoodRecommendation() throws Exception {
        String requestBody = """
                {
                    "message": "河北有什么特色美食"
                }
                """;

        MvcResult result = mockMvc.perform(post("/api/math/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(request().asyncStarted())
                .andReturn();

        // ⭐ 等待异步结果并验证
        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("河北")));
    }

    @Test
    @DisplayName("集成测试2: 成都特色菜查询")
    void testChengduSpecialtyCuisine() throws Exception {
        String requestBody = """
                {
                    "message": "成都有什么好吃的特色菜"
                }
                """;

        MvcResult result = mockMvc.perform(post("/api/math/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("成都")));
    }

    @Test
    @DisplayName("集成测试3: 四川省名菜查询（带后缀）")
    void testSichuanProvinceWithSuffix() throws Exception {
        String requestBody = """
                {
                    "message": "四川省有什么名菜"
                }
                """;

        MvcResult result = mockMvc.perform(post("/api/math/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("四川")));
    }

    @Test
    @DisplayName("集成测试4: 麻辣口味查询")
    void testSpicyTasteQuery() throws Exception {
        String requestBody = """
                {
                    "message": "我想吃麻辣口味的菜"
                }
                """;

        MvcResult result = mockMvc.perform(post("/api/math/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("麻辣")));
    }

    @Test
    @DisplayName("集成测试5: 北京烤鸭查询")
    void testPekingDuckQuery() throws Exception {
        String requestBody = """
                {
                    "message": "北京烤鸭是哪里的特色菜"
                }
                """;

        MvcResult result = mockMvc.perform(post("/api/math/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("烤鸭")));
    }

    @Test
    @DisplayName("集成测试6: 河北省（带后缀）美食推荐")
    void testHebeiProvinceWithSuffix() throws Exception {
        String requestBody = """
                {
                    "message": "河北省有什么特色美食"
                }
                """;

        MvcResult result = mockMvc.perform(post("/api/math/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("河北")));
    }
}
