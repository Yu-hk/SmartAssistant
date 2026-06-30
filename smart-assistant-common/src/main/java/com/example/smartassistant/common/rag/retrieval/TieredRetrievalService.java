/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.retrieval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Function;

/**
 * 分级检索服务——根据查询分类动态选择检索强度。
 * <p>
 * 轻量链路：耗时短、资源少、适合高频简单问题。
 * 深度链路：召回全、精度高、适合高价值复杂问题。
 * </p>
 *
 * <p>使用方式：</p>
 * <pre>{@code
 * TieredRetrievalService<RetrievalResult> service = new TieredRetrievalService<>(
 *     query -> ProductRagService.retrieveWithQuality(query, RetrievalProfile.STANDARD),
 *     QueryClassifier::classify
 * );
 * RetrievalResult result = service.retrieve("如何申请退款");
 * // result.profile() == RetrievalProfile.DEEP
 * }</pre>
 *
 * @param <T> 检索结果类型
 */
public class TieredRetrievalService<T> {

    private static final Logger log = LoggerFactory.getLogger(TieredRetrievalService.class);

    /** 检索执行器：接收 (query, profile) 返回结果 */
    private final Function<String, T> retrievalExecutor;

    /** 查询分类器 */
    private final Function<String, RetrievalProfile> classifier;

    /** 默认 profile（当分类器返回 null 或异常时使用） */
    private final RetrievalProfile defaultProfile;

    public TieredRetrievalService(Function<String, T> retrievalExecutor,
                                   Function<String, RetrievalProfile> classifier) {
        this(retrievalExecutor, classifier, RetrievalProfile.STANDARD);
    }

    public TieredRetrievalService(Function<String, T> retrievalExecutor,
                                   Function<String, RetrievalProfile> classifier,
                                   RetrievalProfile defaultProfile) {
        this.retrievalExecutor = retrievalExecutor;
        this.classifier = classifier;
        this.defaultProfile = defaultProfile;
    }

    /**
     * 执行分级检索。
     *
     * @param query 用户查询
     * @return 包装了 profile 信息的检索结果
     */
    public TieredResult<T> retrieve(String query) {
        long start = System.currentTimeMillis();

        RetrievalProfile profile;
        try {
            profile = classifier.apply(query);
            if (profile == null) profile = defaultProfile;
        } catch (Exception e) {
            log.warn("[TieredRetrieval] 查询分类失败，使用默认: {}", defaultProfile, e);
            profile = defaultProfile;
        }

        // 执行检索
        T result = retrievalExecutor.apply(query);

        long elapsed = System.currentTimeMillis() - start;
        log.info("[TieredRetrieval] 分级检索: query={}, profile={}, 耗时={}ms",
                truncate(query, 40), profile, elapsed);

        return new TieredResult<>(result, profile, elapsed);
    }

    private static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) + "..." : s;
    }

    /**
     * 分级检索结果。
     *
     * @param result   检索结果
     * @param profile  使用的检索强度
     * @param elapsedMs 耗时
     */
    public record TieredResult<T>(
            T result,
            RetrievalProfile profile,
            long elapsedMs
    ) {}
}
