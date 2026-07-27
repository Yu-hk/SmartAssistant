/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * ⭐ G3 工具结果预算：单个工具返回超过 token 预算时，将完整内容写盘，
 * 上下文仅保留「预览 + 指针」，避免大结果撑爆上下文窗口（对应文章"写盘 + 预览"策略）。
 *
 * <p>设计取舍：
 * <ul>
 *   <li>小结果（≤ {@link #SPOOL_TOKENS}）直接保留，零开销；</li>
 *   <li>大结果写盘到系统临时目录，上下文留前 {@link #PREVIEW_TOKENS} token 预览与绝对路径；
 *       若后续接入文件读取类工具，Agent 可据路径回载完整内容。</li>
 *   <li>写盘失败自动降级为纯截断，绝不中断 Agent 主流程。</li>
 * </ul>
 * </p>
 *
 * @author Yu-hk
 * @since 2026-07-27
 */
public final class ToolResultBudget {

    private static final Logger log = LoggerFactory.getLogger(ToolResultBudget.class);

    /** 写盘阈值：超过此 token 数才落盘（小结果直接保留） */
    public static final int SPOOL_TOKENS = 4000;

    /** 预览保留 token 数（中文≈1 token/字，约 1500 字） */
    public static final int PREVIEW_TOKENS = 1500;

    /** 落盘目录：系统临时目录下，跨平台安全 */
    private static final Path DUMP_DIR = Paths.get(
            System.getProperty("java.io.tmpdir"), "smart-assistant", "tool-dump");

    private ToolResultBudget() {}

    /**
     * 应用工具结果预算。
     *
     * @param responses 原始工具返回（可能为 null）
     * @return 经预算裁剪后的返回列表；若无超阈项则原样返回
     */
    public static List<ToolResponseMessage.ToolResponse> apply(
            List<ToolResponseMessage.ToolResponse> responses) {
        if (responses == null) return null;

        boolean anyOver = false;
        for (var r : responses) {
            if (r != null && TokenEstimator.estimate(r.responseData()) > SPOOL_TOKENS) {
                anyOver = true;
                break;
            }
        }
        if (!anyOver) return responses;

        List<ToolResponseMessage.ToolResponse> out = new ArrayList<>(responses.size());
        for (var r : responses) {
            if (r == null) {
                out.add(null);
                continue;
            }
            String data = r.responseData();
            int est = TokenEstimator.estimate(data);
            if (est <= SPOOL_TOKENS) {
                out.add(r);
                continue;
            }
            String path = spool(r.name(), data);
            String preview = previewOf(data);
            String truncated = preview
                    + String.format(
                    "\n[工具结果过长：约 %d tokens，完整内容已写盘 %s；如需完整数据请使用文件读取类工具加载该路径]",
                    est, path);
            out.add(new ToolResponseMessage.ToolResponse(r.id(), r.name(), truncated));
            log.info("[ToolResultBudget] 工具 {} 结果 {} tokens 超阈，已写盘预览: {}", r.name(), est, path);
        }
        return out;
    }

    private static String spool(String toolName, String data) {
        try {
            Files.createDirectories(DUMP_DIR);
            String safe = (toolName == null ? "tool" : toolName.replaceAll("[^a-zA-Z0-9_\\-]", "_"));
            Path file = DUMP_DIR.resolve(safe + "-" + UUID.randomUUID() + ".txt");
            Files.writeString(file, data);
            return file.toAbsolutePath().toString();
        } catch (Exception e) {
            log.warn("[ToolResultBudget] 写盘失败，降级为纯截断: {}", e.getMessage());
            return "<写盘失败>";
        }
    }

    private static String previewOf(String data) {
        int maxChars = PREVIEW_TOKENS; // 中文近似 1:1，按字符保守截断
        if (data.length() <= maxChars) return data;
        return data.substring(0, maxChars)
                + String.format("\n... [预览截断，前 %d 字符 / 共约 %d tokens]", maxChars, TokenEstimator.estimate(data));
    }
}
