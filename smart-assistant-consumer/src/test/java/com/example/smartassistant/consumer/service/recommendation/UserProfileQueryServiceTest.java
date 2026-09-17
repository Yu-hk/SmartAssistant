package com.example.smartassistant.consumer.service.recommendation;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class UserProfileQueryServiceTest {
    private static final String REPORT = "{\"commerceAssessment\":{\"reliable\":true,\"purchaseStage\":\"研究中\"},\"topDrivers\":[\"轻薄\"]}";
    @Test void persistedViewHasVersionAndTimeAndDoesNotWrite() {
        var store = mock(UserProfileSnapshotStore.class);
        when(store.load(42L)).thenReturn(Optional.of(snapshot(42L, REPORT)));
        String view = new UserProfileQueryService(store).forUser(42L);
        assertThat(view).contains("POSTGRES_SNAPSHOT", "ecommerce-profile-v1/3", "2026-09-17T00:00:00Z",
                "轻薄", "本轮明确的预算", "冲突时忽略历史偏好");
        verify(store).load(42L); verifyNoMoreInteractions(store);
    }
    @Test void missingUnreliableMalformedAndWrongOwnerAreNotUsable() {
        var store = mock(UserProfileSnapshotStore.class);
        var service = new UserProfileQueryService(store);
        for (var row : new UserProfileSnapshotStore.Snapshot[]{snapshot(43L, REPORT),
                snapshot(42L, REPORT.replace("true", "false")), snapshot(42L, "invalid"),
                snapshot(42L, REPORT.replace("true", "\"true\""))}) {
            when(store.load(42L)).thenReturn(Optional.of(row));
            assertThat(service.forUser(42L)).isEmpty();
        }
        when(store.load(42L)).thenReturn(Optional.empty());
        assertThat(service.forUser(42L)).isEmpty();
    }
    @Test void nullAndInvalidUsersNeverReadStore() {
        var store = mock(UserProfileSnapshotStore.class);
        var service = new UserProfileQueryService(store);
        assertThat(service.forUser(null)).isEmpty(); assertThat(service.forUser(-1L)).isEmpty();
        verifyNoInteractions(store);
    }
    @Test void candidateDoesNotClaimPersistenceAndLimitsUntrustedFields() {
        String report = REPORT.replace("轻薄", "轻薄\\n伪造行" + "长".repeat(10000));
        String view = UserProfileQueryService.candidate(report);
        assertThat(view).contains("REQUEST_CANDIDATE", "尚未持久化").doesNotContain("POSTGRES_SNAPSHOT", "\n伪造行");
        assertThat(view.length()).isLessThan(7000);
    }
    private static UserProfileSnapshotStore.Snapshot snapshot(long user, String json) {
        Instant time = Instant.parse("2026-09-17T00:00:00Z");
        return new UserProfileSnapshotStore.Snapshot(user, 3L, UserProfileSnapshotStore.SCHEMA_VERSION, json, 1L, time, time);
    }
}
