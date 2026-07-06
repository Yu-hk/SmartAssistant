/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.db;

/**
 * 数据库方言接口——抽象 PG/MySQL 语法差异。
 *
 * <p>支持 PostgreSQL 和 MySQL 之间的 SQL 语法切换。
 * 各模块注入此接口，根据 active profile 自动选择实现。
 */
public interface DatabaseDialect {

    /** 数据库类型 */
    String getType();

    /**
     * 返回表示「N 天前」的时间戳表达式。
     * PG: NOW() - INTERVAL '7 days'
     * MySQL: DATE_SUB(NOW(), INTERVAL 7 DAY)
     */
    default String dateSub(String interval) {
        return "NOW() - INTERVAL '" + interval + " days'";
    }

    /**
     * 返回 BOOLEAN 类型的列定义 DDL。
     * PG: boolean
     * MySQL: tinyint(1)
     */
    default String booleanType() { return "boolean"; }

    /**
     * 返回 JSON 类型的列定义 DDL。
     * PG: jsonb
     * MySQL: json
     */
    default String jsonType() { return "jsonb"; }

    /**
     * 返回自增主键的列定义 DDL。
     * PG: BIGSERIAL
     * MySQL: BIGINT AUTO_INCREMENT
     */
    default String serialType() { return "BIGSERIAL"; }

    /**
     * 返回 LIMIT 子句。
     * PG/MySQL: LIMIT n
     */
    default String limit(int n) { return "LIMIT " + n; }

    /**
     * 返回条件聚合表达式（COUNT + FILTER 替代）。
     * PG: COUNT(*) FILTER (WHERE condition)
     * MySQL: SUM(CASE WHEN condition THEN 1 ELSE 0 END)
     */
    default String countFilter(String condition) {
        return "COUNT(*) FILTER (WHERE " + condition + ")";
    }

    /**
     * 返回 DATE 函数。
     * PG: DATE(created_at)
     * MySQL: DATE(created_at)
     */
    default String dateFunc(String column) {
        return "DATE(" + column + ")";
    }

    /**
     * 返回自增主键的 GET 函数。
     * PG: LASTVAL()
     * MySQL: LAST_INSERT_ID()
     */
    default String lastInsertId() { return "LASTVAL()"; }

    /**
     * 返回 ON CONFLICT DO UPDATE 子句。
     * PG: ON CONFLICT (pk) DO UPDATE SET ...
     * MySQL: ON DUPLICATE KEY UPDATE ...
     */
    default String onConflictDoUpdate(String constraint) {
        return "ON CONFLICT (" + constraint + ") DO UPDATE SET ";
    }

    /**
     * 返回 TRUE 字面量。
     * PG: true
     * MySQL: 1
     */
    default String trueLiteral() { return "true"; }

    /**
     * 返回 FALSE 字面量。
     * PG: false
     * MySQL: 0
     */
    default String falseLiteral() { return "false"; }

    /** PostgreSQL 实现 */
    class PostgresDialect implements DatabaseDialect {
        @Override public String getType() { return "postgresql"; }
        @Override public String booleanType() { return "boolean"; }
        @Override public String jsonType() { return "jsonb"; }
        @Override public String serialType() { return "BIGSERIAL"; }
        @Override public String trueLiteral() { return "true"; }
        @Override public String falseLiteral() { return "false"; }
    }

    /** MySQL 实现 */
    class MySQLDialect implements DatabaseDialect {
        @Override public String getType() { return "mysql"; }
        @Override
        public String dateSub(String interval) {
            return "DATE_SUB(NOW(), INTERVAL " + interval + " DAY)";
        }
        @Override public String booleanType() { return "tinyint(1)"; }
        @Override public String jsonType() { return "json"; }
        @Override public String serialType() { return "BIGINT AUTO_INCREMENT"; }
        @Override
        public String countFilter(String condition) {
            return "SUM(CASE WHEN " + condition + " THEN 1 ELSE 0 END)";
        }
        @Override
        public String onConflictDoUpdate(String constraint) {
            return "ON DUPLICATE KEY UPDATE ";
        }
        @Override public String trueLiteral() { return "1"; }
        @Override public String falseLiteral() { return "0"; }
        @Override public String lastInsertId() { return "LAST_INSERT_ID()"; }
    }
}
