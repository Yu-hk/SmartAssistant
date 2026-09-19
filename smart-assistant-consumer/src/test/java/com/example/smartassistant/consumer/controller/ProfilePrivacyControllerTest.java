package com.example.smartassistant.consumer.controller;

import com.example.smartassistant.consumer.service.recommendation.ProfileCleanupService;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProfilePrivacyControllerTest {
    @Test void authenticationAndConfirmationAreMandatory() {
        var service=mock(ProfileCleanupService.class);var api=new ProfilePrivacyController(service);
        assertEquals(401,api.overview(null).getStatusCode().value());
        assertEquals(401,api.delete(0L,new ProfilePrivacyController.DeleteRequest(UUID.randomUUID(),"DELETE_PROFILE_PAUSE_ANALYSIS")).getStatusCode().value());
        assertEquals(400,api.delete(42L,new ProfilePrivacyController.DeleteRequest(UUID.randomUUID(),"yes")).getStatusCode().value());
        assertEquals(401,api.status(null,UUID.randomUUID()).getStatusCode().value());verifyNoInteractions(service);
    }
    @Test void requiresAllGatesAndUsesAuthenticatedOwnerOnly() {
        var service=mock(ProfileCleanupService.class);var api=new ProfilePrivacyController(service);
        UUID key=UUID.randomUUID(),job=UUID.randomUUID();var request=new ProfilePrivacyController.DeleteRequest(key,"DELETE_PROFILE_PAUSE_ANALYSIS");
        assertEquals(503,api.delete(42L,request).getStatusCode().value());verify(service,never()).request(any(),any());
        when(service.operational()).thenReturn(true);when(service.request(42L,key)).thenReturn(job);
        assertEquals(Map.of("jobId",job.toString()),api.delete(42L,request).getBody());verify(service).request(42L,key);
    }
    @Test void foreignStatusIsNotFoundAndInternalErrorsAreNotExposed() {
        var service=mock(ProfileCleanupService.class);var api=new ProfilePrivacyController(service);UUID job=UUID.randomUUID();
        when(service.status(42L,job)).thenReturn(Map.of());assertEquals(404,api.status(42L,job).getStatusCode().value());
        when(service.overview(42L)).thenThrow(new IllegalStateException("private path and profile"));
        var response=api.overview(42L);assertEquals(503,response.getStatusCode().value());assertFalse(response.getBody().toString().contains("private"));
    }
}
