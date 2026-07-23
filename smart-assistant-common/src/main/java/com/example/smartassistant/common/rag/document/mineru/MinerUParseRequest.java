/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.document.mineru;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * MinerU 解析请求（客户端 → sidecar CLI，JSON over stdio）。
 * <p>
 * 字段严格对齐设计响应 schema：
 * <pre>{@code {"pdf":"/abs/f.pdf","pages":"all","request_id":"u","images_dir":"/tmp/mineru/u"}}</pre>
 * </p>
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MinerUParseRequest {

    /** PDF 绝对路径 */
    private String pdf;

    /** 页范围，默认 "all" */
    private String pages = "all";

    /** 请求 ID，用于关联请求/响应 */
    @JsonProperty("request_id")
    private String requestId;

    /** 本次解析的图片临时目录 */
    @JsonProperty("images_dir")
    private String imagesDir;

    /** 仅用于（反）序列化占位，避免 Jackson 无参构造告警 */
    public MinerUParseRequest() {
    }

    public MinerUParseRequest(String pdf, String pages, String requestId, String imagesDir) {
        this.pdf = pdf;
        this.pages = pages;
        this.requestId = requestId;
        this.imagesDir = imagesDir;
    }
}
