package com.example.smartassistant.common.rag.advisor;

import com.example.smartassistant.common.agent.ContextCompressor;
import com.example.smartassistant.common.agent.ReActProfile;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * Spring AI Advisor facade for the existing rolling context compressor.
 * SmartReAct and ordinary ChatClient chains now use the same compression boundary.
 */
public class SummarizationAdvisor extends ContextCompressor
        implements CallAdvisor, StreamAdvisor, Ordered {

    private static final String APPLIED = SummarizationAdvisor.class.getName() + ".applied";

    public SummarizationAdvisor(ChatModel chatModel, ReActProfile profile, List<String> summaryChain) {
        super(chatModel, profile, summaryChain);
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        if (Boolean.TRUE.equals(request.context().get(APPLIED)) || request.prompt() == null) {
            return chain.nextCall(request);
        }
        Prompt compacted = new Prompt(compress(request.prompt().getInstructions()), request.prompt().getOptions());
        return chain.nextCall(request.mutate().prompt(compacted).context(APPLIED, true).build());
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        if (Boolean.TRUE.equals(request.context().get(APPLIED)) || request.prompt() == null) {
            return chain.nextStream(request);
        }
        return Flux.defer(() -> {
            Prompt compacted = new Prompt(compress(request.prompt().getInstructions()), request.prompt().getOptions());
            return chain.nextStream(request.mutate().prompt(compacted).context(APPLIED, true).build());
        });
    }

    @Override public String getName() { return "SummarizationAdvisor"; }
    @Override public int getOrder() { return 70; }
}
