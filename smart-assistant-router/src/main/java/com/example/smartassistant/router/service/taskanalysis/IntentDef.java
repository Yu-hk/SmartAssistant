/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.taskanalysis;

import java.util.List;

/**
 * 意图定义——结构与 {@code IntentRetriever} 配合使用。
 *
 * @param id          意图标识，如 {@code ORDER}
 * @param name        意图短名，如 {@code 订单/物流/退款}
 * @param description 意图详细说明，用于 LLM prompt
 * @param keywords    匹配关键词（供检索排序参考）
 * @param examples    示例查询，如 {@code "示例: '我的订单到哪了'"}
 * @param relevantTools 相关工具，如 {@code "相关工具: query_order, pay_order"}
 */
public record IntentDef(
        String id,
        String name,
        String description,
        List<String> keywords,
        String examples,
        String relevantTools
) {}
