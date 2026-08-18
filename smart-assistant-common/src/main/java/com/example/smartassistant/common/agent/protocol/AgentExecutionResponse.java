package com.example.smartassistant.common.agent.protocol;

import com.example.smartassistant.common.quality.DomainQualityResult;

import java.util.List;
import java.util.Map;

/** Typed Agent response. Failures are data, not emoji-prefixed success bodies. */
public record AgentExecutionResponse(
        String protocolVersion,
        Status status,
        String answer,
        Map<String, Object> data,
        AgentError error,
        Quality quality) {

    public AgentExecutionResponse {
        protocolVersion = protocolVersion == null || protocolVersion.isBlank()
                ? AgentExecutionRequest.CURRENT_VERSION : protocolVersion;
        status = status != null ? status : Status.FAILED;
        data = data != null ? Map.copyOf(data) : Map.of();
    }

    public static AgentExecutionResponse success(String answer, DomainQualityResult quality) {
        return success(answer, Map.of(), quality);
    }

    public static AgentExecutionResponse success(
            String answer, Map<String, Object> data, DomainQualityResult quality) {
        return new AgentExecutionResponse(
                AgentExecutionRequest.CURRENT_VERSION, Status.SUCCEEDED, answer,
                data, null, Quality.from(quality));
    }

    public static AgentExecutionResponse failure(String code, String message, boolean retryable) {
        return new AgentExecutionResponse(
                AgentExecutionRequest.CURRENT_VERSION,
                retryable ? Status.RETRYABLE_FAILED : Status.FAILED,
                null, Map.of(), new AgentError(code, message, retryable),
                Quality.from(DomainQualityResult.fail(code)));
    }

    public enum Status {
        SUCCEEDED,
        FAILED,
        RETRYABLE_FAILED,
        WAITING_APPROVAL
    }

    public record AgentError(String code, String message, boolean retryable) {
    }

    public record Quality(String status, double score, List<String> reasonCodes) {
        public Quality {
            reasonCodes = reasonCodes != null ? List.copyOf(reasonCodes) : List.of();
        }

        public static Quality from(DomainQualityResult result) {
            DomainQualityResult value = result != null ? result : DomainQualityResult.unknown();
            return new Quality(value.getStatus().name(), value.getScore(), value.getReasonCodes());
        }

        public DomainQualityResult toDomainQuality() {
            return new DomainQualityResult(
                    DomainQualityResult.Status.valueOf(status), score, reasonCodes);
        }
    }
}
