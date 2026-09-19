package com.example.smartassistant.consumer.service.recommendation;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ProfileRequestRedisStoreTest {
    @Test void rejectsInvalidIdentityAndUnboundedTtlBeforeRedis() {
        var redis=mock(StringRedisTemplate.class); var store=new ProfileRequestRedisStore(redis);
        assertThrows(IllegalArgumentException.class,()->store.publish(null,"r",0,"P",null,false,Duration.ofSeconds(30)));
        assertThrows(IllegalArgumentException.class,()->store.publish(1L,"r",-1,"P",null,false,Duration.ofSeconds(30)));
        assertThrows(IllegalArgumentException.class,()->store.publish(1L," ",0,"P",null,false,Duration.ofSeconds(30)));
        assertThrows(IllegalArgumentException.class,()->store.publish(1L,"r",0,"P",null,false,Duration.ofSeconds(601)));
        assertThrows(IllegalArgumentException.class,()->store.publish(1L,"r",0,"P",null,false,Duration.ofMillis(999)));
        verifyNoInteractions(redis);
    }
    @Test void publishesOneScriptWithOwnerAndGeneration() {
        var redis=mock(StringRedisTemplate.class); UserProfilePrefetchTest.stubPublication(redis);
        new ProfileRequestRedisStore(redis).publish(42L,"r",3,"READY:value","candidate",true,Duration.ofSeconds(120));
        verify(redis).execute(eq(ProfileRequestRedisStore.PUBLISH),eq(ProfileRequestRedisStore.keys(42L,"r")),
                eq("42|3"),eq("READY:value"),eq("candidate"),eq("1"),eq("120000"),eq("r"));
        verify(redis,never()).opsForValue();
    }
    @Test void rejectsMissingOrNegativeScriptAcknowledgement() {
        var redis=mock(StringRedisTemplate.class); var store=new ProfileRequestRedisStore(redis);
        when(redis.execute(eq(ProfileRequestRedisStore.PUBLISH),anyList(),any(Object[].class))).thenReturn(null,-1L,-2L,-3L);
        for(int i=0;i<4;i++) assertThrows(IllegalStateException.class,()->store.publish(1L,"r",0,"P",null,false,Duration.ofSeconds(30)));
    }
    @Test void completedRequestNoOpIsAccepted() {
        var redis=mock(StringRedisTemplate.class);
        when(redis.execute(eq(ProfileRequestRedisStore.PUBLISH),anyList(),any(Object[].class))).thenReturn(0L);
        assertDoesNotThrow(()->new ProfileRequestRedisStore(redis).publish(1L,"r",0,"P",null,false,Duration.ofSeconds(30)));
    }
}
