package com.example.smartassistant.router.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/** RabbitMQ topology and publishing settings for automatic workflow recovery. */
@ConfigurationProperties(prefix = "router.graph.recovery.rabbit")
public class WorkflowRecoveryRabbitProperties {

    private String exchange = "smartassistant.workflow";
    private String queue = "smartassistant.workflow.recovery";
    private String routingKey = "workflow.recovery";
    private String deadLetterExchange = "smartassistant.workflow.dlx";
    private String deadLetterQueue = "smartassistant.workflow.recovery.dlq";
    private String deadLetterRoutingKey = "workflow.recovery.dead";
    private String notificationQueue = "smartassistant.workflow.recovery.completed";
    private String notificationRoutingKey = "workflow.recovery.completed";
    private String notificationDeadLetterExchange = "smartassistant.workflow.notification.dlx";
    private String notificationDeadLetterQueue = "smartassistant.workflow.recovery.completed.dlq";
    private String notificationDeadLetterRoutingKey = "workflow.recovery.completed.dead";
    private List<Duration> retryDelays = List.of(
            Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(30));
    private Duration confirmTimeout = Duration.ofSeconds(5);

    public String getExchange() {
        return exchange;
    }

    public void setExchange(String exchange) {
        this.exchange = exchange;
    }

    public String getQueue() {
        return queue;
    }

    public void setQueue(String queue) {
        this.queue = queue;
    }

    public String getRoutingKey() {
        return routingKey;
    }

    public void setRoutingKey(String routingKey) {
        this.routingKey = routingKey;
    }

    public String getDeadLetterExchange() {
        return deadLetterExchange;
    }

    public void setDeadLetterExchange(String deadLetterExchange) {
        this.deadLetterExchange = deadLetterExchange;
    }

    public String getDeadLetterQueue() {
        return deadLetterQueue;
    }

    public void setDeadLetterQueue(String deadLetterQueue) {
        this.deadLetterQueue = deadLetterQueue;
    }

    public String getDeadLetterRoutingKey() {
        return deadLetterRoutingKey;
    }

    public void setDeadLetterRoutingKey(String deadLetterRoutingKey) {
        this.deadLetterRoutingKey = deadLetterRoutingKey;
    }

    public String getNotificationQueue() {
        return notificationQueue;
    }

    public void setNotificationQueue(String notificationQueue) {
        this.notificationQueue = notificationQueue;
    }

    public String getNotificationRoutingKey() {
        return notificationRoutingKey;
    }

    public void setNotificationRoutingKey(String notificationRoutingKey) {
        this.notificationRoutingKey = notificationRoutingKey;
    }

    public String getNotificationDeadLetterExchange() {
        return notificationDeadLetterExchange;
    }

    public void setNotificationDeadLetterExchange(String value) {
        this.notificationDeadLetterExchange = value;
    }

    public String getNotificationDeadLetterQueue() {
        return notificationDeadLetterQueue;
    }

    public void setNotificationDeadLetterQueue(String value) {
        this.notificationDeadLetterQueue = value;
    }

    public String getNotificationDeadLetterRoutingKey() {
        return notificationDeadLetterRoutingKey;
    }

    public void setNotificationDeadLetterRoutingKey(String value) {
        this.notificationDeadLetterRoutingKey = value;
    }

    public List<Duration> getRetryDelays() {
        return retryDelays;
    }

    public void setRetryDelays(List<Duration> retryDelays) {
        this.retryDelays = retryDelays;
    }

    public Duration getConfirmTimeout() {
        return confirmTimeout;
    }

    public void setConfirmTimeout(Duration confirmTimeout) {
        this.confirmTimeout = confirmTimeout;
    }
}
