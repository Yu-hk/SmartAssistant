/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.chunking;

import com.example.smartassistant.common.rag.KnowledgeDocument;
import com.example.smartassistant.common.rag.document.ParsedDocument;
import com.example.smartassistant.common.rag.ingestion.DocumentMetadataEnricher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文档分块编排器——将 {@link ParsedDocument} 列表分块后转换为 {@link KnowledgeDocument} 列表。
 * <p>
 * 使用语义分块（按标题/章节边界）作为主策略，超长段落 fallback 到递归分块。
 * 元数据从 ParsedDocument 继承到每个 KnowledgeDocument。
 * </p>
 *
 * <p>使用方式：</p>
 * <pre>{@code
 * DocumentChunker chunker = new DocumentChunker();
 * List<ParsedDocument> parsed = router.parse("/path/to/doc.pdf");
 * List<KnowledgeDocument> docs = chunker.chunk(parsed);
 * knowledgeBase.addDocuments(docs);
 * }</pre>
 */
public class DocumentChunker {

    private static final Logger log = LoggerFactory.getLogger(DocumentChunker.class);

    private final ChunkStrategy primaryStrategy;
    private final int maxTokens;
    private final int overlap;

    public DocumentChunker() {
        this(new SemanticChunkStrategy(), ChunkStrategy.defaultMaxTokens(), ChunkStrategy.defaultOverlap());
    }

    public DocumentChunker(ChunkStrategy primaryStrategy, int maxTokens, int overlap) {
        this.primaryStrategy = primaryStrategy;
        this.maxTokens = maxTokens;
        this.overlap = overlap;
    }

    /**
     * 对解析后的文档元素进行分块并转换为 KnowledgeDocument。
     *
     * @param parsedElements 解析后的文档元素列表
     * @return 分块后的知识文档列表
     */
    public List<KnowledgeDocument> chunk(List<ParsedDocument> parsedElements) {
        if (parsedElements == null || parsedElements.isEmpty()) return List.of();

        List<KnowledgeDocument> result = new ArrayList<>();
        int globalSequence = 0;

        for (ParsedDocument element : parsedElements) {
            String text = element.getContent();
            if (text == null || text.isBlank()) continue;

            // 对每个 ParsedDocument 元素进行分块
            List<Chunk> chunks = primaryStrategy.chunk(text, maxTokens, overlap);

            for (int i = 0; i < chunks.size(); i++) {
                Chunk chunk = chunks.get(i);
                String chunkContent = chunk.getPrefix() + chunk.getText();

                // 构造 docId: 原始ID + 全局序号 + 块内序号
                String docId = element.getDocId()
                        + "-g" + globalSequence
                        + "-c" + chunk.getIndex();

                // 构造关键词（取标题和原有关键词）
                String keywords = buildKeywords(element, chunk, i, chunks.size());

                KnowledgeDocument doc = new KnowledgeDocument(
                        docId,
                        element.getTitle(),
                        chunkContent,
                        element.getCategory(),
                        keywords,
                        element.getEffectiveAt(),
                        element.getExpireAt(),
                        element.getTenantId(),
                        element.getVersion(),
                        element.getSourceUrl(),
                        globalSequence,
                        "", // parentDocId（纯文本分块无父块）
                        DocumentMetadataEnricher.toSourceType(element.getContentType())
                );

                result.add(doc);
                globalSequence++;
            }
        }

        // 图注回贴：把被分页/分块切断、落到下一块开头的孤立图注，回贴到引用它的上一块
        result = reattachOrphanedFigureCaptions(result);

        log.info("[DocumentChunker] 分块完成: parsedElements={}, knowledgeDocs={}",
                parsedElements.size(), result.size());
        return result;
    }

    /** 构建关键词：取标题关键词 + 段落中提取的关键词 + 原始关键词 */
    private String buildKeywords(ParsedDocument element, Chunk chunk,
                                  int chunkIdx, int totalChunks) {
        StringBuilder sb = new StringBuilder();

        // 原标题关键词
        if (element.getKeywords() != null && !element.getKeywords().isBlank()) {
            sb.append(element.getKeywords());
        }

        // 从标题中提取 1~3 个关键词
        String title = element.getTitle();
        if (title != null && !title.isBlank()) {
            String[] titleParts = title.split("[，,、\\s]+");
            for (String part : titleParts) {
                part = part.trim();
                if (part.length() >= 2 && !sb.toString().contains(part)) {
                    if (sb.length() > 0) sb.append(",");
                    sb.append(part);
                }
                if (sb.toString().split(",").length > 5) break;
            }
        }

        // 分块位置提示
        if (totalChunks > 1) {
            sb.append(",chunk-").append(chunkIdx + 1).append("-of-").append(totalChunks);
        }

        return sb.toString();
    }

    // ==================== 图注回贴（修复图与正文被分页切断）====================

    /**
     * 图注起始行正则：匹配「图」/「图2」/「图 2」/「图2 文件放置路径」等形式。
     * <p>用于判断一个 chunk 是否以孤立图注开头。</p>
     */
    private static final Pattern FIGURE_CAPTION_START =
            Pattern.compile("^图(\\s*\\d+)?(\\s+[\\u4e00-\\u9fff].*)?$");

    /** 纯数字行（被拆分的孤立图号，如单独的「2」） */
    private static final Pattern PURE_NUMBER = Pattern.compile("^\\d+$");

    /**
     * 正文对图的引用短语：出现在块末尾时，说明紧随其后的下一块开头应是对应图注。
     * <p>允许短语后到文末之间有「换行 + 孤立数字」（图号被拆到本块末行的情形）。</p>
     */
    private static final Pattern FIGURE_REFERENCE = Pattern.compile(
            "(如下图所示|如图所示|见下图|如下图|如上图|见上图|如图|见图|参见图)"
                    + "[。.；;]?(\\s*\\d+)?\\s*$");

    /** 上一块末尾可能残留的孤立图号行（如「\n2」），回贴图注前需清理 */
    private static final Pattern TRAILING_ORPHAN_FIGURE_NUMBER =
            Pattern.compile("\\n(\\d+)\\s*$");

    /** 中文章节标题（如「一、背景」「二、方案」），视作正文起始 */
    private static final Pattern BODY_CN_NUM_HEADING =
            Pattern.compile("^[一二三四五六七八九十]+[、．.]");

    /** 中文章节编号（如「第一章」「第3节」），视作正文起始 */
    private static final Pattern BODY_CN_SECTION =
            Pattern.compile("^第[一二三四五六七八九十百千0-9]+[章节条款篇]");

    /** 数字编号（如「1.」「2.3.」），视作正文起始 */
    private static final Pattern BODY_NUM_SECTION = Pattern.compile("^\\d+\\.");

    /** 小写字母条目（如「a)」…「h)」），视作正文起始 */
    private static final Pattern BODY_LOWER_ALPHA = Pattern.compile("^[a-h]\\)$");

    /** 图注提取结果：图注整段文本 + 剩余正文 */
    private record FigureCaption(String captionBlock, String rest) {}

    /**
     * 后处理：把「下一块开头的孤立图注」回贴到「引用了图的上一块」。
     * <p>
     * 仅当上一块末尾显式引用了图（{@link #refersToFigure}）且当前块以图注开头
     * （{@link #extractLeadingFigureCaption}）时才回贴，避免误合并。
     * 回贴需重建 {@link KnowledgeDocument}（content 为 final），不就地修改。
     * </p>
     *
     * @param docs 分块后的知识文档列表
     * @return 回贴图注后的新列表（原列表不被修改）
     */
    private static List<KnowledgeDocument> reattachOrphanedFigureCaptions(List<KnowledgeDocument> docs) {
        if (docs == null || docs.isEmpty()) return docs;

        List<KnowledgeDocument> out = new ArrayList<>();
        for (KnowledgeDocument cur : docs) {
            // 第一块没有「上一块」，直接保留
            if (out.isEmpty()) {
                out.add(cur);
                continue;
            }

            KnowledgeDocument prev = out.get(out.size() - 1);
            FigureCaption cap = extractLeadingFigureCaption(cur.getContent());

            if (cap != null && refersToFigure(prev.getContent())) {
                // 1) 清理上一块末尾可能残留的孤立图号行（如「\n2」）
                String prevContent = stripTrailingOrphanFigureNumber(prev.getContent());
                // 2) 把图注回贴到上一块末尾
                KnowledgeDocument merged = rebuildWithContent(prev, prevContent + "\n" + cap.captionBlock());
                out.set(out.size() - 1, merged);
                // 3) 剩余正文若去空白后为空则丢弃当前块，否则保留为新的当前块
                if (cap.rest().strip().isEmpty()) {
                    log.debug("[DocumentChunker] 图注回贴后当前块为空，已丢弃: id={}", cur.getId());
                } else {
                    out.add(rebuildWithContent(cur, cap.rest()));
                }
            } else {
                out.add(cur);
            }
        }
        return out;
    }

    /**
     * 判断文本末尾是否引用了图（"如下图所示" 等短语）。
     *
     * @param text 待判断文本
     * @return 若末尾引用了图则返回 true
     */
    private static boolean refersToFigure(String text) {
        if (text == null || text.isBlank()) return false;
        return FIGURE_REFERENCE.matcher(text).find();
    }

    /**
     * 提取文本开头的图注（若存在）。
     * <p>
     * 算法：首行须为图注起始行；跳过首行后的前导空行；消费紧随其后的纯数字行
     * （图号被拆分的情形）；继续消费图注正文行，直到遇到「正文起始」行停止。
     * 图注相关非空行以空格连成一段 {@code captionBlock}，其后的文本作为 {@code rest} 返回。
     * </p>
     *
     * @param text 待提取文本
     * @return 图注结果；若不以图注开头则返回 null
     */
    private static FigureCaption extractLeadingFigureCaption(String text) {
        if (text == null || text.isBlank()) return null;

        String[] lines = text.split("\n", -1);
        if (lines.length == 0) return null;

        // 首行必须是图注起始行
        if (!FIGURE_CAPTION_START.matcher(lines[0].trim()).matches()) return null;

        List<String> captionLines = new ArrayList<>();
        captionLines.add(lines[0].trim());

        int i = 1;
        // 跳过首行后的前导空行
        while (i < lines.length && lines[i].trim().isEmpty()) i++;

        // 消费紧随其后的纯数字行（图号被拆分的情形，如单独的「2」）
        if (i < lines.length && PURE_NUMBER.matcher(lines[i].trim()).matches()) {
            captionLines.add(lines[i].trim());
            i++;
        }

        // 继续消费图注正文行，直到遇到「正文起始」行
        while (i < lines.length) {
            String trimmed = lines[i].trim();
            if (trimmed.isEmpty()) {
                // 空行：跳过并继续（图注通常紧凑，空行不计入图注段落）
                i++;
                continue;
            }
            if (isBodyStart(trimmed)) {
                break;
            }
            captionLines.add(trimmed);
            i++;
        }

        if (captionLines.isEmpty()) return null;

        String captionBlock = String.join(" ", captionLines);
        String rest = String.join("\n", Arrays.asList(lines).subList(i, lines.length));
        return new FigureCaption(captionBlock, rest);
    }

    /**
     * 判断某行是否为「正文起始」行（应停止图注消费）。
     *
     * @param line 已 trim 的行
     * @return 若为正文起始则返回 true
     */
    private static boolean isBodyStart(String line) {
        if (line.isEmpty()) return false;
        if (BODY_LOWER_ALPHA.matcher(line).matches()) return true;
        if (BODY_CN_NUM_HEADING.matcher(line).matches()) return true;
        if (BODY_CN_SECTION.matcher(line).matches()) return true;
        if (BODY_NUM_SECTION.matcher(line).matches()) return true;
        // 以句号/分号/逗号结尾，通常为本段小结，视作正文
        if (line.endsWith("。") || line.endsWith("；") || line.endsWith("，")) return true;
        // 过长的行更可能是正文而非图注
        return line.length() > 40;
    }

    /**
     * 清理块末尾残留的孤立图号行（如「\n2」）。
     *
     * @param text 待清理文本
     * @return 清理后的文本（无多余图号行时原样返回）
     */
    private static String stripTrailingOrphanFigureNumber(String text) {
        if (text == null) return null;
        Matcher m = TRAILING_ORPHAN_FIGURE_NUMBER.matcher(text);
        if (m.find()) {
            return text.substring(0, m.start());
        }
        return text;
    }

    /**
     * 以新内容重建 {@link KnowledgeDocument}，保留其余所有字段。
     * <p>content 为 final，故回贴只能重建对象；使用含 sourceType 的 14 参构造器。</p>
     *
     * @param kd         原知识文档
     * @param newContent 新内容
     * @return 重建后的知识文档
     */
    private static KnowledgeDocument rebuildWithContent(KnowledgeDocument kd, String newContent) {
        return new KnowledgeDocument(
                kd.getId(), kd.getTitle(), newContent, kd.getCategory(),
                kd.getKeywords(), kd.getEffectiveAt(), kd.getExpireAt(),
                kd.getTenantId(), kd.getVersion(), kd.getSourceUrl(),
                kd.getChunkIndex(), kd.getParentDocId(), kd.getSourceType());
    }
}
