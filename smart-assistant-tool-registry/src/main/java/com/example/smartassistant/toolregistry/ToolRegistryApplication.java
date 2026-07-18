package com.example.smartassistant.toolregistry;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Tool Registry 服务启动类。
 * <p>
 * 独立工具注册中心，端口 8090。
 * 统一管理所有工具的注册、查询、废弃、健康检查和依赖追踪。
 * </p>
 *
 * @author Yu-hk
 * @since 2026-07-10
 */
@SpringBootApplication
public class ToolRegistryApplication {

    public static void main(String[] args) {
        SpringApplication.run(ToolRegistryApplication.class, args);
    }
}
