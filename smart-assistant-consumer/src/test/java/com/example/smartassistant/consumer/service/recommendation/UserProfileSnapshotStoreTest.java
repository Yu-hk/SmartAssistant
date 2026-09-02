package com.example.smartassistant.consumer.service.recommendation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserProfileSnapshotStoreTest {

    @Test
    void keepReturnsExistingSnapshotWithoutWriting() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UserProfileSnapshotStore.Snapshot existing = snapshot(42L, 3L, "{\"saved\":true}");
        when(jdbc.query(anyString(), any(RowMapper.class), eq(42L)))
                .thenReturn(List.of(existing));
        UserProfileSnapshotStore store = new UserProfileSnapshotStore(jdbc, new ObjectMapper());

        UserProfileSnapshotStore.Snapshot result = store.save(
                42L, "request-keep", 3L,
                LLMPreferenceExtractor.UserInsightReport.empty("没有新增证据"),
                10L, List.of(8L, 10L));

        assertThat(result).isSameAs(existing);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void keepWithoutExistingProfileReturnsTransientProjectionWithoutPersistingFailure() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), eq(42L))).thenReturn(List.of());
        UserProfileSnapshotStore store = new UserProfileSnapshotStore(jdbc, new ObjectMapper());

        UserProfileSnapshotStore.Snapshot result = store.save(
                42L, "request-model-failed", 0L,
                LLMPreferenceExtractor.UserInsightReport.empty("模型暂时不可用"),
                10L, List.of(10L));

        assertThat(result.profileVersion()).isZero();
        assertThat(result.reportJson()).contains("模型暂时不可用");
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void rejectsStaleExpectedVersionBeforeWriting() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), eq(42L)))
                .thenReturn(List.of(snapshot(42L, 4L, "{}")));
        UserProfileSnapshotStore store = new UserProfileSnapshotStore(jdbc, new ObjectMapper());

        assertThatThrownBy(() -> store.save(
                42L, "request-stale", 3L, report("UPDATE"), 12L, List.of(12L)))
                .isInstanceOf(UserProfileSnapshotStore.OptimisticProfileUpdateException.class)
                .hasMessageContaining("expected=3, actual=4");
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void createWritesSnapshotAndImmutableChangeEvent() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), eq(42L))).thenReturn(List.of());
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        UserProfileSnapshotStore store = new UserProfileSnapshotStore(jdbc, new ObjectMapper());

        UserProfileSnapshotStore.Snapshot saved = store.save(
                42L, "request-create", 0L, report("CREATE"), 15L, List.of(11L, 15L));

        assertThat(saved.profileVersion()).isEqualTo(1L);
        assertThat(saved.reportJson()).contains("深度咨询", "购买意愿明确");
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2)).update(sql.capture(), any(Object[].class));
        assertThat(sql.getAllValues().get(0)).contains("INSERT INTO user_profile_snapshot");
        assertThat(sql.getAllValues().get(1)).contains("INSERT INTO user_profile_change_log");
    }

    private static UserProfileSnapshotStore.Snapshot snapshot(
            Long userId, long version, String reportJson) {
        return new UserProfileSnapshotStore.Snapshot(userId, version,
                UserProfileSnapshotStore.SCHEMA_VERSION, reportJson, 10L,
                Instant.now(), Instant.now());
    }

    private static LLMPreferenceExtractor.UserInsightReport report(String action) {
        return new LLMPreferenceExtractor.UserInsightReport(
                new LLMPreferenceExtractor.ProfileUpdate(action,
                        List.of("commerceAssessment"), List.of(),
                        "新增购买意愿证据", List.of("准备下单")),
                Map.of(), List.of(), Map.of("购买意愿", 8),
                List.of("购买意愿明确"), List.of("售后顾虑"),
                new LLMPreferenceExtractor.CommerceAssessment(
                        true, "深度咨询", "审慎型", "中", 75, "中",
                        List.of("售后顾虑"), "当前", false, List.of(), List.of()),
                List.of());
    }
}
