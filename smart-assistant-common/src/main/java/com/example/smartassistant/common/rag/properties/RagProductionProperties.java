/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

/**
 * RAG 生产化改造集中配置（承载 {@code app.rag.*} 与 {@code app.compliance.*}）。
 * <p>
 * 三态存储模式 {@code app.rag.store.mode = pg | memory | auto}（默认 {@code auto}）：
 * <ul>
 *   <li>{@code pg}：强制使用 PgVector 持久化；</li>
 *   <li>{@code memory}：强制使用内存模式（测试/降级）；</li>
 *   <li>{@code auto}：启动尝试 PG，失败整体降级内存；运行时按请求降级。</li>
 * </ul>
 * 合规默认策略 {@code app.compliance.default-strategy = warn | rewrite | block}（默认 {@code rewrite}）。
 * </p>
 *
 * <p>所有字段均有安全默认值，缺失配置时仍可按 auto + rewrite 行为启动。</p>
 */
@ConfigurationProperties(prefix = "app")
@Data
public class RagProductionProperties {

    /** RAG 相关配置（app.rag.*） */
    private final Rag rag = new Rag();

    /** 合规校验相关配置（app.compliance.*） */
    private final Compliance compliance = new Compliance();

    // ==================== app.rag.* ====================

    @Data
    public static class Rag {

        /** 存储模式：pg | memory | auto */
        private Store store = new Store();

        /** 运行时降级开关 */
        private Degrade degrade = new Degrade();

        /** 摄入相关 */
        private Ingest ingest = new Ingest();

        /** 内存模式水印/刷新（REQ-4 多实例一致性） */
        private Memory memory = new Memory();

        /** 种子迁移 */
        private Seed seed = new Seed();

        /** PostgreSQL / pgvector 相关 */
        private Pg pg = new Pg();
    }

    @Data
    public static class Store {
        /** pg | memory | auto，默认 auto（pg 优先，失败降级 memory） */
        private String mode = "auto";
    }

    @Data
    public static class Degrade {
        /** 运行时按请求降级到内存（PG 不可用时） */
        private boolean autoEnabled = true;

        /** 连续失败次数触发整体降级 */
        private int healthFailThreshold = 3;
    }

    @Data
    public static class Ingest {
        /** 关键：关闭每次全量 reindex（增量 upsert） */
        private boolean reindexOnIngest = false;

        /** 触发方式：manual | webhook | schedule | all */
        private String trigger = "manual,webhook";

        /** 定时扫描目录（兜底） */
        private String scanDir = "";
    }

    @Data
    public static class Memory {
        /** 内存模式水印刷新间隔（毫秒，REQ-4） */
        private long refreshIntervalMs = 5000;
    }

    @Data
    public static class Seed {
        /** 首启动把 KnowledgeSeedData 灌入 PG */
        private boolean migrateToPgOnStartup = true;
    }

    @Data
    public static class Pg {
        /** 开发态可由应用启动建表（生产走 Flyway，默认 false） */
        private boolean initSchemaOnStartup = false;

        /** 知识库名称（用于 KnowledgeRetrievalService 注册） */
        private String knowledgeBaseName = "product_knowledge";
    }

    // ==================== app.compliance.* ====================

    @Data
    public static class Compliance {
        /** 是否启用生成后合规校验 */
        private boolean enabled = true;

        /** 默认分级策略：warn | rewrite | block */
        private String defaultStrategy = "rewrite";

        /** 是否启用可选 LLM 判分（默认关闭，纯规则） */
        private boolean llmEnabled = false;

        /** 误杀申诉/人工复核是否启用 */
        private boolean appealEnabled = true;
    }
}
