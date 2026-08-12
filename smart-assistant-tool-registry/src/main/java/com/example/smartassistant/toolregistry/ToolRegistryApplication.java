package com.example.smartassistant.toolregistry;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import com.example.smartassistant.common.correction.CorrectionService;
import com.example.smartassistant.common.gateway.tool.ToolGateway;
import com.example.smartassistant.common.gateway.tool.ToolRegistry;
import com.example.smartassistant.common.tool.GifCacheStore;

/**
 * Tool Registry 服务启动类。
 * <p>
 * 独立工具注册中心，端口 8088。
 * 统一管理所有工具的注册、查询、废弃、健康检查和依赖追踪。
 * </p>
 *
 * @author Yu-hk
 * @since 2026-07-10
 */
@SpringBootApplication
@ComponentScan({
        "com.example.smartassistant.toolregistry"
})
@Import({ToolRegistry.class, ToolGateway.class, CorrectionService.class, GifCacheStore.class})
public class ToolRegistryApplication {

    public static void main(String[] args) {
        SpringApplication.run(ToolRegistryApplication.class, args);
    }
}
