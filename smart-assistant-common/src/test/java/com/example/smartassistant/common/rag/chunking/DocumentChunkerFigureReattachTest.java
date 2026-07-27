/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.chunking;

import com.example.smartassistant.common.rag.KnowledgeDocument;
import com.example.smartassistant.common.rag.document.ParsedDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DocumentChunker 图注回贴修复的单元测试。
 * <p>
 * 复现真实用户 PDF 切分报告中「图注被错分到下一块、与引用它的正文分离」的现象，
 * 验证 {@link DocumentChunker#chunk(List)} 能把孤立图注回贴到引用它的上一块，
 * 同时保证未引用图时不会误合并。
 * </p>
 */
class DocumentChunkerFigureReattachTest {

    /**
     * 分块1（page2）末尾：以「如下图所示。」引用图，并残留被拆分的孤立图号「2」。
     * 注意末尾没有空行——「2」直接接在「如下图所示。」之后。
     */
    private static final String CHUNK1_PAGE2 = """
            (二)
            Windows系统下使用
            VS工具调用
            a)
            将二次开发相关文件拷贝至调用工程中
            b)
            将.h、.lib文件拷贝至工程根目录下，.dll文件拷贝至
            Debug目
            录下，如下图所示。
            2""";

    /**
     * 分块2（page3）开头：孤立图注「图 … 2 … 文件放置路径」被切断到本块开头，
     * 其后才是正文「c)」。
     */
    private static final String CHUNK2_PAGE3 = """
            图

            2
            文件放置路径
            c)
            将.h引入工程，并添加包
            include含语句，如下图所示。
            图

            3
            引入头文件
            d)
            存放底层支撑数据文件
            本模型的运行需要部分底层数据支撑，本模型已在安装光盘中提
            供数据文件包，需要将之拷贝至运行
            API函数的电脑硬盘上，如下图
            所示。
            3""";

    @Test
    @DisplayName("被分页切断的图注应回贴到引用它的上一块")
    void orphanFigureCaption_shouldReattachToPreviousChunk() {
        ParsedDocument p1 = ParsedDocument.builder()
                .docId("doc-page2").title("模型使用说明").content(CHUNK1_PAGE2)
                .contentType("pdf").category("说明").build();
        ParsedDocument p2 = ParsedDocument.builder()
                .docId("doc-page3").title("模型使用说明").content(CHUNK2_PAGE3)
                .contentType("pdf").category("说明").build();

        List<KnowledgeDocument> docs = new DocumentChunker().chunk(List.of(p1, p2));

        // 1) 结果至少 2 块
        assertTrue(docs.size() >= 2, "应至少产生 2 个分块，实际=" + docs.size());

        String first = docs.get(0).getContent();
        String second = docs.get(1).getContent();

        // 2) 第1块应包含被回贴的图注「图 2 文件放置路径」（或原始换行形态）
        assertTrue(first.contains("图 2 文件放置路径") || first.contains("图\n\n2\n文件放置路径"),
                "第1块应包含被回贴的图注，实际第1块末尾=\n" + first);

        // 3) 第2块不再以「图」开头，且以 c) 这类正文开头（图注已从第2块剥离）
        assertFalse(second.startsWith("图"),
                "第2块不应再以「图」开头，实际第2块开头=\n" + second);
        assertTrue(second.startsWith("c)"),
                "第2块应以正文（c)）开头，实际第2块开头=\n" + second);

        // 4) 第2块仍包含 c) 与 d)
        assertTrue(second.contains("c)"), "第2块应仍包含 c)");
        assertTrue(second.contains("d)"), "第2块应仍包含 d)");
    }

    @Test
    @DisplayName("上一块未引用图时不应误回贴图注")
    void noFigureReference_shouldNotReattach() {
        // 上一块末尾没有「如下图所示」之类引用
        String prevNoRef = """
                这是一段普通正文，没有引用任何图片。
                仅仅是一段说明文字。""";
        // 下一块仍以图注开头
        String curWithCaption = """
                图

                1
                示例示意图
                a)
                第一条说明内容。""";

        ParsedDocument p1 = ParsedDocument.builder()
                .docId("doc-a").title("说明").content(prevNoRef)
                .contentType("pdf").category("说明").build();
        ParsedDocument p2 = ParsedDocument.builder()
                .docId("doc-b").title("说明").content(curWithCaption)
                .contentType("pdf").category("说明").build();

        List<KnowledgeDocument> docs = new DocumentChunker().chunk(List.of(p1, p2));

        assertTrue(docs.size() >= 2, "应至少产生 2 个分块，实际=" + docs.size());

        String first = docs.get(0).getContent();
        String second = docs.get(1).getContent();

        // 图注不应被回贴到上一块
        assertFalse(first.contains("图 1 示例示意图"),
                "上一块未引用图时，图注不应被回贴，第1块=\n" + first);
        // 图注应保留在原本的块（第2块）开头
        assertTrue(second.startsWith("图"),
                "未引用图时图注应保留在第2块开头，实际=\n" + second);
    }
}
