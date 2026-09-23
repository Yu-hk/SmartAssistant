package com.example.smartassistant.consumer.service.admin;

import com.example.smartassistant.consumer.controller.VisitRecordController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class VisitRecordServiceTest {
    JdbcTemplate jdbc;
    VisitRecordService service;
    VisitModuleCatalog catalog;
    String visitor;
    @BeforeEach void setup() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:visits" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE users(id BIGINT PRIMARY KEY, username VARCHAR(100))");
        jdbc.update("INSERT INTO users VALUES(1,'alice'),(2,'administrator')");
        catalog = new VisitModuleCatalog(); service = new VisitRecordService(jdbc, catalog, 90, "Asia/Shanghai");
        service.initialize(); visitor = UUID.randomUUID().toString();
    }
    VisitRecordService.Event event(String module) { return new VisitRecordService.Event(UUID.randomUUID().toString(), visitor, module); }
    Map<String,Object> query(String view, String module, String identity, String search, String target, int page, int size) {
        return service.query(view, null, null, module, identity, search, target, page, size);
    }
    @SuppressWarnings("unchecked")
    @Test void recordsAnonymousAndAuthenticatedVisitsWithIdempotencyAndStablePagination() {
        var login = event("login"); service.record(login, null, null, "Mozilla Chrome/1 Edg/1"); service.record(login, null, null, "Mozilla");
        service.record(event("workbench"), 1L, "ROLE_USER", "Chrome/1");
        service.record(event("orders"), 1L, "ROLE_USER", "Mobile Safari/1");
        assertThat(query("events", null, null, null, null, 0, 1).get("total")).isEqualTo(3L);
        var visitors = query("visitors", null, null, null, null, 0, 20);
        assertThat(visitors.get("total")).isEqualTo(2L);
        assertThat((List<?>) visitors.get("items")).hasSize(2);
        assertThat(query("events", "orders", "user", "alice", visitor, 0, 20).get("total")).isEqualTo(1L);
        assertThat(query("events", null, "user", "%", null, 0, 20).get("total")).isEqualTo(0L);
        var guest = (List<Map<String,Object>>) query("events", null, "guest", null, null, 0, 20).get("items");
        assertThat(guest.getFirst()).containsEntry("browser", "Edge").containsEntry("userId", null).containsEntry("kind", "PAGE_VIEW");
        var first = (List<Map<String,Object>>) query("events", null, null, null, null, 0, 1).get("items");
        var second = (List<Map<String,Object>>) query("events", null, null, null, null, 1, 1).get("items");
        assertThat(first.getFirst().get("id")).isNotEqualTo(second.getFirst().get("id"));
    }
    @Test void refusesForgedAudienceUnknownModulesAndInvalidBounds() {
        assertThatThrownBy(() -> service.record(event("admin-overview"), 1L, "ROLE_USER", "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.record(event("workbench"), null, null, "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.record(event("unknown"), null, null, "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query("events", null, null, null, null, -1, 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.query("events", LocalDate.now(), LocalDate.now().minusDays(1), null, null, null, null, 0, 20)).isInstanceOf(IllegalArgumentException.class);
        var controller = new VisitRecordController(service, catalog);
        assertThatThrownBy(() -> controller.query(1L, "ROLE_USER", "visitors", null, null, null, null, null, null, 0, 20)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.query(null, "ROLE_ADMIN", "visitors", null, null, null, null, null, null, 0, 20)).isInstanceOf(ResponseStatusException.class);
        assertThat(controller.query(2L, "ROLE_ADMIN", "events", null, null, null, null, null, null, 0, 20).get("total")).isEqualTo(0L);
    }
    @Test void retentionOnlyRemovesExpiredVisitRecords() {
        service.record(event("login"), null, null, "");
        jdbc.update("UPDATE site_visit_event SET created_at=?", Timestamp.from(Instant.now().minus(Duration.ofDays(91))));
        service.record(event("workbench"), 1L, "ROLE_USER", "");
        service.cleanup();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM site_visit_event", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class)).isEqualTo(2L);
    }
}
