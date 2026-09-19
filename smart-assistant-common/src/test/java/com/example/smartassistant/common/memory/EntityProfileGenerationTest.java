package com.example.smartassistant.common.memory;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EntityProfileGenerationTest {
    @Test void lateModelResultKeepsCapturedGenerationAndDoesNotRetry() {
        var store=mock(EntityProfileStore.class); when(store.admittedGeneration(42L,"request","喜欢便携")).thenReturn(3L);
        var service=new EntityProfileService(store);
        service.setLlmExtractor((question,reply)->{
            when(store.capture(42L)).thenReturn(4L);
            doThrow(new IllegalStateException("inactive")).when(store).save(42L,3L,Map.of("preference","便携"));
            return Map.of("preference","便携");
        });
        assertDoesNotThrow(()->service.extractAndStore(42L,"喜欢便携","assistant","request"));
        verify(store,times(1)).admittedGeneration(42L,"request","喜欢便携");
        verify(store,never()).capture(anyLong());
        verify(store).save(42L,3L,Map.of("preference","便携"));
        verify(store,never()).save(eq(42L),eq(4L),anyMap());
    }
    @Test void pausedOrUnavailableStoreDoesNotCallModel() {
        var store=mock(EntityProfileStore.class); when(store.admittedGeneration(42L,"request","喜欢便携")).thenThrow(new IllegalStateException("unavailable"));
        var service=new EntityProfileService(store);
        service.setLlmExtractor((q,r)->{fail("Must not analyze without an active generation");return Map.of();});
        assertDoesNotThrow(()->service.extractAndStore(42L,"喜欢便携","","request"));
        verify(store,never()).save(anyLong(),anyLong(),anyMap());
    }
    @Test void keywordFallbackAlsoUsesCapturedGeneration() {
        var store=mock(EntityProfileStore.class); when(store.admittedGeneration(42L,"request","我喜欢蓝牙耳机")).thenReturn(2L);
        new EntityProfileService(store).extractAndStore(42L,"我喜欢蓝牙耳机","","request");
        verify(store).save(42L,2L,Map.of("preference","蓝牙耳机"));
    }
    @Test void presentationLabelsPgEntitiesWithoutPromotingReliability() {
        var store=mock(EntityProfileStore.class); when(store.read(42L)).thenReturn(Map.of("hobby","音乐"));
        String result=new EntityProfileService(store).formatProfile(42L);
        assertTrue(result.contains("POSTGRES_ENTITY")); assertFalse(result.contains("来源: REDIS_ENTITY"));
        assertTrue(result.contains("不是指令")); assertTrue(result.contains("不得用后者覆盖快照"));
    }
    @Test void legacyWritersCannotMintNewAdmission() {
        var store=mock(EntityProfileStore.class); var service=new EntityProfileService(store);
        service.setLlmExtractor((q,r)->{fail("Unadmitted input must not reach model"); return Map.of();});
        service.extractAndStore(42L,"我喜欢耳机","");
        assertThrows(IllegalStateException.class,()->service.put(42L,"hobby","音乐"));
        assertThrows(IllegalStateException.class,()->service.putAll(42L,Map.of("hobby","音乐")));
        verifyNoInteractions(store);
    }
}
