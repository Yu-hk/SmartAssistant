package com.example.smartassistant.intake.controller;

import com.example.smartassistant.intake.service.admin.AdminProductSuitabilityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/** Administrator-maintained audience/use-case declarations, never an agent write tool. */
@RestController
@RequestMapping("/api/admin/products/{code}/suitability")
public class AdminProductSuitabilityController {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final AdminProductSuitabilityService service;

    public AdminProductSuitabilityController(AdminProductSuitabilityService service) { this.service = service; }

    @GetMapping
    public Map<String, Object> get(@PathVariable String code,
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestHeader(value = "X-User-Id", required = false) Long actorId) {
        requireAdmin(role, actorId);
        return service.get(code);
    }

    @PutMapping
    public Map<String, Object> save(@PathVariable String code, @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestHeader(value = "X-User-Id", required = false) Long actorId) {
        requireAdmin(role, actorId);
        return service.save(code, JSON.valueToTree(body), actorId);
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, String>> storageUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "code", "PRODUCT_SUITABILITY_STORAGE_UNAVAILABLE",
                "message", "商品适用标签存储暂不可用，请确认数据库连接及迁移状态后重试"));
    }

    private static void requireAdmin(String role, Long actorId) {
        if (!"ROLE_ADMIN".equals(role) || actorId == null || actorId <= 0) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要管理员权限");
        }
    }
}
