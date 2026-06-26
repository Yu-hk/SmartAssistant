/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分页工具结果格式化——为批量查询工具提供统一的 offset/limit 续读入口。
 * <p>
 * 参照 Claude Code 的工具设计：大结果截断时附上总量和续读参数，
 * LLM 发现数据不够时可以自主发起后续分页查询。
 * </p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * List<Item> items = queryItems(limit, offset);
 * boolean hasMore = items.size() >= limit;
 * return ToolPageResult.builder()
 *     .title("订单列表（第" + (offset+1) + "~" + (offset+items.size()) + "条）")
 *     .items(items)
 *     .totalHint("共 50+ 条")
 *     .hasMore(hasMore)
 *     .nextOffset(offset + limit)
 *     .pageSize(limit)
 *     .build()
 *     .format("您的订单列表：\n\n{items}\n\n{footer}");
 * }</pre>
 */
public class ToolPageResult {

    private final String title;
    private final List<?> items;
    private final String totalHint;
    private final boolean hasMore;
    private final int nextOffset;
    private final int pageSize;

    private ToolPageResult(String title, List<?> items, String totalHint,
                           boolean hasMore, int nextOffset, int pageSize) {
        this.title = title;
        this.items = items;
        this.totalHint = totalHint;
        this.hasMore = hasMore;
        this.nextOffset = nextOffset;
        this.pageSize = pageSize;
    }

    /**
     * 格式化为 LLM 可读文本（含续读入口）。
     */
    public String format(String itemFormat) {
        StringBuilder sb = new StringBuilder();
        if (title != null) sb.append(title).append("\n\n");
        sb.append(itemFormat);
        if (totalHint != null) sb.append("\n").append(totalHint);
        return sb.toString();
    }

    /**
     * 格式化为带续读入口的结果。
     */
    public String formatWithContinuation(String itemFormat, String queryName) {
        StringBuilder sb = new StringBuilder();
        if (title != null) sb.append(title).append("\n\n");
        sb.append(itemFormat);
        if (totalHint != null) sb.append("\n").append(totalHint);

        // ★ 续读入口：当有更多数据时，告诉 LLM 如何继续查询
        if (hasMore && queryName != null) {
            sb.append("\n\n📌 还有更多数据未显示。如需继续查看，请调用 ")
              .append(queryName)
              .append("(..., offset=").append(nextOffset)
              .append(", limit=").append(pageSize)
              .append(") 获取下一页。");
        }
        return sb.toString();
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String title;
        private List<?> items;
        private String totalHint;
        private boolean hasMore;
        private int nextOffset;
        private int pageSize = 10;

        public Builder title(String v) { this.title = v; return this; }
        public Builder items(List<?> v) { this.items = v; return this; }
        public Builder totalHint(String v) { this.totalHint = v; return this; }
        public Builder hasMore(boolean v) { this.hasMore = v; return this; }
        public Builder nextOffset(int v) { this.nextOffset = v; return this; }
        public Builder pageSize(int v) { this.pageSize = v; return this; }
        public ToolPageResult build() { return new ToolPageResult(title, items, totalHint, hasMore, nextOffset, pageSize); }
    }
}
