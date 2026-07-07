/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */
package com.example.smartassistant.common.rag;

/**
 * 知识文档状态——对标字节 RAG 七连问第七问「版本治理·隔离与回滚」。
 * <p>
 * <ul>
 *   <li>ACTIVE：正常可检索</li>
 *   <li>SUPERSEDED：已被新版本取代（保留用于历史回溯，检索排除）</li>
 *   <li>QUARANTINED：隔离（错误文档入库后标记隔离，从检索排除但保留存储，可恢复）</li>
 * </ul>
 * 检索阶段过滤掉 SUPERSEDED / QUARANTINED，仅返回 ACTIVE。
 * </p>
 */
public enum DocumentStatus {

    ACTIVE,
    SUPERSEDED,
    QUARANTINED;

    /** 由字符串解析（如 "QUARANTINED"），非法值默认 ACTIVE */
    public static DocumentStatus fromCode(String code) {
        if (code == null || code.isBlank()) return ACTIVE;
        try {
            return DocumentStatus.valueOf(code.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ACTIVE;
        }
    }

    /** 是否可被检索返回 */
    public boolean isRetrievable() {
        return this == ACTIVE;
    }
}
