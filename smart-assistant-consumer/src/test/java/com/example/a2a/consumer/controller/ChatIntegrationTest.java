package com.example.smartassistant.consumer.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * A2A Consumer 集成测试
 * 通过 HTTP chat 接口模拟远程调用，验证地点识别功能
 */
@SpringBootTest
@AutoConfigureMockMvc
class ChatIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("集成测试1: 河北美食推荐（单意图）")
    void testHebeiFoodRecommendation() throws Exception {
        String requestBody = """
                {
                    "message": "河北有什么特色美食"
                }
                """;

        mockMvc.perform(post("/api/math/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("河北")))
                .andExpect(content().string(org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.containsString("驴肉火烧"),
                        org.hamcrest.Matchers.containsString("金凤扒鸡"),
                        org.hamcrest.Matchers.containsString("正定崩肝"),
                        org.hamcrest.Matchers.containsString("缸炉烧饼")
                )))
                .andDo(result -> {
                    System.out.println("=== 河北美食推荐响应 ===");
                    System.out.println(result.getResponse().getContentAsString());
                });
    }

    @Test
    @DisplayName("集成测试2: 成都特色菜查询")
    void testChengduSpecialtyCuisine() throws Exception {
        String requestBody = """
                {
                    "message": "成都有什么好吃的特色菜"
                }
                """;

        mockMvc.perform(post("/api/math/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("成都")))
                .andExpect(content().string(org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.containsString("麻婆豆腐"),
                        org.hamcrest.Matchers.containsString("火锅"),
                        org.hamcrest.Matchers.containsString("回锅肉")
                )))
                .andDo(result -> {
                    System.out.println("=== 成都特色菜查询响应 ===");
                    System.out.println(result.getResponse().getContentAsString());
                });
    }

    @Test
    @DisplayName("集成测试3: 四川省名菜查询（带后缀）")
    void testSichuanProvinceWithSuffix() throws Exception {
        String requestBody = """
                {
                    "message": "四川省有什么名菜"
                }
                """;

        mockMvc.perform(post("/api/math/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("四川")))
                .andExpect(content().string(org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.containsString("麻婆豆腐"),
                        org.hamcrest.Matchers.containsString("回锅肉"),
                        org.hamcrest.Matchers.containsString("宫保鸡丁")
                )))
                .andDo(result -> {
                    System.out.println("=== 四川省名菜查询响应 ===");
                    System.out.println(result.getResponse().getContentAsString());
                });
    }

    @Test
    @DisplayName("集成测试4: 麻辣口味查询")
    void testSpicyTasteQuery() throws Exception {
        String requestBody = """
                {
                    "message": "我想吃麻辣口味的菜"
                }
                """;

        mockMvc.perform(post("/api/math/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("麻辣")))
                .andExpect(content().string(org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.containsString("麻婆豆腐"),
                        org.hamcrest.Matchers.containsString("火锅")
                )))
                .andDo(result -> {
                    System.out.println("=== 麻辣口味查询响应 ===");
                    System.out.println(result.getResponse().getContentAsString());
                });
    }

    @Test
    @DisplayName("集成测试5: 北京烤鸭查询")
    void testPekingDuckQuery() throws Exception {
        String requestBody = """
                {
                    "message": "北京烤鸭是哪里的特色菜"
                }
                """;

        mockMvc.perform(post("/api/math/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("烤鸭")))
                .andDo(result -> {
                    System.out.println("=== 北京烤鸭查询响应 ===");
                    System.out.println(result.getResponse().getContentAsString());
                });
    }

    @Test
    @DisplayName("集成测试6: 河北省（带后缀）美食推荐")
    void testHebeiProvinceWithSuffix() throws Exception {
        String requestBody = """
                {
                    "message": "河北省有什么特色美食"
                }
                """;

        mockMvc.perform(post("/api/math/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("河北")))
                .andExpect(content().string(org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.containsString("驴肉火烧"),
                        org.hamcrest.Matchers.containsString("河北")
                )))
                .andDo(result -> {
                    System.out.println("=== 河北省美食推荐响应 ===");
                    System.out.println(result.getResponse().getContentAsString());
                });
    }
}
