/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.sql;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * URL 安全校验器——防止 SSRF 攻击。
 *
 * <p>参考 Snail AI 的 HttpTool 安全设计。校验规则：
 * <ul>
 *   <li>禁止内网 IP 地址（127.x, 10.x, 172.16-31.x, 192.168.x, 169.254.x）</li>
 *   <li>禁止 localhost 主机名</li>
 *   <li>禁止内部域名后缀（.internal, .local, .corp）</li>
 *   <li>禁止非 HTTP(S) 协议</li>
 *   <li>可选白名单/黑名单域名</li>
 * </ul>
 */
public class UrlSecurityValidator {

    private static final Logger log = LoggerFactory.getLogger(UrlSecurityValidator.class);

    /** 内网 IP 前缀 */
    private static final List<Pattern> PRIVATE_IP_PATTERNS = Arrays.asList(
            Pattern.compile("^127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$"),
            Pattern.compile("^10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$"),
            Pattern.compile("^172\\.(1[6-9]|2\\d|3[01])\\.\\d{1,3}\\.\\d{1,3}$"),
            Pattern.compile("^192\\.168\\.\\d{1,3}\\.\\d{1,3}$"),
            Pattern.compile("^169\\.254\\.\\d{1,3}\\.\\d{1,3}$"),
            Pattern.compile("^0\\.0\\.0\\.0$"),
            Pattern.compile("^127\\.0\\.0\\.1$")
    );

    /** 禁止的主机名 */
    private static final List<String> BLOCKED_HOSTS = Arrays.asList(
            "localhost", "127.0.0.1", "0.0.0.0", "::1", "[::1]"
    );

    /** 禁止的域名后缀 */
    private static final List<String> BLOCKED_DOMAIN_SUFFIXES = Arrays.asList(
            ".internal", ".local", ".corp", ".intranet",
            ".lan", ".private", ".test"
    );

    /** 禁止的协议 */
    private static final List<String> ALLOWED_PROTOCOLS = Arrays.asList("http", "https");

    /** 可选域名白名单（精确匹配） */
    private final List<String> whitelistDomains;

    /** 可选域名黑名单（精确匹配，在白名单之前检查） */
    private final List<String> blacklistDomains;

    public UrlSecurityValidator(List<String> whitelistDomains, List<String> blacklistDomains) {
        this.whitelistDomains = whitelistDomains != null ? whitelistDomains : List.of();
        this.blacklistDomains = blacklistDomains != null ? blacklistDomains : List.of();
    }

    public UrlSecurityValidator() {
        this(List.of(), List.of());
    }

    /**
     * 验证 URL 是否安全。
     *
     * @param url 待验证的 URL
     * @return ValidationResult 验证结果
     */
    public ValidationResult validate(String url) {
        if (url == null || url.isBlank()) {
            return ValidationResult.reject("URL 为空");
        }

        try {
            URI uri = new URI(url);

            // 1. 协议检查
            String scheme = uri.getScheme();
            if (scheme == null || !ALLOWED_PROTOCOLS.contains(scheme.toLowerCase())) {
                return ValidationResult.reject("不支持的协议: " + scheme
                        + "（仅允许 http/https）");
            }

            String host = uri.getHost();
            if (host == null) {
                return ValidationResult.reject("URL 缺少主机名");
            }

            String lowerHost = host.toLowerCase();

            // 2. 黑名单检查（优先于白名单）
            for (String blocked : blacklistDomains) {
                if (lowerHost.equals(blocked) || lowerHost.endsWith("." + blocked)) {
                    return ValidationResult.reject("域名在黑名单中: " + host);
                }
            }

            // 3. 白名单检查（如果配置了白名单，非白名单域名直接拒绝）
            if (!whitelistDomains.isEmpty()) {
                boolean inWhitelist = false;
                for (String allowed : whitelistDomains) {
                    if (lowerHost.equals(allowed) || lowerHost.endsWith("." + allowed)) {
                        inWhitelist = true;
                        break;
                    }
                }
                if (!inWhitelist) {
                    return ValidationResult.reject("域名不在白名单中: " + host);
                }
            }

            // 4. 禁止的主机名
            if (BLOCKED_HOSTS.contains(lowerHost)) {
                return ValidationResult.reject("禁止访问本地地址: " + host);
            }

            // 5. 禁止的内部域名后缀
            for (String suffix : BLOCKED_DOMAIN_SUFFIXES) {
                if (lowerHost.endsWith(suffix)) {
                    return ValidationResult.reject("禁止访问内部域名: " + host);
                }
            }

            // 6. 内网 IP 地址检测
            // 先尝试 IP 格式检测（不涉及 DNS），再 DNS 解析
            boolean isIpFormat = host.matches("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$");
            if (isIpFormat) {
                for (Pattern pattern : PRIVATE_IP_PATTERNS) {
                    if (pattern.matcher(host).matches()) {
                        return ValidationResult.reject("禁止访问内网地址: " + host);
                    }
                }
            } else {
                // 域名格式：DNS 解析后检查 IP
                try {
                    InetAddress inetAddr = InetAddress.getByName(host);
                    String ip = inetAddr.getHostAddress();
                    if (ip.startsWith("::ffff:")) ip = ip.substring(7);
                    for (Pattern pattern : PRIVATE_IP_PATTERNS) {
                        if (pattern.matcher(ip).matches()) {
                            return ValidationResult.reject("禁止访问内网地址: " + host + " -> " + ip);
                        }
                    }
                } catch (UnknownHostException e) {
                    log.warn("[UrlSecurityValidator] DNS 解析失败: host={}", host);
                    // DNS 不可解析时：如果有白名单则拒绝，无白名单则放行（可能为开发环境）
                    if (!whitelistDomains.isEmpty()) {
                        return ValidationResult.reject("无法解析主机名: " + host);
                    }
                }
            }

            return ValidationResult.ok();

        } catch (Exception e) {
            return ValidationResult.reject("URL 格式无效: " + e.getMessage());
        }
    }

    /**
     * 验证 URL，通过时返回 null，拒绝时返回拒绝原因。
     */
    public String validateOrNull(String url) {
        ValidationResult result = validate(url);
        return result.isAllowed() ? null : result.reason();
    }

    /**
     * 验证结果。
     */
    public record ValidationResult(boolean isAllowed, String reason) {
        public static ValidationResult ok() {
            return new ValidationResult(true, null);
        }
        public static ValidationResult reject(String reason) {
            return new ValidationResult(false, reason);
        }
    }
}
