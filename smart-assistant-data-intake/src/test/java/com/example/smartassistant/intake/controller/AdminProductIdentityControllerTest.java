package com.example.smartassistant.intake.controller;
import com.example.smartassistant.intake.service.admin.AdminProductIdentityService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class AdminProductIdentityControllerTest {
    @Test void onlyGatewayVerifiedAdminCanReadAndUpdate() throws Exception {
        var service = mock(AdminProductIdentityService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new AdminProductIdentityController(service)).build();
        String path = "/api/admin/products/AP/identity";
        for (String role : new String[]{"", "ROLE_USER", "ADMIN", "ROLE_ADMIN,ROLE_USER"}) {
            mvc.perform(get(path).header("X-User-Role", role).header("X-User-Id", "7")).andExpect(status().isForbidden());
            mvc.perform(put(path).header("X-User-Role", role).header("X-User-Id", "7").contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get(path).header("X-User-Role", "ROLE_ADMIN")).andExpect(status().isForbidden());
        verifyNoInteractions(service);
        when(service.get("AP")).thenReturn(Map.of("revision", 0));
        mvc.perform(get(path).header("X-User-Role", "ROLE_ADMIN").header("X-User-Id", "7")).andExpect(status().isOk());
        mvc.perform(put(path).header("X-User-Role", "ROLE_ADMIN").header("X-User-Id", "7").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        verify(service).save(eq("AP"), any(), eq(7L));
    }
}
