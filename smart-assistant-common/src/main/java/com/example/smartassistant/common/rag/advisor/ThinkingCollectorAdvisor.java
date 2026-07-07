/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.advisor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * 思维链收集 Advisor — 从 ChatResponse metadata 中提取推理过程内容。
 * <p>
 * 仅在 Stream 路径生效，收集 {@code reasoning_content} 或 {@code reasoning} 字段。
 * 日志格式包含 {@code [requestId=xxx]} 以区分多请求并发时的调用链路。
 * </p>
 */
public class ThinkingCollectorAdvisor implements StreamAdvisor, Ordered {
    private static final Logger log = LoggerFactory.getLogger(ThinkingCollectorAdvisor.class);

    /** 从 MDC 获取请求追踪 ID */
    private static String traceId() {
        String rid = MDC.get("requestId");
        if (rid != null && !rid.isBlank()) return rid;
        String trace = MDC.get("traceId");
        return trace != null && !trace.isBlank() ? trace : "-";
    }

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
            if (!chunks.isEmpty()) {
                log.debug("[ThinkingCollector][requestId={}] 收集 {} 个推理块, 总共 {} 字符",
                        traceId(), chunks.size(), chunks.stream().mapToInt(String::length).sum());
            } else {
                log.trace("[ThinkingCollector][requestId={}] 无推理内容", traceId());
            }
        });
    }

    @Override public String getName() { return "ThinkingCollectorAdvisor"; }
    @Override public int getOrder() { return 400; }
}
