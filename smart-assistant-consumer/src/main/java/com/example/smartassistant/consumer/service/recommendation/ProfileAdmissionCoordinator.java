package com.example.smartassistant.consumer.service.recommendation;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.OptionalLong;
import java.util.concurrent.*;

/** Bounded pre-queue admission. A timed-out DB operation may leave a receipt, never start analysis. */
@Service
public class ProfileAdmissionCoordinator {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ProfileAdmissionCoordinator.class);
    private final ProfileAdmissionStore store;
    private final ExecutorService executor;
    private final long budgetMs;

    public ProfileAdmissionCoordinator(ProfileAdmissionStore store,
            @Qualifier("profileAdmissionExecutor") ExecutorService executor,
            @Value("${profile.admission.wait-ms:100}") long budgetMs) {
        this.store = store;
        this.executor = executor;
        this.budgetMs = Math.max(20, Math.min(250, budgetMs));
    }

    public OptionalLong admit(Long userId, String requestId, String question) {
        Future<Long> future = null;
        try {
            future = executor.submit(() -> store.admit(userId, requestId, question));
            return OptionalLong.of(future.get(budgetMs, TimeUnit.MILLISECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return OptionalLong.empty();
        } catch (ExecutionException | TimeoutException | RejectedExecutionException unavailable) {
            log.debug("[ProfileAdmission] Optional analysis skipped: type={}", unavailable.getClass().getSimpleName());
            return OptionalLong.empty();
        } finally {
            if (future != null && !future.isDone()) future.cancel(true);
        }
    }
}
