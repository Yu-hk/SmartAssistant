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
 * MinerU 响应中的单页（含块列表）。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MinerUPage {

    /** 页号（从 1 开始） */
    @JsonProperty("page_no")
    private int pageNo;

    /** 本页的块（text / table / image） */
    private List<MinerUBlock> blocks;

    public MinerUPage() {
    }

    public MinerUPage(int pageNo, List<MinerUBlock> blocks) {
        this.pageNo = pageNo;
        this.blocks = blocks;
    }
}
