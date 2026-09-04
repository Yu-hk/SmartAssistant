/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.config;

import com.example.smartassistant.consumer.infrastructure.db.DatabaseDialect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.jdbc.DatabaseDriver;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Consumer 数据库方言配置。
 *
 * <p>方言目前只服务于 Consumer 的管理统计与分析查询，因此由业务模块
 * 自行装配；Common 仅保留可复用的 {@link DatabaseDialect} 契约。</p>
 */
@Configuration(proxyBeanMethods = false)
public class DatabaseDialectConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DatabaseDialectConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(DatabaseDialect.class)
    public DatabaseDialect databaseDialect(ObjectProvider<DataSourceProperties> dataSourcePropertiesProvider) {
        DataSourceProperties dataSourceProperties = dataSourcePropertiesProvider.getIfAvailable();
        String url = dataSourceProperties == null ? null : dataSourceProperties.getUrl();
        DatabaseDriver driver = url == null ? DatabaseDriver.UNKNOWN : DatabaseDriver.fromJdbcUrl(url);
        if (driver == DatabaseDriver.MYSQL || driver == DatabaseDriver.MARIADB) {
            log.info("[Consumer DB] 检测到 MySQL 数据源，使用 MySQLDialect");
            return new DatabaseDialect.MySQLDialect();
        }
        log.info("[Consumer DB] 检测到 PostgreSQL 数据源，使用 PostgresDialect");
        return new DatabaseDialect.PostgresDialect();
    }
}
