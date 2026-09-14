package com.example.smartassistant.consumer.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.concurrent.*;

/** Separate bounded bulkheads; optional profile work must never run on a chat caller. */
@Configuration
public class ProfileExecutionConfig {
    @Bean(name = "profilePreparationExecutor", destroyMethod = "shutdownNow")
    public ExecutorService preparation() { return pool("profile-prepare-"); }
    @Bean(name = "profileCommitExecutor", destroyMethod = "shutdownNow")
    public ExecutorService commit() { return pool("profile-commit-"); }
    private static ExecutorService pool(String prefix) {
        return new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(32),
                Thread.ofPlatform().daemon().name(prefix, 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    }
}
