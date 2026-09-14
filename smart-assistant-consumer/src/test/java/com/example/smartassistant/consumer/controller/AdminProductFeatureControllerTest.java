package com.example.smartassistant.consumer.controller;

import com.example.smartassistant.consumer.service.admin.AdminProductFeatureService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminProductFeatureControllerTest {
    private static final String PATH = "/api/admin/products/FEATURE-TEST-A/features";

    @Test
    void missingOrdinaryAndMalformedAdminIdentitiesCannotReadOrWrite() throws Exception {
        var service = mock(AdminProductFeatureService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new AdminProductFeatureController(service)).build();
        for (String role : new String[]{"", "ROLE_USER", "ADMIN", "ROLE_ADMIN,ROLE_USER"}) {
            mvc.perform(get(PATH).header("X-User-Role", role).header("X-User-Id", "7")).andExpect(status().isForbidden());
            mvc.perform(put(PATH).header("X-User-Role", role).header("X-User-Id", "7")
                    .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        }
        mvc.perform(get(PATH).header("X-User-Role", "ROLE_ADMIN")).andExpect(status().isForbidden());
        mvc.perform(put(PATH).header("X-User-Role", "ROLE_ADMIN").header("X-User-Id", "0")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void administratorCanReadAndSaveWithGatewayIdentity() throws Exception {
        var service = mock(AdminProductFeatureService.class);
        when(service.get("FEATURE-TEST-A")).thenReturn(Map.of("revision", 0));
        when(service.save(eq("FEATURE-TEST-A"), any(), eq(7L))).thenReturn(Map.of("revision", 1));
        var mvc = MockMvcBuilders.standaloneSetup(new AdminProductFeatureController(service)).build();
        mvc.perform(get(PATH).header("X-User-Role", "ROLE_ADMIN").header("X-User-Id", "7"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(0));
        mvc.perform(put(PATH).header("X-User-Role", "ROLE_ADMIN").header("X-User-Id", "7")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(1));
        verify(service).save(eq("FEATURE-TEST-A"), any(), eq(7L));
    }

    @Test
    void databaseFailureDoesNotReturnSuccessOrExposeDatabaseDetails() throws Exception {
        var service = mock(AdminProductFeatureService.class);
        when(service.save(anyString(), any(), anyLong())).thenThrow(new DataAccessResourceFailureException("private-db-detail-canary"));
        var mvc = MockMvcBuilders.standaloneSetup(new AdminProductFeatureController(service)).build();
        mvc.perform(put(PATH).header("X-User-Role", "ROLE_ADMIN").header("X-User-Id", "7")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("PRODUCT_FEATURE_STORAGE_UNAVAILABLE"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-db-detail-canary"))));
    }
}
