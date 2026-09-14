package com.example.smartassistant.consumer.service.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.Executors;
import static org.assertj.core.api.Assertions.*;

class AdminProductFeaturePersistenceTest {
    @TempDir Path directory;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-14T10:00:00Z"), ZoneOffset.UTC);

    private JdbcTemplate connect() {
        String url = "jdbc:h2:file:" + directory.resolve("product-features").toAbsolutePath().toString().replace('\\', '/')
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE";
        return new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
    }

    private JdbcTemplate initialize() throws Exception {
        JdbcTemplate jdbc = connect();
        jdbc.execute("CREATE TABLE products(product_code VARCHAR(50) PRIMARY KEY, product_name VARCHAR(200), price NUMERIC(10,2))");
        jdbc.update("INSERT INTO products VALUES (?, ?, ?)", "FEATURE-TEST-A", "持久化测试商品（虚构）", 1999);
        migrate(jdbc);
        return jdbc;
    }

    private static void migrate(JdbcTemplate jdbc) throws Exception {
        Path base = Path.of(System.getProperty("basedir", ".")).toAbsolutePath();
        Path migration = base.resolve("../docs/database/migrations/20260914_add_product_structured_features.sql").normalize();
        if (!Files.exists(migration)) migration = base.resolve("docs/database/migrations/20260914_add_product_structured_features.sql");
        // Execute the canonical migration unchanged, not a test-only approximation of the schema.
        String sql = Files.readString(migration);
        new ResourceDatabasePopulator(new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8))).execute(jdbc.getDataSource());
    }

    private static ObjectNode body(long revision) throws Exception {
        ObjectNode body = (ObjectNode) JSON.readTree("""
                {"expectedRevision":0,"features":{"weightGrams":1200.125,"batteryLifeHours":12.5,
                 "batteryLifeScenario":"video_playback","noiseCancelling":false,
                 "source":"synthetic-test-source-not-a-real-product","verifiedAt":"2026-09-14T09:00:00+08:00"}}
                """);
        body.put("expectedRevision", revision);
        return body;
    }

    @Test
    void httpSaveAndReadUseTheSameDurableCatalog() throws Exception {
        JdbcTemplate jdbc = initialize();
        var service = new AdminProductFeatureService(jdbc, CLOCK);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                new com.example.smartassistant.consumer.controller.AdminProductFeatureController(service)).build();
        String path = "/api/admin/products/FEATURE-TEST-A/features";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path)
                .header("X-User-Role", "ROLE_ADMIN").header("X-User-Id", "7")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body(0).toString()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.revision").value(1));
        jdbc.execute("SHUTDOWN");
        var newMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                new com.example.smartassistant.consumer.controller.AdminProductFeatureController(
                        new AdminProductFeatureService(connect(), CLOCK))).build();
        newMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                .header("X-User-Role", "ROLE_ADMIN").header("X-User-Id", "7"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.features.weightGrams").value(1200.125))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.features.noiseCancelling").value(false))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.features.verifiedAt").value("2026-09-14T01:00:00Z"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void valuesSurviveDatabaseShutdownAndNewServiceInstance() throws Exception {
        JdbcTemplate first = initialize();
        var writer = new AdminProductFeatureService(first, CLOCK);
        Map<String, Object> committed = writer.save("feature-test-a", body(0), 7);
        assertThat(committed.get("revision")).isEqualTo(1L);
        first.execute("SHUTDOWN");

        JdbcTemplate reopened = connect();
        var reader = new AdminProductFeatureService(reopened, CLOCK);
        Map<String, Object> actual = reader.get("FEATURE-TEST-A");
        Map<String, Object> features = (Map<String, Object>) actual.get("features");
        assertThat((java.math.BigDecimal) features.get("weightGrams")).isEqualByComparingTo("1200.125");
        assertThat((java.math.BigDecimal) features.get("batteryLifeHours")).isEqualByComparingTo("12.5");
        assertThat(features).containsEntry("batteryLifeScenario", "video_playback")
                .containsEntry("noiseCancelling", false).containsEntry("verifiedAt", "2026-09-14T01:00:00Z")
                .containsEntry("source", "synthetic-test-source-not-a-real-product");
        assertThat(actual).containsEntry("revision", 1L).containsEntry("updatedBy", 7L)
                .containsEntry("updatedAt", "2026-09-14T10:00:00Z");
        // Product's existing feature SQL reads these exact persisted column names.
        assertThat(reopened.queryForObject("SELECT weight_grams FROM products WHERE product_code = ?",
                java.math.BigDecimal.class, "FEATURE-TEST-A")).isEqualByComparingTo("1200.125");
        assertThat(Files.exists(directory.resolve("product-features.mv.db"))).isTrue();
        reopened.execute("SHUTDOWN");
    }

    @Test
    void migrationCanRunTwiceWithoutChangingExistingProducts() throws Exception {
        JdbcTemplate jdbc = initialize();
        migrate(jdbc);
        var data = new AdminProductFeatureService(jdbc, CLOCK).get("FEATURE-TEST-A");
        assertThat(data).containsEntry("revision", 0L).containsEntry("updatedBy", null);
        assertThat(jdbc.queryForObject("SELECT price FROM products", Integer.class)).isEqualTo(1999);
        assertThat((Map<?, ?>) data.get("features")).allSatisfy((key, value) -> assertThat(value).isNull());
    }

    @Test
    void explicitNullClearsParametersDurablyWithoutTurningUnknownIntoFalse() throws Exception {
        JdbcTemplate jdbc = initialize();
        var service = new AdminProductFeatureService(jdbc, CLOCK);
        service.save("FEATURE-TEST-A", body(0), 7);
        ObjectNode clear = body(1);
        ObjectNode features = (ObjectNode) clear.get("features");
        java.util.List<String> keys = new java.util.ArrayList<>();
        features.fieldNames().forEachRemaining(keys::add);
        keys.forEach(features::putNull);
        service.save("FEATURE-TEST-A", clear, 8);
        jdbc.execute("SHUTDOWN");
        var loaded = new AdminProductFeatureService(connect(), CLOCK).get("FEATURE-TEST-A");
        assertThat(loaded).containsEntry("revision", 2L).containsEntry("updatedBy", 8L);
        assertThat((Map<?, ?>) loaded.get("features")).allSatisfy((key, value) -> assertThat(value).isNull());
    }

    @Test
    void staleRevisionCannotOverwriteSavedFeatures() throws Exception {
        var service = new AdminProductFeatureService(initialize(), CLOCK);
        service.save("FEATURE-TEST-A", body(0), 7);
        assertThatThrownBy(() -> service.save("FEATURE-TEST-A", body(0), 8))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(409));
        assertThat(service.get("FEATURE-TEST-A")).containsEntry("updatedBy", 7L).containsEntry("revision", 1L);
    }

    @Test
    void twoConcurrentWritersProduceOneCommitAndOneConflict() throws Exception {
        var service = new AdminProductFeatureService(initialize(), CLOCK);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var writes = java.util.List.<java.util.concurrent.Callable<Integer>>of(
                    () -> saveStatus(service, 7), () -> saveStatus(service, 8));
            var results = workers.invokeAll(writes);
            assertThat(java.util.List.of(results.get(0).get(), results.get(1).get())).containsExactlyInAnyOrder(200, 409);
        }
        assertThat(service.get("FEATURE-TEST-A")).containsEntry("revision", 1L);
    }

    private static int saveStatus(AdminProductFeatureService service, long actor) throws Exception {
        try { service.save("FEATURE-TEST-A", body(0), actor); return 200; }
        catch (ResponseStatusException e) { return e.getStatusCode().value(); }
    }

    @ParameterizedTest
    @ValueSource(strings={"negativeWeight", "weightPrecision", "weightString", "unknownScenario", "missingScenario",
            "missingSource", "futureDate", "invalidDate", "booleanString", "unknownField", "omittedField", "missingRevision"})
    void invalidUpdatesNeverAlterTheDatabase(String mutation) throws Exception {
        var service = new AdminProductFeatureService(initialize(), CLOCK);
        ObjectNode data = body(0);
        ObjectNode fields = (ObjectNode) data.get("features");
        switch (mutation) {
            case "negativeWeight" -> fields.put("weightGrams", -1);
            case "weightPrecision" -> fields.put("weightGrams", new java.math.BigDecimal("12.3456"));
            case "weightString" -> fields.put("weightGrams", "1200");
            case "unknownScenario" -> fields.put("batteryLifeScenario", "standby");
            case "missingScenario" -> fields.putNull("batteryLifeScenario");
            case "missingSource" -> fields.putNull("source");
            case "futureDate" -> fields.put("verifiedAt", "2099-01-01T00:00:00Z");
            case "invalidDate" -> fields.put("verifiedAt", "yesterday");
            case "booleanString" -> fields.put("noiseCancelling", "false");
            case "unknownField" -> fields.put("isAdmin", true);
            case "omittedField" -> fields.remove("noiseCancelling");
            case "missingRevision" -> data.remove("expectedRevision");
        }
        assertThatThrownBy(() -> service.save("FEATURE-TEST-A", data, 7))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
        assertThat(service.get("FEATURE-TEST-A")).containsEntry("revision", 0L);
    }

    @Test
    void unknownCodeIsNotUpsertedAndInvalidCodeIsNeverExecuted() throws Exception {
        JdbcTemplate jdbc = initialize();
        var service = new AdminProductFeatureService(jdbc, CLOCK);
        assertThatThrownBy(() -> service.save("MISSING", body(0), 7))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
        assertThatThrownBy(() -> service.get("A' OR 1=1 --")).isInstanceOf(ResponseStatusException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM products", Integer.class)).isEqualTo(1);
    }
}
