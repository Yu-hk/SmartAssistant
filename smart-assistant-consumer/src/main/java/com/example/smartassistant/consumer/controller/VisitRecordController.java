package com.example.smartassistant.consumer.controller;

import com.example.smartassistant.consumer.service.admin.VisitModuleCatalog;
import com.example.smartassistant.consumer.service.admin.VisitRecordService;
import org.springframework.dao.DataAccessException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api")
public class VisitRecordController {
    private final VisitRecordService service;
    private final VisitModuleCatalog catalog;
    public VisitRecordController(VisitRecordService service, VisitModuleCatalog catalog) { this.service = service; this.catalog = catalog; }

    @GetMapping("/public/visit-modules")
    public List<VisitModuleCatalog.Module> modules() { return catalog.all(); }

    @PostMapping("/public/visits")
    public ResponseEntity<Void> anonymous(@RequestBody VisitRecordService.Event event,
            @RequestHeader(value="User-Agent", required=false) String agent) {
        service.record(event, null, null, agent);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/visits")
    public ResponseEntity<Void> authenticated(@RequestBody VisitRecordService.Event event,
            @RequestHeader(value="X-User-Id", required=false) Long userId,
            @RequestHeader(value="X-User-Role", required=false) String role,
            @RequestHeader(value="User-Agent", required=false) String agent) {
        if (userId == null || userId <= 0 || !List.of("ROLE_USER", "ROLE_ADMIN").contains(role == null ? "" : role))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        service.record(event, userId, role, agent);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/admin/visits")
    public Map<String, Object> query(@RequestHeader(value="X-User-Id", required=false) Long userId,
            @RequestHeader(value="X-User-Role", required=false) String role,
            @RequestParam(defaultValue="visitors") String view,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required=false) String module, @RequestParam(required=false) String identity,
            @RequestParam(required=false) String query, @RequestParam(required=false) String visitor,
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) {
        if (!"ROLE_ADMIN".equals(role) || userId == null || userId <= 0) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要管理员权限");
        return service.query(view, from, to, module, identity, query, visitor, page, size);
    }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> invalid(IllegalArgumentException error) { return ResponseEntity.badRequest().body(Map.of("message", error.getMessage())); }
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<?> unavailable() { return ResponseEntity.status(503).body(Map.of("message", "访问记录暂时无法读取，请稍后重试")); }
}
