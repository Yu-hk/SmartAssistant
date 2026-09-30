package com.example.smartassistant.intake.service.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Real SQL and Spring transaction checks. Only an explicitly selected disposable test DB is allowed. */
@EnabledIfSystemProperty(named = "identity.pg.integration", matches = "true")
class AdminProductIdentityPgIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private AdminProductIdentityService service;
    private String schema;

    @Configuration @EnableTransactionManagement
    static class Transactions { }

    @BeforeEach void initialize() throws Exception {
        String url = Objects.requireNonNull(System.getenv("PG_TEST_URL"), "PG_TEST_URL required");
        if (!url.matches("jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/smartassistant_integration"))
            throw new IllegalArgumentException("Only a loopback disposable smartassistant_integration database is allowed");
        var ds = new DriverManagerDataSource(url, System.getenv("PG_TEST_USER"), System.getenv("PG_TEST_PASSWORD"));
        jdbc = new JdbcTemplate(ds);
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo("smartassistant_integration");
        schema = "identity_test_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE SCHEMA " + schema);
        ds.setUrl(url + "?currentSchema=" + schema);
        jdbc.execute("CREATE TABLE products(product_code VARCHAR(50) PRIMARY KEY, product_name VARCHAR(200) NOT NULL)");
        for (String key : List.of("QA-A", "QA-B", "QA-C")) jdbc.update("INSERT INTO products VALUES (?,?)", key, "Synthetic " + key);
        Path migration = Path.of(System.getProperty("basedir", ".")).resolve("../docs/database/migrations/20260930_add_product_identity.sql").normalize();
        if (!Files.exists(migration)) migration = Path.of("docs/database/migrations/20260930_add_product_identity.sql");
        jdbc.execute(Files.readString(migration));
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class);
        context.registerBean(JdbcTemplate.class, () -> jdbc);
        context.registerBean(DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(ds));
        context.registerBean(AdminProductIdentityService.class);
        context.refresh();
        service = context.getBean(AdminProductIdentityService.class);
    }
    @AfterEach void cleanup() {
        if (context != null) context.close();
        if (schema != null) jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
    }
    private Map<String, Object> save(String code, long revision, String parent) throws Exception {
        return service.save(code, JSON.readTree(JSON.writeValueAsString(Map.of("expectedRevision", revision,
                "brand", "Synthetic", "family", "QA", "variant", code, "parentCode", parent, "source", "Synthetic QA catalog"))), 7);
    }
    @Test void durableMetadataAndAtomicAudit() throws Exception {
        assertThat(save("qa-a", 0, "")).containsEntry("revision", 1L);
        var read = new AdminProductIdentityService(jdbc).get("QA-A");
        assertThat(((Map<?, ?>) read.get("metadata")).containsKey("reviewedAt")).isTrue();
        assertThat(jdbc.queryForObject("SELECT actor_id FROM product_identity_audit WHERE product_code='QA-A'", Long.class)).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT before_metadata::text FROM product_identity_audit WHERE product_code='QA-A'", String.class)).isEqualTo("{}");
    }
    @Test void staleRevisionNeverWritesOrAudits() throws Exception {
        save("QA-A", 0, "");
        assertThatThrownBy(() -> save("QA-A", 0, "")).isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(409));
        assertThat(service.get("QA-A")).containsEntry("revision", 1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product_identity_audit", Integer.class)).isEqualTo(1);
    }
    @Test void parentExistenceSelfAndCycleAreEnforced() throws Exception {
        for (String parent : List.of("QA-A", "QA-MISSING"))
            assertThatThrownBy(() -> save("QA-A", 0, parent)).isInstanceOf(ResponseStatusException.class);
        save("QA-A", 0, "QA-B");
        save("QA-B", 0, "QA-C");
        assertThatThrownBy(() -> save("QA-C", 0, "QA-A")).isInstanceOf(ResponseStatusException.class);
        assertThat(service.get("QA-C")).containsEntry("revision", 0L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product_identity_audit", Integer.class)).isEqualTo(2);
    }
    @Test void auditFailureRollsBackMetadataRevision() throws Exception {
        jdbc.execute("ALTER TABLE product_identity_audit ADD CONSTRAINT synthetic_audit_failure CHECK (actor_id <> 7)");
        assertThatThrownBy(() -> save("QA-A", 0, "")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(service.get("QA-A")).containsEntry("revision", 0L);
        assertThat((Map<?, ?>) service.get("QA-A").get("metadata")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product_identity_audit", Integer.class)).isZero();
    }
}
