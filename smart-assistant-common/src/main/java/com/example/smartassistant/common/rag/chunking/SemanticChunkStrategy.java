/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.chunking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 语义分块策略——按文档的语义边界（标题、章节标记）进行分块。
 * <p>
 * 适用于结构清晰的文档：Markdown、技术文档、带有标题编号的业务文档。
 * 语义边界保留完整段落，避免将一个逻辑单元切割到两个 chunk 中。
 * </p>
 *
 * <p>支持的语义边界（按优先级）：</p>
 * <ol>
 *   <li>Markdown 标题：`# `, `## `, `### `</li>
 *   <li>章节编号：`第X章`，`第X节`，`X.` / `X.X.` 编号格式</li>
 *   <li>加粗/大号标题行</li>
 * </ol>
 */
public class SemanticChunkStrategy implements ChunkStrategy {

    private static final Logger log = LoggerFactory.getLogger(SemanticChunkStrategy.class);

    /** Markdown 标题模式 */
    private static final Pattern MD_HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$", Pattern.MULTILINE);

    /** 中文章节编号模式（如 "第1章 总则"、"第3节 产品说明"） */
    private static final Pattern CN_SECTION = Pattern.compile("^第[一二三四五六七八九十百千0-9]+[章节条款篇]",
            Pattern.MULTILINE);

    /** 数字编号模式（如 "1. 引言"、"2.3 架构设计"） */
    private static final Pattern NUM_SECTION = Pattern.compile("^(\\d+\\.?)(\\d+\\.?)?\\s+",
            Pattern.MULTILINE);

    /** 阶段标题模式（如 "一、背景"、"二、方案"） */
    private static final Pattern CN_NUM_HEADING = Pattern.compile("^[一二三四五六七八九十]+[、．.]",
            Pattern.MULTILINE);

    /** 最小块字符数 */
    private static final int MIN_CHUNK_CHARS = 20;

    /** 默认使用递归分块作为语义分块的 fallback */
    private final RecursiveChunkStrategy fallback;

    public SemanticChunkStrategy() {
        this.fallback = new RecursiveChunkStrategy();
    }

    @Override
    public List<Chunk> chunk(String text, int maxTokens, int overlap) {
        if (text == null || text.isBlank()) return List.of();
        if (maxTokens <= 0) maxTokens = ChunkStrategy.defaultMaxTokens();
        if (overlap < 0) overlap = 0;
        if (overlap >= maxTokens) overlap = Math.max(0, maxTokens - 1);

        // 为重叠前缀预留容量，保证最终 prefix + text 仍不超过 maxTokens。
        int payloadMaxTokens = Math.max(1, maxTokens - overlap);

        // Stage 1: 按语义边界切分为段落组
        List<String> sections = splitBySemanticBoundaries(text);

        // Stage 2: 将段落组合并为不超过 maxTokens 的 chunk
        List<Chunk> result = mergeSections(sections, payloadMaxTokens);

        // Stage 3: 对仍超长的段落进行递归分块 fallback
        result = applyFallbackForOversized(result, payloadMaxTokens);

        // Stage 4: 从上一块尾部提取 overlap token 作为当前块前缀。
        result = applyOverlap(result, overlap, maxTokens);

        log.debug("[SemanticChunk] 分块完成: textLen={}, sections={}, chunks={}",
                text.length(), sections.size(), result.size());
        return result;
    }

    /** 按语义边界将文本切分为语义段落 */
    private List<String> splitBySemanticBoundaries(String text) {
        List<Integer> boundaries = new ArrayList<>();
        boundaries.add(0);

        // 1. 查找 Markdown 标题边界
        Matcher mdMatcher = MD_HEADING.matcher(text);
        while (mdMatcher.find()) {
            int pos = mdMatcher.start();
            if (pos > 0 && !boundaries.contains(pos)) {
                boundaries.add(pos);
            }
        }

        // 2. 查找中文章节边界
        Matcher cnMatcher = CN_SECTION.matcher(text);
        while (cnMatcher.find()) {
            int pos = cnMatcher.start();
            if (pos > 0 && !boundaries.contains(pos)) {
                boundaries.add(pos);
            }
        }

        // 3. 查找中文数字标题边界
        Matcher cnNumMatcher = CN_NUM_HEADING.matcher(text);
        while (cnNumMatcher.find()) {
            int pos = cnNumMatcher.start();
            if (pos > 0 && !boundaries.contains(pos)) {
                boundaries.add(pos);
            }
        }

        // 4. 查找数字编号边界
        Matcher numMatcher = NUM_SECTION.matcher(text);
        while (numMatcher.find()) {
            int pos = numMatcher.start();
            if (pos > 0 && !boundaries.contains(pos)) {
                boundaries.add(pos);
            }
        }

        // 排序并添加结尾
        boundaries.sort(Integer::compareTo);
        if (!boundaries.contains(text.length())) {
            boundaries.add(text.length());
        }

        // 按边界切分
        List<String> sections = new ArrayList<>();
        for (int i = 0; i < boundaries.size() - 1; i++) {
            int start = boundaries.get(i);
            int end = boundaries.get(i + 1);
            String section = text.substring(start, end).trim();
            if (!section.isBlank()) {
                sections.add(section);
            }
        }

        return sections;
    }

    /** 合并小段落为不超过 maxTokens 的 chunk */
    private List<Chunk> mergeSections(List<String> sections, int maxTokens) {
        List<Chunk> result = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();

        for (String section : sections) {
            int sectionTokens = RecursiveChunkStrategy.estimateTokens(section);
            int bufferTokens = RecursiveChunkStrategy.estimateTokens(buffer.toString());

            if (bufferTokens + sectionTokens > maxTokens && buffer.length() > 0) {
                // 保存当前 buffer
                String text = buffer.toString().trim();
                if (text.length() >= MIN_CHUNK_CHARS) {
                    result.add(new Chunk(text, result.size(),
                            RecursiveChunkStrategy.estimateTokens(text), ""));
                }
                buffer.setLength(0);
            }

            if (buffer.length() > 0) buffer.append("\n\n");
            buffer.append(section);
        }

        // 最后一段
        if (buffer.length() > 0) {
            String text = buffer.toString().trim();
            if (text.length() >= MIN_CHUNK_CHARS) {
                result.add(new Chunk(text, result.size(),
                        RecursiveChunkStrategy.estimateTokens(text), ""));
            }
        }

        return result;
    }

    /** 对仍超长的 chunk 使用递归分块 fallback */
    private List<Chunk> applyFallbackForOversized(List<Chunk> chunks, int maxTokens) {
        List<Chunk> result = new ArrayList<>();
        for (Chunk chunk : chunks) {
            if (RecursiveChunkStrategy.estimateTokens(chunk.getText()) > maxTokens) {
                List<Chunk> subChunks = fallback.chunk(chunk.getText(), maxTokens, 0);
                for (Chunk sub : subChunks) {
                    result.add(new Chunk(sub.getText(), result.size(),
                            sub.getTokenCount(), chunk.getPrefix() + sub.getPrefix()));
                }
            } else {
                result.add(chunk);
            }
        }
        return result;
    }

    private List<Chunk> applyOverlap(List<Chunk> chunks, int overlap, int maxTokens) {
        if (overlap <= 0 || chunks.size() <= 1) return chunks;

        List<Chunk> result = new ArrayList<>(chunks.size());
        result.add(chunks.get(0));
        for (int i = 1; i < chunks.size(); i++) {
            Chunk previous = result.get(i - 1);
            String previousContent = previous.getPrefix() + previous.getText();
            String prefix = suffixWithinTokenBudget(previousContent, overlap);
            Chunk current = chunks.get(i);
            int totalTokens = RecursiveChunkStrategy.estimateTokens(prefix + current.getText());
            if (totalTokens > maxTokens) {
                int available = Math.max(0,
                        maxTokens - RecursiveChunkStrategy.estimateTokens(current.getText()));
                prefix = suffixWithinTokenBudget(previousContent, available);
                totalTokens = RecursiveChunkStrategy.estimateTokens(prefix + current.getText());
            }
            result.add(new Chunk(current.getText(), current.getIndex(), totalTokens, prefix));
        }
        return result;
    }

    /** 返回不超过 tokenBudget 的最长文本后缀。 */
    private static String suffixWithinTokenBudget(String text, int tokenBudget) {
        if (text == null || text.isEmpty() || tokenBudget <= 0) return "";
        int low = 0;
        int high = text.length();
        while (low < high) {
            int length = (low + high + 1) >>> 1;
            String suffix = text.substring(text.length() - length);
            if (RecursiveChunkStrategy.estimateTokens(suffix) <= tokenBudget) {
                low = length;
            } else {
                high = length - 1;
            }
        }
        return text.substring(text.length() - low);
    }
}
