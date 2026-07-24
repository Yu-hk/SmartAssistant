/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.chunking;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 分块上下文工具——为子块计算其应携带的「最近标题前缀」，实现 RAG 文章推荐的
 * Contextual / Late Chunking：短片段在向量化时携带所属章节标题，避免脱离上下文
 * 导致检索歧义（例如「退款将在 3 个工作日内到账」脱离「退款政策」章节后语义模糊）。
 *
 * <p>判定优先级（与 {@link SemanticChunkStrategy} 的语义边界保持一致）：</p>
 * <ol>
 *   <li>片段自身以标题开头 → 已自带上下文，不重复注入</li>
 *   <li>在父块文本中向前回扫最近标题行 → 注入该「标题短语」</li>
 *   <li>回退 element 级标题（仅当是真正的短标题）</li>
 *   <li>末级回退章节标记（拒绝"第X页"布局标记，避免污染嵌入向量）</li>
 * </ol>
 *
 * <p>⚠️ 关键：本项目 PDF 解析器常把「标题 + 正文」输出到同一行（如
 * {@code "五、常见问题 识别，订单即进入..."}），因此标题识别必须是<b>行首前缀匹配</b>
 * （{@code find()} + {@code ^} 锚定），且抽取时只取「标题短语」（截到空白），
 * 不能用 {@code matches()} 要求整行恰好就是标题——否则标题会被漏判，
 * 退化为注入无语义的布局标记。</p>
 */
public final class ChunkContextUtil {

    /**
     * 标题行识别 + 标题短语抽取（唯一权威正则）。
     * 命中行首即可（{@code ^} 锚定），抽取组为「标题短语」：
     * 截到第一个空白或合理长度，避免把整行正文都当成标题注入。
     */
    private static final Pattern HEADING = Pattern.compile(
            "^(?:#{1,6}\\s+\\S+"                              // Markdown 标题: "# 标题"
                    + "|第[一二三四五六七八九十百千0-9]+[章节条款篇]\\S*"   // 第X章/节/条/款/篇
                    + "|[一二三四五六七八九十]+[、．.]\\S*"                // 一、 二、 三、
                    + "|\\d{1,3}(?:\\.\\d{1,3})*\\.?\\s+\\S+)");          // 1. 引言 / 3.2 退款流程

    /** 布局标记（如 "第1页-1"、"第2页-表格1"）——语义弱，不作为标题前缀注入 */
    private static final Pattern PAGE_MARKER = Pattern.compile("^第\\d+页");

    private ChunkContextUtil() {
    }

    /** 判断单行是否以标题开头（正文可能同附在该行，前缀匹配即可） */
    public static boolean isHeadingLine(String line) {
        if (line == null) return false;
        String t = line.trim();
        if (t.isEmpty()) return false;
        return HEADING.matcher(t).find(); // ^ 锚定行首，find() 命中前缀即成立
    }

    /**
     * 为子块片段解析其应携带的标题前缀（含尾随换行，便于直接拼接到正文前）。
     *
     * @param fullText       完整父块文本（子块片段的来源，可能含内联标题）
     * @param fragment       子块片段文本（即 {@code chunk.getText()}，不含已有 prefix）
     * @param fallbackTitle  元素级标题（如 PDF 解析出的章节标题），无最近标题时回退
     * @param fallbackSection 元素级章节标记（如"第X页-Y"），末级回退
     * @return 标题前缀（形如 {@code "一、下单前准备\n\n"}），或空串表示无需注入
     *         （片段自身已以标题开头，或全文无可用标题）
     */
    public static String resolveChildPrefix(String fullText, String fragment,
                                            String fallbackTitle, String fallbackSection) {
        if (fragment == null) fragment = "";

        // 1. 片段自身以标题开头 → 已含上下文，不重复注入
        String firstLine = fragment.split("\n", 2)[0].trim();
        if (isHeadingLine(firstLine)) {
            return "";
        }

        // 2. 在完整文本中定位片段起点，向前回扫最近的标题行
        String nearest = findNearestHeadingBefore(fullText, fragment);
        if (nearest != null) {
            return nearest;
        }

        // 3. 回退：仅当 fallbackTitle 是"真正的短标题"时才注入。
        // ⚠️ 本项目 extractTitle 会把整段首行（最长 80 字）当作标题，
        //    若直接注入会把一整段文本当成长前缀，反而污染检索并撑破子块尺寸上限。
        if (fallbackTitle != null && isShortHeadingLike(fallbackTitle)) {
            return capPrefix(fallbackTitle);
        }
        // 4. 末级回退：章节标记（如"第3节 产品说明"）可安全注入；
        //    但拒绝"第X页-Y"这类布局标记，避免把页面坐标当语义上下文注入向量。
        if (fallbackSection != null && !PAGE_MARKER.matcher(fallbackSection.trim()).find()
                && isShortHeadingLike(fallbackSection)) {
            return capPrefix(fallbackSection);
        }
        return "";
    }

    /** 是否为"短标题"：长度受限且不含句读，避免把长段落首行当标题前缀注入 */
    private static boolean isShortHeadingLike(String text) {
        String s = text.trim();
        if (s.isEmpty()) return false;
        if (s.length() > 24) return false;
        if (s.matches(".*[。！？；：，、．.].*")) return false;
        return true;
    }

    /** 前缀截断上限，避免异常长标题撑破子块尺寸 */
    private static String capPrefix(String heading) {
        String s = heading.trim();
        if (s.length() > 32) s = s.substring(0, 32);
        return s + "\n\n";
    }

    /** 从一行中抽取「标题短语」（标题部分，截到空白），并套用前缀格式 */
    private static String extractHeading(String line) {
        Matcher m = HEADING.matcher(line.trim());
        if (m.find()) {
            return capPrefix(m.group());
        }
        return "";
    }

    /** 在 fullText 中定位 fragment 起点，从该行向前逐行回扫最近的标题行，返回标题短语 */
    private static String findNearestHeadingBefore(String fullText, String fragment) {
        if (fullText == null || fullText.isEmpty()) return null;
        int idx = fullText.indexOf(fragment);
        if (idx < 0) idx = 0;
        String before = fullText.substring(0, idx);
        String[] lines = before.split("\n", -1);
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (isHeadingLine(line)) {
                return extractHeading(line);
            }
        }
        return null;
    }
}
