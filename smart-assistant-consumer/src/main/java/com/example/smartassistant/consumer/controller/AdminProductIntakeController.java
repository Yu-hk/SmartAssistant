package com.example.smartassistant.consumer.controller;

import com.example.smartassistant.consumer.service.admin.AdminProductIntakeService;
import com.example.smartassistant.consumer.service.admin.ProductFeatureExtractor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/products")
public class AdminProductIntakeController {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final AdminProductIntakeService service;
    public AdminProductIntakeController(AdminProductIntakeService service) { this.service = service; }

    @PostMapping("/extract-features")
    public ProductFeatureExtractor.Extraction preview(@RequestBody Map<String, Object> body,
            @RequestHeader(value="X-User-Role", required=false) String role,
            @RequestHeader(value="X-User-Id", required=false) Long actorId) {
        requireAdmin(role, actorId);
        return service.preview(JSON.valueToTree(body));
    }
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body,
            @RequestHeader(value="X-User-Role", required=false) String role,
            @RequestHeader(value="X-User-Id", required=false) Long actorId) {
        requireAdmin(role, actorId);
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(JSON.valueToTree(body), actorId));
    }
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, String>> storageUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "code", "PRODUCT_INTAKE_STORAGE_UNAVAILABLE", "message", "商品录入存储暂不可用，请确认迁移状态；若上次提交结果不确定，请先按商品编码查询，勿重复录入"));
    }
    private static void requireAdmin(String role, Long id) {
        if (!"ROLE_ADMIN".equals(role) || id == null || id <= 0) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要管理员权限");
    }
}
