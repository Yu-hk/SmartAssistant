package com.example.smartassistant.consumer.dto;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * StructuredPrompt 测试
 */
class StructuredPromptTest {
    
    @Test
    void testFullPromptWithMetadata() {
        // 构建完整的 StructuredPrompt（包含所有增强功能）
        StructuredPrompt.RequestMetadata metadata = StructuredPrompt.RequestMetadata.builder()
                .requestId("test-request-123")
                .timestamp(System.currentTimeMillis())
                .clientId("web-app")
                .userId(1001L)
                .sessionId("session-abc")
                .extra("{\"source\":\"chat-page\"}")
                .build();
        
        StructuredPrompt.UserProfile userProfile = StructuredPrompt.UserProfile.builder()
                .preferences("喜欢川菜和火锅")
                .historyBehavior("上周查询过北京火锅")
                .additionalInfo("{\"level\":\"vip\"}")
                .build();
        
        List<StructuredPrompt.ConversationMessage> history = List.of(
            StructuredPrompt.ConversationMessage.builder()
                .role("user")
                .content("北京有什么好吃的？")
                .timestamp(System.currentTimeMillis())
                .build(),
            StructuredPrompt.ConversationMessage.builder()
                .role("agent")
                .content("推荐全聚德烤鸭")
                .timestamp(System.currentTimeMillis())
                .build()
        );
        
        StructuredPrompt prompt = StructuredPrompt.builder()
                .version("1.1")
                .metadata(metadata)
                .userProfile(userProfile)
                .conversationHistory(history)
                .currentQuestion("明天天气怎么样？")
                .compressed(false)
                .build();
        
        // 转换为 JSON
        String json = prompt.toJson();
        
        System.out.println("=== 完整 JSON Prompt ===");
        System.out.println(json);
        
        // 验证 JSON 包含所有字段
        assertNotNull(json);
        assertTrue(json.contains("\"version\":\"1.1\""));
        assertTrue(json.contains("\"requestId\":\"test-request-123\""));
        assertTrue(json.contains("\"clientId\":\"web-app\""));
        assertTrue(json.contains("\"userId\":1001"));
        assertTrue(json.contains("\"preferences\":\"喜欢川菜和火锅\""));
        assertTrue(json.contains("\"currentQuestion\":\"明天天气怎么样？\""));
        
        // 从 JSON 解析
        StructuredPrompt parsed = StructuredPrompt.fromJson(json);
        
        // 验证解析结果
        assertEquals("1.1", parsed.getVersion());
        assertNotNull(parsed.getMetadata());
        assertEquals("test-request-123", parsed.getMetadata().getRequestId());
        assertEquals("web-app", parsed.getMetadata().getClientId());
        assertEquals(Long.valueOf(1001L), parsed.getMetadata().getUserId());
        
        assertNotNull(parsed.getUserProfile());
        assertEquals("喜欢川菜和火锅", parsed.getUserProfile().getPreferences());
        
        assertNotNull(parsed.getConversationHistory());
        assertEquals(2, parsed.getConversationHistory().size());
        assertEquals("user", parsed.getConversationHistory().get(0).getRole());
        assertEquals("agent", parsed.getConversationHistory().get(1).getRole());
        
        assertEquals("明天天气怎么样？", parsed.getCurrentQuestion());
        assertFalse(parsed.getCompressed());
    }
    
    @Test
    void testMinimalPrompt() {
        // 最小化 Prompt（只有问题）
        StructuredPrompt prompt = StructuredPrompt.builder()
                .currentQuestion("你好")
                .build();
        
        String json = prompt.toJson();
        System.out.println("\n=== 最小化 JSON Prompt ===");
        System.out.println(json);
        
        // 验证默认值
        assertTrue(json.contains("\"version\":\"1.1\""));
        assertFalse(prompt.getCompressed());
        
        // 解析
        StructuredPrompt parsed = StructuredPrompt.fromJson(json);
        assertEquals("1.1", parsed.getVersion());
        assertEquals("你好", parsed.getCurrentQuestion());
        assertNull(parsed.getUserProfile());
        assertNull(parsed.getConversationHistory());
    }
    
    @Test
    void testCompressedPrompt() {
        // 模拟压缩后的 Prompt
        List<StructuredPrompt.ConversationMessage> compressedHistory = List.of(
            StructuredPrompt.ConversationMessage.builder()
                .role("user")
                .content("地点:北京;意图:美食")
                .build(),
            StructuredPrompt.ConversationMessage.builder()
                .role("agent")
                .content("推荐全聚德...")
                .build()
        );
        
        StructuredPrompt prompt = StructuredPrompt.builder()
                .version("1.1")
                .conversationHistory(compressedHistory)
                .currentQuestion("还有其他推荐吗？")
                .compressed(true)  // 标记为已压缩
                .build();
        
        String json = prompt.toJson();
        System.out.println("\n=== 压缩 JSON Prompt ===");
        System.out.println(json);
        
        assertTrue(json.contains("\"compressed\":true"));
        
        StructuredPrompt parsed = StructuredPrompt.fromJson(json);
        assertTrue(parsed.getCompressed());
        assertEquals(2, parsed.getConversationHistory().size());
    }
    
    @Test
    void testVersionCompatibility() {
        // 测试向后兼容性：解析旧版本 JSON
        String oldVersionJson = """
            {
              "version": "1.0",
              "currentQuestion": "测试问题"
            }
            """;
        
        StructuredPrompt parsed = StructuredPrompt.fromJson(oldVersionJson);
        
        // 应该能正常解析，缺失字段为 null
        assertEquals("1.0", parsed.getVersion());
        assertEquals("测试问题", parsed.getCurrentQuestion());
        assertNull(parsed.getMetadata());
        assertNull(parsed.getUserProfile());
    }
}
