/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.retrieval;

import com.example.smartassistant.common.rag.KnowledgeBase;
import com.example.smartassistant.common.rag.KnowledgeDocument;
import com.example.smartassistant.common.rag.KnowledgeHit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Parent-Child 检索侧展开器——「小块召回、大块生成」（small-to-big）。
 * <p>
 * 向量检索为了召回精度命中的是细粒度子块（约 256 token），但直接把子块喂给 LLM
 * 会因上下文过窄而"断章取义"。本展开器在检索命中之后、上下文格式化之前介入：
 * 按子块的 {@code parentDocId} 反查父块（约 1024 token）并替换其内容，
 * 让 LLM 拿到完整语义的父块生成答案，真正消费分块侧已构建的父块粒度。
 * </p>
 *
 * <h3>处理规则</h3>
 * <ul>
 *   <li><b>子块（parentDocId 非空）</b>：按 id 反查父块 → 用父块替换；同一父块被多个
 *       子块命中时<b>去重</b>，仅保留一次（取命中子块中的最高分作为父块代表分）。</li>
 *   <li><b>父块 / 独立文档（parentDocId 为空）</b>：原样保留（它自身即完整上下文）。</li>
 *   <li><b>父块反查失败（getById 返回 null）</b>：兜底保留原子块，绝不丢证据。</li>
 * </ul>
 *
 * <p>输出按代表分数降序排列（去重可能打乱原顺序）。本类无状态、线程安全。</p>
 */
public final class ParentChildExpander {

    private static final Logger log = LoggerFactory.getLogger(ParentChildExpander.class);

    private ParentChildExpander() {
    }

    /**
     * 将子块命中展开为父块命中。
     *
     * @param childHits 原始检索命中（可能是子块 / 父块 / 独立文档的混合）
     * @param kb        承载这些命中的知识库（用于 {@link KnowledgeBase#getById(String)} 反查父块）
     * @return 展开并去重后的命中（父块优先），入参为空或 kb 为 null 时原样返回
     */
    public static List<KnowledgeHit> expand(List<KnowledgeHit> childHits, KnowledgeBase kb) {
        if (childHits == null || childHits.isEmpty() || kb == null) {
            return childHits;
        }

        // key = 最终文档 id（父块 id 或原文档 id）→ 代表命中；LinkedHashMap 保序，末尾统一按分数排
        LinkedHashMap<String, KnowledgeHit> byKey = new LinkedHashMap<>();
        int expanded = 0;
        int missed = 0;
        int deduped = 0;

        for (KnowledgeHit hit : childHits) {
            if (hit == null || hit.getDocument() == null) continue;
            KnowledgeDocument child = hit.getDocument();
            String parentId = child.getParentDocId();

            KnowledgeHit repr;
            String key;
            if (parentId == null || parentId.isBlank()) {
                // 已是父块 / 独立文档：自身即完整上下文
                repr = hit;
                key = child.getId();
            } else {
                KnowledgeDocument parent = kb.getById(parentId);
                if (parent == null) {
                    // 兜底：父块反查不到，保留子块（不丢证据）
                    repr = hit;
                    key = child.getId();
                    missed++;
                } else {
                    // 用父块替换子块，沿用命中子块的相关度分数
                    repr = new KnowledgeHit(parent, hit.getScore());
                    key = parent.getId();
                    expanded++;
                }
            }

            // 去重：同一父块被多个子块命中 → 只保留一次，取更高分作为代表
            KnowledgeHit prev = byKey.get(key);
            if (prev == null) {
                byKey.put(key, repr);
            } else {
                deduped++;
                if (repr.getScore() > prev.getScore()) {
                    byKey.put(key, repr);
                }
            }
        }

        List<KnowledgeHit> result = new ArrayList<>(byKey.values());
        // 去重与替换后按代表分数降序，保证最相关证据在前
        result.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));

        if (expanded > 0 || missed > 0 || deduped > 0) {
            log.debug("[ParentChildExpander] 子块→父块展开: in={}, out={}, expanded={}, deduped={}, missed={}",
                    childHits.size(), result.size(), expanded, deduped, missed);
        }
        return result;
    }
}
