/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.advisor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

public class ThinkingCollectorAdvisor implements StreamAdvisor, Ordered {
    private static final Logger log = LoggerFactory.getLogger(ThinkingCollectorAdvisor.class);

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        List<String> chunks = new ArrayList<>();
        return chain.nextStream(request).doOnNext(r -> {
            var cr = r.chatResponse();
            if (cr != null && cr.getResult() != null && cr.getResult().getOutput() != null) {
                var m = cr.getResult().getOutput().getMetadata();
                if (m != null) {
                    Object v = m.get("reasoning_content");
                    if (v == null) v = m.get("reasoning");
                    if (v != null) chunks.add(v.toString());
                }
            }
        }).doOnComplete(() -> {
            if (!chunks.isEmpty()) log.debug("[ThinkingCollector] {} chunks", chunks.size());
        });
    }

    @Override public String getName() { return "ThinkingCollectorAdvisor"; }
    @Override public int getOrder() { return 400; }
}
