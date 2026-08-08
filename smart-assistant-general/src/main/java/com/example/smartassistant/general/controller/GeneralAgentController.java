package com.example.smartassistant.general.controller;

import com.example.smartassistant.common.agent.SmartReActAgent;
import com.example.smartassistant.common.intent.WeatherQuerySupport;
import com.example.smartassistant.common.location.DeviceLocation;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    public String process(@RequestBody Map<String, Object> request) {
        Object rawQuestion = request.get("question");
        String question = rawQuestion instanceof String value ? value : null;
        if (question == null || question.isBlank()) {
            return "Question must not be blank";
        }
        DeviceLocation deviceLocation = DeviceLocation.from(request.get("deviceLocation"));
        if (WeatherQuerySupport.requiresCityClarification(question, deviceLocation)) {
            return WeatherQuerySupport.CITY_CLARIFICATION;
        }
        if (WeatherQuerySupport.isWeatherLookup(question)
                && WeatherQuerySupport.extractCity(question) == null
                && deviceLocation != null && deviceLocation.isUsable()) {
            question = WeatherQuerySupport.withDeviceLocation(question, deviceLocation);
        }
        return generalChatAgent.execute(question);
    }
}
