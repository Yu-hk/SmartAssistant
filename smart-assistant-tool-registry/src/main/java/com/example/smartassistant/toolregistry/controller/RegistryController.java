package com.example.smartassistant.toolregistry.controller;

import com.example.smartassistant.common.gateway.tool.ToolDefinition;
import com.example.smartassistant.common.gateway.tool.ToolStatus;
import com.example.smartassistant.toolregistry.config.RequestIdHolder;
import com.example.smartassistant.toolregistry.model.ApiResponse;
import com.example.smartassistant.toolregistry.model.HealthResult;
import com.example.smartassistant.toolregistry.model.ToolDependRecord;
import com.example.smartassistant.toolregistry.service.RegistryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 工具注册中心 REST API。
 * <p>
 * 提供工具的注册、查询、废弃、健康检查和依赖追踪功能。
 * </p>
 *
 * @author Yu-hk
 * @since 2026-07-10
 */
@RestController
@RequestMapping("/api/tools")
public class RegistryController {

    private static final Logger log = LoggerFactory.getLogger(RegistryController.class);

    private final RegistryService registryService;

    public RegistryController(RegistryService registryService) {
        this.registryService = registryService;
    }

    // ==================== 注册 API ====================

    /**
     * 注册工具。
     */
    @PostMapping("/register")
    public ApiResponse<Map<String, String>> register(@RequestBody ToolDefinition definition) {
        registryService.register(definition);
        log.info("[RegistryController][{}] POST /api/tools/register: name={}, version={}",
                RequestIdHolder.get(), definition.getName(), definition.getVersion());
        return ApiResponse.ok("注册成功",
                Map.of("name", definition.getName(), "version", definition.getVersion()));
    }

    /**
     * 批量注册工具。
     */
    @PostMapping("/register/batch")
    public ApiResponse<Integer> registerBatch(@RequestBody List<ToolDefinition> definitions) {
        registryService.registerAll(definitions);
        return ApiResponse.ok("批量注册成功", definitions.size());
    }

    // ==================== 查询 API ====================

    /**
     * 查询工具列表。支持按标签、状态、命名空间过滤。
     *
     * @param tags      标签列表（逗号分隔，如 "ORDER,READ_ONLY"）
     * @param status    状态（可选）
     * @param namespace 命名空间（可选）
     * @return 匹配的工具定义列表
     */
    @GetMapping
    public ApiResponse<List<ToolDefinition>> query(
            @RequestParam(required = false) String[] tags,
            @RequestParam(required = false) ToolStatus status,
            @RequestParam(required = false) String namespace) {
        List<ToolDefinition> result = registryService.query(tags, status, namespace);
        return ApiResponse.ok(result);
    }

    /**
     * 按名称获取工具详情。
     */
    @GetMapping("/{name}")
    public ApiResponse<ToolDefinition> getByName(@PathVariable String name) {
        Optional<ToolDefinition> def = registryService.get(name);
        return def.map(ApiResponse::ok)
                .orElse(ApiResponse.error(404, "工具未注册: " + name));
    }

    // ==================== 废弃 API ====================

    /**
     * 废弃工具。
     */
    @PostMapping("/deprecate")
    public ApiResponse<String> deprecate(@RequestBody DeprecateRequest request) {
        boolean success = registryService.deprecate(
                request.name, request.deprecatedBy, request.sunsetDate, request.reason);
        if (success) {
            log.info("[RegistryController][{}] POST /api/tools/deprecate: name={}, deprecatedBy={}",
                    RequestIdHolder.get(), request.name, request.deprecatedBy);
            return ApiResponse.ok("废弃成功，下线日期: " + request.sunsetDate, request.name);
        }
        return ApiResponse.error(404, "工具未注册: " + request.name);
    }

    /**
     * 启用工具（DEPRECATED/DISABLED → ACTIVE）。
     */
    @PostMapping("/{name}/activate")
    public ApiResponse<String> activate(@PathVariable String name) {
        boolean success = registryService.activate(name);
        if (success) {
            return ApiResponse.ok("启用成功", name);
        }
        return ApiResponse.error(404, "工具未注册: " + name);
    }

    // ==================== 健康检查 API ====================

    /**
     * 获取所有工具的健康状态。
     */
    @GetMapping("/health")
    public ApiResponse<HealthResult> health() {
        HealthResult result = registryService.getHealth();
        return ApiResponse.ok(result);
    }

    // ==================== 依赖追踪 API ====================

    /**
     * 获取工具的依赖者列表。
     */
    @GetMapping("/{name}/dependents")
    public ApiResponse<List<ToolDependRecord>> getDependents(@PathVariable String name) {
        List<ToolDependRecord> dependents = registryService.getDependents(name);
        return ApiResponse.ok(dependents);
    }

    /**
     * 记录工具调用（由 ToolGateway 回调，用于统计）。
     */
    @PostMapping("/call/record")
    public ApiResponse<String> recordCall(@RequestBody CallRecordRequest request) {
        registryService.recordCall(request.toolName, request.agentId,
                request.latencyMs, request.success);
        log.info("[RegistryController][{}] POST /api/tools/call/record: tool={}, agent={}, lat={}ms, success={}",
                RequestIdHolder.get(), request.toolName, request.agentId,
                request.latencyMs, request.success);
        return ApiResponse.ok("记录成功", request.toolName);
    }

    // ==================== 请求体 ====================

    /** 废弃请求 */
    public record DeprecateRequest(
            String name,
            String deprecatedBy,
            String sunsetDate,
            String reason
    ) {}

    /** 调用记录请求 */
    public record CallRecordRequest(
            String toolName,
            String agentId,
            long latencyMs,
            boolean success
    ) {}
}
