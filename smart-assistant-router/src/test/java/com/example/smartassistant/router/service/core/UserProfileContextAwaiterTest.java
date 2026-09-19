package com.example.smartassistant.router.service.core;

import com.example.smartassistant.routing.contract.RoutingKeys;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class UserProfileContextAwaiterTest {
    private final ProfileProjectionReader reader=mock(ProfileProjectionReader.class);
    private final UserProfileContextAwaiter awaiter=new UserProfileContextAwaiter();
    private static final String READY=RoutingKeys.USER_PROFILE_READY_PREFIX;
    @BeforeEach void setup() {
        ReflectionTestUtils.setField(awaiter,"projectionReader",reader);
        ReflectionTestUtils.setField(awaiter,"waitTimeoutMs",100L);
        ReflectionTestUtils.setField(awaiter,"pollIntervalMs",1L);
    }
    @AfterEach void close() { awaiter.close(); Thread.interrupted(); }
    @Test void readyIsRevalidatedAndChangedBodyIsNotSubstituted() {
        when(reader.read(1L,"r")).thenReturn("PENDING",READY+"original");
        assertThat(awaiter.await("r",1L)).isEqualTo("original");
        when(reader.read(1L,"r")).thenReturn(READY+"changed");
        assertThat(awaiter.await("r",1L)).isEmpty();
    }
    @Test void pauseAfterSelectionCannotReuseCachedBody() {
        when(reader.read(1L,"r")).thenReturn(READY+"private");
        assertThat(awaiter.await("r",1L)).isEqualTo("private");
        when(reader.read(1L,"r")).thenReturn(null);
        assertThat(awaiter.await("r",1L)).isEmpty();
    }
    @Test void absentIdentityNeverReadsPersonalData() {
        assertThat(awaiter.await("r")).isEmpty();
        assertThat(awaiter.await("r",0L)).isEmpty();
        verifyNoInteractions(reader);
    }
    @Test void missingFailedEmptyAndUnknownStatesDoNotThrow() {
        for(String state:new String[]{null,"FAILED","EMPTY","invalid"}) {
            when(reader.read(anyLong(),anyString())).thenReturn(state);
            assertThat(awaiter.await("r-"+state,1L)).isEmpty();
        }
    }
    @Test void storageFailureFallsBack() {
        when(reader.read(anyLong(),anyString())).thenThrow(new IllegalStateException("unavailable"));
        assertThat(awaiter.await("r",1L)).isEmpty();
    }
    @Test void usersDoNotShareSelection() {
        when(reader.read(1L,"r")).thenReturn(READY+"A");
        when(reader.read(2L,"r")).thenReturn(READY+"B");
        assertThat(awaiter.await("r",1L)).isEqualTo("A");
        assertThat(awaiter.await("r",2L)).isEqualTo("B");
    }
    @Test void timeoutRemainsStickyWithoutReadingLateProfile() {
        when(reader.read(1L,"r")).thenReturn("PENDING");
        assertThat(awaiter.await("r",1L)).isEmpty();
        when(reader.read(1L,"r")).thenReturn(READY+"late");
        clearInvocations(reader);
        assertThat(awaiter.await("r",1L)).isEmpty();
        verifyNoInteractions(reader);
    }
    @Test void uninterruptibleReadDoesNotHoldBusinessCaller() {
        var release=new CountDownLatch(1);
        when(reader.read(anyLong(),anyString())).thenAnswer(call->{
            while(true) { try { release.await(); break; } catch(InterruptedException ignored) {} }
            return READY+"late";
        });
        try {
            long started=System.nanoTime();
            assertThat(awaiter.await("r",1L)).isEmpty();
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)).isLessThan(450);
        } finally { release.countDown(); }
    }
    @Test void repeatedReadAlsoHasBoundedBudget() {
        when(reader.read(1L,"r")).thenReturn(READY+"private");
        assertThat(awaiter.await("r",1L)).isEqualTo("private");
        when(reader.read(1L,"r")).thenAnswer(call->{Thread.sleep(1000);return READY+"private";});
        long started=System.nanoTime();
        assertThat(awaiter.await("r",1L)).isEmpty();
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)).isLessThan(450);
    }
    @Test void concurrentNodesRevalidateTheirOwnRead() throws Exception {
        ReflectionTestUtils.setField(awaiter,"waitTimeoutMs",1000L);
        when(reader.read(1L,"r")).thenReturn(READY+"same");
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->awaiter.await("r",1L)); var b=pool.submit(()->awaiter.await("r",1L));
            assertThat(a.get()).isEqualTo("same"); assertThat(b.get()).isEqualTo("same");
            verify(reader,times(3)).read(1L,"r");
        }
    }
    @Test void interruptedCallerPreservesCancellation() {
        Thread.currentThread().interrupt();
        assertThatThrownBy(()->awaiter.await("r",1L)).isInstanceOf(CancellationException.class);
        assertThat(Thread.currentThread().isInterrupted()).isTrue(); verifyNoInteractions(reader);
    }
    @Test void readerRejectionDegradesImmediately() {
        awaiter.close(); assertThat(awaiter.await("r",1L)).isEmpty(); verifyNoInteractions(reader);
    }
}
