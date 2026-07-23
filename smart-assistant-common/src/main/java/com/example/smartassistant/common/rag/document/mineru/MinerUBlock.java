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

/**
 * MinerU 响应中的块。
 * <p>
 * 字段对齐设计 schema：
 * <pre>{@code
 * {"type":"text","text":"..."}
 * {"type":"table","text":"|a|b|","table_caption":"表1"}
 * {"type":"image","image_path":"i/x.jpg","image_caption":"图述","text":"ocr字"}
 * }</pre>
 * </p>
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MinerUBlock {

    /** 块类型：text / table / image */
    private String type;

    /** 文本内容（正文/表格 Markdown/图片 OCR 字） */
    private String text;

    /** 表格标题（仅 table 块） */
    @JsonProperty("table_caption")
    private String tableCaption;

    /** 图片路径（仅 image 块，存临时目录路径，不存字节） */
    @JsonProperty("image_path")
    private String imagePath;

    /** 图片语义说明（仅 image 块） */
    @JsonProperty("image_caption")
    private String imageCaption;
}
