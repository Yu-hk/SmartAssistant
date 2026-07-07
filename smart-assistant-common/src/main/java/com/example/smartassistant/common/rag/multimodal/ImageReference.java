/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */
package com.example.smartassistant.common.rag.multimodal;

/**
 * 多模态摄取中的图片引用——承载字节内容与 MIME 类型。
 * <p>
 * 由摄取方从文件系统 / 网络 / 上传流构建，交给 {@link ImageCaptioner} 做视觉描述。
 * 字节内容不可变，便于缓存哈希与幂等重摄取。
 * </p>
 */
public class ImageReference {

    /** 来源名（文件名 / URL），同时用作图谱实体线索与文档标题前缀 */
    private final String sourceName;

    /** 图片字节内容（PNG/JPEG/WEBP 等） */
    private final byte[] bytes;

    /** MIME 类型（如 image/png），为空时按 image/png 处理 */
    private final String mimeType;

    public ImageReference(String sourceName, byte[] bytes, String mimeType) {
        this.sourceName = sourceName;
        this.bytes = bytes;
        this.mimeType = mimeType;
    }

    public String getSourceName() {
        return sourceName;
    }

    public byte[] getBytes() {
        return bytes;
    }

    public String getMimeType() {
        return mimeType;
    }
}
