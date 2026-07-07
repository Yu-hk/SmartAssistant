/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 评测基线快照 — 记录某次评测运行的关键指标，用于后续 CI 运行的回归比对。
 *
 * <p>JSON 序列化，提交于仓库（如 {@code src/test/resources/eval-baseline.json}），
 * 作为质量基准。仅在人工确认质量变化后通过 {@link GoldenSuiteEvalGate} 重新生成并提交，
 * 避免 PR 随意漂移基线、使门禁失效。</p>
 *
 * @author Yu-hk
 * @since 2026-07-07
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class EvalBaseline {

    private static final Logger log = LoggerFactory.getLogger(EvalBaseline.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /** 生成时间（ISO-8601） */
    public String generatedAt;

    /** 备注 */
    public String note = "SmartAssistant 评测基线快照";

    /** 指标快照（键见 {@link EvalGate} 的 {@code METRIC_DIRECTION}） */
    public Map<String, Double> metrics = new LinkedHashMap<>();

    public EvalBaseline() {
    }

    /**
     * 从 JSON 文件加载基线；文件不存在或损坏时返回 {@code null}（调用方应跳过基线比对）。
     */
    public static EvalBaseline load(Path path) {
        if (path == null || !Files.exists(path)) {
            return null;
        }
        try {
            return MAPPER.readValue(path.toFile(), EvalBaseline.class);
        } catch (IOException e) {
            log.warn("[EvalBaseline] 加载失败（将跳过基线比对）: {}", e.getMessage());
            return null;
        }
    }

    /** 写出基线到 JSON 文件（自动创建父目录）。 */
    public void save(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        MAPPER.writeValue(path.toFile(), this);
        log.info("[EvalBaseline] 已写出基线: {}", path);
    }
}
