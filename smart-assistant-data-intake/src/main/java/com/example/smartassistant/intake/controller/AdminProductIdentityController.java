package com.example.smartassistant.intake.controller;

import com.example.smartassistant.intake.service.admin.AdminProductIdentityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

/** Trusted gateway strips forged identity headers, as for other intake admin endpoints. */
@RestController
@RequestMapping("/api/admin/products/{code}/identity")
public class AdminProductIdentityController {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final AdminProductIdentityService service;
    public AdminProductIdentityController(AdminProductIdentityService service) { this.service = service; }
    @GetMapping public Map<String, Object> get(@PathVariable String code,
            @RequestHeader(value="X-User-Role", required=false) String role,
            @RequestHeader(value="X-User-Id", required=false) Long actor) {
        admin(role, actor); return service.get(code);
    }
    @PutMapping public Map<String, Object> save(@PathVariable String code, @RequestBody Map<String, Object> body,
            @RequestHeader(value="X-User-Role", required=false) String role,
            @RequestHeader(value="X-User-Id", required=false) Long actor) {
        admin(role, actor); return service.save(code, JSON.valueToTree(body), actor);
    }
    @ExceptionHandler(DataAccessException.class) public ResponseEntity<Map<String, String>> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("code", "PRODUCT_IDENTITY_STORAGE_UNAVAILABLE",
                "message", "商品身份存储暂不可用，请确认数据库迁移状态"));
    }
    private static void admin(String role, Long actor) {
        if (!"ROLE_ADMIN".equals(role) || actor == null || actor <= 0)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要管理员权限");
    }
}
