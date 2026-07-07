/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */
package com.example.smartassistant.common.rag.multimodal;

import com.example.smartassistant.common.rag.AuthorityLevel;
import com.example.smartassistant.common.rag.DocumentStatus;
import com.example.smartassistant.common.rag.KnowledgeBase;
import com.example.smartassistant.common.rag.KnowledgeDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;

/**
 * 多模态摄取编排器——图片 → 视觉描述 → 知识文档入库。
 * <p>
 * 对标 RAG 七连问「多模态数据源」：将截图、扫描件、图表等非文本知识经视觉模型
 * 转述为文本后纳入同一向量知识库，使纯文本检索也能召回图片中的事实。
 * </p>
 *
 * <p>设计：</p>
 * <ul>
 *   <li>描述器不可用时整体空操作（{@link ImageCaptioner#isAvailable()} 为 false）；</li>
 *   <li>单图描述为空则跳过，不污染知识库；</li>
 *   <li>文档 ID 由图片字节 SHA-256 派生，重摄取同一图片幂等（同 ID 覆盖）；</li>
 *   <li>图片知识默认 {@link AuthorityLevel#L3_NOTE}（笔记级），可在调用方覆盖。</li>
 * </ul>
 */
public class MultimodalIngestor {

    private static final Logger log = LoggerFactory.getLogger(MultimodalIngestor.class);

    private final ImageCaptioner captioner;

    private final KnowledgeBase knowledgeBase;

    public MultimodalIngestor(KnowledgeBase knowledgeBase) {
        this(new NoopImageCaptioner(), knowledgeBase);
    }

    public MultimodalIngestor(ImageCaptioner captioner, KnowledgeBase knowledgeBase) {
        this.captioner = captioner != null ? captioner : new NoopImageCaptioner();
        this.knowledgeBase = knowledgeBase;
    }

    /**
     * 批量摄取图片。
     *
     * @param images   图片引用列表
     * @param tenantId 租户 ID（空串表示公开）
     * @return 实际入库的图片数
     */
    public int ingestImages(List<ImageReference> images, String tenantId) {
        if (images == null || images.isEmpty() || knowledgeBase == null) {
            return 0;
        }
        if (!captioner.isAvailable()) {
            log.debug("[MultimodalIngestor] 描述器不可用，跳过多模态摄取");
            return 0;
        }
        int ingested = 0;
        for (ImageReference img : images) {
            String caption = captioner.caption(img);
            if (caption == null || caption.isBlank()) {
                continue;
            }
            String docId = "img-" + hashBytes(img.getBytes());
            KnowledgeDocument doc = new KnowledgeDocument(
                    docId,
                    "图片知识-" + (img.getSourceName() != null ? img.getSourceName() : "unknown"),
                    caption,
                    "multimodal",
                    "image," + (img.getMimeType() != null ? img.getMimeType() : "image"),
                    -1, -1,
                    (tenantId != null && !tenantId.isBlank()) ? tenantId : "",
                    "v1", "", 0, "",
                    AuthorityLevel.L3_NOTE, DocumentStatus.ACTIVE);
            knowledgeBase.addDocument(doc);
            ingested++;
        }
        if (ingested > 0) {
            log.info("[MultimodalIngestor] 多模态入库完成: 输入={}, 入库={}", images.size(), ingested);
        }
        return ingested;
    }

    /** 由图片字节派生稳定短哈希（SHA-256 取前 8 字节） */
    private static String hashBytes(byte[] bytes) {
        if (bytes == null) return "null";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", d[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(Arrays.hashCode(bytes));
        }
    }
}
