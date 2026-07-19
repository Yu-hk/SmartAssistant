package com.example.smartassistant.toolregistry;

import com.example.smartassistant.common.gateway.tool.ToolGateway;
import com.example.smartassistant.common.gateway.tool.ToolRegistry;
import com.example.smartassistant.common.tool.GifCacheStore;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

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
// P0 修复：tool-registry 组件扫描仅覆盖自身包，需显式引入 common 中的本地注册中心 Bean，
// 否则 DataGifTool 注入 ToolRegistry / ToolRegistryClient 会因缺少 Bean 而启动失败。
@Import({
        ToolRegistry.class,
        ToolGateway.class,
        GifCacheStore.class
})
public class ToolRegistryApplication {

    public static void main(String[] args) {
        SpringApplication.run(ToolRegistryApplication.class, args);
    }
}
