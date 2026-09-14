package com.example.smartassistant.consumer.controller;

import com.example.smartassistant.consumer.service.admin.AdminProductFeatureService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

/** Behind the authenticated gateway, like the other /api/admin endpoints; never exposed as an agent tool. */
@RestController
@RequestMapping("/api/admin/products/{code}/features")
public class AdminProductFeatureController {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final AdminProductFeatureService service;
    public AdminProductFeatureController(AdminProductFeatureService service) { this.service = service; }

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
        // Use a plain transport map: the application has Jackson 3 HTTP converters and
        // Jackson 2 domain utilities; a Jackson 2 tree is not an HTTP DTO for Jackson 3.
        return service.save(code, JSON.valueToTree(body), actorId);
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, String>> storageUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "code", "PRODUCT_FEATURE_STORAGE_UNAVAILABLE",
                "message", "商品参数存储暂不可用，请确认数据库连接及迁移状态后重试"));
    }

    private static void requireAdmin(String role, Long actorId) {
        if (!"ROLE_ADMIN".equals(role) || actorId == null || actorId <= 0) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要管理员权限");
        }
    }
}
