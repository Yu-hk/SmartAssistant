package com.example.smartassistant.consumer.service.cache;

import com.example.smartassistant.consumer.entity.UserProfile;
import com.example.smartassistant.consumer.service.recommendation.UserProfileService;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class UserGroupingServiceTest {
    @Test void rechecksGovernedStorageInsteadOfRetainingAnErasedGroup() {
        var profiles = mock(UserProfileService.class);
        var profile = new UserProfile();
        profile.setBudgetRange("high");
        when(profiles.getProfile(42L)).thenReturn(profile).thenReturn(null);
        var groups = new UserGroupingService(profiles);
        assertThat(groups.getGroupId("42")).contains("budget=high");
        assertThat(groups.getGroupId("42")).startsWith("grp:new_").doesNotContain("high");
        verify(profiles, times(2)).getProfile(42L);
    }

    @Test void storageFailureCannotReusePreviousPersonalizedGroup() {
        var profiles = mock(UserProfileService.class);
        var profile = new UserProfile();
        profile.setBudgetRange("low");
        when(profiles.getProfile(42L)).thenReturn(profile).thenThrow(new IllegalStateException("fixture"));
        var groups = new UserGroupingService(profiles);
        assertThat(groups.getGroupId("42")).contains("budget=low");
        assertThat(groups.getGroupId("42")).startsWith("grp:new_");
        assertThat(groups.getGroupId(null)).isEqualTo("grp:anonymous");
    }
}
