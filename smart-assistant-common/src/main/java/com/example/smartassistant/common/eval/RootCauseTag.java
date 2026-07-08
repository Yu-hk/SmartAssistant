/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.eval;

/**
 * 根因责任角色 — 对应文章《Agent 评测体系》§10 「定责到角色」。
 *
 * @author Yu-hk
 * @since 2026-07-08
 */
public enum RootCauseTag {
    ALGORITHM("算法/模型/评测逻辑"),
    OPS("知识库/配置/运营"),
    ENGINEERING("工程/工具/基础设施"),
    UNKNOWN("未归类");

    private final String label;

    RootCauseTag(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
