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

/**
 * 递归分块策略——按分隔符优先级逐级切分，直到每个块不超过 maxTokens。
 * <p>
 * 分隔符优先级：段落（\n\n）> 句子（。！？）> 逗号分号 > 固定长度回退。
 * <p>
 * 参考 LangChain 的 RecursiveCharacterTextSplitter 设计思路。
 * BGE-small-zh 的 tokenizer 按字符级编码，近似 1 个中文字 ≈ 1 token,
 * 因此可直接按字符长度估算 token 数。
 * </p>
 */
public class RecursiveChunkStrategy implements ChunkStrategy {

    private static final Logger log = LoggerFactory.getLogger(RecursiveChunkStrategy.class);

    /** 递归分隔符（按优先级降序） */
    private static final String[] SEPARATORS = {
            "\n\n",   // 段落分隔
            "\n",     // 行分隔
            "。",     // 句号（中文）
            "！",     // 感叹号
            "？",     // 问号
            "；",     // 中文分号
            "；\n",   //
            ".\n",    // 句号+换行
            ". ",     // 英文句号+空格
            "，",     // 逗号
            ",",      // 英文逗号
            "、",     // 顿号
            " ",      // 空格
    };

    /** 最小块字符数（避免产生过小的碎片） */
    private static final int MIN_CHUNK_CHARS = 20;

    @Override
    public List<Chunk> chunk(String text, int maxTokens, int overlap) {
        if (text == null || text.isBlank()) return List.of();
        if (maxTokens <= 0) maxTokens = ChunkStrategy.defaultMaxTokens();
        if (overlap < 0) overlap = 0;

        List<Chunk> result = new ArrayList<>();
        splitRecursive(text, maxTokens, overlap, 0, SEPARATORS, result);

        log.debug("[RecursiveChunk] 分块完成: textLen={}, maxTokens={}, overlap={}, chunks={}",
                text.length(), maxTokens, overlap, result.size());
        return result;
    }

    /**
     * 递归分块核心逻辑。
     *
     * @param text      待分块文本
     * @param maxTokens 最大 token 数（按字符估算）
     * @param overlap   重叠字符数
     * @param depth     当前递归深度（用于选择分隔符层级）
     * @param separators 当前使用的分隔符列表
     * @param result    累积结果
     */
    private void splitRecursive(String text, int maxTokens, int overlap,
                                 int depth, String[] separators, List<Chunk> result) {
        if (text == null || text.isBlank()) return;

        // 如果文本长度 <= maxTokens，直接作为一个块
        if (estimateTokens(text) <= maxTokens) {
            addChunk(result, text.trim());
            return;
        }

        // 选择当前层级的分隔符
        String separator = depth < separators.length ? separators[depth] : null;

        if (separator == null) {
            // 已用完所有分隔符，按固定长度截断
            splitByFixedLength(text, maxTokens, overlap, result);
            return;
        }

        // 用当前分隔符切分
        List<String> segments = splitBySeparator(text, separator);

        if (segments.size() == 1) {
            // 当前分隔符没有有效切分，进入下一级
            splitRecursive(text, maxTokens, overlap, depth + 1, separators, result);
            return;
        }

        // 合并小段，递归处理大段
        StringBuilder currentBuffer = new StringBuilder();
        for (String segment : segments) {
            String trimmed = segment.trim();
            if (trimmed.isBlank()) continue;

            int segmentTokens = estimateTokens(trimmed);

            if (segmentTokens > maxTokens) {
                // 大段：先 flush 当前 buffer，再递归处理该段
                flushBuffer(result, currentBuffer);
                splitRecursive(trimmed, maxTokens, overlap, depth + 1, separators, result);
            } else {
                // 检查加入后是否超限
                int bufferTokens = estimateTokens(currentBuffer.toString());
                if (bufferTokens + segmentTokens > maxTokens) {
                    flushBuffer(result, currentBuffer);
                }
                if (currentBuffer.length() > 0) currentBuffer.append(separator);
                currentBuffer.append(trimmed);
            }
        }

        // flush 最后一段
        flushBuffer(result, currentBuffer);

        // 应用重叠
        if (overlap > 0 && result.size() > 1) {
            applyOverlap(result, overlap);
        }
    }

    /** 按固定长度截断 */
    private void splitByFixedLength(String text, int maxTokens, int overlap,
                                     List<Chunk> result) {
        int start = 0;
        int chunkIdx = result.size();
        while (start < text.length()) {
            int end = Math.min(start + maxTokens, text.length());
            String chunkText = text.substring(start, end).trim();
            if (chunkText.length() >= MIN_CHUNK_CHARS) {
                result.add(new Chunk(chunkText, chunkIdx++, estimateTokens(chunkText), ""));
            }
            if (end >= text.length()) break;
            start = end - overlap;
            if (start <= 0 || start >= text.length()) break;
        }
    }

    /** 根据分隔符切分文本，保留分隔符 */
    private List<String> splitBySeparator(String text, String separator) {
        List<String> result = new ArrayList<>();

        if (separator.equals("\n\n")) {
            // 段落分隔：按空行切分
            for (String part : text.split("\n\\s*\n")) {
                String trimmed = part.trim();
                if (!trimmed.isBlank()) result.add(trimmed);
            }
        } else if (separator.equals("。") || separator.equals("！") || separator.equals("？")
                || separator.equals("；") || separator.equals("。\n")) {
            // 中文标点分句：保留标点
            StringBuilder sb = new StringBuilder();
            for (char c : text.toCharArray()) {
                sb.append(c);
                if (c == '。' || c == '！' || c == '？' || c == '；') {
                    String s = sb.toString().trim();
                    if (!s.isBlank()) result.add(s);
                    sb.setLength(0);
                }
            }
            if (!sb.isEmpty()) {
                String s = sb.toString().trim();
                if (!s.isBlank()) result.add(s);
            }
        } else {
            // 其他分隔符：直接 split
            for (String part : text.split(java.util.regex.Pattern.quote(separator))) {
                String trimmed = part.trim();
                if (!trimmed.isBlank()) result.add(trimmed);
            }
        }

        return result;
    }

    /** 将 buffer 中的文本作为一个块添加 */
    private void flushBuffer(List<Chunk> result, StringBuilder buffer) {
        if (buffer.isEmpty()) return;
        String text = buffer.toString().trim();
        if (text.length() >= MIN_CHUNK_CHARS) {
            addChunk(result, text);
        }
        buffer.setLength(0);
    }

    private void addChunk(List<Chunk> result, String text) {
        int idx = result.size();
        result.add(new Chunk(text, idx, estimateTokens(text), ""));
    }

    /** 应用重叠：从上一个 chunk 的尾部取 overlap 字符拼到当前 chunk 开头 */
    private void applyOverlap(List<Chunk> chunks, int overlap) {
        for (int i = chunks.size() - 1; i > 0; i--) {
            Chunk prev = chunks.get(i - 1);
            String prevText = prev.getText();
            int overlapLen = Math.min(overlap, prevText.length());
            String overlapText = prevText.substring(prevText.length() - overlapLen);

            Chunk current = chunks.get(i);
            String newText = overlapText + current.getText();
            chunks.set(i, new Chunk(newText, current.getIndex(),
                    estimateTokens(newText), overlapText));
        }
    }

    /**
     * 估算文本的 token 数（中文近似 1 字 ≈ 1 token, 英文 ≈ 0.4 token/char）。
     * BGE-small-zh 使用字符级 tokenizer，此估算足够用于分块决策。
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        int chineseChars = 0;
        int otherChars = 0;
        for (char c : text.toCharArray()) {
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                chineseChars++;
            } else {
                otherChars++;
            }
        }
        return chineseChars + (int) Math.ceil(otherChars * 0.4);
    }
}
