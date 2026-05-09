package com.example.smartassistant.consumer.service.session;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * ConversationDocumentService 单元测试（增量追加模式）
 * <p>
 * 测试阈值控制、叙事摘要集成、增量追加逻辑。
 * 注意：文件写入由 @Async 异步执行，测试中通过 Thread.sleep 等待完成。
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
        Thread.sleep(200);

        String content = readSavedFile(ctx);
        assertTrue(content.contains("> Turn 1 | raw |"));
        assertTrue(content.contains("北京天气怎么样？"));
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
        assertTrue(content.contains("> Turn 5 | raw |"));
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
        assertTrue(content.contains("> Turn 1 | raw |"));
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
        assertTrue(content.contains("> Turn 3 | narrative |"));
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
        assertTrue(content.contains("> Turn 3 | narrative |")); // 格式标记仍是 narrative（外部未知 fallback）
    }

    // ========== Frontmatter 测试 ==========

    @Test
    void frontmatter_shouldContainAllFields() throws Exception {
        String content = "用户: 测试\n助手: 回复";
        ConversationValueService.ConversationValueContext ctx = createCtx(2, content);

        service.saveValuableConversation(ctx);
        Thread.sleep(200);

        String saved = readSavedFile(ctx);
        assertTrue(saved.contains("created_at:"));
        assertTrue(saved.contains("session: test_session"));
        assertTrue(saved.contains("turn_range: 2-2"));
        assertTrue(saved.contains("entries: 1"));
        // 条目头部包含 intent
        assertTrue(saved.contains("> Turn 2 | raw | intent: 景点推荐"));
    }

    // ========== 增量追加测试 ==========

    @Test
    void multipleTurns_shouldAppendEntries() throws Exception {
        // Turn 3：触发叙事摘要
        String turn3Content = "用户: 推荐北京餐厅\n助手: " + "C".repeat(1200);
        when(summarizationService.summarize(turn3Content)).thenReturn("用户查询北京餐厅推荐。");

        service.saveValuableConversation(createCtx(3, turn3Content));
        Thread.sleep(200);

        // Turn 4：追加原文（短内容）
        String turn4Content = "用户: 有川菜吗\n助手: 眉州东坡";
        service.saveValuableConversation(createCtx(4, turn4Content));
        Thread.sleep(200);

        // Turn 5：追加叙事摘要
        String turn5Content = "用户: 规划三日游\n助手: " + "D".repeat(1500);
        when(summarizationService.summarize(turn5Content)).thenReturn("用户查询北京三日游计划。");

        service.saveValuableConversation(createCtx(5, turn5Content));
        Thread.sleep(200);

        // 验证：文件包含 3 个条目
        String saved = readSavedFile(createCtx(3, ""));
        assertTrue(saved.contains("turn_range: 3-5"));
        assertTrue(saved.contains("entries: 3"));

        // 验证每个条目都存在
        assertTrue(saved.contains("> Turn 3 | narrative | intent: 景点推荐"));
        assertTrue(saved.contains("用户查询北京餐厅推荐。"));
        assertTrue(saved.contains("> Turn 4 | raw | intent: 景点推荐"));
        assertTrue(saved.contains("用户: 有川菜吗\n助手: 眉州东坡"));
        assertTrue(saved.contains("> Turn 5 | narrative | intent: 景点推荐"));
        assertTrue(saved.contains("用户查询北京三日游计划。"));
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
        return java.time.LocalDate.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                + "_" + sessionId + ".md";
    }

    private String readSavedFile(ConversationValueService.ConversationValueContext ctx) throws IOException {
        Path filePath = tempDir.resolve(
                String.valueOf(ctx.userId()) + "/memories/" + getTodayFilename(ctx.sessionId()));
        return Files.readString(filePath);
    }
}
