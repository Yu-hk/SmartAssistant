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
}
