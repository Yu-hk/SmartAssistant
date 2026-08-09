package com.example.smartassistant.general.controller;

import com.example.smartassistant.common.agent.SmartReActAgent;
import com.example.smartassistant.common.audit.TokenUsageCache;
import com.example.smartassistant.common.audit.TokenUsageHeaders;
import com.example.smartassistant.common.audit.ToolUsageCache;
import com.example.smartassistant.common.audit.ToolUsageHeaders;
import com.example.smartassistant.common.intent.WeatherQuerySupport;
import com.example.smartassistant.common.location.DeviceLocation;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Internal synchronous endpoint used by the Router service. */
@RestController
@RequestMapping("/api/general/agent")
public class GeneralAgentController {

    private final SmartReActAgent generalChatAgent;

    public GeneralAgentController(@Qualifier("generalChatAgent") SmartReActAgent generalChatAgent) {
        this.generalChatAgent = generalChatAgent;
    }

    @PostMapping("/process")
    public ResponseEntity<String> process(
            @RequestBody Map<String, Object> request,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        ToolUsageCache.start(requestId);
        Object rawQuestion = request.get("question");
        String question = rawQuestion instanceof String value ? value : null;
        if (question == null || question.isBlank()) {
            return responseWithUsage("Question must not be blank", requestId);
        }
        DeviceLocation deviceLocation = DeviceLocation.from(request.get("deviceLocation"));
        if (WeatherQuerySupport.requiresCityClarification(question, deviceLocation)) {
            return responseWithUsage(WeatherQuerySupport.CITY_CLARIFICATION, requestId);
        }
        if (WeatherQuerySupport.isWeatherLookup(question)
                && WeatherQuerySupport.extractCity(question) == null
                && deviceLocation != null && deviceLocation.isUsable()) {
            question = WeatherQuerySupport.withDeviceLocation(question, deviceLocation);
        }
        String answer = generalChatAgent.execute(question);
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok();
        TokenUsageCache.TokenUsage usage = TokenUsageCache.consume(requestId);
        if (usage != null) {
            if (usage.promptTokens() != null) {
                builder.header(TokenUsageHeaders.PROMPT_TOKENS, String.valueOf(usage.promptTokens()));
            }
            if (usage.completionTokens() != null) {
                builder.header(TokenUsageHeaders.COMPLETION_TOKENS, String.valueOf(usage.completionTokens()));
            }
            if (usage.totalTokens() != null) {
                builder.header(TokenUsageHeaders.TOTAL_TOKENS, String.valueOf(usage.totalTokens()));
            }
        }
        addToolUsageHeader(builder, ToolUsageCache.consume(requestId));
        return builder.body(answer);
    }

    private ResponseEntity<String> responseWithUsage(String body, String requestId) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok();
        addToolUsageHeader(builder, ToolUsageCache.consume(requestId));
        return builder.body(body);
    }

    private void addToolUsageHeader(ResponseEntity.BodyBuilder builder,
                                    ToolUsageCache.ToolUsage usage) {
        String encoded = ToolUsageHeaders.encode(usage);
        if (encoded != null) builder.header(ToolUsageHeaders.TOOL_USAGE, encoded);
    }
}
