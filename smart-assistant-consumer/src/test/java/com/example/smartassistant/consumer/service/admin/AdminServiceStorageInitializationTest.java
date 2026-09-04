/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 */

package com.example.smartassistant.consumer.service.admin;

import com.example.smartassistant.consumer.infrastructure.db.DatabaseDialect;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminServiceStorageInitializationTest {

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private DatabaseDialect dialect;
    @Mock private DataSource dataSource;
    @Mock private Connection connection;
    @Mock private DatabaseMetaData metadata;
    @Mock private ResultSet tables;
    @Mock private ResultSet columns;

    @Test
    void startupSelfHealsAuditAndKnowledgeSourceColumnsUsingDatabaseMetadata() throws Exception {
        when(jdbcTemplate.getDataSource()).thenReturn(dataSource);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(connection.getCatalog()).thenReturn(null);
        when(connection.getSchema()).thenReturn("public");
        when(metadata.storesUpperCaseIdentifiers()).thenReturn(false);
        when(metadata.storesLowerCaseIdentifiers()).thenReturn(false);
        when(metadata.getTables(
                nullable(String.class), nullable(String.class), nullable(String.class),
                nullable(String[].class)))
                .thenReturn(tables);
        when(tables.next()).thenReturn(true);
        when(metadata.getColumns(
                nullable(String.class), nullable(String.class), nullable(String.class),
                nullable(String.class)))
                .thenReturn(columns);
        when(columns.next()).thenReturn(false);
        when(dialect.serialType()).thenReturn("BIGSERIAL");
        when(dialect.getType()).thenReturn("postgresql");
        when(jdbcTemplate.queryForObject(anyString(), eq(Number.class), any(Object[].class)))
                .thenReturn(1);

        new AdminService(jdbcTemplate, dialect).initializePersistentAdminStorage();

        verify(jdbcTemplate, times(5)).execute(
                startsWith("ALTER TABLE routing_call_log ADD COLUMN"));
        verify(jdbcTemplate, times(2)).execute(
                startsWith("ALTER TABLE admin_faq ADD COLUMN"));
    }
}
