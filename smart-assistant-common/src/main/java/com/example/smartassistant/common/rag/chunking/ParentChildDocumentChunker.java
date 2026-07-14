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
import java.util.List;
import java.util.UUID;

/**
 * ⭐ Parent-Child 双粒度分块编排器。
 * <p>
 * 对应 RAG 文章"索引层"的 Parent-Child Chunking 策略：
 * <ul>
 *   <li><b>子块（Child Chunks）</b>：粒度小（~256 tokens），用于向量检索，命中率高</li>
 *   <li><b>父块（Parent Chunks）</b>：粒度大（~1024 tokens），提供完整上下文用于 LLM 阅读</li>
 * </ul>
 * 检索时命中子块 → 通过 parentId 找到父块 → 将父块交给 LLM。
 * </p>
 *
 * <p>使用方式：</p>
 * <pre>{@code
 * ParentChildDocumentChunker chunker = new ParentChildDocumentChunker();
 * ParentChildResult result = chunker.chunkParentChild(parsedDocs);
 * // result.getChildDocs() → 建索引（用于检索）
 * // result.getParentDocs() → 查出来送 LLM
 * }</pre>
 */
public class ParentChildDocumentChunker {

    private static final Logger log = LoggerFactory.getLogger(ParentChildDocumentChunker.class);

    /** 子块（检索用）：最大 token 数 */
    private final int childMaxTokens;

    /** 父块（阅读用）：最大 token 数 */
    private final int parentMaxTokens;

    /** 重叠 token 数 */
    private final int overlap;

    /** 主分块策略 */
    private final ChunkStrategy chunkStrategy;

    public ParentChildDocumentChunker() {
        this(new SemanticChunkStrategy(), 256, 1024, 50);
    }

    public ParentChildDocumentChunker(ChunkStrategy chunkStrategy,
                                       int childMaxTokens, int parentMaxTokens, int overlap) {
        this.chunkStrategy = chunkStrategy;
        this.childMaxTokens = childMaxTokens;
        this.parentMaxTokens = parentMaxTokens;
        this.overlap = overlap;
    }

    /**
     * 执行 Parent-Child 双粒度分块。
     *
     * @param parsedElements 解析后的文档元素列表
     * @return 父子分块结果
     */
    public ParentChildResult chunkParentChild(List<ParsedDocument> parsedElements) {
        if (parsedElements == null || parsedElements.isEmpty()) {
            return new ParentChildResult(List.of(), List.of());
        }

        List<KnowledgeDocument> parentDocs = new ArrayList<>();
        List<KnowledgeDocument> childDocs = new ArrayList<>();
        int globalSequence = 0;

        for (ParsedDocument element : parsedElements) {
            String text = element.getContent();
            if (text == null || text.isBlank()) continue;

            // Step 1: 以父块粒度分块（阅读用，大块）
            List<Chunk> parentChunks = chunkStrategy.chunk(text, parentMaxTokens, overlap);

            for (int pIdx = 0; pIdx < parentChunks.size(); pIdx++) {
                Chunk parentChunk = parentChunks.get(pIdx);
                String parentContent = parentChunk.getPrefix() + parentChunk.getText();

                // 生成父块 ID（用于子块关联）
                String parentDocId = generateParentDocId(element, globalSequence);

                // 创建父块 KnowledgeDocument（parentDocId 为空，表示自身就是父块）
                KnowledgeDocument parentDoc = createKnowledgeDoc(
                        parentDocId, element, parentContent,
                        buildKeywords(element, parentChunk, pIdx, parentChunks.size()),
                        globalSequence, ""
                );
                parentDocs.add(parentDoc);

                // Step 2: 以子块粒度分块（检索用，小块），关联到父块
                List<Chunk> childChunks = chunkStrategy.chunk(parentContent, childMaxTokens, 0);

                for (int cIdx = 0; cIdx < childChunks.size(); cIdx++) {
                    Chunk childChunk = childChunks.get(cIdx);
                    String childContent = childChunk.getPrefix() + childChunk.getText();

                    String childDocId = parentDocId + "-child-" + cIdx;
                    String childKeywords = buildKeywords(element, childChunk, cIdx, childChunks.size());

                    KnowledgeDocument childDoc = createKnowledgeDoc(
                            childDocId, element, childContent, childKeywords, globalSequence, parentDocId
                    );
                    childDocs.add(childDoc);
                    globalSequence++;
                }
            }
        }

        log.info("[ParentChildChunker] 分块完成: parents={}, children={}",
                parentDocs.size(), childDocs.size());
        return new ParentChildResult(parentDocs, childDocs);
    }

    /** 生成父块唯一 ID */
    private String generateParentDocId(ParsedDocument element, int seq) {
        String baseId = element.getDocId() != null ? element.getDocId() : UUID.randomUUID().toString();
        return baseId + "-parent-" + seq;
    }

    /** 创建 KnowledgeDocument（支持 parentDocId） */
    private KnowledgeDocument createKnowledgeDoc(String docId, ParsedDocument element,
                                                  String content, String keywords,
                                                  int chunkIndex, String parentDocId) {
        return new KnowledgeDocument(
                docId,
                element.getTitle(),
                content,
                element.getCategory(),
                keywords,
                element.getEffectiveAt(),
                element.getExpireAt(),
                element.getTenantId(),
                element.getVersion(),
                element.getSourceUrl(),
                chunkIndex,
                parentDocId,
                DocumentMetadataEnricher.toSourceType(element.getContentType())
        );
    }

    /** 构建关键词（复用 DocumentChunker 的逻辑） */
    private String buildKeywords(ParsedDocument element, Chunk chunk,
                                  int chunkIdx, int totalChunks) {
        StringBuilder sb = new StringBuilder();

        if (element.getKeywords() != null && !element.getKeywords().isBlank()) {
            sb.append(element.getKeywords());
        }

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

        if (totalChunks > 1) {
            sb.append(",chunk-").append(chunkIdx + 1).append("-of-").append(totalChunks);
        }

        return sb.toString();
    }

    // ==================== 结果类 ====================

    /**
     * Parent-Child 分块结果。
     */
    public record ParentChildResult(
            /** 父块列表（阅读用，大块 → 送 LLM） */
            List<KnowledgeDocument> parentDocs,
            /** 子块列表（检索用，小块 → 建索引） */
            List<KnowledgeDocument> childDocs
    ) {}
}
