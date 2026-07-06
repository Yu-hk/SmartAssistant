package com.example.smartassistant.common.sql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link UrlSecurityValidator} 单元测试。
 */
class UrlSecurityValidatorTest {

    private final UrlSecurityValidator validator = new UrlSecurityValidator();

    @Test
    @DisplayName("合法 URL 应通过（跳过 DNS 解析）")
    void validExternalUrl() {
        // IP 地址格式不涉及 DNS 解析
        assertTrue(validator.validate("https://93.184.216.34/test").isAllowed());
        assertTrue(validator.validate("http://8.8.8.8/query").isAllowed());
    }

    @Test
    @DisplayName("localhost 应被拒绝")
    void rejectLocalhost() {
        assertFalse(validator.validate("http://localhost:8080/api").isAllowed());
        assertFalse(validator.validate("https://localhost/health").isAllowed());
    }

    @Test
    @DisplayName("127.0.0.1 应被拒绝")
    void rejectLoopback() {
        assertFalse(validator.validate("http://127.0.0.1:6379/").isAllowed());
    }

    @Test
    @DisplayName("内网 IP 应被拒绝")
    void rejectPrivateIps() {
        assertFalse(validator.validate("http://10.0.0.1/secret").isAllowed());
        assertFalse(validator.validate("http://192.168.1.1/admin").isAllowed());
        assertFalse(validator.validate("http://172.16.0.1/config").isAllowed());
    }

    @Test
    @DisplayName("内部域名后缀应被拒绝")
    void rejectInternalDomains() {
        assertFalse(validator.validate("http://service.internal/api").isAllowed());
        assertFalse(validator.validate("http://db.local/query").isAllowed());
        assertFalse(validator.validate("http://server.corp/config").isAllowed());
    }

    @Test
    @DisplayName("非 HTTP 协议应被拒绝")
    void rejectNonHttp() {
        assertFalse(validator.validate("ftp://files.example.com/file").isAllowed());
        assertFalse(validator.validate("file:///etc/passwd").isAllowed());
        assertFalse(validator.validate("data://test").isAllowed());
    }

    @Test
    @DisplayName("空或 null URL 应被拒绝")
    void rejectNullOrEmpty() {
        assertFalse(validator.validate(null).isAllowed());
        assertFalse(validator.validate("").isAllowed());
        assertFalse(validator.validate("   ").isAllowed());
    }

    @Test
    @DisplayName("白名单模式：仅允许白名单域名")
    void whitelistOnly() {
        var whitelistValidator = new UrlSecurityValidator(
                List.of("93.184.216.34"), List.of());
        assertTrue(whitelistValidator.validate("http://93.184.216.34/data").isAllowed());
        assertFalse(whitelistValidator.validate("https://8.8.8.8/hack").isAllowed());
    }

    @Test
    @DisplayName("黑名单优先于白名单")
    void blacklistTakesPriority() {
        var validator = new UrlSecurityValidator(
                List.of("api.example.com"), List.of("api.example.com"));
        assertFalse(validator.validate("https://api.example.com/data").isAllowed());
    }

    @Test
    @DisplayName("validateOrNull 返回合适的值")
    void validateOrNull() {
        assertNull(validator.validateOrNull("http://93.184.216.34"));
        assertNotNull(validator.validateOrNull("http://localhost"));
    }
}
