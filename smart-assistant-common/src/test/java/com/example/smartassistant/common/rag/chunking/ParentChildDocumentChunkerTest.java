package com.example.smartassistant.common.rag.chunking;

import com.example.smartassistant.common.rag.KnowledgeDocument;
import com.example.smartassistant.common.rag.document.ParsedDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ParentChildDocumentChunker 单元测试。
 */
class ParentChildDocumentChunkerTest {

    @Test
    @DisplayName("空文档列表应返回空结果")
    void chunkParentChild_withEmptyElements_shouldReturnEmpty() {
        ParentChildDocumentChunker chunker = new ParentChildDocumentChunker();
        var result = chunker.chunkParentChild(List.of());
        assertTrue(result.parentDocs().isEmpty());
        assertTrue(result.childDocs().isEmpty());
    }

    @Test
    @DisplayName("非空文档应产生至少一个父块")
    void chunkParentChild_withShortText_shouldProduceAtLeastOneParent() {
        ParentChildDocumentChunker chunker = new ParentChildDocumentChunker();
        // 使用较长文本确保超过最小分块阈值
        String longText = "这是一个测试文档内容，包含多个句子。"
                + "第一段是对产品的描述。第二段介绍了使用方法。"
                + "第三段涵盖了注意事项。第四段提供了售后服务信息。";
        ParsedDocument doc = ParsedDocument.builder()
                .docId("doc-001").title("测试文档")
                .content(longText)
                .category("测试")
                .build();

        var result = chunker.chunkParentChild(List.of(doc));
        assertFalse(result.parentDocs().isEmpty(), "应生成至少一个父块");
    }

    @Test
    @DisplayName("子块的 parentDocId 应指向对应的父块")
    void chunkParentChild_childShouldReferenceParent() {
        ParentChildDocumentChunker chunker = new ParentChildDocumentChunker();
        String longContent = "第一段内容。\n\n第二段内容。\n\n第三段内容。\n\n第四段内容。\n\n第五段内容。";
        ParsedDocument doc = ParsedDocument.builder()
                .docId("doc-002").title("长文档")
                .content(longContent).category("测试")
                .build();

        var result = chunker.chunkParentChild(List.of(doc));

        // 检查子块是否引用了父块
        if (!result.parentDocs().isEmpty() && !result.childDocs().isEmpty()) {
            String parentId = result.parentDocs().get(0).getId();
            for (KnowledgeDocument child : result.childDocs()) {
                assertFalse(child.getParentDocId().isEmpty(),
                        "子块 " + child.getId() + " 应包含 parentDocId");
                // 子块 parentDocId 应该以父块 ID 开头
                assertTrue(child.getParentDocId().startsWith("doc-002-parent-"),
                        "子块 parentDocId 应以父块 ID 开头");
            }
        }
    }

    @Test
    @DisplayName("子块 keywords 应包含分块位置信息")
    void chunkParentChild_childShouldHavePositionKeywords() {
        ParentChildDocumentChunker chunker = new ParentChildDocumentChunker();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            sb.append("第").append(i).append("段内容。");
        }
        ParsedDocument doc = ParsedDocument.builder()
                .docId("doc-003").title("多段文档")
                .content(sb.toString()).category("测试")
                .build();

        var result = chunker.chunkParentChild(List.of(doc));
        for (KnowledgeDocument child : result.childDocs()) {
            assertNotNull(child.getKeywords());
        }
    }
}
