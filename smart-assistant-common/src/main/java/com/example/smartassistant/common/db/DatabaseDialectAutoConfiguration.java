/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 数据库方言自动配置——根据 JDBC URL 自动选择 PG 或 MySQL。
 */
@Configuration
public class DatabaseDialectAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DatabaseDialectAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public DatabaseDialect databaseDialect(DataSourceProperties dataSourceProperties) {
        String url = dataSourceProperties.getUrl();
        if (url != null && url.startsWith("jdbc:mysql")) {
            log.info("[DB] 检测到 MySQL 数据源，使用 MySQLDialect");
            return new DatabaseDialect.MySQLDialect();
        }
        log.info("[DB] 检测到 PostgreSQL 数据源，使用 PostgresDialect");
        return new DatabaseDialect.PostgresDialect();
    }
}
