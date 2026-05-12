/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.dotenv;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.io.FileSystemResource;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Spring Boot 环境变量后处理器
 * <p>
 * 在应用启动时自动加载项目根目录下的 {@code .env} 文件，
 * 将其中的 {@code KEY=VALUE} 键值对注入 Spring 环境。
 * 优先级高于 application.yml，低于 OS 环境变量。
 */
@Order(Ordered.LOWEST_PRECEDENCE - 100)
public class DotenvEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(DotenvEnvironmentPostProcessor.class);

    private static final String[] SEARCH_PATHS = {
            ".env",
            "../.env"
    };

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String userDir = System.getProperty("user.dir");
        log.info("[Dotenv] 搜索 .env: baseDir={}", userDir);

        Properties props = new Properties();
        String foundPath = null;

        for (String relPath : SEARCH_PATHS) {
            File file = new File(userDir, relPath);
            if (file.exists() && file.isFile()) {
                foundPath = file.getAbsolutePath();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(new java.io.FileInputStream(file), StandardCharsets.UTF_8))) {
                    String line;
                    int lineNum = 0;
                    while ((line = reader.readLine()) != null) {
                        lineNum++;
                        String trimmed = line.trim();
                        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                            continue;
                        }
                        int eqIdx = trimmed.indexOf('=');
                        if (eqIdx <= 0) {
                            log.trace("[Dotenv] 跳过无效行 {}: {}", lineNum, trimmed);
                            continue;
                        }
                        String key = trimmed.substring(0, eqIdx).trim();
                        String value = trimmed.substring(eqIdx + 1).trim();
                        if (environment.containsProperty(key)) {
                            log.trace("[Dotenv] 跳过已有变量: {}", key);
                            continue;
                        }
                        props.setProperty(key, value);
                    }
                    break;
                } catch (IOException e) {
                    log.warn("[Dotenv] 读取失败: {}", e.getMessage());
                }
            }
        }

        if (props.isEmpty()) {
            log.info("[Dotenv] .env 文件未找到或无有效变量，跳过加载");
            return;
        }

        MutablePropertySources sources = environment.getPropertySources();
        sources.addAfter("systemEnvironment",
                new PropertiesPropertySource("dotenv", props));

        log.info("[Dotenv] ✅ 已加载 {} ({} 个变量)", foundPath, props.size());
    }
}
