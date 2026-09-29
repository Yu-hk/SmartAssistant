package com.example.smartassistant.intake.service.admin;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Aliases are explicit catalog metadata, never inferred from arbitrary user text. */
@Service
public class AdminProductAliasService {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Autowired
    public AdminProductAliasService(JdbcTemplate jdbc) { this(jdbc, Clock.systemUTC()); }
    AdminProductAliasService(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }

    public Map<String, Object> get(String productCode) {
        String code = code(productCode);
        List<Map<String, Object>> rows = jdbc.query("""
                SELECT product_code, product_name, aliases_revision, aliases_updated_by, aliases_updated_at
                  FROM products WHERE product_code = ?
                """, (rs, row) -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("productCode", rs.getString("product_code"));
            result.put("productName", rs.getString("product_name"));
            result.put("revision", rs.getLong("aliases_revision"));
            result.put("updatedBy", rs.getObject("aliases_updated_by", Long.class));
            Object updatedAt = rs.getObject("aliases_updated_at");
            result.put("updatedAt", updatedAt == null ? null : updatedAt.toString());
            return result;
        }, code);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "商品不存在");
        Map<String, Object> result = rows.getFirst();
        result.put("aliases", jdbc.queryForList(
                "SELECT alias FROM product_aliases WHERE product_code = ? ORDER BY normalized_alias",
                String.class, code));
        return result;
    }

    @Transactional
    public Map<String, Object> save(String productCode, JsonNode body, long actorId) {
        if (actorId <= 0) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "缺少有效管理员身份");
        String code = code(productCode);
        ProductAliasUpdate update = ProductAliasUpdate.parse(body);
        String canonical = jdbc.query("SELECT product_name FROM products WHERE product_code = ?",
                rs -> rs.next() ? rs.getString(1) : null, code);
        if (canonical == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "商品不存在");
        if (update.aliases().stream().anyMatch(alias -> alias.equalsIgnoreCase(canonical))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "别名不能与商品名称相同");
        }
        OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
        int count = jdbc.update("""
                UPDATE products SET aliases_revision = aliases_revision + 1,
                       aliases_updated_by = ?, aliases_updated_at = ?
                 WHERE product_code = ? AND aliases_revision = ?
                """, actorId, now, code, update.expectedRevision());
        if (count == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "别名已被其他操作修改，请重新读取后保存");
        jdbc.update("DELETE FROM product_aliases WHERE product_code = ?", code);
        for (String alias : update.aliases()) {
            jdbc.update("INSERT INTO product_aliases(product_code, alias, normalized_alias) VALUES (?, ?, ?)",
                    code, alias, alias.toUpperCase(Locale.ROOT));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("productCode", code);
        result.put("productName", canonical);
        result.put("revision", update.expectedRevision() + 1);
        result.put("aliases", update.aliases());
        result.put("updatedBy", actorId);
        result.put("updatedAt", now.toInstant().toString());
        return result;
    }

    private static String code(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,49}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请提供有效的商品编码");
        }
        return value.toUpperCase(Locale.ROOT);
    }
}
