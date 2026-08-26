package com.example.smartassistant.router.service.agent;

import com.example.smartassistant.common.quality.DomainQualityResult;

import java.util.List;
import java.util.Map;

/**
 * Agent 调用结果，包含响应文本和工具输出中提取的真实游记标题。
 */
public class AgentCallResult {
    /** Internal marker: the Agent call failed at transport level and must not be semantically retried. */
    public static final String TRANSPORT_FAILURE_KEY = "_agentTransportFailure";
    /** Internal marker: the Agent returned a typed retryable protocol failure. */
    public static final String PROTOCOL_RETRYABLE_FAILURE_KEY = "_agentProtocolRetryableFailure";

    private final String response;
    private final List<String> realTitles;
    private final Map<String, String> tagsByTitle;
    private final DomainQualityResult domainQuality;
    private final Map<String, Object> data;

    public AgentCallResult(String response, List<String> realTitles) {
        this(response, realTitles, Map.of());
    }

    public AgentCallResult(String response, List<String> realTitles, Map<String, String> tagsByTitle) {
        this(response, realTitles, tagsByTitle, DomainQualityResult.unknown());
    }

    public AgentCallResult(String response, List<String> realTitles, Map<String, String> tagsByTitle,
                           DomainQualityResult domainQuality) {
        this(response, realTitles, tagsByTitle, domainQuality, Map.of());
    }

    public AgentCallResult(String response, List<String> realTitles, Map<String, String> tagsByTitle,
                           DomainQualityResult domainQuality, Map<String, Object> data) {
        this.response = response;
        this.realTitles = realTitles != null ? realTitles : List.of();
        this.tagsByTitle = tagsByTitle != null ? tagsByTitle : Map.of();
        this.domainQuality = domainQuality != null ? domainQuality : DomainQualityResult.unknown();
        this.data = data != null ? Map.copyOf(data) : Map.of();
    }

    public AgentCallResult(String response) {
        this(response, List.of(), Map.of());
    }

    public String getResponse() { return response; }
    public List<String> getRealTitles() { return realTitles; }
    public Map<String, String> getTagsByTitle() { return tagsByTitle; }
    public DomainQualityResult getDomainQuality() { return domainQuality; }
    public Map<String, Object> getData() { return data; }
}
