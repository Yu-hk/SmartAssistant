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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Administrator-only suitability declarations; independent of measurable feature facts. */
@Service
public class AdminProductSuitabilityService {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Autowired
    public AdminProductSuitabilityService(JdbcTemplate jdbc) { this(jdbc, Clock.systemUTC()); }
    AdminProductSuitabilityService(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }

    public Map<String, Object> get(String productCode) {
        String code = code(productCode);
        List<Map<String, Object>> rows = jdbc.query("""
                SELECT product_code, product_name, suitability_revision, suitability_source,
                       suitability_reviewed_at, suitability_updated_by, suitability_updated_at
                  FROM products WHERE product_code = ?
                """, (rs, row) -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("productCode", rs.getString("product_code"));
            result.put("productName", rs.getString("product_name"));
            result.put("revision", rs.getLong("suitability_revision"));
            result.put("source", rs.getString("suitability_source"));
            result.put("reviewedAt", timestamp(rs.getObject("suitability_reviewed_at")));
            result.put("updatedBy", rs.getObject("suitability_updated_by", Long.class));
            result.put("updatedAt", timestamp(rs.getObject("suitability_updated_at")));
            return result;
        }, code);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "商品不存在");
        Map<String, Object> result = rows.getFirst();
        List<String> audiences = new ArrayList<>();
        List<String> useCases = new ArrayList<>();
        jdbc.query("SELECT kind, tag FROM product_suitability_tags WHERE product_code = ? ORDER BY kind, tag",
                rs -> {
                    if ("audience".equals(rs.getString("kind"))) audiences.add(rs.getString("tag"));
                    else useCases.add(rs.getString("tag"));
                }, code);
        result.put("audiences", List.copyOf(audiences));
        result.put("useCases", List.copyOf(useCases));
        return result;
    }

    @Transactional
    public Map<String, Object> save(String productCode, JsonNode body, long actorId) {
        if (actorId <= 0) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "缺少有效管理员身份");
        String code = code(productCode);
        ProductSuitabilityUpdate update = ProductSuitabilityUpdate.parse(body);
        OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
        int count = jdbc.update("""
                UPDATE products SET suitability_revision = suitability_revision + 1,
                       suitability_source = ?, suitability_reviewed_at = ?,
                       suitability_updated_by = ?, suitability_updated_at = ?
                 WHERE product_code = ? AND suitability_revision = ?
                """, update.source(), update.source() == null ? null : now,
                actorId, now, code, update.expectedRevision());
        if (count == 0) {
            get(code);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "适用标签已被其他操作修改，请重新读取后保存");
        }
        jdbc.update("DELETE FROM product_suitability_tags WHERE product_code = ?", code);
        for (String audience : update.audiences()) add(code, "audience", audience);
        for (String useCase : update.useCases()) add(code, "use_case", useCase);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("productCode", code);
        result.put("revision", update.expectedRevision() + 1);
        result.put("audiences", update.audiences());
        result.put("useCases", update.useCases());
        result.put("source", update.source());
        result.put("reviewedAt", update.source() == null ? null : now.toInstant().toString());
        result.put("updatedBy", actorId);
        result.put("updatedAt", now.toInstant().toString());
        return result;
    }

    private void add(String code, String kind, String tag) {
        jdbc.update("INSERT INTO product_suitability_tags(product_code, kind, tag) VALUES (?, ?, ?)", code, kind, tag);
    }

    private static String timestamp(Object value) {
        if (value == null) return null;
        if (value instanceof OffsetDateTime time) return time.toInstant().toString();
        if (value instanceof java.sql.Timestamp time) return time.toInstant().toString();
        return value.toString();
    }

    private static String code(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,49}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请提供有效的商品编码");
        }
        return value.toUpperCase(Locale.ROOT);
    }
}
