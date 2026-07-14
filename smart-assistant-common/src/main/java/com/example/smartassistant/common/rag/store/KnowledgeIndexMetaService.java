/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.store;

import com.example.smartassistant.common.rag.KnowledgeDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 索引版本元数据服务——维护全局 {@code active_index_version}（单行）。
 * <p>
 * 摄入时由 {@link #getActiveVersion()} 取当前值打在 chunk 的 {@code indexVersion} 字段；
 * 检索时按 {@code active_index_version} 过滤，保证查到的向量是同一索引版本构建的。
 * 模型/切分策略/解析策略变更 → 运维 {@link #bump(String)} 生成新版本，旧版本检索不可见但保留。
 * </p>
 *
 * <p>存储：优先写入 {@code knowledge_index_meta} 表（需 {@link JdbcTemplate}）；
 * 无 JdbcTemplate（内存/测试态）时退化为内存缓存，行为一致。</p>
 */
public class KnowledgeIndexMetaService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexMetaService.class);

    /** 单行元数据的固定主键 */
    public static final int META_ROW_ID = 1;

    /** 默认索引版本 */
    public static final String DEFAULT_VERSION = "v1";

    private final JdbcTemplate jdbcTemplate;
    private final AtomicReference<String> cachedActiveVersion = new AtomicReference<>(DEFAULT_VERSION);

    public KnowledgeIndexMetaService() {
        this(null);
    }

    public KnowledgeIndexMetaService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        initTable();
        // 启动即尝试从库里加载已有 active 版本，覆盖默认
        String loaded = loadFromDb();
        if (loaded != null && !loaded.isBlank()) {
            cachedActiveVersion.set(loaded);
        }
    }

    /** 幂等建表 + 种子行 */
    private void initTable() {
        if (jdbcTemplate == null) return;
        try {
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS knowledge_index_meta ("
                    + "id INT PRIMARY KEY DEFAULT " + META_ROW_ID + ","
                    + "active_index_version VARCHAR(32) NOT NULL DEFAULT '" + DEFAULT_VERSION + "',"
                    + "bumped_at BIGINT NOT NULL DEFAULT 0)");
            jdbcTemplate.update("INSERT INTO knowledge_index_meta (id, active_index_version, bumped_at) "
                    + "VALUES (" + META_ROW_ID + ", '" + DEFAULT_VERSION + "', 0) "
                    + "ON CONFLICT (id) DO NOTHING");
        } catch (Exception e) {
            log.warn("[IndexMeta] 建表/种子失败（可忽略，可能已存在）: {}", e.getMessage());
        }
    }

    private String loadFromDb() {
        if (jdbcTemplate == null) return null;
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT active_index_version FROM knowledge_index_meta WHERE id = " + META_ROW_ID,
                    String.class);
        } catch (Exception e) {
            log.debug("[IndexMeta] 读取 active 版本失败: {}", e.getMessage());
            return null;
        }
    }

    /** 当前 active 索引版本 */
    public String getActiveVersion() {
        if (jdbcTemplate != null) {
            String v = loadFromDb();
            if (v != null && !v.isBlank()) {
                cachedActiveVersion.set(v);
                return v;
            }
        }
        return cachedActiveVersion.get();
    }

    /** 设置 active 索引版本（持久化 + 缓存） */
    public void setActiveVersion(String version) {
        if (version == null || version.isBlank()) return;
        cachedActiveVersion.set(version);
        if (jdbcTemplate != null) {
            try {
                jdbcTemplate.update(
                        "UPDATE knowledge_index_meta SET active_index_version = ?, bumped_at = ? WHERE id = "
                                + META_ROW_ID,
                        version, System.currentTimeMillis());
            } catch (Exception e) {
                log.warn("[IndexMeta] 更新 active 版本失败: {}", e.getMessage());
            }
        }
    }

    /** 升级到新版本（运维动作） */
    public void bump(String newVersion) {
        setActiveVersion(newVersion);
        log.info("[IndexMeta] bump active_index_version -> {}", newVersion);
    }

    /**
     * 为文档打标当前 active 版本。
     * <p>{@link KnowledgeDocument} 字段不可变，本方法不修改文档，仅返回应使用的索引版本号，
     * 由调用方在构造 KnowledgeDocument 时填入。保留此 API 以对齐类图契约。</p>
     *
     * @param doc 待打标文档（仅用于记录/日志）
     * @return 当前 active 索引版本
     */
    public String stamp(KnowledgeDocument doc) {
        return getActiveVersion();
    }
}
