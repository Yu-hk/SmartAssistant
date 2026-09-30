package com.example.smartassistant.intake.service.admin;

import com.fasterxml.jackson.databind.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.OffsetDateTime;
import java.util.*;

@Service
public class AdminProductIdentityService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final JdbcTemplate jdbc;
    public AdminProductIdentityService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public Map<String, Object> get(String code) {
        String key = code(code);
        return jdbc.query("SELECT product_code, product_name, identity_revision, identity_metadata::text FROM products WHERE product_code=?",
                rs -> {
                    if (!rs.next()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "商品不存在");
                    try { return Map.of("productCode", rs.getString(1), "productName", rs.getString(2),
                            "revision", rs.getLong(3), "metadata", JSON.convertValue(JSON.readTree(rs.getString(4)), Map.class)); }
                    catch (java.io.IOException invalid) { throw new IllegalStateException("Invalid stored identity", invalid); }
                }, key);
    }
    @Transactional
    public Map<String, Object> save(String code, JsonNode body, long actorId) {
        if (actorId <= 0) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "缺少管理员身份");
        String key = code(code);
        var update = ProductIdentityUpdate.parse(body);
        // Serializes parent graph changes, including opposite concurrent links.
        jdbc.execute("SELECT pg_advisory_xact_lock(73060930)");
        Map<String, Object> before = get(key);
        String parent = update.metadata().get("parentCode");
        if (!parent.isBlank()) {
            if (parent.equals(key)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "商品不能指向自身");
            if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM products WHERE product_code=?)", Boolean.class, parent)))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "父商品不存在");
            boolean cycle = Boolean.TRUE.equals(jdbc.queryForObject("""
                    WITH RECURSIVE ancestors AS (
                      SELECT product_code, identity_metadata->>'parentCode' parent FROM products WHERE product_code=?
                      UNION
                      SELECT p.product_code, p.identity_metadata->>'parentCode' FROM products p
                        JOIN ancestors a ON p.product_code=a.parent
                    ) SELECT EXISTS(SELECT 1 FROM ancestors WHERE product_code=?)
                    """, Boolean.class, parent, key));
            if (cycle) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "父商品关系不能形成循环");
        }
        Map<String, String> metadata = new LinkedHashMap<>(update.metadata());
        metadata.put("reviewedAt", OffsetDateTime.now(java.time.ZoneOffset.UTC).toString());
        metadata.put("reviewedBy", Long.toString(actorId));
        try {
            String after = JSON.writeValueAsString(metadata);
            int changed = jdbc.update("UPDATE products SET identity_metadata=?::jsonb, identity_revision=identity_revision+1 WHERE product_code=? AND identity_revision=?",
                    after, key, update.expectedRevision());
            if (changed != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "身份信息已变更，请重新读取");
            jdbc.update("INSERT INTO product_identity_audit(product_code, revision, actor_id, before_metadata, after_metadata) VALUES (?,?,?,?::jsonb,?::jsonb)",
                    key, update.expectedRevision() + 1, actorId, JSON.writeValueAsString(before.get("metadata")), after);
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new IllegalStateException("Identity serialization failed", invalid); }
        return get(key);
    }
    private static String code(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,49}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "商品编码无效");
        return value.toUpperCase(Locale.ROOT);
    }
}
