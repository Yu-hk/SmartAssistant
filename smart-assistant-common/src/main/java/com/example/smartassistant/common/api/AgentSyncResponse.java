/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.api;

import java.util.List;
import java.util.Map;

/**
 * 同步 Agent 聊天响应体。
 * <p>作为 {@link AgentApiResponse#data} 的类型参数，包含 Agent 对话的完整回复。</p>
 *
 * <pre>
 * {
 *   "success": true,
 *   "message": "操作成功",
 *   "data": {
 *     "reply": "成都的美食有火锅、串串...",
 *     "suggestions": ["查天气", "找景点"],
 *     "intentTag": "food_query",
 *     "toolInvoked": true,
 *     "titles": ["标题1"],
 *     "tagsByTitle": {"标题1": "美食"}
 *   },
 *   "requestId": "abc123",
 *   ...
 * }
 * </pre>
 */
public class AgentSyncResponse {

    /** Agent 的文字回复（markdown 格式） */
    private String reply;

    /** 推荐的后续问题列表 */
    private List<String> suggestions;

    /** 意图标签（用于画像统计） */
    private String intentTag;

    /** 是否触发了工具调用 */
    private boolean toolInvoked;

    /** 真实标题列表（游记/订单场景） */
    private List<String> titles;

    /** 标题对应的标签映射 */
    private Map<String, String> tagsByTitle;

    public AgentSyncResponse() {}

    // ---- Builder ----

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String reply;
        private List<String> suggestions;
        private String intentTag;
        private boolean toolInvoked;
        private List<String> titles;
        private Map<String, String> tagsByTitle;

        Builder() {}

        public Builder reply(String reply) { this.reply = reply; return this; }
        public Builder suggestions(List<String> suggestions) { this.suggestions = suggestions; return this; }
        public Builder intentTag(String intentTag) { this.intentTag = intentTag; return this; }
        public Builder toolInvoked(boolean toolInvoked) { this.toolInvoked = toolInvoked; return this; }
        public Builder titles(List<String> titles) { this.titles = titles; return this; }
        public Builder tagsByTitle(Map<String, String> tagsByTitle) { this.tagsByTitle = tagsByTitle; return this; }

        public AgentSyncResponse build() {
            AgentSyncResponse r = new AgentSyncResponse();
            r.reply = this.reply;
            r.suggestions = this.suggestions;
            r.intentTag = this.intentTag;
            r.toolInvoked = this.toolInvoked;
            r.titles = this.titles;
            r.tagsByTitle = this.tagsByTitle;
            return r;
        }
    }

    // ---- Getters / Setters ----

    public String getReply() { return reply; }
    public void setReply(String reply) { this.reply = reply; }

    public List<String> getSuggestions() { return suggestions; }
    public void setSuggestions(List<String> suggestions) { this.suggestions = suggestions; }

    public String getIntentTag() { return intentTag; }
    public void setIntentTag(String intentTag) { this.intentTag = intentTag; }

    public boolean isToolInvoked() { return toolInvoked; }
    public void setToolInvoked(boolean toolInvoked) { this.toolInvoked = toolInvoked; }

    public List<String> getTitles() { return titles; }
    public void setTitles(List<String> titles) { this.titles = titles; }

    public Map<String, String> getTagsByTitle() { return tagsByTitle; }
    public void setTagsByTitle(Map<String, String> tagsByTitle) { this.tagsByTitle = tagsByTitle; }
}
