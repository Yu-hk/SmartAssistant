package com.example.smartassistant.common.rag.advisor;

import com.example.smartassistant.common.security.PiiPolicyEngine;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

import java.util.List;

/** Sanitizes model input and both blocking/streaming output with the shared PII policy. */
public class PiiAdvisor implements CallAdvisor, StreamAdvisor, Ordered {

    private final PiiPolicyEngine engine;

    public PiiAdvisor(PiiPolicyEngine engine) {
        this.engine = engine;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        return sanitize(chain.nextCall(sanitize(request)));
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return chain.nextStream(sanitize(request)).map(this::sanitize);
    }

    private ChatClientRequest sanitize(ChatClientRequest request) {
        if (request.prompt() == null) return request;
        List<Message> messages = request.prompt().getInstructions().stream().map(this::sanitize).toList();
        Prompt prompt = Prompt.builder().messages(messages).chatOptions(request.prompt().getOptions()).build();
        return request.mutate().prompt(prompt).build();
    }

    private Message sanitize(Message message) {
        if (message instanceof UserMessage user) {
            return UserMessage.builder().text(engine.sanitize(user.getText()))
                    .media(user.getMedia()).metadata(user.getMetadata()).build();
        }
        if (message instanceof SystemMessage system) {
            return SystemMessage.builder().text(engine.sanitize(system.getText()))
                    .metadata(system.getMetadata()).build();
        }
        if (message instanceof AssistantMessage assistant) {
            return assistant.mutate().content(engine.sanitize(assistant.getText())).build();
        }
        if (message instanceof ToolResponseMessage tool) {
            List<ToolResponseMessage.ToolResponse> responses = tool.getResponses().stream()
                    .map(r -> new ToolResponseMessage.ToolResponse(r.id(), r.name(), engine.sanitize(r.responseData())))
                    .toList();
            return ToolResponseMessage.builder().responses(responses).metadata(tool.getMetadata()).build();
        }
        return message;
    }

    private ChatClientResponse sanitize(ChatClientResponse response) {
        if (response == null || response.chatResponse() == null) return response;
        ChatResponse source = response.chatResponse();
        List<Generation> generations = source.getResults().stream().map(generation -> {
            AssistantMessage output = generation.getOutput();
            AssistantMessage sanitized = output == null ? null
                    : output.mutate().content(engine.sanitize(output.getText())).build();
            return new Generation(sanitized, generation.getMetadata());
        }).toList();
        ChatResponse chatResponse = ChatResponse.builder().from(source).generations(generations).build();
        return response.mutate().chatResponse(chatResponse).build();
    }

    @Override public String getName() { return "PiiAdvisor"; }
    @Override public int getOrder() { return 60; }
}
