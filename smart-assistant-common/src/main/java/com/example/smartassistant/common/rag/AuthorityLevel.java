/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */
package com.example.smartassistant.common.rag;

/**
 * 知识来源权威性等级——对标字节 RAG 七连问第六问「冲突处理·来源权威性排序」。
 * <p>
 * 当多个 chunk 语义相似但内容冲突时，按权威性排序优先取高权威来源：
 * <ul>
 *   <li>L1_OFFICIAL（4）：官方文档 / 最新版规范（最高权威）</li>
 *   <li>L2_INTERNAL（3）：内部正式文档（公司 Wiki 正式发布页）</li>
 *   <li>L3_NOTE（2）：内部非正式文档 / 团队笔记 / 会议纪要</li>
 *   <li>L4_EXTERNAL（1）：外部非正式来源（博客 / 论坛帖子，最低权威）</li>
 * </ul>
 * rank 越大越权威，检索 rerank 阶段用作加权因子（见 {@code composeScore}）。
 * </p>
 */
public enum AuthorityLevel {

    L1_OFFICIAL(4, "官方文档/最新版规范"),
    L2_INTERNAL(3, "内部正式文档"),
    L3_NOTE(2, "内部非正式文档/团队笔记"),
    L4_EXTERNAL(1, "外部非正式来源");

    private final int rank;
    private final String desc;

    AuthorityLevel(int rank, String desc) {
        this.rank = rank;
        this.desc = desc;
    }

    /** 权威性数值（越大越权威） */
    public int getRank() {
        return rank;
    }

    public String getDesc() {
        return desc;
    }

    /** 由代码解析（如 "L1_OFFICIAL" / "L1"），非法值默认 L2_INTERNAL */
    public static AuthorityLevel fromCode(String code) {
        if (code == null || code.isBlank()) return L2_INTERNAL;
        String c = code.trim().toUpperCase();
        for (AuthorityLevel lvl : values()) {
            if (lvl.name().equals(c) || lvl.name().startsWith(c)) return lvl;
        }
        return L2_INTERNAL;
    }

    /** 由 rank 数值解析，越界默认 L2_INTERNAL */
    public static AuthorityLevel fromRank(int rank) {
        for (AuthorityLevel lvl : values()) {
            if (lvl.rank == rank) return lvl;
        }
        return L2_INTERNAL;
    }
}
