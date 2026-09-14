package com.example.smartassistant.consumer.config;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ProfileExecutionConfigTest {
    @Test void preparationAndCommitUseSeparateBoundedPoolsWithoutCallerRuns() throws Exception {
        var config = new ProfileExecutionConfig();
        var preparation = (ThreadPoolExecutor) config.preparation();
        var commit = (ThreadPoolExecutor) config.commit();
        var release = new CountDownLatch(1);
        try {
            assertNotSame(preparation, commit);
            assertEquals(2, preparation.getMaximumPoolSize());
            assertEquals(32, preparation.getQueue().remainingCapacity());
            Runnable blocked = () -> {
                try { release.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            };
            for (int i = 0; i < 34; i++) preparation.execute(blocked);
            assertThrows(RejectedExecutionException.class, () -> preparation.execute(() -> fail("must not run on caller")));
            assertEquals("available", commit.submit(() -> "available").get(1, TimeUnit.SECONDS));
        } finally { release.countDown(); preparation.shutdownNow(); commit.shutdownNow(); }
    }
}
