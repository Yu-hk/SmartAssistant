package com.example.smartassistant.common.rag.advisor;

import com.example.smartassistant.common.governance.CallLimitProperties;
import com.example.smartassistant.common.governance.InvocationBudgetRegistry;
import com.example.smartassistant.common.governance.InvocationIdentity;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

/** Applies one model-call budget policy to every managed ChatClient. */
public class ModelCallLimitAdvisor implements CallAdvisor, StreamAdvisor, Ordered {

    static final String APPLIED_CONTEXT_KEY = ModelCallLimitAdvisor.class.getName() + ".applied";

    private final InvocationBudgetRegistry registry;
    private final int requestLimit;
    private final int sessionLimit;

    public ModelCallLimitAdvisor(InvocationBudgetRegistry registry, CallLimitProperties properties) {
        this(registry, properties.getModel().getMaxPerRequest(), properties.getModel().getMaxPerSession());
    }

    public ModelCallLimitAdvisor(InvocationBudgetRegistry registry, int requestLimit, int sessionLimit) {
        this.registry = registry;
        this.requestLimit = requestLimit;
        this.sessionLimit = sessionLimit;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        if (Boolean.TRUE.equals(request.context().get(APPLIED_CONTEXT_KEY))) return chain.nextCall(request);
        acquire(request);
        return chain.nextCall(request.mutate().context(APPLIED_CONTEXT_KEY, true).build());
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return Flux.defer(() -> {
            if (Boolean.TRUE.equals(request.context().get(APPLIED_CONTEXT_KEY))) return chain.nextStream(request);
            acquire(request);
            return chain.nextStream(request.mutate().context(APPLIED_CONTEXT_KEY, true).build());
        });
    }

    private void acquire(ChatClientRequest request) {
        registry.acquireModel(InvocationIdentity.resolve(request.context()), requestLimit, sessionLimit);
    }

    @Override public String getName() { return "ModelCallLimitAdvisor"; }
    @Override public int getOrder() { return 80; }
}
