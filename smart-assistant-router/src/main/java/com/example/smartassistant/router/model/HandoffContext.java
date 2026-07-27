/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.model;

import java.util.ArrayList;
import java.util.List;

/**
 * ⭐ G4: Handoff 结构化上下文。
 *
 * <p>将原先自由文本 {@code contextPayload} 升级为结构化字段，使目标 Agent
 * 能精确消费「摘要 / 必读文件 / 关键约束 / 关键数据」四段信息，避免长链路
 * 交接中信息丢失或歧义（对应文章④ Handoff 模式的核心诉求：显式传递累积上下文）。</p>
 *
 * <p>兼容策略：自由文本经 {@link #fromFreeText(String)} 尽力解析（无标记时整体作为摘要），
 * 结构化生产者可直接用 {@link HandoffCommand#structured} 工厂填充各字段。</p>
 *
 * @param summary        交接摘要（必填建议）：前置 Agent 已完成/已掌握的核心结论
 * @param mustReadFiles  目标 Agent 必读的文件 / 接口 / 文档路径
 * @param keyConstraints 目标 Agent 必须遵守的约束（口径 / 边界 / 禁止项）
 * @param criticalData   关键数据片段（订单号 / 用户 ID / 中间计算结果等）
 */
public record HandoffContext(
        String summary,
        List<String> mustReadFiles,
        List<String> keyConstraints,
        String criticalData
) {

    /** 空上下文（字段安全默认值） */
    public static HandoffContext empty() {
        return new HandoffContext(null, List.of(), List.of(), null);
    }

    /**
     * 从自由文本尽力解析为结构化上下文。
     * 识别常见中文分段标记（必读 / 约束 / 数据）；无标记时整体作为摘要。
     */
    public static HandoffContext fromFreeText(String freeText) {
        if (freeText == null || freeText.isBlank()) {
            return empty();
        }
        List<String> files = new ArrayList<>();
        List<String> constraints = new ArrayList<>();
        StringBuilder summarySb = new StringBuilder();
        StringBuilder dataSb = new StringBuilder();
        boolean captured = false;

        for (String rawLine : freeText.split("\n", -1)) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("必读") || line.startsWith("文件") || line.startsWith("读取")) {
                files.add(stripPrefix(line));
                captured = true;
            } else if (line.startsWith("约束") || line.startsWith("限制") || line.startsWith("禁止")) {
                constraints.add(stripPrefix(line));
                captured = true;
            } else if (line.startsWith("数据") || line.startsWith("关键") && line.contains("：")) {
                dataSb.append(stripPrefix(line)).append("\n");
                captured = true;
            } else {
                summarySb.append(line).append("\n");
            }
        }
        if (!captured) {
            // 无结构化标记：整体作为摘要
            return new HandoffContext(freeText.strip(), List.of(), List.of(), null);
        }
        String summary = summarySb.toString().strip();
        String data = dataSb.toString().strip();
        return new HandoffContext(
                summary.isEmpty() ? null : summary,
                files,
                constraints,
                data.isEmpty() ? null : data
        );
    }

    private static String stripPrefix(String line) {
        int idx = line.indexOf('：');
        if (idx < 0) idx = line.indexOf(':');
        if (idx >= 0 && idx + 1 < line.length()) {
            return line.substring(idx + 1).strip();
        }
        return line;
    }

    /**
     * 渲染为结构化文本，供目标 Agent 作为上下文消费。
     * 各段用方头括号标题包裹，便于 LLM 精准定位。
     */
    public String render() {
        StringBuilder sb = new StringBuilder();
        if (summary != null && !summary.isBlank()) {
            sb.append("【交接摘要】\n").append(summary).append("\n\n");
        }
        if (mustReadFiles != null && !mustReadFiles.isEmpty()) {
            sb.append("【必读文件】\n");
            for (String f : mustReadFiles) sb.append("- ").append(f).append("\n");
            sb.append("\n");
        }
        if (keyConstraints != null && !keyConstraints.isEmpty()) {
            sb.append("【关键约束】\n");
            for (String c : keyConstraints) sb.append("- ").append(c).append("\n");
            sb.append("\n");
        }
        if (criticalData != null && !criticalData.isBlank()) {
            sb.append("【关键数据】\n").append(criticalData).append("\n\n");
        }
        return sb.toString().strip();
    }
}
