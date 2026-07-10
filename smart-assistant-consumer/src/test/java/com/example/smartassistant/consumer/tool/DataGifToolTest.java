/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.tool;

import com.example.smartassistant.common.gateway.tool.ToolRegistry;
import com.example.smartassistant.common.tool.GifCacheStore;
import com.example.smartassistant.common.tool.client.ToolRegistryClient;
import com.example.smartassistant.toolregistry.tool.common.DataGifTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 纯单元测试：不加载 Spring 上下文，直接构造 DataGifTool 并注入
 * Mockito 桩（ToolRegistry / ToolRegistryClient）与真实 GifCacheStore。
 */
@ExtendWith(MockitoExtension.class)
public class DataGifToolTest {

    @Mock
    private ToolRegistry toolRegistry;

    @Mock
    private ToolRegistryClient registryClient;

    private GifCacheStore gifCacheStore;

    private DataGifTool dataGifTool;

    @BeforeEach
    void setUp() {
        gifCacheStore = new GifCacheStore();
        dataGifTool = new DataGifTool(toolRegistry, registryClient, gifCacheStore);
    }

    @Test
    void generateUserGrowthGif() throws Exception {
        // 直接构造时间序列 JSON 数据点（无需 JDBC / 数据库）
        String json = "[{\"date\":\"2026-04-06\",\"value\":3},"
            + "{\"date\":\"2026-04-07\",\"value\":5},"
            + "{\"date\":\"2026-04-08\",\"value\":8}]";

        // 调用工具（新契约：返回 GIF_CACHE:<uuid> 缓存 key）
        String cacheKey = dataGifTool.generateTrendGif(
            "近30天用户增长趋势",
            "日期",
            "新增用户数",
            json,
            "blue"
        );

        // 断言：返回以 "GIF_CACHE:" 开头的缓存 key
        assertTrue(cacheKey != null && cacheKey.startsWith("GIF_CACHE:"),
            "GIF 生成失败，未返回缓存 key");

        // 断言：能从缓存中取出非空 GIF 字节（验证写入→读取语义）
        byte[] gifData = dataGifTool.getGifFromCache(cacheKey.replace("GIF_CACHE:", ""));
        assertNotNull(gifData, "GIF 缓存为空");
        assertTrue(gifData.length > 0, "GIF 缓存字节长度为 0");

        // 可选：写出文件便于人工查看
        Files.write(Path.of("target/test-user-growth.gif"), gifData);
    }
}
