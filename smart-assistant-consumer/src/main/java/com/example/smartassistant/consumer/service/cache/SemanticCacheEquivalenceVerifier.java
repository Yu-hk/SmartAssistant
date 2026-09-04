/*
 * Copyright (c) 2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.service.cache;

import com.example.smartassistant.common.rag.advisor.AiChatService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Fail-closed semantic equivalence gate for pre-Route cache candidates. */
@Service
public class SemanticCacheEquivalenceVerifier {

    private static final Logger log = LoggerFactory.getLogger(SemanticCacheEquivalenceVerifier.class);
    private final AiChatService aiChatService;
    private final ChatModel lightModel;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @Value("${consumer.semantic-answer-cache.verifier-timeout-ms:6000}")
    private long timeoutMs;

    public SemanticCacheEquivalenceVerifier(
            AiChatService aiChatService,
            @Qualifier("lightChatModel") ChatModel lightModel) {
        this.aiChatService = aiChatService;
        this.lightModel = lightModel;
    }

    public Verification verify(String scope, String certifiedQuestion, String incomingQuestion) {
        if (certifiedQuestion == null || certifiedQuestion.isBlank()
                || incomingQuestion == null || incomingQuestion.isBlank()) {
            return Verification.rejected("blank_question");
        }
        Future<Verification> future = executor.submit(() -> aiChatService.buildChatClient(lightModel)
                .prompt()
                .system("""
                        你是语义缓存的严格等价性校验器，不负责回答用户问题。
                        判断新问题能否完整复用已认证问题的答案。只要新问题增加了任务、改变了商品类别/品牌/价格等约束、
                        引入订单/物流/售后等用户数据，或两个问题不是双向等价，就必须拒绝。
                        不允许因为主题相近而放行。输出必须符合指定结构。""")
                .user("""
                        缓存范围：%s
                        已认证问题：%s
                        新问题：%s

                        分别判断：语义是否双向等价、新问题是否单一意图、是否新增意图、约束是否一致。
                        """.formatted(scope, certifiedQuestion, incomingQuestion))
                .call()
                .entity(Verification.class));
        try {
            Verification result = future.get(Math.max(100L, timeoutMs), TimeUnit.MILLISECONDS);
            return result != null ? result : Verification.rejected("empty_model_result");
        } catch (TimeoutException error) {
            future.cancel(true);
            log.info("[ConsumerSemanticCache] Equivalence verifier timed out; bypass cache");
            return Verification.rejected("timeout");
        } catch (InterruptedException error) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return Verification.rejected("interrupted");
        } catch (ExecutionException | RuntimeException error) {
            log.info("[ConsumerSemanticCache] Equivalence verifier failed; bypass cache: {}",
                    error.getMessage());
            return Verification.rejected("model_failure");
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    public record Verification(
            boolean semanticallyEquivalent,
            boolean singleIntent,
            boolean additionalIntent,
            boolean constraintsConsistent,
            double confidence,
            String reason) {

        public static Verification rejected(String reason) {
            return new Verification(false, false, true, false, 0d, reason);
        }

        public boolean accepted(double threshold) {
            return semanticallyEquivalent && singleIntent && !additionalIntent
                    && constraintsConsistent && confidence >= threshold;
        }
    }
}
