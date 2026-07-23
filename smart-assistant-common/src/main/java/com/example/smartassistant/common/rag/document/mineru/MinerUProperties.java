/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.document.mineru;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MinerU 适配器配置（承载 {@code app.rag.mineru.*}）。
 * <p>
 * 与项目现有 {@link org.springframework.boot.context.properties.ConfigurationProperties}
 * 风格一致（见 {@code RagProductionProperties}），所有字段均带安全默认值，缺失配置也可按
 * "关闭 + PDFBox 兜底" 行为启动。
 * </p>
 *
 * <ul>
 *   <li>{@code enabled}：总开关，默认 false（关闭时 {@code DocumentParseRouter} 行为完全不变）。</li>
 *   <li>{@code sidecarCommand}：常驻 sidecar CLI 启动命令（v1 进程协议，后续可无缝切 gRPC）。</li>
 *   <li>{@code warmInstances}：进程池预热实例数（模型仅加载一次）。</li>
 *   <li>{@code timeoutMs}：单次解析超时（毫秒）。</li>
 *   <li>{@code fallbackToPdfbox}：MinerU 失败时回退 PDFBox 全链路。</li>
 *   <li>{@code captionExclusive}：R5 caption 独占——MinerU 活跃时结构性禁用 PDFBox 图片 caption/OCR。</li>
 *   <li>{@code imagesTempDir}：MinerU 抽取图片的临时目录。</li>
 *   <li>{@code enabledImageVectorization}：是否启用图片向量化（v1 默认关闭，仅存路径）。</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "app.rag.mineru")
@Data
public class MinerUProperties {

    /** 总开关：是否启用 MinerU 增强解析（默认关闭） */
    private boolean enabled = false;

    /** 常驻 sidecar CLI 启动命令（默认 python mineru_sidecar.py） */
    private String sidecarCommand = "python mineru_sidecar.py";

    /** 进程池预热实例数（模型仅加载一次），默认 1 */
    private int warmInstances = 1;

    /** 单次解析超时（毫秒），默认 120000 */
    private long timeoutMs = 120000L;

    /** MinerU 失败时回退 PDFBox 全链路（含 caption 兜底），默认 true */
    private boolean fallbackToPdfbox = true;

    /** R5 caption 独占：MinerU 活跃时结构性禁用 PDFBox 图片 caption/OCR，默认 true */
    private boolean captionExclusive = true;

    /** MinerU 抽取图片的临时目录，默认 /tmp/mineru */
    private String imagesTempDir = "/tmp/mineru";

    /** 是否启用图片向量化（v1 仅存路径，不向量化），默认 false */
    private boolean enabledImageVectorization = false;
}
