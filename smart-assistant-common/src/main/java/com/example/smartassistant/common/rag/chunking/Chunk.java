/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.chunking;

/**
 * 分块结果——包含文本内容和块序号。
 */
public class Chunk {

    private final String text;
    private final int index;
    private final int tokenCount;
    private final String prefix;

    public Chunk(String text, int index, int tokenCount, String prefix) {
        this.text = text;
        this.index = index;
        this.tokenCount = tokenCount;
        this.prefix = prefix != null ? prefix : "";
    }

    public String getText() { return text; }
    public int getIndex() { return index; }
    public int getTokenCount() { return tokenCount; }
    public String getPrefix() { return prefix; }

    @Override
    public String toString() {
        return "Chunk{idx=" + index + ", tokens=" + tokenCount
                + ", prefix='" + prefix + "', textLen=" + text.length() + "}";
    }
}
