/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.sql;

import lombok.Value;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * SQL 安全校验器
 * <p>
 * 基于 jsqlparser AST 解析，提供精确的表名提取和白名单校验，
 * 替代原有的 {@code contains()} 子串匹配方式，防止 SQL 注入绕过。
 * <p>
 * 使用示例：
 * <pre>{@code
 * SqlSecurityValidator.ValidationResult result =
 *     SqlSecurityValidator.validateSelect(sql, allowedTables, forbiddenTables);
 * if (!result.isValid()) {
 *     throw new IllegalArgumentException(result.getReason());
 * }
 * }</pre>
 */
@Slf4j
@UtilityClass
public class SqlSecurityValidator {

    /**
     * 校验 SELECT 语句的表访问权限
     *
     * @param sql             用户提交的 SQL 语句
     * @param allowedTables   允许访问的表名列表（不区分大小写）
     * @param forbiddenTables 禁止访问的表名列表（不区分大小写）
     * @return 校验结果
     */
    public ValidationResult validateSelect(String sql,
                                           List<String> allowedTables,
                                           List<String> forbiddenTables) {
        if (sql == null || sql.isBlank()) {
            return invalid("SQL 语句不能为空");
        }

        Set<String> allowed = toLowerSet(allowedTables);
        Set<String> forbidden = toLowerSet(forbiddenTables);

        // 解析 SQL 为 AST
        Statement statement;
        try {
            statement = CCJSqlParserUtil.parse(sql);
        } catch (Exception e) {
            log.warn("[SqlSecurityValidator] SQL 解析失败: {}", e.getMessage());
            return invalid("SQL 语法解析失败: " + e.getMessage());
        }

        // 校验：必须是 SELECT 语句
        if (!(statement instanceof Select)) {
            return invalid("只允许执行 SELECT 查询");
        }

        // 提取所有涉及的表名
        Set<String> tableNames = new HashSet<>();
        Set<String> cteNames = new HashSet<>();
        try {
            extractTableNames((Select) statement, tableNames, cteNames);
        } catch (Exception e) {
            log.warn("[SqlSecurityValidator] 提取表名失败: {}", e.getMessage());
            return invalid("SQL 表名解析失败: " + e.getMessage());
        }

        // 排除 CTE 名称（虚拟表，非真实数据库表）
        tableNames.removeAll(cteNames);

        if (tableNames.isEmpty()) {
            log.warn("[SqlSecurityValidator] SQL 中未解析到任何表名: sql={}", sql);
            return invalid("SQL 中未识别到任何数据库表");
        }

        // 检查黑名单
        for (String table : tableNames) {
            if (forbidden.contains(table)) {
                log.warn("[SqlSecurityValidator] ❌ 禁止访问的表: {}", table);
                return invalid("无权访问表: " + table);
            }
        }

        // 检查白名单（所有涉及的表必须在白名单中）
        for (String table : tableNames) {
            if (!allowed.contains(table)) {
                log.warn("[SqlSecurityValidator] ❌ 未授权的表: {}，允许的表: {}", table, allowed);
                return invalid("无权访问表: " + table + "，仅允许访问: " + allowed);
            }
        }

        return valid();
    }

    /**
     * 安全构建 IN 子句的占位符和参数列表
     * <p>
     * 用于替代字符串拼接方式的 {@code IN (...)} 子句构建，防止 SQL 注入。
     *
     * @param values 参数值列表
     * @return 占位符和参数列表
     */
    public SqlWithArgs buildSafeInClause(List<String> values) {
        if (values == null || values.isEmpty()) {
            return new SqlWithArgs("''", List.of());
        }
        String placeholders = values.stream()
                .map(v -> "?")
                .collect(Collectors.joining(", "));
        return new SqlWithArgs(placeholders, values);
    }

    // ========== 内部方法 ==========

    /**
     * 从 Select AST 节点中递归提取所有表名
     */
    private void extractTableNames(Select select, Set<String> tableNames, Set<String> cteNames) {
        // CTE (WITH 子句)
        if (select.getWithItemsList() != null) {
            for (WithItem withItem : select.getWithItemsList()) {
                // 记录 CTE 名称（虚拟表，排除在白名单校验外）
                if (withItem.getAlias() != null && withItem.getAlias().getName() != null) {
                    cteNames.add(withItem.getAlias().getName().toLowerCase());
                }
                // 递归提取 CTE 内部引用的真实表
                if (withItem.getSelect() != null) {
                    extractTableNames(withItem.getSelect(), tableNames, cteNames);
                }
            }
        }

        // 根据具体类型处理
        if (select instanceof PlainSelect) {
            extractFromPlainSelect((PlainSelect) select, tableNames);
        } else if (select instanceof SetOperationList) {
            SetOperationList setOp = (SetOperationList) select;
            for (Select body : setOp.getSelects()) {
                extractTableNames(body, tableNames, cteNames);
            }
        } else if (select instanceof ParenthesedSelect) {
            Select inner = ((ParenthesedSelect) select).getSelect();
            if (inner != null) {
                extractTableNames(inner, tableNames, cteNames);
            }
        }
    }

    /**
     * 从 PlainSelect 中提取 FROM 和 JOIN 的表名
     */
    private void extractFromPlainSelect(PlainSelect plainSelect, Set<String> tableNames) {
        // FROM 子句
        if (plainSelect.getFromItem() != null) {
            extractFromItem(plainSelect.getFromItem(), tableNames);
        }

        // JOIN 子句
        if (plainSelect.getJoins() != null) {
            for (Join join : plainSelect.getJoins()) {
                if (join.getRightItem() != null) {
                    extractFromItem(join.getRightItem(), tableNames);
                }
            }
        }
    }

    /**
     * 从 FromItem（表/子查询）中提取表名
     */
    private void extractFromItem(FromItem fromItem, Set<String> tableNames) {
        if (fromItem instanceof Table) {
            String tableName = ((Table) fromItem).getName();
            if (tableName != null && !tableName.isBlank()) {
                tableNames.add(tableName.toLowerCase());
            }
        } else if (fromItem instanceof ParenthesedSelect) {
            // 子查询：SELECT * FROM (SELECT ...) t
            Select innerSelect = ((ParenthesedSelect) fromItem).getSelect();
            if (innerSelect != null) {
                extractTableNames(innerSelect, tableNames);
            }
        } else if (fromItem instanceof ParenthesedFromItem) {
            ParenthesedFromItem pfi = (ParenthesedFromItem) fromItem;
            if (pfi.getFromItem() != null) {
                extractFromItem(pfi.getFromItem(), tableNames);
            }
        }
        // LateralSubSelect, TableFunction 等忽略（不在白名单管控范围内）
    }

    /**
     * 从 FromItem（表/子查询）中提取表名（重载 - 不含 CTE 收集）
     */
    private void extractTableNames(Select select, Set<String> tableNames) {
        extractTableNames(select, tableNames, new HashSet<>());
    }

    private Set<String> toLowerSet(List<String> list) {
        if (list == null) return Set.of();
        return list.stream()
                .map(s -> s != null ? s.toLowerCase() : "")
                .filter(s -> !s.isBlank())
                .collect(Collectors.toSet());
    }

    private ValidationResult valid() {
        return new ValidationResult(true, null);
    }

    private ValidationResult invalid(String reason) {
        return new ValidationResult(false, reason);
    }

    // ========== 内部类型 ==========

    @Value
    public static class ValidationResult {
        boolean valid;
        String reason;
    }

    @Value
    public static class SqlWithArgs {
        String placeholders;
        List<String> args;
    }
}
