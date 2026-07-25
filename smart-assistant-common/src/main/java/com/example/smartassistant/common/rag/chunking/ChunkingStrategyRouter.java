/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.chunking;

import com.example.smartassistant.common.rag.document.ParsedDocument;

import java.util.regex.Pattern;

/**
 * 按文件类型 / 结构信号路由分块策略（实现 {@link ChunkStrategySelector}）。
 * <p>
 * 路由矩阵（与 RAG 文章观点一致：语义切分适合「稳定但无结构」的文档，不适合「结构清晰」文档）：
 * <pre>
 *   图片 / OCR 流水 (image-caption / image-ocr) → BGE 语义切分
 *   txt / text / markdown（短，≤ shortThreshold） → 规则切分（不切也行，免费）
 *   txt / text / markdown（长 且有标题结构）       → 规则切分（标题正则已足够）
 *   txt / text / markdown（长 且无结构）           → BGE 语义切分
 *   pdf / word / html 正文                          → 规则切分
 *   表格类 (含 table)                               → 规则切分（保结构）
 * </pre>
 * </p>
 */
public class ChunkingStrategyRouter implements ChunkStrategySelector {

    /** 图片 / OCR 走 BGE 语义切分 */
    private final ChunkStrategy bgeStrategy;

    /** 规则切分（Recursive / Semantic）兜底 */
    private final ChunkStrategy ruleStrategy;

    /** 短文本阈值（token）：≤ 此值不值得语义切分，直接规则 */
    private final int shortThresholdTokens;

    // 结构标题正则（复用 SemanticChunkStrategy 的边界识别，零新增依赖）
    private static final Pattern MD_HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern CN_SECTION = Pattern.compile("^第[一二三四五六七八九十百千0-9]+[章节条款篇]");
    private static final Pattern NUM_SECTION = Pattern.compile("^(\\d+\\.?)(\\d+\\.?)?\\s+");
    private static final Pattern CN_NUM_HEADING = Pattern.compile("^[一二三四五六七八九十]+[、．.]");

    public ChunkingStrategyRouter(ChunkStrategy bgeStrategy, ChunkStrategy ruleStrategy) {
        this(bgeStrategy, ruleStrategy, 512); // 默认 ≤512 token（约 2 个子块容量）视为短
    }

    public ChunkingStrategyRouter(ChunkStrategy bgeStrategy, ChunkStrategy ruleStrategy,
                                  int shortThresholdTokens) {
        this.bgeStrategy = bgeStrategy != null ? bgeStrategy : ruleStrategy;
        this.ruleStrategy = ruleStrategy != null ? ruleStrategy : new RecursiveChunkStrategy();
        this.shortThresholdTokens = shortThresholdTokens;
    }

    @Override
    public ChunkStrategy select(ParsedDocument doc) {
        if (doc == null) return ruleStrategy;
        String ct = doc.getContentType() == null ? "" : doc.getContentType().toLowerCase();

        // 1. 图片 / OCR → BGE（视觉/扫描文本最需语义）
        if (ct.contains("image") || ct.contains("ocr")) {
            return bgeStrategy;
        }
        // 2. 表格 → 规则（保结构，BGE 会把单元格拆碎）
        if (ct.contains("table")) {
            return ruleStrategy;
        }
        // 3. txt / text / markdown → 短固定 / 长 BGE
        if (ct.equals("txt") || ct.equals("text")
                || ct.contains("markdown") || ct.equals("md")) {
            int tok = RecursiveChunkStrategy.estimateTokens(doc.getContent());
            if (tok <= shortThresholdTokens) {
                return ruleStrategy; // 短 → 规则（免费）
            }
            if (hasStructuralHeadings(doc.getContent())) {
                return ruleStrategy; // 长但结构化 → 标题正则足够，BGE 无收益
            }
            return bgeStrategy;      // 长且无结构 → BGE 语义切分
        }
        // 4. 其他（pdf / word / html 正文）→ 规则
        return ruleStrategy;
    }

    /** 判断文本是否含结构标题（Markdown / 中文章节 / 编号 / 中文数字） */
    static boolean hasStructuralHeadings(String text) {
        if (text == null || text.isBlank()) return false;
        String[] lines = text.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (MD_HEADING.matcher(trimmed).find()
                    || CN_SECTION.matcher(trimmed).find()
                    || NUM_SECTION.matcher(trimmed).find()
                    || CN_NUM_HEADING.matcher(trimmed).find()) {
                return true;
            }
        }
        return false;
    }
}
