package com.example.smartassistant.consumer.service.admin;

import com.example.smartassistant.consumer.controller.AdminProductIntakeController;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.FileSystemResource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.Path;
import java.time.*;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminProductIntakeTest {
    @TempDir Path directory;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-14T10:00:00Z"), ZoneOffset.UTC);
    private JdbcTemplate connect() {
        return new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:file:" + directory.resolve("intake").toString().replace('\\','/') + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE", "sa", ""));
    }
    private JdbcTemplate initialize() {
        var jdbc = connect();
        jdbc.execute("CREATE TABLE products(product_code VARCHAR(50) PRIMARY KEY, product_name VARCHAR(200),price NUMERIC(10,2),stock VARCHAR(20),category VARCHAR(50),spec TEXT,color VARCHAR(200))");
        migrate(jdbc, "20260914_add_product_structured_features.sql");
        migrate(jdbc, "20260914_add_product_intake.sql");
        return jdbc;
    }
    private void migrate(JdbcTemplate jdbc, String file) {
        var path = Path.of(System.getProperty("basedir", ".")).resolve("../docs/database/migrations/" + file).normalize();
        new ResourceDatabasePopulator(new FileSystemResource(path)).execute(jdbc.getDataSource());
    }
    private AdminProductIntakeService service(JdbcTemplate jdbc, AdminProductFeatureService featureService) {
        var service = new AdminProductIntakeService(jdbc, featureService, new ProductFeatureExtractor(), CLOCK);
        var factory = new ProxyFactory(service);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(jdbc.getDataSource()), new AnnotationTransactionAttributeSource()));
        return (AdminProductIntakeService) factory.getProxy();
    }
    private AdminProductIntakeService service(JdbcTemplate jdbc) { return service(jdbc, new AdminProductFeatureService(jdbc, CLOCK)); }
    private ObjectNode body() throws Exception {
        return (ObjectNode) JSON.readTree("""
            {"productCode":"INTAKE-TEST-A","productName":"录入测试商品（虚构）","category":"笔记本电脑",
             "price":3999,"stock":"缺货","description":"整机净重1.2kg，视频播放续航12小时。",
             "spec":"不支持主动降噪。","color":"白色","featuresConfirmed":true}
            """);
    }

    @Test void intakeExtractsAndPersistsWithoutSeparateFeatureWriteByCaller() throws Exception {
        var jdbc = initialize();
        var result = service(jdbc).create(body(), 7);
        assertThat(result).containsEntry("revision", 1L).containsEntry("updatedBy", 7L);
        jdbc.execute("SHUTDOWN");
        var reopened = connect();
        var stored = new AdminProductFeatureService(reopened, CLOCK).get("INTAKE-TEST-A");
        assertThat(((Map<?, ?>) stored.get("features")).get("noiseCancelling")).isEqualTo(false);
        assertThat(((Map<?, ?>) stored.get("features")).get("batteryLifeScenario")).isEqualTo("video_playback");
        assertThat(reopened.queryForObject("SELECT weight_grams FROM products", java.math.BigDecimal.class)).isEqualByComparingTo("1200");
        assertThat(reopened.queryForObject("SELECT description FROM products", String.class)).contains("整机净重1.2kg");
        var audit = JSON.readTree(reopened.queryForObject("SELECT feature_ingestion_audit FROM products", String.class));
        assertThat(audit.path("externalVerification").booleanValue()).isFalse();
        assertThat(audit.path("manualOverrides").isEmpty()).isTrue();
        assertThat(audit.path("evidence").path("weightGrams").asText()).contains("1.2kg");
        assertThat(audit.path("sourceTextSha256").asText()).hasSize(64);
        migrate(reopened, "20260914_add_product_intake.sql");
        assertThat(reopened.queryForObject("SELECT count(*) FROM products", Integer.class)).isEqualTo(1);
        reopened.execute("SHUTDOWN");
    }

    @Test void previewNeverWritesAndCreateRecomputesFromCurrentText() throws Exception {
        var jdbc = initialize(); var service = service(jdbc);
        assertThat(service.preview(JSON.readTree("{\"description\":\"净重1200g\",\"spec\":\"\"}")).features().weightGrams()).isEqualByComparingTo("1200");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM products", Integer.class)).isZero();
        service.create(body().put("description", "净重1300g"), 7);
        assertThat(jdbc.queryForObject("SELECT weight_grams FROM products", Integer.class)).isEqualTo(1300);
    }

    @Test void explicitlyConfirmedManualCorrectionsAreAudited() throws Exception {
        var jdbc = initialize(); var input = body();
        input.set("features", JSON.readTree("{\"weightGrams\":1250,\"batteryLifeHours\":12,\"batteryLifeScenario\":\"video_playback\",\"noiseCancelling\":false}"));
        var result = service(jdbc).create(input, 8);
        assertThat(result.get("manualOverrides")).isEqualTo(java.util.List.of("weightGrams"));
        assertThat(jdbc.queryForObject("SELECT weight_grams FROM products", Integer.class)).isEqualTo(1250);
    }

    @Test void confirmationRequiredForKnownFactsButUnknownCanRemainNull() throws Exception {
        var jdbc = initialize(); var service = service(jdbc);
        assertThatThrownBy(() -> service.create(body().put("featuresConfirmed", false), 7)).isInstanceOf(ResponseStatusException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM products", Integer.class)).isZero();
        service.create(body().put("description", "轻便、续航长").put("spec", "5000mAh").put("featuresConfirmed", false), 7);
        assertThat(jdbc.queryForObject("SELECT weight_grams FROM products", Object.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT noise_cancelling FROM products", Object.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT features_verified_at FROM products", Object.class)).isNull();
    }

    @Test void duplicateCodeCannotOverwriteAndFeatureWriteFailureRollsBackBaseRow() throws Exception {
        var jdbc = initialize(); service(jdbc).create(body(), 7);
        assertThatThrownBy(() -> service(jdbc).create(body().put("productCode", "intake-test-a").put("price", 1), 7))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        assertThat(jdbc.queryForObject("SELECT price FROM products", Integer.class)).isEqualTo(3999);
        var broken = mock(AdminProductFeatureService.class);
        when(broken.save(anyString(), any(), anyLong())).thenThrow(new DataAccessResourceFailureException("private-storage-canary"));
        assertThatThrownBy(() -> service(jdbc, broken).create(body().put("productCode", "INTAKE-TEST-B"), 7)).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM products", Integer.class)).isEqualTo(1);
    }

    @Test void badPayloadsCannotWriteOrAssignServerAuditFields() throws Exception {
        var jdbc = initialize(); var service = service(jdbc);
        for (var input : java.util.List.of(body().put("price", -1), body().put("features_updated_by", 99),
                body().put("description", "x".repeat(10001)), body().put("featuresConfirmed", "true"), body().put("stock", "unknown"))) {
            assertThatThrownBy(() -> service.create(input, 7)).isInstanceOf(ResponseStatusException.class);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM products", Integer.class)).isZero();
    }

    @Test void httpRoutesAreAdminOnlyAndReturnPreviewAndCreatedProduct() throws Exception {
        var jdbc = initialize(); var mvc = MockMvcBuilders.standaloneSetup(new AdminProductIntakeController(service(jdbc))).build();
        for (String path : java.util.List.of("/api/admin/products", "/api/admin/products/extract-features")) {
            for (String role : java.util.List.of("", "ROLE_USER", "ADMIN", "ROLE_ADMIN,ROLE_USER"))
                mvc.perform(post(path).header("X-User-Role", role).header("X-User-Id", "7").contentType("application/json").content("{}"))
                    .andExpect(status().isForbidden());
            mvc.perform(post(path).header("X-User-Role", "ROLE_ADMIN").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        }
        mvc.perform(post("/api/admin/products/extract-features").header("X-User-Role", "ROLE_ADMIN").header("X-User-Id", "7")
            .contentType("application/json").content("{\"description\":\"净重1.2kg\",\"spec\":\"\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.features.weightGrams").value(1200));
        mvc.perform(post("/api/admin/products").header("X-User-Role", "ROLE_ADMIN").header("X-User-Id", "7")
            .contentType("application/json").content(body().toString()))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.features.noiseCancelling").value(false));
    }

    @Test void storageFailureReturns503WithoutLeakingDriverDetails() throws Exception {
        var service = mock(AdminProductIntakeService.class);
        when(service.create(any(), anyLong())).thenThrow(new DataAccessResourceFailureException("private-storage-canary"));
        var mvc = MockMvcBuilders.standaloneSetup(new AdminProductIntakeController(service)).build();
        mvc.perform(post("/api/admin/products").header("X-User-Role", "ROLE_ADMIN").header("X-User-Id", "7")
            .contentType("application/json").content(body().toString()))
            .andExpect(status().isServiceUnavailable()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-storage-canary"))));
    }
}
