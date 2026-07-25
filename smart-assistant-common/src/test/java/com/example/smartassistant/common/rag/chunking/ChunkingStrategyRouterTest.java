/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.chunking;

import com.example.smartassistant.common.rag.chunking.RecursiveChunkStrategy;
import com.example.smartassistant.common.rag.document.ParsedDocument;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * ChunkingStrategyRouter 路由判定测试：图片→BGE、短 txt→规则、长无结构 txt→BGE、
 * 长有结构 txt→规则、表格→规则。
 */
class ChunkingStrategyRouterTest {

    private static final ChunkStrategy BGE = (t, m, o) -> List.of(new Chunk("BGE", 0, 1, ""));
    private static final ChunkStrategy RULE = (t, m, o) -> List.of(new Chunk("RULE", 0, 1, ""));

    private ChunkingStrategyRouter router() {
        return new ChunkingStrategyRouter(BGE, RULE);
    }

    @Test
    void imageCaptionRoutesToBge() {
        ParsedDocument d = ParsedDocument.builder().docId("i1")
                .content("截图显示订单状态为已发货物流单号SF123。").contentType("image-caption").build();
        assertSame(BGE, router().select(d));
    }

    @Test
    void shortTxtRoutesToRule() {
        ParsedDocument d = ParsedDocument.builder().docId("t1")
                .content("这是一个很短的说明文本。").contentType("txt").build();
        assertSame(RULE, router().select(d));
    }

    @Test
    void longUnstructuredTxtRoutesToBge() {
        // 构造超过短阈值（512 token）的长无结构文本（无标题、无章节）
        StringBuilder sb = new StringBuilder();
        String para = "会议记录要点如下上午讨论了客户投诉处理流程下午确认了退款时效标准。"
                + "晚间汇总了运营指标异常后续需要跟踪物流延误问题各方对超时赔付方案达成初步共识。";
        while (RecursiveChunkStrategy.estimateTokens(sb.toString()) < 600) {
            sb.append(para);
        }
        ParsedDocument d = ParsedDocument.builder().docId("t2")
                .content(sb.toString()).contentType("txt").build();
        assertSame(BGE, router().select(d));
    }

    @Test
    void longStructuredTxtRoutesToRule() {
        String structured = "第一章 引言\n本节介绍背景。\n第二章 方法\n本节说明步骤。\n第三章 结论\n本节总结结果。";
        ParsedDocument d = ParsedDocument.builder().docId("t3")
                .content(structured).contentType("txt").build();
        assertSame(RULE, router().select(d));
    }

    @Test
    void tableRoutesToRule() {
        ParsedDocument d = ParsedDocument.builder().docId("p1")
                .content("表头行列数据。").contentType("pdf-table").build();
        assertSame(RULE, router().select(d));
    }
}
