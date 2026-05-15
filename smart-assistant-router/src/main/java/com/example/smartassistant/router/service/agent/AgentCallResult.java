package com.example.smartassistant.router.service.agent;

import java.util.List;
import java.util.Map;

/**
 * Agent 调用结果，包含响应文本和工具输出中提取的真实游记标题。
 */
public class AgentCallResult {
    private final String response;
    private final List<String> realTitles;
    private final Map<String, String> tagsByTitle;

    public AgentCallResult(String response, List<String> realTitles) {
        this(response, realTitles, Map.of());
    }

    public AgentCallResult(String response, List<String> realTitles, Map<String, String> tagsByTitle) {
        this.response = response;
        this.realTitles = realTitles != null ? realTitles : List.of();
        this.tagsByTitle = tagsByTitle != null ? tagsByTitle : Map.of();
    }

    public AgentCallResult(String response) {
        this(response, List.of(), Map.of());
    }

    public String getResponse() { return response; }
    public List<String> getRealTitles() { return realTitles; }
    public Map<String, String> getTagsByTitle() { return tagsByTitle; }
}
