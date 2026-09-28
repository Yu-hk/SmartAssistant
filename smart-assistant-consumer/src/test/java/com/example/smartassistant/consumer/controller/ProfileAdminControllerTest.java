package com.example.smartassistant.consumer.controller;

import com.example.smartassistant.consumer.service.admin.AdminProfileService;
import com.example.smartassistant.consumer.service.recommendation.ProfileCleanupService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.Map;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ProfileAdminControllerTest {
    @Test void cleanupRequiresExplicitConfirmationAndUsesVerifiedActor() throws Exception {
        var profiles=mock(AdminProfileService.class);var cleanup=mock(ProfileCleanupService.class);
        var mvc=MockMvcBuilders.standaloneSetup(new ProfileAdminController(profiles,cleanup)).build();
        UUID action=UUID.randomUUID(),job=UUID.randomUUID();
        String body="{\"idempotencyKey\":\""+action+"\",\"expectedUsername\":\"qa-user\",\"reasonCode\":\"USER_REQUEST\",\"confirmation\":\"ADMIN_DELETE_PROFILE_PAUSE_ANALYSIS\"}";
        mvc.perform(post("/api/admin/profiles/99/deletions").contentType("application/json").content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/profiles/99/deletions").requestAttr("profileAdminActor",42L)
                .contentType("application/json").content(body.replace("ADMIN_DELETE_PROFILE_PAUSE_ANALYSIS","NO")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(cleanup);
        when(cleanup.operational()).thenReturn(true);
        when(cleanup.requestForAdmin(42L,99L,"qa-user","USER_REQUEST",action)).thenReturn(job);
        mvc.perform(post("/api/admin/profiles/99/deletions").requestAttr("profileAdminActor",42L)
                .contentType("application/json").content(body))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.jobId").value(job.toString()));
        verify(cleanup).requestForAdmin(42L,99L,"qa-user","USER_REQUEST",action);
    }
}
