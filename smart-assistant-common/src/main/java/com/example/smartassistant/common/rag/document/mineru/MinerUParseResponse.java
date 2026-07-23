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
 * MinerU 解析响应（sidecar CLI → 客户端，JSON over stdio）。
 * <p>
 * 字段严格对齐设计响应 schema：
 * <pre>{@code {"status":"ok","request_id":"u","pages":[{"page_no":1,"blocks":[...]}]}}</pre>
 * </p>
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MinerUParseResponse {

    /** 解析状态：ok / error */
    private String status;

    /** 请求 ID（与请求对应，用于关联） */
    @JsonProperty("request_id")
    private String requestId;

    /** 解析出的页面列表（可为空，调用方据此判断失败） */
    private List<MinerUPage> pages;

    /** 错误时的附加信息 */
    private String message;

    /** 是否成功 */
    public boolean isOk() {
        return "ok".equalsIgnoreCase(status);
    }
}
