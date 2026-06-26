/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link AgentMemoryService} 集成测试 —— 模拟多轮下单对话记忆流程。
 *
 * <p>测试覆盖：保存、读取、格式化输出、老化警告、条目截断、文件格式验证。</p>
 */
class AgentMemoryServiceIntegrationTest {

    @TempDir
    Path tempDir;

    private AgentMemoryService createService() {
        return new AgentMemoryService(tempDir.toString());
    }

    // ============ 多轮对话模拟 ============

    @Test
    void multiTurnOrderConversation() {
        AgentMemoryService svc = createService();
        String userId = "user123";

        // Turn 1: 用户订票选靠窗 → Agent 应保存偏好
        svc.save("order", userId, "preferWindowSeat", "靠窗");
        assertEquals("靠窗", svc.get("order", userId, "preferWindowSeat"));

        // Turn 2: 用户说常去上海 → 保存常用路线
        svc.save("order", userId, "frequentRoute", "北京→上海");
        assertEquals("北京→上海", svc.get("order", userId, "frequentRoute"));

        // Turn 3: 用户说都用微信支付 → 保存支付偏好
        svc.save("order", userId, "preferPaymentMethod", "微信支付");

        // 验证全部记忆并检查格式化输出
        String formatted = svc.getAllFormatted("order", userId);
        assertTrue(formatted.contains("偏好窗口座位"));
        assertTrue(formatted.contains("常用出行路线"));
        assertTrue(formatted.contains("偏好支付方式"));
        System.out.println("=== 多轮对话后记忆 ===");
        System.out.println(formatted);
    }

    // ============ 老化警告 ============

    @Test
    void agingWarnings() {
        AgentMemoryService svc = createService();
        String userId = "user456";

        // 保存一个"近期"记忆（当前日期，由 save 自动写入）
        svc.save("order", userId, "preferWindowSeat", "靠窗");
        System.out.println("=== 保存后已保存 ===");

        // 输出不应包含老化警告
        String formatted = svc.getAllFormatted("order", userId);
        System.out.println("=== 格式化输出 ===");
        System.out.println(formatted);
        assertFalse(formatted.contains("⚠️"), "近期记忆不应有警告");
        assertFalse(formatted.contains("天前"), "近期记忆不应显示天数");

        // 验证文件存在
        Path file = tempDir.resolve(userId).resolve("order-memory.md");
        System.out.println("=== 检查文件 ===");
        System.out.println("tempDir=" + tempDir);
        System.out.println("file=" + file.toAbsolutePath());
        System.out.println("file exists=" + Files.exists(file));
        System.out.println("userDir exists=" + Files.exists(tempDir.resolve(userId)));
        System.out.println("=== 记忆文件内容 ===");
        try {
            String content = Files.readString(file);
            System.out.println(content);
            assertTrue(content.contains("preferWindowSeat"));
            assertTrue(content.contains("||"), "应包含日期时间戳");
        } catch (IOException e) {
            fail("无法读取记忆文件: " + e.getMessage());
        }
    }

    // ============ 条目截断 + 索引 ============

    @Test
    void truncationWithIndex() {
        AgentMemoryService svc = createService();
        String userId = "user789";

        // 保存 12 条记忆（超过 MAX_DISPLAY_ENTRIES=10）
        for (int i = 0; i < 12; i++) {
            svc.save("product", userId, "pref_" + i, "value_" + i);
        }

        String formatted = svc.getAllFormatted("product", userId);
        System.out.println("=== 截断测试输出 ===");
        System.out.println(formatted);

        // 验证截断提示
        assertTrue(formatted.contains("仅显示前"), "应显示截断提示");
        assertTrue(formatted.contains("可调用 recallMemories 获取详情"), "应显示 key-only 索引");

        // 验证条目数不超过 5（MAX_DISPLAY_ENTRIES/2）
        long lines = formatted.lines().filter(l -> l.startsWith("-") || l.startsWith("⚠️")).count();
        assertTrue(lines <= 6, "应不超过 5 条显示条目 + 索引, 实际: " + lines);
    }

    // ============ 删除记忆 ============

    @Test
    void deleteMemory() {
        AgentMemoryService svc = createService();
        String userId = "userDel";

        svc.save("order", userId, "preferWindowSeat", "靠窗");
        assertTrue(svc.hasMemory("order", userId));

        svc.delete("order", userId, "preferWindowSeat");
        assertNull(svc.get("order", userId, "preferWindowSeat"));

        // 删除后无记忆
        assertTrue(svc.getAllFormatted("order", userId).isBlank());
    }

    // ============ 跨 Agent 独立 ============

    @Test
    void agentMemoryIsolation() {
        AgentMemoryService svc = createService();
        String userId = "userIso";

        svc.save("order", userId, "preferWindowSeat", "靠窗");
        svc.save("product", userId, "maxPrice", "500");

        // Order 只有自己
        String orderMem = svc.getAllFormatted("order", userId);
        assertTrue(orderMem.contains("偏好窗口座位"));
        assertFalse(orderMem.contains("价格上限"));

        // Product 只有自己
        String productMem = svc.getAllFormatted("product", userId);
        assertTrue(productMem.contains("价格上限"));
        assertFalse(productMem.contains("偏好窗口座位"));
    }

    // ============ 文件格式验证 ============

    @Test
    void fileFormatIsMarkdown() {
        AgentMemoryService svc = createService();
        String userId = "userFmt";

        svc.save("order", userId, "preferWindowSeat", "靠窗");
        svc.save("order", userId, "frequentRoute", "北京→上海");

        Path file = tempDir.resolve(userId).resolve("order-memory.md");
        assertTrue(Files.exists(file), "文件应存在");

        try {
            String content = Files.readString(file);
            System.out.println("=== Markdown 文件内容 ===");
            System.out.println(content);

            // 验证 Markdown 格式
            assertTrue(content.startsWith("# Order Agent 用户偏好"), "应以 H1 标题开头");
            assertTrue(content.contains("- preferWindowSeat"), "应为无序列表格式");
            assertTrue(content.contains("- frequentRoute"), "应为无序列表格式");
            assertTrue(content.contains("||"), "应包含日期时间戳");
        } catch (IOException e) {
            fail("文件验证失败: " + e.getMessage());
        }
    }
}
