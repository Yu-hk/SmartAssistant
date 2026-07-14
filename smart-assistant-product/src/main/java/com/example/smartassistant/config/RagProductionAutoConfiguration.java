/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.config;

import com.example.smartassistant.common.embedding.BgeEmbeddingModel;
import com.example.smartassistant.common.rag.InMemoryKnowledgeBase;
import com.example.smartassistant.common.rag.KnowledgeBase;
import com.example.smartassistant.common.rag.KnowledgeDocument;
import com.example.smartassistant.common.rag.KnowledgeSeedData;
import com.example.smartassistant.common.rag.PgVectorKnowledgeBase;
import com.example.smartassistant.common.rag.Reranker;
import com.example.smartassistant.common.rag.SafeReranker;
import com.example.smartassistant.common.rag.properties.RagProductionProperties;
import com.example.smartassistant.common.rag.store.KnowledgeIndexMetaService;
import com.example.smartassistant.common.rag.store.MemoryRefreshCoordinator;
import com.example.smartassistant.common.rag.store.ResilientKnowledgeBase;
import com.example.smartassistant.common.tokenizer.ChineseTokenizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

/**
 * RAG 生产化集成装配（T05）——把生产化能力落到 Product 模块。
 *
 * <p>产出 {@code productKnowledgeBase} Bean（类型 {@link KnowledgeBase}）：</p>
 * <ul>
 *   <li><b>内存快照</b>：始终构建并灌入 {@link KnowledgeSeedData} 种子，作为 {@code memory} 模式直接返回 /
 *       {@code pg/auto} 模式的降级目标；</li>
 *   <li><b>PG 主库</b>：{@code mode != memory} 且 JdbcTemplate 可达时，构建 {@link PgVectorKnowledgeBase} 主库，
 *       并把种子同步进 PG（{@code app.rag.seed.migrate-to-pg-on-startup}，默认 true）；</li>
 *   <li><b>弹性装饰</b>：PG 主库 + 内存快照包裹进 {@link ResilientKnowledgeBase}（请求级故障转移）；</li>
 *   <li><b>快照刷新</b>：{@link MemoryRefreshCoordinator} 周期从 PG 拉取全量刷新内存快照，
 *       保障 PG 偶发不可用时降级仍可读（REQ-4 多实例读共享一致性）。</li>
 * </ul>
 *
 * <p><b>默认模式 {@code auto}</b>（{@link RagProductionProperties}）即「优先 PG，失败整体降级内存」；
 * 无 JdbcTemplate / PG 不可达时静默降级为纯内存，行为与改造前完全一致（GoldenSuite 零回归）。</p>
 *
 * <p>数据源读取根级 {@code datasource.*}（非 {@code spring.datasource}，避免触发 Flyway 自动迁移在
 * 无 PG 时打断上下文）；仅以匿名 {@link DataSource} 构建 {@link JdbcTemplate}（不注册 DataSource Bean，
 * 故 FlywayAutoConfiguration 不会自动迁移，建表由 {@link PgVectorKnowledgeBase#initSchema()} 完成）。</p>
 */
@Configuration
@EnableConfigurationProperties(RagProductionProperties.class)
public class RagProductionAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RagProductionAutoConfiguration.class);

    @Value("${datasource.url:}")
    private String datasourceUrl;

    @Value("${datasource.username:postgres}")
    private String datasourceUsername;

    @Value("${datasource.password:}")
    private String datasourcePassword;

    @Value("${datasource.driver-class-name:org.postgresql.Driver}")
    private String datasourceDriver;

    /** Reranker 默认关闭（实验性功能，与 ProductKnowledgeConfig 保持一致） */
    @Value("${app.rag.reranker.enabled:false}")
    private boolean rerankerEnabled;

    /**
     * ⭐ 生产化知识库 Bean（替换原纯内存 {@code productKnowledgeBase}）。
     * <p>类型提升为 {@link KnowledgeBase}，下游 {@code productKnowledgeRetrievalService} 通过类型注入，
     * 检索 API（{@code KnowledgeRetrievalService}）保持不变。</p>
     */
    @Bean
    @ConditionalOnMissingBean
    public KnowledgeBase productKnowledgeBase(
            BgeEmbeddingModel embeddingModel,
            ChineseTokenizer tokenizer,
            ObjectProvider<JdbcTemplate> jdbcTemplateProvider,
            ObjectProvider<KnowledgeIndexMetaService> indexMetaProvider,
            ObjectProvider<Reranker> rerankerProvider,
            RagProductionProperties props) {

        String mode = props.getRag().getStore().getMode();
        String kbName = props.getRag().getPg().getKnowledgeBaseName();
        Reranker reranker = buildReranker(rerankerProvider);

        // 内存快照（始终构建：memory 模式直接返回 / pg 模式作为降级目标）
        InMemoryKnowledgeBase memoryFallback = new InMemoryKnowledgeBase(
                kbName, embeddingModel, tokenizer, reranker);
        memoryFallback.addDocuments(KnowledgeSeedData.productDocuments());
        memoryFallback.addDocuments(KnowledgeSeedData.orderDocuments());
        memoryFallback.reindex();

        JdbcTemplate jdbc = jdbcTemplateProvider.getIfAvailable();

        // auto/pg 模式：先快速预检 PG 可达性（bounded），不可达则降级内存
        boolean pgReachable = false;
        if (!"memory".equalsIgnoreCase(mode) && jdbc != null) {
            try {
                jdbc.execute("SELECT 1");
                pgReachable = true;
            } catch (Exception e) {
                log.warn("[RagProd] PG 不可达（auto 降级内存）: {}", e.getMessage());
            }
        }
        if ("memory".equalsIgnoreCase(mode) || !pgReachable) {
            log.info("[RagProd] 知识库模式={} → 纯内存（{} 篇种子）", mode, memoryFallback.size());
            return memoryFallback;
        }

        // pg / auto（可达）：构建 PG 主库 + 弹性装饰 + 刷新协调器
        PgVectorKnowledgeBase pgPrimary = null;
        try {
            KnowledgeIndexMetaService indexMeta = indexMetaProvider.getIfAvailable();
            pgPrimary = new PgVectorKnowledgeBase(kbName, embeddingModel, jdbc, tokenizer, indexMeta);
            if (props.getRag().getSeed().isMigrateToPgOnStartup()) {
                seedToPg(pgPrimary);
            }
        } catch (Exception e) {
            log.warn("[RagProd] PG 主库构建失败，降级内存: {}", e.getMessage());
            pgPrimary = null;
        }
        if (pgPrimary == null) {
            return memoryFallback;
        }

        ResilientKnowledgeBase resilient = new ResilientKnowledgeBase(
                kbName, pgPrimary, memoryFallback,
                props.getRag().getDegrade().isAutoEnabled(),
                props.getRag().getDegrade().getHealthFailThreshold());

        // 启动内存快照刷新协调器（从 PG 周期拉取，保障降级可读）
        MemoryRefreshCoordinator coordinator = new MemoryRefreshCoordinator(
                memoryFallback, pgPrimary, props.getRag().getMemory().getRefreshIntervalMs());
        coordinator.start();

        log.info("[RagProd] 知识库模式={} → PG 主库 + 内存降级（已启动刷新协调器，间隔 {}ms）",
                mode, props.getRag().getMemory().getRefreshIntervalMs());
        return resilient;
    }

    /**
     * ⭐ 显式 JdbcTemplate Bean（从根级 {@code datasource.*} 读取）。
     * <p>未配置 {@code datasource.url} 时返回 null（不注册 Bean），整体走内存模式。
     * 使用匿名 {@link DriverManagerDataSource}（非 Bean），避免注册 DataSource Bean 触发 Flyway 自动迁移。</p>
     */
    @Bean
    @ConditionalOnMissingBean
    public JdbcTemplate ragProductionJdbcTemplate() {
        if (datasourceUrl == null || datasourceUrl.isBlank()) {
            log.info("[RagProd] 未配置 datasource.url，不创建 JdbcTemplate（内存模式）");
            return null;
        }
        // 追加有界超时，避免 PG 不可达时连接挂起
        String url = datasourceUrl.contains("?")
                ? datasourceUrl : datasourceUrl + "?connectTimeout=3&socketTimeout=10&loginTimeout=3";
        try {
            DriverManagerDataSource ds = new DriverManagerDataSource(url, datasourceUsername, datasourcePassword);
            ds.setDriverClassName(datasourceDriver);
            // 连通性预检（bounded）
            try (Connection ignored = ds.getConnection()) {
                // ok
            }
            log.info("[RagProd] JdbcTemplate 就绪（数据源={}）", datasourceUrl);
            return new JdbcTemplate(ds);
        } catch (Exception e) {
            log.warn("[RagProd] 数据源不可达，JdbcTemplate 置空（内存模式）: {}", e.getMessage());
            return null;
        }
    }

    /** 种子同步进 PG（增量 upsert，幂等） */
    private void seedToPg(PgVectorKnowledgeBase pg) {
        List<KnowledgeDocument> all = new ArrayList<>();
        all.addAll(KnowledgeSeedData.productDocuments());
        all.addAll(KnowledgeSeedData.orderDocuments());
        pg.addDocuments(all);
        log.info("[RagProd] 种子数据已同步 PG：{} 篇", all.size());
    }

    /** Reranker：默认关闭；开启且有可用实例时以 SafeReranker 包装 */
    private Reranker buildReranker(ObjectProvider<Reranker> rerankerProvider) {
        if (!rerankerEnabled) {
            return Reranker.identity();
        }
        Reranker raw = rerankerProvider.getIfAvailable();
        return raw != null ? new SafeReranker(raw) : Reranker.identity();
    }
}
