package com.example.smartassistant.general.controller;

import com.example.smartassistant.common.agent.SmartReActAgent;
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
    public String process(@RequestBody Map<String, String> request) {
        String question = request.get("question");
        if (question == null || question.isBlank()) {
            return "Question must not be blank";
        }
        return generalChatAgent.execute(question);
    }
}
