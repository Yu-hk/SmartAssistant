package com.example.smartassistant.intake.controller;

import com.example.smartassistant.intake.service.admin.AdminProductAliasService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/** Administrator-only product alias maintenance. */
@RestController
@RequestMapping("/api/admin/products/{code}/aliases")
public class AdminProductAliasController {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final AdminProductAliasService service;

    public AdminProductAliasController(AdminProductAliasService service) { this.service = service; }

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
                "code", "PRODUCT_ALIAS_STORAGE_UNAVAILABLE",
                "message", "商品别名存储暂不可用，请确认数据库连接及迁移状态后重试"));
    }

    private static void requireAdmin(String role, Long actorId) {
        if (!"ROLE_ADMIN".equals(role) || actorId == null || actorId <= 0) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要管理员权限");
        }
    }
}
