package com.example.smartassistant.consumer.service.session;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * ConversationDocumentService 单元测试
 * <p>
 * 测试阈值控制和叙事摘要集成逻辑。
 * 注意：文件写入由 @Async 异步执行，测试中同步验证文件内容。
 */
@ExtendWith(MockitoExtension.class)
class ConversationDocumentServiceTest {

    @Mock
    private ConversationSummarizationService summarizationService;

    private ConversationDocumentService service;

    private Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("conv-doc-test-");
        service = new ConversationDocumentService(tempDir.toString(), summarizationService);
    }

    // ========== 阈值测试：内容不足 1000 字 ==========

    @Test
    void shortContent_fewTurns_shouldSaveRaw() throws Exception {
        ConversationValueService.ConversationValueContext ctx = createCtx(1, "北京天气怎么样？");

        service.saveValuableConversation(ctx);
        Thread.sleep(200); // 等待 @Async 完成

        Path filePath = tempDir.resolve("1/memories/" + getTodayFilename("test_session"));
        assertTrue(Files.exists(filePath));
        String content = Files.readString(filePath);
        assertTrue(content.contains("format: raw"));
        assertTrue(content.contains("turn_count: 1"));
        assertFalse(content.contains("narrative"));
        verify(summarizationService, never()).summarize(anyString());
    }

    @Test
    void shortContent_enoughTurns_shouldSaveRaw() throws Exception {
        // 内容短但轮数多：仍然不触发（content 未达标）
        String shortContent = "好";
        ConversationValueService.ConversationValueContext ctx = createCtx(5, shortContent);

        service.saveValuableConversation(ctx);
        Thread.sleep(200);

        String content = readSavedFile(ctx);
        assertTrue(content.contains("format: raw"));
        assertTrue(content.contains(shortContent));
        verify(summarizationService, never()).summarize(anyString());
    }

    // ========== 阈值测试：轮数不足 3 ==========

    @Test
    void longContent_fewTurns_shouldSaveRaw() throws Exception {
        // 内容长但轮数不足：仍然不触发
        String longContent = "A".repeat(1200);
        ConversationValueService.ConversationValueContext ctx = createCtx(1, longContent);

        service.saveValuableConversation(ctx);
        Thread.sleep(200);

        String content = readSavedFile(ctx);
        assertTrue(content.contains("format: raw"));
        assertTrue(content.contains(longContent));
        verify(summarizationService, never()).summarize(anyString());
    }

    // ========== 阈值达标测试 ==========

    @Test
    void longContent_enoughTurns_shouldCallSummarization() throws Exception {
        String longContent = "用户: 推荐一些北京美食\n助手: " + "A".repeat(1200);
        String expectedNarrative = "用户查询北京美食推荐。";
        when(summarizationService.summarize(longContent)).thenReturn(expectedNarrative);

        ConversationValueService.ConversationValueContext ctx = createCtx(3, longContent);

        service.saveValuableConversation(ctx);
        Thread.sleep(200);

        String content = readSavedFile(ctx);
        assertTrue(content.contains("format: narrative"));
        assertTrue(content.contains(expectedNarrative));
        assertFalse(content.contains(longContent));
        verify(summarizationService).summarize(longContent);
    }

    // ========== fail-safe 测试 ==========

    @Test
    void summarizationFails_shouldSaveOriginal() throws Exception {
        String longContent = "用户: 推荐景点\n助手: " + "B".repeat(1200);
        when(summarizationService.summarize(longContent)).thenReturn(longContent); // fallback 返回原文

        ConversationValueService.ConversationValueContext ctx = createCtx(3, longContent);

        service.saveValuableConversation(ctx);
        Thread.sleep(200);

        String content = readSavedFile(ctx);
        // summarizationService 返回了原文，所以 body 是原文
        assertTrue(content.contains(longContent));
        assertTrue(content.contains("format: narrative")); // 格式标记仍是 narrative（外部未知 fallback）
    }

    // ========== frontmatter 测试 ==========

    @Test
    void frontmatter_shouldContainAllFields() throws Exception {
        String content = "用户: 测试\n助手: 回复";
        ConversationValueService.ConversationValueContext ctx = createCtx(2, content);

        service.saveValuableConversation(ctx);
        Thread.sleep(200);

        String saved = readSavedFile(ctx);
        assertTrue(saved.contains("created_at:"));
        assertTrue(saved.contains("agent: general_chat"));
        assertTrue(saved.contains("intent: 景点推荐"));
        assertTrue(saved.contains("session: test_session"));
        assertTrue(saved.contains("turn_count: 2"));
        assertTrue(saved.contains("format: raw"));
    }

    // ========== 辅助方法 ==========

    private ConversationValueService.ConversationValueContext createCtx(int turnCount, String content) {
        return new ConversationValueService.ConversationValueContext(
                1L,
                "test_session",
                content,
                "general_chat",
                "景点推荐",
                turnCount,
                false,
                false
        );
    }

    private String getTodayFilename(String sessionId) {
        return java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                + "_" + sessionId + ".md";
    }

    private String readSavedFile(ConversationValueService.ConversationValueContext ctx) throws IOException {
        Path filePath = tempDir.resolve(
                String.valueOf(ctx.userId()) + "/memories/" + getTodayFilename(ctx.sessionId()));
        return Files.readString(filePath);
    }
}
