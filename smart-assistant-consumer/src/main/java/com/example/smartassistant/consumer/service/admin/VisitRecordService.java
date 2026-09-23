package com.example.smartassistant.consumer.service.admin;

import jakarta.annotation.PostConstruct;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

@Service
public class VisitRecordService {
    public record Event(String eventId, String visitorId, String module) {}
    private final JdbcTemplate jdbc;
    private final VisitModuleCatalog catalog;
    private final int retentionDays;
    private final ZoneId zone;

    public VisitRecordService(JdbcTemplate jdbc, VisitModuleCatalog catalog,
            @Value("${visits.retention-days:90}") int retentionDays,
            @Value("${visits.time-zone:Asia/Shanghai}") String timeZone) {
        this.jdbc = jdbc; this.catalog = catalog;
        this.retentionDays = Math.max(1, Math.min(365, retentionDays));
        this.zone = ZoneId.of(timeZone);
    }

    @PostConstruct
    public void initialize() {
        new ResourceDatabasePopulator(new ClassPathResource("db/visit-records.sql"))
                .execute(Objects.requireNonNull(jdbc.getDataSource()));
    }

    public void record(Event event, Long userId, String role, String userAgent) {
        if (event == null) throw new IllegalArgumentException("缺少访问记录");
        String eventId = uuid(event.eventId()), visitorId = uuid(event.visitorId());
        var module = catalog.require(event.module());
        boolean admin = "ROLE_ADMIN".equals(role);
        if ((!module.audience().equals("public") && (userId == null || userId <= 0))
                || (module.audience().equals("admin") && !admin)
                || (module.audience().equals("customer") && admin)) {
            throw new IllegalArgumentException("访问身份与功能模块不匹配");
        }
        String identity = userId == null ? "GUEST" : admin ? "ROLE_ADMIN" : "ROLE_USER";
        String agent = userAgent == null ? "" : userAgent.substring(0, Math.min(512, userAgent.length())).toLowerCase(Locale.ROOT);
        String browser = agent.contains("edg/") ? "Edge" : agent.contains("firefox/") ? "Firefox"
                : agent.contains("chrome/") || agent.contains("crios/") ? "Chrome"
                : agent.contains("safari/") ? "Safari" : "其他";
        String device = agent.contains("mobile") || agent.contains("android") ? "移动端" : "桌面端";
        try {
            jdbc.update("INSERT INTO site_visit_event (event_id, visitor_id, user_id, user_role, module_code, event_kind, browser, device, created_at) VALUES (?,?,?,?,?,?,?,?,?)",
                    eventId, visitorId, userId, identity, module.code(), module.kind(), browser, device, Timestamp.from(Instant.now()));
        } catch (DuplicateKeyException duplicate) {
            // Replayed event IDs do not increase any visit counter.
        }
    }

    public Map<String, Object> query(String view, LocalDate from, LocalDate to, String module,
            String identity, String search, String visitor, int page, int size) {
        if (!List.of("visitors", "events").contains(view) || page < 0 || page > 10000 || size < 1 || size > 100)
            throw new IllegalArgumentException("分页参数无效");
        LocalDate end = to == null ? LocalDate.now(zone) : to;
        LocalDate start = from == null ? end.minusDays(6) : from;
        if (start.isAfter(end) || start.plusDays(366).isBefore(end)) throw new IllegalArgumentException("请选择不超过一年的有效时间范围");
        List<Object> parameters = new ArrayList<>();
        parameters.add(Timestamp.from(start.atStartOfDay(zone).toInstant()));
        parameters.add(Timestamp.from(end.plusDays(1).atStartOfDay(zone).toInstant()));
        StringBuilder where = new StringBuilder(" FROM site_visit_event v LEFT JOIN users u ON u.id=v.user_id WHERE v.created_at >= ? AND v.created_at < ?");
        if (module != null && !module.isBlank()) { catalog.require(module); where.append(" AND v.module_code=?"); parameters.add(module); }
        if (identity != null && !identity.isBlank()) {
            String role = switch (identity) { case "guest" -> "GUEST"; case "user" -> "ROLE_USER"; case "admin" -> "ROLE_ADMIN"; default -> throw new IllegalArgumentException("身份筛选无效"); };
            where.append(" AND v.user_role=?"); parameters.add(role);
        }
        if (visitor != null && !visitor.isBlank()) { where.append(" AND v.visitor_id=?"); parameters.add(uuid(visitor)); }
        if (search != null && !search.isBlank()) {
            if (search.length() > 80) throw new IllegalArgumentException("搜索内容过长");
            String pattern = "%" + search.trim().toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
            where.append(" AND (LOWER(u.username) LIKE ? ESCAPE '!' OR LOWER(v.visitor_id) LIKE ? ESCAPE '!')");
            parameters.add(pattern); parameters.add(pattern);
        }
        Object[] args = parameters.toArray();
        String grouped = "SELECT v.visitor_id, v.user_id" + where + " GROUP BY v.visitor_id, v.user_id";
        long visitors = count("SELECT COUNT(*) FROM (" + grouped + ") visitors", args);
        long views = count("SELECT COUNT(*)" + where, args);
        List<Map<String, Object>> modules = jdbc.query("SELECT v.module_code, COUNT(*) AS views, COUNT(DISTINCT v.visitor_id) AS visitors" + where + " GROUP BY v.module_code ORDER BY views DESC, v.module_code",
                (rs, n) -> Map.<String, Object>of("code", rs.getString("module_code"), "label", catalog.require(rs.getString("module_code")).label(), "views", rs.getLong("views"), "visitors", rs.getLong("visitors")), args);
        String select = view.equals("events")
                ? "SELECT v.*, u.username" + where + " ORDER BY v.created_at DESC, v.event_id DESC"
                : "SELECT v.visitor_id, v.user_id, u.username, v.user_role, MIN(v.created_at) AS first_seen, MAX(v.created_at) AS last_seen, COUNT(*) AS views, COUNT(DISTINCT v.module_code) AS modules" + where
                    + " GROUP BY v.visitor_id,v.user_id,u.username,v.user_role ORDER BY last_seen DESC,v.visitor_id,v.user_id";
        parameters.add(size); parameters.add(page * size);
        List<Map<String, Object>> items = jdbc.query(select + " LIMIT ? OFFSET ?", (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("visitorId", rs.getString("visitor_id")); row.put("userId", rs.getObject("user_id"));
            row.put("username", rs.getString("username")); row.put("role", rs.getString("user_role"));
            if (view.equals("events")) {
                row.put("id", rs.getString("event_id")); row.put("module", catalog.require(rs.getString("module_code")).label());
                row.put("kind", rs.getString("event_kind")); row.put("browser", rs.getString("browser")); row.put("device", rs.getString("device"));
                row.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
            } else {
                row.put("firstSeen", rs.getTimestamp("first_seen").toInstant().toString()); row.put("lastSeen", rs.getTimestamp("last_seen").toInstant().toString());
                row.put("views", rs.getLong("views")); row.put("modules", rs.getLong("modules"));
            }
            return row;
        }, parameters.toArray());
        return Map.of("items", items, "total", view.equals("events") ? views : visitors, "page", page, "size", size,
                "summary", Map.of("views", views, "visitors", visitors, "modules", modules.size()), "modules", modules, "retentionDays", retentionDays);
    }

    @Scheduled(fixedDelayString = "${visits.cleanup-interval-ms:86400000}", initialDelay = 60000)
    public void cleanup() {
        try { jdbc.update("DELETE FROM site_visit_event WHERE created_at < ?", Timestamp.from(Instant.now().minus(Duration.ofDays(retentionDays)))); }
        catch (Exception error) { LoggerFactory.getLogger(getClass()).warn("Visit record retention cleanup failed: {}", error.getClass().getSimpleName()); }
    }
    private long count(String sql, Object[] args) { Long value = jdbc.queryForObject(sql, Long.class, args); return value == null ? 0 : value; }
    private static String uuid(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) throw new IllegalArgumentException("访问标识无效");
        return UUID.fromString(value).toString();
    }
}
