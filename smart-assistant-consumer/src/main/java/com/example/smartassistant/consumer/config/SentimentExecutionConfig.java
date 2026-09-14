package com.example.smartassistant.consumer.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.*;

@Configuration(proxyBeanMethods = false)
public class SentimentExecutionConfig {
    @Bean(name = "sentimentExecutor", destroyMethod = "shutdownNow")
    ExecutorService sentimentExecutor(@Value("${consumer.sentiment.workers:2}") int workers,
            @Value("${consumer.sentiment.queue-capacity:32}") int capacity) {
        int size = Math.max(1, workers);
        return new ThreadPoolExecutor(size, size, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(1, capacity)),
                Thread.ofPlatform().daemon().name("sentiment-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    }
}
