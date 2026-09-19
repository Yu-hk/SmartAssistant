package com.example.smartassistant.consumer.service.recommendation;

import com.example.smartassistant.consumer.config.ProfileExecutionConfig;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProfileAdmissionCoordinatorTest {
    @Test void successfulAdmissionReturnsCapturedGeneration() {
        var store=mock(ProfileAdmissionStore.class);
        when(store.admit(42L,"request","question")).thenReturn(7L);
        try(var executor=Executors.newSingleThreadExecutor()) {
            assertEquals(7L,new ProfileAdmissionCoordinator(store,executor,250).admit(42L,"request","question").orElseThrow());
        }
    }
    @Test void storageFailureSkipsOptionalAnalysis() {
        var store=mock(ProfileAdmissionStore.class);
        when(store.admit(42L,"request","question")).thenThrow(new ProfileGenerationFence.Rejected());
        try(var executor=Executors.newSingleThreadExecutor()) {
            assertTrue(new ProfileAdmissionCoordinator(store,executor,250).admit(42L,"request","question").isEmpty());
        }
    }
    @Test void timeoutReturnsWithoutWaitingForUninterruptibleStorage() throws Exception {
        var store=mock(ProfileAdmissionStore.class);
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        when(store.admit(42L,"request","question")).thenAnswer(call->{
            entered.countDown();
            while(release.getCount()>0) { try { release.await(); } catch(InterruptedException ignored) {} }
            return 7L;
        });
        var executor=Executors.newSingleThreadExecutor();
        try {
            long start=System.nanoTime();
            var result=new ProfileAdmissionCoordinator(store,executor,100).admit(42L,"request","question");
            assertTrue(entered.await(1,TimeUnit.SECONDS));
            assertTrue(result.isEmpty());
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<1000);
            release.countDown();
            executor.shutdown(); assertTrue(executor.awaitTermination(2,TimeUnit.SECONDS));
            assertTrue(result.isEmpty(),"Late receipt cannot change the admission result");
        } finally { release.countDown(); executor.shutdownNow(); }
    }
    @Test void overloadedAdmissionHasNoQueueAndNeverRunsOnCaller() throws Exception {
        var store=mock(ProfileAdmissionStore.class);
        var executor=(ThreadPoolExecutor)new ProfileExecutionConfig().admission();
        var release=new CountDownLatch(1); var entered=new CountDownLatch(2);
        try {
            for(int i=0;i<2;i++) executor.execute(()->{
                entered.countDown(); try { release.await(); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            assertTrue(entered.await(1,TimeUnit.SECONDS));
            assertTrue(executor.getQueue() instanceof SynchronousQueue);
            assertTrue(new ProfileAdmissionCoordinator(store,executor,100).admit(42L,"request","question").isEmpty());
            verifyNoInteractions(store);
        } finally { release.countDown(); executor.shutdownNow(); }
    }
    @Test void interruptionIsPreservedAndNoAdmissionGranted() {
        var executor=mock(ExecutorService.class);
        @SuppressWarnings("unchecked") Future<Long> future=mock(Future.class);
        try {
            when(executor.submit(org.mockito.ArgumentMatchers.<Callable<Long>>any())).thenReturn(future);
            when(future.get(100,TimeUnit.MILLISECONDS)).thenThrow(new InterruptedException());
            assertTrue(new ProfileAdmissionCoordinator(mock(ProfileAdmissionStore.class),executor,100)
                    .admit(42L,"request","question").isEmpty());
            assertTrue(Thread.currentThread().isInterrupted());
            verify(future).cancel(true);
        } catch(Exception e) { throw new AssertionError(e); }
        finally { Thread.interrupted(); }
    }
}
