/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.document.mineru;

import com.example.smartassistant.common.rag.document.DocumentParseException;

/**
 * MinerU 解析客户端抽象——便于后续无缝替换为 gRPC 实现。
 * <p>
 * v1 实现为 {@link MinerUSidecarClient}（sidecar CLI + stdin/stdout JSON）。
 * 上层（{@link MinerUDocumentParser} / {@link PdfParserRouter}）仅依赖此接口，
 * 不感知底层传输方式。
 * </p>
 */
public interface MinerUClient {

    /**
     * 解析 PDF，返回归一化后的 MinerU 响应。
     *
     * @param req 解析请求（含 PDF 路径、页范围、request_id、图片目录）
     * @return 解析响应（status=ok 且 pages 非空）
     * @throws DocumentParseException 进程不可用 / 超时 / 重试后仍失败 / 响应非法时抛出，
     *                                上层据此回退 PDFBox
     */
    MinerUParseResponse parse(MinerUParseRequest req) throws DocumentParseException;
}
