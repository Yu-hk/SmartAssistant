package com.example.smartassistant.common.sql;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SqlSecurityValidator 单元测试
 * 验证 AST 级别的 SQL 安全校验是否覆盖预期场景
 */
class SqlSecurityValidatorTest {

    private static final List<String> ALLOWED = List.of("users", "orders", "products", "routing_call_logs");
    private static final List<String> FORBIDDEN = List.of("passwords", "payment_cards");

    // ========== 正常场景 ==========

    @Test
    void simpleSelectShouldPass() {
        SqlSecurityValidator.ValidationResult r = SqlSecurityValidator.validateSelect(
                "SELECT * FROM users", ALLOWED, FORBIDDEN);
        assertTrue(r.isValid());
    }

    @Test
    void selectWithWhereShouldPass() {
        SqlSecurityValidator.ValidationResult r = SqlSecurityValidator.validateSelect(
                "SELECT id, name FROM users WHERE age > 18", ALLOWED, FORBIDDEN);
        assertTrue(r.isValid());
    }

    @Test
    void selectWithJoinShouldPass() {
        SqlSecurityValidator.ValidationResult r = SqlSecurityValidator.validateSelect(
                "SELECT u.name, o.total FROM users u JOIN orders o ON u.id = o.user_id",
                ALLOWED, FORBIDDEN);
        assertTrue(r.isValid());
    }

    @Test
    void selectWithMultipleTablesShouldPass() {
        SqlSecurityValidator.ValidationResult r = SqlSecurityValidator.validateSelect(
                "SELECT * FROM users, orders WHERE users.id = orders.user_id",
                ALLOWED, FORBIDDEN);
        assertTrue(r.isValid());
    }

    // ========== 拒绝场景 ==========

    @Test
    void insertShouldBeRejected() {
        SqlSecurityValidator.ValidationResult r = SqlSecurityValidator.validateSelect(
                "INSERT INTO users (name) VALUES ('test')", ALLOWED, FORBIDDEN);
        assertFalse(r.isValid());
        assertTrue(r.getReason().contains("SELECT"));
    }

    @Test
    void dropShouldBeRejected() {
        SqlSecurityValidator.ValidationResult r = SqlSecurityValidator.validateSelect(
                "DROP TABLE users", ALLOWED, FORBIDDEN);
        assertFalse(r.isValid());
    }

    @Test
    void updateShouldBeRejected() {
        SqlSecurityValidator.ValidationResult r = SqlSecurityValidator.validateSelect(
                "UPDATE users SET name = 'hacker' WHERE id = 1", ALLOWED, FORBIDDEN);
        assertFalse(r.isValid());
    }

    @Test
    void deleteShouldBeRejected() {
        SqlSecurityValidator.ValidationResult r = SqlSecurityValidator.validateSelect(
                "DELETE FROM users WHERE id = 1", ALLOWED, FORBIDDEN);
        assertFalse(r.isValid());
    }

    @Test
    void forbidTableAccessShouldBeRejected() {
        SqlSecurityValidator.ValidationResult r = SqlSecurityValidator.validateSelect(
                "SELECT * FROM passwords", ALLOWED, FORBIDDEN);
        assertFalse(r.isValid());
        assertTrue(r.getReason().contains("passwords"));
    }

    @Test
    void unknownTableShouldBeRejected() {
        SqlSecurityValidator.ValidationResult r = SqlSecurityValidator.validateSelect(
                "SELECT * FROM secret_data", ALLOWED, FORBIDDEN);
        assertFalse(r.isValid());
        assertTrue(r.getReason().contains("secret_data"));
    }

    @Test
    void emptySqlShouldBeRejected() {
        assertFalse(SqlSecurityValidator.validateSelect("", ALLOWED, FORBIDDEN).isValid());
        assertFalse(SqlSecurityValidator.validateSelect(null, ALLOWED, FORBIDDEN).isValid());
        assertFalse(SqlSecurityValidator.validateSelect("   ", ALLOWED, FORBIDDEN).isValid());
    }

    // ========== 绕过测试 ==========

    @Test
    void containsBypassShouldNotWork() {
        // 之前的 contains("users") 会误匹配 "user_profiles"
        // AST 解析能精确区分表名
        SqlSecurityValidator.ValidationResult r = SqlSecurityValidator.validateSelect(
                "SELECT * FROM user_sessions", ALLOWED, FORBIDDEN);
        assertFalse(r.isValid());
    }

    @Test
    void commentInjectionShouldBeDecoded() {
        // DROP 隐藏在注释中，contains("DROP ") 模式会误伤或漏过
        SqlSecurityValidator.ValidationResult r = SqlSecurityValidator.validateSelect(
                "SELECT * FROM users /* DROP TABLE users */", ALLOWED, FORBIDDEN);
        assertTrue(r.isValid()); // 注释中的 DROP 不应影响校验
    }

    @Test
    void subqueryShouldExtractTables() {
        SqlSecurityValidator.ValidationResult r = SqlSecurityValidator.validateSelect(
                "SELECT * FROM (SELECT * FROM users) AS sub", ALLOWED, FORBIDDEN);
        assertTrue(r.isValid());
    }

    @Test
    void cteShouldExtractTables() {
        SqlSecurityValidator.ValidationResult r = SqlSecurityValidator.validateSelect(
                "WITH user_cte AS (SELECT * FROM users) SELECT * FROM user_cte",
                ALLOWED, FORBIDDEN);
        assertTrue(r.isValid());
    }

    // ========== buildSafeInClause 测试 ==========

    @Test
    void buildSafeInClauseShouldGeneratePlaceholders() {
        SqlSecurityValidator.SqlWithArgs result = SqlSecurityValidator.buildSafeInClause(
                List.of("users", "orders"));
        assertEquals("?, ?", result.getPlaceholders());
        assertEquals(2, result.getArgs().size());
        assertEquals("users", result.getArgs().get(0));
        assertEquals("orders", result.getArgs().get(1));
    }

    @Test
    void buildSafeInClauseEmptyShouldReturnEmpty() {
        SqlSecurityValidator.SqlWithArgs result = SqlSecurityValidator.buildSafeInClause(List.of());
        assertEquals("''", result.getPlaceholders());
        assertTrue(result.getArgs().isEmpty());
    }

    @Test
    void buildSafeInClauseNullShouldReturnEmpty() {
        SqlSecurityValidator.SqlWithArgs result = SqlSecurityValidator.buildSafeInClause(null);
        assertEquals("''", result.getPlaceholders());
        assertTrue(result.getArgs().isEmpty());
    }
}
