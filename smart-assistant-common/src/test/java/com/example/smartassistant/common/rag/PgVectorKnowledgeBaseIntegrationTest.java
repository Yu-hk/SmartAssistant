/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag;

import com.example.smartassistant.common.embedding.BgeEmbeddingModel;
import com.example.smartassistant.common.rag.ingestion.ReviewItem;
import com.example.smartassistant.common.rag.ingestion.ReviewQueueService;
import com.example.smartassistant.common.rag.store.KnowledgeIndexMetaService;
import com.example.smartassistant.common.tokenizer.ChineseTokenizer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * PgVectorKnowledgeBase 真 PostgreSQL + pgvector 集成验证（REQ-4 多实例一致性核心证据）。
 * <p>
 * 本测试是「RAG 生产化改造」唯一未连环境验证的环节——Flyway 迁移 + PgVector 增量 upsert +
 * 多实例读共享——的补齐。它在<b>真实 PG</b> 上验证下列核心契约：
 * <ul>
 *   <li>(a) 4 张表（knowledge_docs / knowledge_index_meta / knowledge_review_queue / compliance_audit_log）均存在；</li>
 *   <li>(b) {@code knowledge_docs.embedding} 数据类型为 {@code vector} 且维度 = 1024（非写死 384）；</li>
 *   <li>(c) <b>增量 upsert</b>：同 id 两次写入仅更新，检索反映新内容，全程<b>不触发整库 reindex</b>；</li>
 *   <li>(d) <b>index_version 过滤</b>：active=v1 时仅返回 v1 文档，旧版本不可见但保留；</li>
 *   <li>(e) <b>多实例读共享（REQ-4）</b>：实例 A 写入后，实例 B 无需重启/通知即可检索到；</li>
 *   <li>(f) <b>真实相似度排序</b>：用 pgvector 真实距离验证排序正确。</li>
 * </ul>
 *
 * <h3>跳过规则（无 PG 环境，如 CI / 沙箱）</h3>
 * 通过 {@link org.junit.jupiter.api.Assumptions#assumeTrue(boolean, String)} 实现：
 * 仅当可连接本地 PG（{@code jdbc:postgresql://localhost:5433/a2a_system}）或为测试显式传入
 * {@code -Dpg.integration=true} 时才执行；否则<b>整类优雅 skip，BUILD SUCCESS 不报错</b>。
 * 这保证 SDK 默认 {@code mvn test} 在任意无 PG 环境都能通过。
 *
 * <h3>不依赖真 BGE 模型</h3>
 * 通过 {@link StubBgeEmbeddingModel} 提供 1024 维<b>确定性</b>向量（相同文本 → 相同向量），
 * 无需 embedding-service 即可在真 PG 上验证向量读写与距离语义。
 *
 * <p><b>连接参数</b>（与 docker-compose-infra.yml 的 postgres 服务一致）：
 * url={@code jdbc:postgresql://localhost:5433/a2a_system}，user={@code postgres}，pwd={@code postgres123}。</p>
 */
@Tag("integration")
class PgVectorKnowledgeBaseIntegrationTest {

    /** 与 docker-compose-infra.yml 的 postgres 服务严格一致 */
    private static final String PG_URL = "jdbc:postgresql://localhost:5433/a2a_system";
    private static final String PG_USER = "postgres";
    private static final String PG_PWD = "postgres123";
    private static final String V1_SQL_RESOURCE = "db/migration/V1__rag_knowledge_schema.sql";

    /** 是否具备可达 PG（@BeforeAll 探测；-Dpg.integration=true 可显式开启） */
    private static boolean pgAvailable;

    private JdbcTemplate jdbcTemplate;
    private StubBgeEmbeddingModel stub;

    // ==================== 环境探测与 setUp ====================

    @BeforeAll
    static void detectPg() {
        boolean flag = Boolean.getBoolean("pg.integration");
        pgAvailable = flag || probeConnection();
        if (!pgAvailable) {
            System.out.println("[PG-INTEG] 跳过：未检测到可达 PG（如需启用，请先启动 PG 并传入 -Dpg.integration=true）");
        }
    }

    /** 尝试一次真实连接以判断 PG 是否可达（无 PG 时返回 false 而非抛错） */
    private static boolean probeConnection() {
        try {
            DriverManager.setLoginTimeout(3);
            try (Connection c = DriverManager.getConnection(PG_URL, PG_USER, PG_PWD)) {
                return c.isValid(3);
            }
        } catch (Exception e) {
            return false;
        }
    }

    @BeforeEach
    void setUp() {
        // 无可达 PG 时整类优雅跳过（BUILD SUCCESS，不报错）
        assumeTrue(pgAvailable, "PG 集成测试跳过：未检测到可达 PG（设置 -Dpg.integration=true 且确保 PG 在 5433 可达可启用）");
        try {
            DriverManagerDataSource ds = new DriverManagerDataSource(PG_URL, PG_USER, PG_PWD);
            ds.setDriverClassName("org.postgresql.Driver");
            this.jdbcTemplate = new JdbcTemplate(ds);
            this.stub = new StubBgeEmbeddingModel();
            // 真实连通性探针：即便显式 -Dpg.integration=true 但 PG 实际不可达，也优雅跳过而非报错
            // （runV1Migration/cleanTables 会吞掉各自的内部异常，故需此处先行探活）
            this.jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            // 执行 V1 Flyway 迁移脚本（幂等），保证 4 张表存在且环境干净，再允许各服务自愈
            runV1Migration();
            cleanTables();
        } catch (Exception e) {
            // 显式开启（-Dpg.integration=true）但 PG 实际不可达：优雅跳过而非报错
            assumeTrue(false, "PG 连接/建表失败，跳过集成测试：" + e.getMessage());
        }
    }

    /** 读取并执行 classpath 中的 V1 迁移脚本（幂等） */
    private void runV1Migration() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(V1_SQL_RESOURCE)) {
            if (is == null) {
                System.out.println("[PG-INTEG] 未找到 V1 SQL 资源，依赖各服务自愈建表");
                return;
            }
            String sql = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            for (String raw : sql.split(";")) {
                String stmt = stripSqlComments(raw).trim();
                if (stmt.isEmpty()) continue;
                try {
                    jdbcTemplate.execute(stmt);
                } catch (Exception e) {
                    // 幂等建表/种子冲突等可忽略（如 CREATE EXTENSION 已存在）
                    System.out.println("[PG-INTEG] 执行 V1 语句跳过(可忽略): " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.out.println("[PG-INTEG] 读取 V1 SQL 失败，依赖自愈: " + e.getMessage());
        }
    }

    /** 去掉 SQL 行注释，避免把纯注释块当作语句执行 */
    private static String stripSqlComments(String raw) {
        StringBuilder sb = new StringBuilder();
        for (String line : raw.split("\n")) {
            if (line.trim().startsWith("--")) continue;
            sb.append(line).append("\n");
        }
        return sb.toString();
    }

    /** 清空 4 张表，保证每个测试用例数据隔离 */
    private void cleanTables() {
        for (String t : new String[]{
                "knowledge_docs", "knowledge_index_meta",
                "knowledge_review_queue", "compliance_audit_log"}) {
            try {
                jdbcTemplate.execute("DELETE FROM " + t);
            } catch (Exception ignored) {
                // 表尚未存在等情况忽略
            }
        }
    }

    // ==================== (a)(b) 表结构与向量维度 ====================

    @Test
    void tablesExist_andEmbeddingIsVector1024() {
        // (a) 4 张表均存在
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT tablename FROM pg_tables WHERE schemaname='public' "
                        + "AND tablename IN ('knowledge_docs','knowledge_index_meta',"
                        + "'knowledge_review_queue','compliance_audit_log')",
                String.class);
        assertTrue(tables.contains("knowledge_docs"), "knowledge_docs 应存在");
        assertTrue(tables.contains("knowledge_index_meta"), "knowledge_index_meta 应存在");
        assertTrue(tables.contains("knowledge_review_queue"), "knowledge_review_queue 应存在");
        assertTrue(tables.contains("compliance_audit_log"), "compliance_audit_log 应存在");

        // (b) embedding 数据类型为 vector 且维度 = 1024（非写死 384）
        String typname = jdbcTemplate.queryForObject(
                "SELECT t.typname FROM pg_attribute a "
                        + "JOIN pg_class c ON a.attrelid = c.oid "
                        + "JOIN pg_type t ON a.atttypid = t.oid "
                        + "WHERE c.relname='knowledge_docs' AND a.attname='embedding' AND NOT a.attisdropped",
                String.class);
        assertEquals("vector", typname, "knowledge_docs.embedding 应为 vector 类型");

        Integer dim = jdbcTemplate.queryForObject(
                "SELECT a.atttypmod FROM pg_attribute a "
                        + "JOIN pg_class c ON a.attrelid = c.oid "
                        + "WHERE c.relname='knowledge_docs' AND a.attname='embedding' AND NOT a.attisdropped",
                Integer.class);
        assertNotNull(dim, "应能从 pg_attribute.atttypmod 读取 embedding 维度");
        assertEquals(1024, dim.intValue(),
                "embedding 维度应为 1024（运行时 BgeEmbeddingModel.dimensions() 为准），而非写死 384");
        assertNotEquals(384, dim.intValue(), "embedding 维度不应是旧硬编码 384");
    }

    // ==================== (c) 增量 upsert（不触发整库 reindex）====================

    @Test
    void incrementalUpsert_updatesContentWithoutFullReindex() {
        ReindexCountingKB kb = new ReindexCountingKB("kb-upsert", stub, jdbcTemplate, null, null);

        kb.addDocument(new KnowledgeDocument(
                "upsert-doc-1", "退款政策", "原始内容版本一", "售后", "退款", -1, -1));

        // 同一 id 第二次 upsert，修改 content
        kb.addDocument(new KnowledgeDocument(
                "upsert-doc-1", "退款政策", "更新后的内容版本二", "售后", "退款", -1, -1));

        // 增量 upsert 过程中绝不调用 reindex()（避免全量重算）
        assertEquals(0, kb.reindexCount.get(),
                "增量 upsert 过程中不应调用 reindex()（避免整库重算）");

        // 表内仅 1 行（upsert 覆盖而非重复 insert）
        assertEquals(1, kb.size(), "同一 id 两次 upsert 后应只剩 1 行");

        // 检索应反映最新 content
        List<KnowledgeHit> hits = kb.search(embedText("退款政策", "更新后的内容版本二", "退款"), 5);
        assertFalse(hits.isEmpty(), "应能检索到 upsert 的文档");
        KnowledgeHit top = hits.get(0);
        assertEquals("upsert-doc-1", top.getDocument().getId());
        assertEquals("更新后的内容版本二", top.getDocument().getContent(),
                "检索结果应反映第二次 upsert 的新 content");
        assertNotEquals("原始内容版本一", top.getDocument().getContent(),
                "检索结果不应是旧 content");
    }

    // ==================== (d) index_version 过滤 ====================

    @Test
    void indexVersionFilter_onlyReturnsActiveVersion() {
        KnowledgeIndexMetaService meta = new KnowledgeIndexMetaService(jdbcTemplate);
        meta.setActiveVersion("v1");

        PgVectorKnowledgeBase kb = new PgVectorKnowledgeBase(
                "kb-iv", stub, jdbcTemplate, null, meta);

        // 两份内容相同、向量相同的文档，仅 index_version 不同（v1 / v2）
        String sameContent = "索引版本过滤验证的固定内容用于隔离维度";
        kb.addDocument(new KnowledgeDocument("iv-doc-1", "版本测试", sameContent, "cat", "kw", -1, -1,
                "", "v1", "", 0, "", AuthorityLevel.L2_INTERNAL, DocumentStatus.ACTIVE, "v1"));
        kb.addDocument(new KnowledgeDocument("iv-doc-2", "版本测试", sameContent, "cat", "kw", -1, -1,
                "", "v1", "", 0, "", AuthorityLevel.L2_INTERNAL, DocumentStatus.ACTIVE, "v2"));

        List<KnowledgeHit> hits = kb.search(embedText("版本测试", sameContent, "kw"), 10);
        List<String> ids = hits.stream()
                .map(h -> h.getDocument().getId())
                .collect(Collectors.toList());

        assertTrue(ids.contains("iv-doc-1"), "active=v1 时应返回 v1 文档");
        assertFalse(ids.contains("iv-doc-2"),
                "active=v1 时 v2 文档应被 index_version 过滤排除（旧版本不可见但保留可回滚）");
    }

    // ==================== (e) 多实例读共享（REQ-4）====================

    @Test
    void multiInstanceReadSharing_sharedPgStorage() {
        // 两个独立实例（独立对象），共享同一 JdbcTemplate / 同一 PG
        PgVectorKnowledgeBase instanceA = new PgVectorKnowledgeBase(
                "inst-A", stub, jdbcTemplate, null, null);
        PgVectorKnowledgeBase instanceB = new PgVectorKnowledgeBase(
                "inst-B", stub, jdbcTemplate, null, null);

        instanceA.addDocument(new KnowledgeDocument(
                "shared-doc-1", "共享文档", "多实例读共享验证内容", "cat", "kw", -1, -1));

        // 实例 B 不重启、未收到 A 的任何通知，直接检索
        List<KnowledgeHit> hitsB = instanceB.search(embedText("共享文档", "多实例读共享验证内容", "kw"), 5);
        assertFalse(hitsB.isEmpty(),
                "实例 B 应能直接读到实例 A 写入共享 PG 的文档（REQ-4 多实例一致性）");
        assertEquals("shared-doc-1", hitsB.get(0).getDocument().getId());
    }

    // ==================== (f) 真实相似度排序 ====================

    @Test
    void realSimilarityOrdering_byPgVectorDistance() {
        PgVectorKnowledgeBase kb = new PgVectorKnowledgeBase(
                "kb-sim", stub, jdbcTemplate, null, null);

        KnowledgeDocument refund = new KnowledgeDocument(
                "sim-refund", "退款政策", "用户可在七天内无理由申请退款并原路退回", "售后", "退款,退货", -1, -1);
        KnowledgeDocument ship = new KnowledgeDocument(
                "sim-ship", "发货规则", "订单将在四十八小时内完成出库并由快递发货", "物流", "发货,快递", -1, -1);
        kb.addDocument(refund);
        kb.addDocument(ship);

        // (f1) 直接 pgvector 距离：自身距离 = 0 < 不同文档距离（真实距离排序）
        Double distSelf = jdbcTemplate.queryForObject(
                "SELECT embedding <-> (SELECT embedding FROM knowledge_docs WHERE id='sim-refund') "
                        + "FROM knowledge_docs WHERE id='sim-refund'",
                Double.class);
        Double distOther = jdbcTemplate.queryForObject(
                "SELECT embedding <-> (SELECT embedding FROM knowledge_docs WHERE id='sim-ship') "
                        + "FROM knowledge_docs WHERE id='sim-refund'",
                Double.class);
        assertNotNull(distSelf);
        assertNotNull(distOther);
        assertEquals(0.0, distSelf, 1e-6, "相同文档向量自身距离应为 0");
        assertTrue(distOther > distSelf, "不同文档向量距离应大于自身距离（真实 pgvector 距离排序）");

        // (f2) 两个不同查询向量 → 排序正确（各自最相似文档排第一）
        List<KnowledgeHit> hitsRefund = kb.search(refund.toEmbedText(), 5);
        assertFalse(hitsRefund.isEmpty(), "退款查询应返回结果");
        assertEquals("sim-refund", hitsRefund.get(0).getDocument().getId(),
                "退款查询应将退款文档排第一");

        List<KnowledgeHit> hitsShip = kb.search(ship.toEmbedText(), 5);
        assertFalse(hitsShip.isEmpty(), "发货查询应返回结果");
        assertEquals("sim-ship", hitsShip.get(0).getDocument().getId(),
                "发货查询应将发货文档排第一");
    }

    // ==================== 双建表一致性：ReviewQueueService 自愈验证 ====================

    @Test
    void reviewQueueService_selfHealsMissingTable() {
        // 故意删除表，验证 ReviewQueueService 自愈建表（对齐 V1 迁移脚本列定义）
        jdbcTemplate.execute("DROP TABLE IF EXISTS knowledge_review_queue");
        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_tables WHERE tablename='knowledge_review_queue'", Integer.class);
        assertEquals(0, cnt, "前置：knowledge_review_queue 应已被删除");

        ReviewQueueService rq = new ReviewQueueService(jdbcTemplate);
        KnowledgeDocument doc = new KnowledgeDocument(
                "rq-doc-1", "复核样例", "需要人工复核的内容", "cat", "kw", -1, -1);
        ReviewItem item = ReviewItem.of(doc, "疑似脏数据", "DIRTY", "uploader-1");
        rq.enqueue(item);

        Integer rows = jdbcTemplate.queryForObject("SELECT count(*) FROM knowledge_review_queue", Integer.class);
        assertEquals(1, rows, "ReviewQueueService 应自愈建表并成功写入 1 行");

        ReviewItem loaded = rq.get(item.getId());
        assertNotNull(loaded, "应能经自愈后的表读回复核条目");
        assertEquals(ReviewItem.STATUS_REVIEW, loaded.getStatus());
    }

    // ==================== 辅助 ====================

    /** 复刻 KnowledgeDocument.toEmbedText()，用于构造与文档完全一致的查询向量 */
    private static String embedText(String title, String content, String keywords) {
        return title + "。\n" + content + "\n关键词：" + keywords;
    }

    /**
     * 不依赖真 BGE 模型的 stub：固定返回 1024 维<b>确定性</b>向量。
     * <p>相同文本 → 相同向量（保证 upsert 与查询向量一致、dist=0）；
     * 不同文本 → 由确定性 PRNG 累加得到近似正交的随机方向（验证真实距离排序）。</p>
     */
    static final class StubBgeEmbeddingModel extends BgeEmbeddingModel {
        private static final int DIM = 1024;

        StubBgeEmbeddingModel() {
            // 传入不存在的模型路径：构造函数内部捕获 RuntimeException，不触发 ONNX 原生初始化
            super("__integration_test_stub_no_model__");
        }

        @Override
        public int dimensions() {
            return DIM;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public float[] embedding(String text) {
            if (text == null) text = "";
            float[] v = new float[DIM];
            for (int p = 0; p < text.length(); p++) {
                char c = text.charAt(p);
                // 以 (字符, 位置) 为确定性种子，累加方向贡献
                long seed = ((long) c) * 1000003L + p;
                Random rnd = new Random(seed);
                for (int i = 0; i < DIM; i++) {
                    v[i] += (rnd.nextFloat() * 2.0f - 1.0f);
                }
            }
            // 归一化为单位向量，保证 pgvector 距离语义稳定
            double norm = 0.0;
            for (float x : v) norm += (double) x * x;
            norm = Math.sqrt(norm);
            if (norm > 1e-12) {
                for (int i = 0; i < DIM; i++) v[i] /= (float) norm;
            }
            return v;
        }
    }

    /**
     * reindex 计数子类：用于证明增量 upsert 不触发整库 reindex。
     * <p>addDocument 在 PgVectorKnowledgeBase 中完成向量化与 upsert，<b>不调用 reindex()</b>；
     * 若任何路径误触发全量重算，reindexCount 将 > 0。</p>
     */
    static final class ReindexCountingKB extends PgVectorKnowledgeBase {
        final AtomicInteger reindexCount = new AtomicInteger(0);

        ReindexCountingKB(String name, BgeEmbeddingModel embeddingModel, JdbcTemplate jdbcTemplate,
                          ChineseTokenizer tokenizer, KnowledgeIndexMetaService indexMetaService) {
            super(name, embeddingModel, jdbcTemplate, tokenizer, indexMetaService);
        }

        @Override
        public void reindex() {
            reindexCount.incrementAndGet();
        }
    }
}
